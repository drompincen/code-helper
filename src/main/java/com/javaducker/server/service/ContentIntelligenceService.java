package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
public class ContentIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(ContentIntelligenceService.class);
    private final DuckDBDataSource dataSource;

    public ContentIntelligenceService(DuckDBDataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ── Write operations (used by async post-processor) ─────────────────────

    public Map<String, Object> classify(String artifactId, String docType, double confidence, String method) throws SQLException {
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM artifact_classifications WHERE artifact_id = '" + esc(artifactId) + "'");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO artifact_classifications (artifact_id, doc_type, confidence, method, classified_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
            ps.setString(1, artifactId);
            ps.setString(2, docType);
            ps.setDouble(3, confidence);
            ps.setString(4, method);
            ps.executeUpdate();
        }
        return Map.of("artifact_id", artifactId, "doc_type", docType, "confidence", confidence, "method", method);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> tag(String artifactId, List<Map<String, String>> tags) throws SQLException {
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM artifact_tags WHERE artifact_id = '" + esc(artifactId) + "'");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO artifact_tags (artifact_id, tag, tag_type, source) VALUES (?, ?, ?, ?)")) {
            for (Map<String, String> t : tags) {
                ps.setString(1, artifactId);
                ps.setString(2, t.get("tag"));
                ps.setString(3, t.getOrDefault("tag_type", "topic"));
                ps.setString(4, t.getOrDefault("source", "llm"));
                ps.executeUpdate();
            }
        }
        return Map.of("artifact_id", artifactId, "tags_count", tags.size());
    }

    public Map<String, Object> extractPoints(String artifactId, List<Map<String, String>> points) throws SQLException {
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM artifact_salient_points WHERE artifact_id = '" + esc(artifactId) + "'");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO artifact_salient_points (point_id, artifact_id, chunk_id, point_type, point_text, source) VALUES (?, ?, ?, ?, ?, ?)")) {
            int idx = 0;
            for (Map<String, String> p : points) {
                ps.setString(1, artifactId + "-" + p.get("point_type").toLowerCase() + "-" + idx++);
                ps.setString(2, artifactId);
                ps.setString(3, p.getOrDefault("chunk_id", null));
                ps.setString(4, p.get("point_type"));
                ps.setString(5, p.get("point_text"));
                ps.setString(6, p.getOrDefault("source", "llm"));
                ps.executeUpdate();
            }
        }
        return Map.of("artifact_id", artifactId, "points_count", points.size());
    }

    public Map<String, Object> saveConcepts(String artifactId, List<Map<String, Object>> concepts) throws SQLException {
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM artifact_concepts WHERE artifact_id = '" + esc(artifactId) + "'");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO artifact_concepts (concept_id, artifact_id, concept, concept_type, mention_count, chunk_ids) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Map<String, Object> c : concepts) {
                String concept = (String) c.get("concept");
                String slug = concept.toLowerCase().replaceAll("[^a-z0-9]+", "-");
                ps.setString(1, artifactId + "-" + slug);
                ps.setString(2, artifactId);
                ps.setString(3, concept);
                ps.setString(4, (String) c.getOrDefault("concept_type", "topic"));
                ps.setInt(5, c.containsKey("mention_count") ? ((Number) c.get("mention_count")).intValue() : 1);
                ps.setString(6, (String) c.getOrDefault("chunk_ids", null));
                ps.executeUpdate();
            }
        }
        return Map.of("artifact_id", artifactId, "concepts_count", concepts.size());
    }

    public Map<String, Object> setFreshness(String artifactId, String freshness, String supersededBy) throws SQLException {
        Connection conn = dataSource.getConnection();
        // DuckDB ART index bug: UPDATE on PK tables can fail. Use SELECT-then-DELETE+INSERT.
        Map<String, Object> artifact = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM artifacts WHERE artifact_id = ?")) {
            ps.setString(1, artifactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                artifact = extractArtifactRow(rs);
            }
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM artifacts WHERE artifact_id = '" + esc(artifactId) + "'");
        }
        rebuildArtifactRow(conn, artifact, Map.of(
                "freshness", freshness,
                "superseded_by", supersededBy != null ? supersededBy : "",
                "freshness_updated_at", new java.sql.Timestamp(System.currentTimeMillis())));
        return Map.of("artifact_id", artifactId, "freshness", freshness,
                "superseded_by", supersededBy != null ? supersededBy : "");
    }

    public Map<String, Object> synthesize(String artifactId, String summaryText, String tags,
                                           String keyPoints, String outcome, String originalFilePath) throws SQLException {
        Connection conn = dataSource.getConnection();
        // Safety: only allow synthesize on stale/superseded artifacts
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT freshness FROM artifacts WHERE artifact_id = ?")) {
            ps.setString(1, artifactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String freshness = rs.getString(1);
                if ("current".equals(freshness)) {
                    return Map.of("error", "Cannot synthesize a current artifact", "artifact_id", artifactId);
                }
            }
        }
        // Write synthesis record
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM artifact_synthesis WHERE artifact_id = '" + esc(artifactId) + "'");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO artifact_synthesis (artifact_id, summary_text, tags, key_points, outcome, original_file_path) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, artifactId);
            ps.setString(2, summaryText);
            ps.setString(3, tags);
            ps.setString(4, keyPoints);
            ps.setString(5, outcome);
            ps.setString(6, originalFilePath);
            ps.executeUpdate();
        }
        // Prune full text, chunks, embeddings
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM chunk_embeddings WHERE chunk_id IN (SELECT chunk_id FROM artifact_chunks WHERE artifact_id = '" + esc(artifactId) + "')");
            stmt.execute("DELETE FROM artifact_chunks WHERE artifact_id = '" + esc(artifactId) + "'");
            stmt.execute("DELETE FROM artifact_text WHERE artifact_id = '" + esc(artifactId) + "'");
        }
        // Update status using DELETE+INSERT pattern (DuckDB ART index bug)
        Map<String, Object> artifact = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM artifacts WHERE artifact_id = ?")) {
            ps.setString(1, artifactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) artifact = extractArtifactRow(rs);
            }
        }
        if (artifact != null) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM artifacts WHERE artifact_id = '" + esc(artifactId) + "'");
            }
            rebuildArtifactRow(conn, artifact, Map.of(
                    "enrichment_status", "enriched",
                    "freshness_updated_at", new java.sql.Timestamp(System.currentTimeMillis())));
        }
        return Map.of("artifact_id", artifactId, "synthesized", true);
    }

    public Map<String, Object> linkConcepts(List<Map<String, Object>> links) throws SQLException {
        Connection conn = dataSource.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO concept_links (concept, artifact_a, artifact_b, strength) VALUES (?, ?, ?, ?)")) {
            for (Map<String, Object> link : links) {
                ps.setString(1, (String) link.get("concept"));
                ps.setString(2, (String) link.get("artifact_a"));
                ps.setString(3, (String) link.get("artifact_b"));
                ps.setDouble(4, link.containsKey("strength") ? ((Number) link.get("strength")).doubleValue() : 1.0);
                ps.executeUpdate();
            }
        }
        return Map.of("links_created", links.size());
    }

    public List<Map<String, Object>> getEnrichQueue(int limit) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> queue = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT artifact_id, file_name, status, created_at FROM artifacts WHERE enrichment_status = 'pending' AND status = 'INDEXED' ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    queue.add(Map.of(
                            "artifact_id", rs.getString("artifact_id"),
                            "file_name", rs.getString("file_name"),
                            "status", rs.getString("status"),
                            "created_at", String.valueOf(rs.getTimestamp("created_at"))));
                }
            }
        }
        return queue;
    }

    public Map<String, Object> markEnriched(String artifactId) throws SQLException {
        Connection conn = dataSource.getConnection();
        Map<String, Object> artifact = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM artifacts WHERE artifact_id = ?")) {
            ps.setString(1, artifactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                artifact = extractArtifactRow(rs);
            }
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM artifacts WHERE artifact_id = '" + esc(artifactId) + "'");
        }
        rebuildArtifactRow(conn, artifact, Map.of(
                "enrichment_status", "enriched",
                "freshness_updated_at", new java.sql.Timestamp(System.currentTimeMillis())));
        return Map.of("artifact_id", artifactId, "enrichment_status", "enriched");
    }

    // ── Read operations (used by query layer) ───────────────────────────────

    public Map<String, Object> getLatest(String topic) throws SQLException {
        Connection conn = dataSource.getConnection();
        // Search in concepts and tags for topic match, prefer current artifacts
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT DISTINCT a.artifact_id, a.file_name, a.freshness, a.updated_at,
                       c.doc_type, c.confidence
                FROM artifacts a
                LEFT JOIN artifact_classifications c ON a.artifact_id = c.artifact_id
                LEFT JOIN artifact_concepts ac ON a.artifact_id = ac.artifact_id
                LEFT JOIN artifact_tags t ON a.artifact_id = t.artifact_id
                WHERE a.freshness = 'current'
                  AND (LOWER(ac.concept) LIKE ? OR LOWER(t.tag) LIKE ? OR LOWER(a.file_name) LIKE ?)
                ORDER BY a.updated_at DESC
                LIMIT 1
                """)) {
            String pattern = "%" + topic.toLowerCase() + "%";
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Map.of("found", false, "topic", topic);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("found", true);
                result.put("artifact_id", rs.getString("artifact_id"));
                result.put("file_name", rs.getString("file_name"));
                result.put("freshness", rs.getString("freshness"));
                result.put("updated_at", String.valueOf(rs.getTimestamp("updated_at")));
                result.put("doc_type", rs.getString("doc_type"));
                result.put("confidence", rs.getObject("confidence"));
                return result;
            }
        }
    }

    public List<Map<String, Object>> findByType(String docType) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT a.artifact_id, a.file_name, a.freshness, a.updated_at,
                       c.doc_type, c.confidence
                FROM artifacts a
                JOIN artifact_classifications c ON a.artifact_id = c.artifact_id
                WHERE UPPER(c.doc_type) = UPPER(?) AND a.freshness != 'superseded'
                ORDER BY a.updated_at DESC
                """)) {
            ps.setString(1, docType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(Map.of(
                            "artifact_id", rs.getString("artifact_id"),
                            "file_name", rs.getString("file_name"),
                            "freshness", rs.getString("freshness"),
                            "updated_at", String.valueOf(rs.getTimestamp("updated_at")),
                            "doc_type", rs.getString("doc_type")));
                }
            }
        }
        return results;
    }

    public List<Map<String, Object>> findByTag(String tag) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT a.artifact_id, a.file_name, a.freshness, a.updated_at,
                       t.tag, t.tag_type
                FROM artifacts a
                JOIN artifact_tags t ON a.artifact_id = t.artifact_id
                WHERE LOWER(t.tag) = LOWER(?)
                ORDER BY a.updated_at DESC
                """)) {
            ps.setString(1, tag);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(Map.of(
                            "artifact_id", rs.getString("artifact_id"),
                            "file_name", rs.getString("file_name"),
                            "freshness", rs.getString("freshness"),
                            "updated_at", String.valueOf(rs.getTimestamp("updated_at")),
                            "tag", rs.getString("tag"),
                            "tag_type", rs.getString("tag_type")));
                }
            }
        }
        return results;
    }

    public List<Map<String, Object>> findPoints(String pointType, String tag) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        String sql;
        if (tag != null && !tag.isBlank()) {
            sql = """
                SELECT sp.point_id, sp.artifact_id, sp.point_type, sp.point_text, a.file_name
                FROM artifact_salient_points sp
                JOIN artifacts a ON sp.artifact_id = a.artifact_id
                JOIN artifact_tags t ON sp.artifact_id = t.artifact_id
                WHERE UPPER(sp.point_type) = UPPER(?) AND LOWER(t.tag) = LOWER(?)
                ORDER BY sp.created_at DESC
                """;
        } else {
            sql = """
                SELECT sp.point_id, sp.artifact_id, sp.point_type, sp.point_text, a.file_name
                FROM artifact_salient_points sp
                JOIN artifacts a ON sp.artifact_id = a.artifact_id
                WHERE UPPER(sp.point_type) = UPPER(?)
                ORDER BY sp.created_at DESC
                """;
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pointType);
            if (tag != null && !tag.isBlank()) ps.setString(2, tag);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(Map.of(
                            "point_id", rs.getString("point_id"),
                            "artifact_id", rs.getString("artifact_id"),
                            "point_type", rs.getString("point_type"),
                            "point_text", rs.getString("point_text"),
                            "file_name", rs.getString("file_name")));
                }
            }
        }
        return results;
    }

    public List<Map<String, Object>> listConcepts() throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                SELECT concept, COUNT(DISTINCT artifact_id) as doc_count, SUM(mention_count) as total_mentions
                FROM artifact_concepts
                GROUP BY concept
                ORDER BY doc_count DESC
                """)) {
            while (rs.next()) {
                results.add(Map.of(
                        "concept", rs.getString("concept"),
                        "doc_count", rs.getInt("doc_count"),
                        "total_mentions", rs.getInt("total_mentions")));
            }
        }
        return results;
    }

    public Map<String, Object> getConceptTimeline(String concept) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> entries = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT ac.artifact_id, a.file_name, a.freshness, a.created_at, a.updated_at,
                       cl.doc_type
                FROM artifact_concepts ac
                JOIN artifacts a ON ac.artifact_id = a.artifact_id
                LEFT JOIN artifact_classifications cl ON ac.artifact_id = cl.artifact_id
                WHERE LOWER(ac.concept) = LOWER(?)
                ORDER BY a.created_at ASC
                """)) {
            ps.setString(1, concept);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("artifact_id", rs.getString("artifact_id"));
                    entry.put("file_name", rs.getString("file_name"));
                    entry.put("freshness", rs.getString("freshness"));
                    entry.put("created_at", String.valueOf(rs.getTimestamp("created_at")));
                    entry.put("doc_type", rs.getString("doc_type"));
                    entries.add(entry);
                }
            }
        }
        return Map.of("concept", concept, "timeline", entries, "total_docs", entries.size());
    }

    public List<Map<String, Object>> getStaleContent() throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                SELECT a.artifact_id, a.file_name, a.freshness, a.superseded_by, a.freshness_updated_at,
                       cl.doc_type,
                       s.summary_text as synthesis_summary
                FROM artifacts a
                LEFT JOIN artifact_classifications cl ON a.artifact_id = cl.artifact_id
                LEFT JOIN artifact_synthesis s ON a.artifact_id = s.artifact_id
                WHERE a.freshness IN ('stale', 'superseded')
                ORDER BY a.freshness_updated_at DESC
                """)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("artifact_id", rs.getString("artifact_id"));
                row.put("file_name", rs.getString("file_name"));
                row.put("freshness", rs.getString("freshness"));
                row.put("superseded_by", rs.getString("superseded_by"));
                row.put("doc_type", rs.getString("doc_type"));
                row.put("synthesis_summary", rs.getString("synthesis_summary"));
                results.add(row);
            }
        }
        return results;
    }

    public Map<String, Object> getSynthesis(String artifactId) throws SQLException {
        Connection conn = dataSource.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM artifact_synthesis WHERE artifact_id = ?")) {
            ps.setString(1, artifactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("artifact_id", rs.getString("artifact_id"));
                result.put("summary_text", rs.getString("summary_text"));
                result.put("tags", rs.getString("tags"));
                result.put("key_points", rs.getString("key_points"));
                result.put("outcome", rs.getString("outcome"));
                result.put("original_file_path", rs.getString("original_file_path"));
                result.put("synthesized_at", String.valueOf(rs.getTimestamp("synthesized_at")));
                return result;
            }
        }
    }

    public List<Map<String, Object>> getRelatedByConcept(String artifactId) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT DISTINCT a2.artifact_id, a2.file_name, a2.freshness, a2.updated_at,
                       ac2.concept
                FROM artifact_concepts ac1
                JOIN artifact_concepts ac2 ON LOWER(ac1.concept) = LOWER(ac2.concept) AND ac1.artifact_id != ac2.artifact_id
                JOIN artifacts a2 ON ac2.artifact_id = a2.artifact_id
                WHERE ac1.artifact_id = ?
                ORDER BY a2.updated_at DESC
                """)) {
            ps.setString(1, artifactId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(Map.of(
                            "artifact_id", rs.getString("artifact_id"),
                            "file_name", rs.getString("file_name"),
                            "freshness", rs.getString("freshness"),
                            "updated_at", String.valueOf(rs.getTimestamp("updated_at")),
                            "shared_concept", rs.getString("concept")));
                }
            }
        }
        return results;
    }

    public Map<String, Object> getConceptHealth() throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> concepts = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                SELECT ac.concept,
                       COUNT(DISTINCT ac.artifact_id) as total_docs,
                       COUNT(DISTINCT CASE WHEN a.freshness = 'current' THEN ac.artifact_id END) as active_docs,
                       COUNT(DISTINCT CASE WHEN a.freshness = 'stale' THEN ac.artifact_id END) as stale_docs,
                       MAX(a.updated_at) as last_mention
                FROM artifact_concepts ac
                JOIN artifacts a ON ac.artifact_id = a.artifact_id
                GROUP BY ac.concept
                ORDER BY active_docs DESC
                """)) {
            while (rs.next()) {
                int active = rs.getInt("active_docs");
                int stale = rs.getInt("stale_docs");
                String trend = stale > active ? "fading" : (active > 0 ? "active" : "cold");
                concepts.add(Map.of(
                        "concept", rs.getString("concept"),
                        "total_docs", rs.getInt("total_docs"),
                        "active_docs", active,
                        "stale_docs", stale,
                        "last_mention", String.valueOf(rs.getTimestamp("last_mention")),
                        "trend", trend));
            }
        }
        return Map.of("concepts", concepts, "total", concepts.size());
    }

    public List<Map<String, Object>> searchSynthesis(String keyword) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT artifact_id, summary_text, tags, key_points, original_file_path
                FROM artifact_synthesis
                WHERE LOWER(summary_text) LIKE ? OR LOWER(tags) LIKE ?
                """)) {
            String pattern = "%" + keyword.toLowerCase() + "%";
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(Map.of(
                            "artifact_id", rs.getString("artifact_id"),
                            "summary_text", rs.getString("summary_text") != null ? rs.getString("summary_text") : "",
                            "tags", rs.getString("tags") != null ? rs.getString("tags") : "",
                            "original_file_path", rs.getString("original_file_path") != null ? rs.getString("original_file_path") : ""));
                }
            }
        }
        return results;
    }

    /** Extract all columns from an artifacts row into a Map. */
    private Map<String, Object> extractArtifactRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnName(i).toLowerCase(), rs.getObject(i));
        }
        return row;
    }

    /** Rebuild an artifact row with overrides applied. Uses INSERT with explicit columns. */
    private void rebuildArtifactRow(Connection conn, Map<String, Object> row, Map<String, Object> overrides) throws SQLException {
        Map<String, Object> merged = new LinkedHashMap<>(row);
        merged.putAll(overrides);
        StringBuilder cols = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            if (cols.length() > 0) { cols.append(", "); placeholders.append(", "); }
            cols.append(entry.getKey());
            placeholders.append("?");
            values.add(entry.getValue());
        }
        String sql = "INSERT INTO artifacts (" + cols + ") VALUES (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.size(); i++) {
                ps.setObject(i + 1, values.get(i));
            }
            ps.executeUpdate();
        }
    }

    private String esc(String s) {
        return s.replace("'", "''");
    }
}
