package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.ingestion.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);
    private final DuckDBDataSource dataSource;
    private final EmbeddingService embeddingService;

    public KnowledgeGraphService(DuckDBDataSource dataSource, EmbeddingService embeddingService) {
        this.dataSource = dataSource;
        this.embeddingService = embeddingService;
    }

    public Map<String, Object> upsertEntity(String entityName, String entityType, String description,
                                             String artifactId, String chunkId) throws SQLException {
        Connection conn = dataSource.getConnection();
        // Check if entity with same name+type exists
        String existingId = null;
        Map<String, Object> existing = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM entities WHERE entity_name = ? AND entity_type = ?")) {
            ps.setString(1, entityName);
            ps.setString(2, entityType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    existingId = rs.getString("entity_id");
                    existing = rowToMap(rs);
                }
            }
        }

        if (existing != null) {
            String updatedArtifacts = appendToJsonArray((String) existing.get("source_artifact_ids"), artifactId);
            String updatedChunks = chunkId != null
                    ? appendToJsonArray((String) existing.get("source_chunk_ids"), chunkId)
                    : (String) existing.get("source_chunk_ids");
            int newCount = ((Number) existing.get("mention_count")).intValue() + 1;
            String newDesc = description != null && (existing.get("description") == null
                    || description.length() > ((String) existing.get("description")).length())
                    ? description : (String) existing.get("description");

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM entities WHERE entity_id = '" + esc(existingId) + "'");
            }
            double[] emb = newDesc != null ? embeddingService.embed(newDesc) : null;
            insertEntity(conn, existingId, entityName, entityType, newDesc,
                    (String) existing.get("summary"), updatedArtifacts, updatedChunks, newCount, emb);
            return Map.of("entity_id", existingId, "entity_name", entityName,
                    "entity_type", entityType, "mention_count", newCount, "action", "merged");
        }

        // New entity
        String entityId = entityType.toLowerCase() + "-" + slugify(entityName);
        double[] emb = description != null ? embeddingService.embed(description) : null;
        String artifactIds = appendToJsonArray(null, artifactId);
        String chunkIds = chunkId != null ? appendToJsonArray(null, chunkId) : null;
        insertEntity(conn, entityId, entityName, entityType, description, null, artifactIds, chunkIds, 1, emb);
        return Map.of("entity_id", entityId, "entity_name", entityName,
                "entity_type", entityType, "mention_count", 1, "action", "created");
    }

    public Map<String, Object> upsertRelationship(String sourceEntityId, String targetEntityId,
                                                    String relationshipType, String description,
                                                    String artifactId, String chunkId,
                                                    double weight) throws SQLException {
        Connection conn = dataSource.getConnection();
        String relId = slugify(sourceEntityId + "-" + relationshipType + "-" + targetEntityId);

        Map<String, Object> existing = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM entity_relationships WHERE source_entity_id = ? AND target_entity_id = ? AND relationship_type = ?")) {
            ps.setString(1, sourceEntityId);
            ps.setString(2, targetEntityId);
            ps.setString(3, relationshipType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    existing = rowToMap(rs);
                    relId = rs.getString("relationship_id");
                }
            }
        }

        if (existing != null) {
            String updatedArtifacts = appendToJsonArray((String) existing.get("source_artifact_ids"), artifactId);
            String updatedChunks = chunkId != null
                    ? appendToJsonArray((String) existing.get("source_chunk_ids"), chunkId)
                    : (String) existing.get("source_chunk_ids");
            double newWeight = ((Number) existing.get("weight")).doubleValue() + weight;

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM entity_relationships WHERE relationship_id = '" + esc(relId) + "'");
            }
            double[] emb = description != null ? embeddingService.embed(description) : null;
            insertRelationship(conn, relId, sourceEntityId, targetEntityId, relationshipType,
                    description, newWeight, updatedArtifacts, updatedChunks, emb);
            return Map.of("relationship_id", relId, "action", "merged", "weight", newWeight);
        }

        double[] emb = description != null ? embeddingService.embed(description) : null;
        String artifactIds = appendToJsonArray(null, artifactId);
        String chunkIds = chunkId != null ? appendToJsonArray(null, chunkId) : null;
        insertRelationship(conn, relId, sourceEntityId, targetEntityId, relationshipType,
                description, weight, artifactIds, chunkIds, emb);
        return Map.of("relationship_id", relId, "action", "created", "weight", weight);
    }

    public Map<String, Object> getEntity(String entityId) throws SQLException {
        Connection conn = dataSource.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM entities WHERE entity_id = ?")) {
            ps.setString(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMap(rs);
            }
        }
        return null;
    }

    public List<Map<String, Object>> findEntitiesByName(String namePattern) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM entities WHERE LOWER(entity_name) LIKE LOWER(?)")) {
            ps.setString(1, "%" + namePattern + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(rowToMap(rs));
            }
        }
        return results;
    }

    public List<Map<String, Object>> findEntitiesByType(String entityType) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM entities WHERE entity_type = ?")) {
            ps.setString(1, entityType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(rowToMap(rs));
            }
        }
        return results;
    }

    public List<Map<String, Object>> getRelationships(String entityId) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM entity_relationships WHERE source_entity_id = ? OR target_entity_id = ?")) {
            ps.setString(1, entityId);
            ps.setString(2, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(rowToMap(rs));
            }
        }
        return results;
    }

    public Map<String, Object> getNeighborhood(String entityId, int depth) throws SQLException {
        Connection conn = dataSource.getConnection();
        Set<String> visited = new LinkedHashSet<>();
        visited.add(entityId);
        Set<String> frontier = new HashSet<>();
        frontier.add(entityId);
        List<Map<String, Object>> edges = new ArrayList<>();

        for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            Set<String> nextFrontier = new HashSet<>();
            for (String nodeId : frontier) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT * FROM entity_relationships WHERE source_entity_id = ? OR target_entity_id = ?")) {
                    ps.setString(1, nodeId);
                    ps.setString(2, nodeId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> edge = rowToMap(rs);
                            edges.add(edge);
                            String src = (String) edge.get("source_entity_id");
                            String tgt = (String) edge.get("target_entity_id");
                            String other = src.equals(nodeId) ? tgt : src;
                            if (!visited.contains(other)) {
                                visited.add(other);
                                nextFrontier.add(other);
                            }
                        }
                    }
                }
            }
            frontier = nextFrontier;
        }

        // Fetch full entity details for all visited nodes
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String nodeId : visited) {
            Map<String, Object> entity = getEntity(nodeId);
            if (entity != null) nodes.add(entity);
        }

        // Deduplicate edges by relationship_id
        Map<String, Map<String, Object>> uniqueEdges = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            uniqueEdges.putIfAbsent((String) edge.get("relationship_id"), edge);
        }

        return Map.of("nodes", nodes, "edges", new ArrayList<>(uniqueEdges.values()));
    }

    public Map<String, Object> getPath(String fromEntityId, String toEntityId) throws SQLException {
        Connection conn = dataSource.getConnection();
        Map<String, String> parentMap = new LinkedHashMap<>();
        Map<String, Map<String, Object>> parentEdge = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(fromEntityId);
        visited.add(fromEntityId);
        boolean found = false;

        while (!queue.isEmpty() && !found) {
            String current = queue.poll();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM entity_relationships WHERE source_entity_id = ? OR target_entity_id = ?")) {
                ps.setString(1, current);
                ps.setString(2, current);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> edge = rowToMap(rs);
                        String src = (String) edge.get("source_entity_id");
                        String tgt = (String) edge.get("target_entity_id");
                        String neighbor = src.equals(current) ? tgt : src;
                        if (!visited.contains(neighbor)) {
                            visited.add(neighbor);
                            parentMap.put(neighbor, current);
                            parentEdge.put(neighbor, edge);
                            if (neighbor.equals(toEntityId)) {
                                found = true;
                                break;
                            }
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }

        if (!found) return Map.of("found", false, "path", List.of(), "edges", List.of());

        // Reconstruct path
        List<String> path = new ArrayList<>();
        List<Map<String, Object>> pathEdges = new ArrayList<>();
        String node = toEntityId;
        while (node != null) {
            path.add(node);
            if (parentEdge.containsKey(node)) pathEdges.add(parentEdge.get(node));
            node = parentMap.get(node);
        }
        Collections.reverse(path);
        Collections.reverse(pathEdges);
        return Map.of("found", true, "path", path, "edges", pathEdges);
    }

    public List<Map<String, Object>> getEntitiesForArtifact(String artifactId) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT entity_id, entity_name, entity_type, description, mention_count " +
                "FROM entities WHERE source_artifact_ids LIKE ?")) {
            ps.setString(1, "%\"" + artifactId + "\"%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("entity_id", rs.getString("entity_id"));
                    row.put("entity_name", rs.getString("entity_name"));
                    row.put("entity_type", rs.getString("entity_type"));
                    row.put("description", rs.getString("description"));
                    row.put("mention_count", rs.getInt("mention_count"));
                    results.add(row);
                }
            }
        }
        return results;
    }

    public Map<String, Object> getStats() throws SQLException {
        Connection conn = dataSource.getConnection();
        Map<String, Object> stats = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM entities")) {
                rs.next();
                stats.put("entity_count", rs.getLong("cnt"));
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM entity_relationships")) {
                rs.next();
                stats.put("relationship_count", rs.getLong("cnt"));
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT entity_type, COUNT(*) AS cnt FROM entities GROUP BY entity_type ORDER BY cnt DESC LIMIT 10")) {
                List<Map<String, Object>> topTypes = new ArrayList<>();
                while (rs.next()) {
                    topTypes.add(Map.of("type", rs.getString("entity_type"), "count", rs.getLong("cnt")));
                }
                stats.put("top_types", topTypes);
            }
        }
        return stats;
    }

    public Map<String, Object> deleteEntitiesForArtifact(String artifactId) throws SQLException {
        Connection conn = dataSource.getConnection();
        int deletedEntities = 0;
        int deletedRelationships = 0;
        int updatedEntities = 0;

        // Find entities sourced from this artifact
        List<Map<String, Object>> entities = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM entities WHERE source_artifact_ids LIKE ?")) {
            ps.setString(1, "%\"" + artifactId + "\"%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) entities.add(rowToMap(rs));
            }
        }

        for (Map<String, Object> entity : entities) {
            String eid = (String) entity.get("entity_id");
            String sources = (String) entity.get("source_artifact_ids");
            String updated = removeFromJsonArray(sources, artifactId);
            int newCount = ((Number) entity.get("mention_count")).intValue() - 1;

            if (updated == null || updated.equals("[]") || newCount <= 0) {
                // Delete entity and its relationships
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM entity_relationships WHERE source_entity_id = '" + esc(eid) + "' OR target_entity_id = '" + esc(eid) + "'");
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM entities WHERE entity_id = '" + esc(eid) + "'");
                }
                deletedEntities++;
                // Count deleted relationships
                deletedRelationships += getRelationshipCountForDeletedEntity(entity);
            } else {
                // Update entity with decremented count
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM entities WHERE entity_id = '" + esc(eid) + "'");
                }
                double[] emb = entity.get("description") != null
                        ? embeddingService.embed((String) entity.get("description")) : null;
                insertEntity(conn, eid, (String) entity.get("entity_name"),
                        (String) entity.get("entity_type"), (String) entity.get("description"),
                        (String) entity.get("summary"), updated,
                        (String) entity.get("source_chunk_ids"), newCount, emb);
                updatedEntities++;
            }
        }

        // Also clean relationships sourced only from this artifact
        List<Map<String, Object>> rels = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM entity_relationships WHERE source_artifact_ids LIKE ?")) {
            ps.setString(1, "%\"" + artifactId + "\"%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rels.add(rowToMap(rs));
            }
        }
        for (Map<String, Object> rel : rels) {
            String rid = (String) rel.get("relationship_id");
            String sources = (String) rel.get("source_artifact_ids");
            String updated = removeFromJsonArray(sources, artifactId);
            if (updated == null || updated.equals("[]")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM entity_relationships WHERE relationship_id = '" + esc(rid) + "'");
                }
                deletedRelationships++;
            }
        }

        return Map.of("deleted_entities", deletedEntities, "deleted_relationships", deletedRelationships,
                "updated_entities", updatedEntities);
    }

    public Map<String, Object> mergeEntities(String sourceEntityId, String targetEntityId,
                                              String mergedDescription) throws SQLException {
        Connection conn = dataSource.getConnection();
        Map<String, Object> source = getEntity(sourceEntityId);
        Map<String, Object> target = getEntity(targetEntityId);
        if (source == null || target == null) {
            return Map.of("error", "One or both entities not found");
        }

        // Merge metadata
        String mergedArtifacts = mergeJsonArrays(
                (String) source.get("source_artifact_ids"), (String) target.get("source_artifact_ids"));
        String mergedChunks = mergeJsonArrays(
                (String) source.get("source_chunk_ids"), (String) target.get("source_chunk_ids"));
        int mergedCount = ((Number) source.get("mention_count")).intValue()
                + ((Number) target.get("mention_count")).intValue();

        // Rewire relationships from source to target
        int rewired = 0;
        List<Map<String, Object>> sourceRels = getRelationships(sourceEntityId);
        for (Map<String, Object> rel : sourceRels) {
            String rid = (String) rel.get("relationship_id");
            String src = (String) rel.get("source_entity_id");
            String tgt = (String) rel.get("target_entity_id");
            String newSrc = src.equals(sourceEntityId) ? targetEntityId : src;
            String newTgt = tgt.equals(sourceEntityId) ? targetEntityId : tgt;
            // Skip self-loops
            if (newSrc.equals(newTgt)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM entity_relationships WHERE relationship_id = '" + esc(rid) + "'");
                }
                continue;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM entity_relationships WHERE relationship_id = '" + esc(rid) + "'");
            }
            double[] emb = rel.get("description") != null
                    ? embeddingService.embed((String) rel.get("description")) : null;
            insertRelationship(conn, rid, newSrc, newTgt,
                    (String) rel.get("relationship_type"), (String) rel.get("description"),
                    ((Number) rel.get("weight")).doubleValue(),
                    (String) rel.get("source_artifact_ids"), (String) rel.get("source_chunk_ids"), emb);
            rewired++;
        }

        // Delete source entity
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM entities WHERE entity_id = '" + esc(sourceEntityId) + "'");
        }

        // Update target entity
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM entities WHERE entity_id = '" + esc(targetEntityId) + "'");
        }
        double[] emb = mergedDescription != null ? embeddingService.embed(mergedDescription) : null;
        insertEntity(conn, targetEntityId, (String) target.get("entity_name"),
                (String) target.get("entity_type"), mergedDescription,
                (String) target.get("summary"), mergedArtifacts, mergedChunks, mergedCount, emb);

        return Map.of("merged_into", targetEntityId, "source_deleted", sourceEntityId,
                "relationships_rewired", rewired, "mention_count", mergedCount);
    }

    // ── Duplicate detection (Chapter 5) ─────────────────────────────────────

    public List<Map<String, Object>> findDuplicateCandidates() throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> allEntities = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT entity_id, entity_name, entity_type FROM entities")) {
            while (rs.next()) {
                allEntities.add(Map.of(
                        "entity_id", rs.getString("entity_id"),
                        "entity_name", rs.getString("entity_name"),
                        "entity_type", rs.getString("entity_type")));
            }
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 0; i < allEntities.size(); i++) {
            for (int j = i + 1; j < allEntities.size(); j++) {
                Map<String, Object> a = allEntities.get(i);
                Map<String, Object> b = allEntities.get(j);
                String nameA = (String) a.get("entity_name");
                String nameB = (String) b.get("entity_name");

                double confidence = 0;
                String reason = null;

                if (nameA.equalsIgnoreCase(nameB) && !nameA.equals(nameB)) {
                    confidence = 1.0;
                    reason = "exact name match (case-insensitive)";
                } else if (!nameA.equalsIgnoreCase(nameB)) {
                    int dist = levenshteinDistance(nameA.toLowerCase(), nameB.toLowerCase());
                    if (dist > 0 && dist <= 2) {
                        confidence = 0.8;
                        reason = "Levenshtein distance " + dist;
                    }
                }

                if (confidence > 0) {
                    Map<String, Object> candidate = new LinkedHashMap<>();
                    candidate.put("source_entity_id", a.get("entity_id"));
                    candidate.put("target_entity_id", b.get("entity_id"));
                    candidate.put("source_name", nameA);
                    candidate.put("target_name", nameB);
                    candidate.put("confidence", confidence);
                    candidate.put("reason", reason);
                    candidates.add(candidate);
                }
            }
        }

        candidates.sort((x, y) -> Double.compare((double) y.get("confidence"), (double) x.get("confidence")));
        if (candidates.size() > 50) candidates = new ArrayList<>(candidates.subList(0, 50));
        return candidates;
    }

    public List<Map<String, Object>> findMergeCandidates(String entityId) throws SQLException {
        Connection conn = dataSource.getConnection();
        double[] targetEmb = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT embedding FROM entities WHERE entity_id = ?")) {
            ps.setString(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) targetEmb = extractEmbedding(rs.getObject("embedding"));
            }
        }
        if (targetEmb == null) return List.of();

        List<Map<String, Object>> candidates = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT entity_id, entity_name, entity_type, embedding FROM entities WHERE entity_id != ?")) {
            ps.setString(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double[] otherEmb = extractEmbedding(rs.getObject("embedding"));
                    if (otherEmb == null) continue;
                    double sim = cosineSimilarity(targetEmb, otherEmb);
                    if (sim > 0.85) {
                        Map<String, Object> candidate = new LinkedHashMap<>();
                        candidate.put("entity_id", rs.getString("entity_id"));
                        candidate.put("entity_name", rs.getString("entity_name"));
                        candidate.put("entity_type", rs.getString("entity_type"));
                        candidate.put("similarity", sim);
                        candidates.add(candidate);
                    }
                }
            }
        }
        candidates.sort((a, b) -> Double.compare((double) b.get("similarity"), (double) a.get("similarity")));
        return candidates;
    }

    static int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    static double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private double[] extractEmbedding(Object embObj) {
        if (embObj == null) return null;
        if (embObj instanceof double[] arr) return arr;
        if (embObj instanceof Object[] objArr) {
            double[] result = new double[objArr.length];
            for (int i = 0; i < objArr.length; i++) result[i] = ((Number) objArr[i]).doubleValue();
            return result;
        }
        if (embObj instanceof java.sql.Array sqlArr) {
            try {
                Object arr = sqlArr.getArray();
                return extractEmbedding(arr);
            } catch (Exception e) { return null; }
        }
        return null;
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void insertEntity(Connection conn, String entityId, String entityName, String entityType,
                              String description, String summary, String sourceArtifactIds,
                              String sourceChunkIds, int mentionCount, double[] embedding) throws SQLException {
        String embSql = embeddingToSql(embedding);
        String sql = "INSERT INTO entities (entity_id, entity_name, entity_type, description, summary, "
                + "source_artifact_ids, source_chunk_ids, mention_count, embedding, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + embSql + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entityId);
            ps.setString(2, entityName);
            ps.setString(3, entityType);
            ps.setString(4, description);
            ps.setString(5, summary);
            ps.setString(6, sourceArtifactIds);
            ps.setString(7, sourceChunkIds);
            ps.setInt(8, mentionCount);
            ps.executeUpdate();
        }
    }

    private void insertRelationship(Connection conn, String relId, String sourceEntityId,
                                     String targetEntityId, String relType, String description,
                                     double weight, String sourceArtifactIds, String sourceChunkIds,
                                     double[] embedding) throws SQLException {
        String embSql = embeddingToSql(embedding);
        String sql = "INSERT INTO entity_relationships (relationship_id, source_entity_id, target_entity_id, "
                + "relationship_type, description, weight, source_artifact_ids, source_chunk_ids, "
                + "embedding, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + embSql + ", CURRENT_TIMESTAMP)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, relId);
            ps.setString(2, sourceEntityId);
            ps.setString(3, targetEntityId);
            ps.setString(4, relType);
            ps.setString(5, description);
            ps.setDouble(6, weight);
            ps.setString(7, sourceArtifactIds);
            ps.setString(8, sourceChunkIds);
            ps.executeUpdate();
        }
    }

    private String embeddingToSql(double[] embedding) {
        if (embedding == null) return "NULL";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]::DOUBLE[]");
        return sb.toString();
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String col = meta.getColumnName(i).toLowerCase();
            if (col.equals("embedding")) continue; // skip large arrays in map output
            map.put(col, rs.getObject(i));
        }
        return map;
    }

    private String slugify(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
    }

    private String appendToJsonArray(String existing, String value) {
        if (value == null) return existing;
        if (existing == null || existing.isBlank()) return "[\"" + value + "\"]";
        if (existing.contains("\"" + value + "\"")) return existing;
        return existing.substring(0, existing.length() - 1) + ",\"" + value + "\"]";
    }

    private String removeFromJsonArray(String jsonArray, String value) {
        if (jsonArray == null || jsonArray.isBlank()) return null;
        String result = jsonArray.replace(",\"" + value + "\"", "").replace("\"" + value + "\",", "")
                .replace("[\"" + value + "\"]", "[]").replace("\"" + value + "\"", "");
        // Clean up leftover commas
        result = result.replace("[,", "[").replace(",]", "]");
        return result;
    }

    private String mergeJsonArrays(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        // Simple merge: parse values from b and append to a
        String result = a;
        String stripped = b.substring(1, b.length() - 1); // remove [ ]
        for (String token : stripped.split(",")) {
            String val = token.trim().replace("\"", "");
            if (!val.isEmpty()) result = appendToJsonArray(result, val);
        }
        return result;
    }

    private int getRelationshipCountForDeletedEntity(Map<String, Object> entity) {
        // Approximate: we already deleted, just return 0 for bookkeeping
        return 0;
    }

    private String esc(String s) {
        return s.replace("'", "''");
    }
}
