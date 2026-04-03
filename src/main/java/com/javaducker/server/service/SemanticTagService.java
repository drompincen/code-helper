package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
public class SemanticTagService {

    private static final Logger log = LoggerFactory.getLogger(SemanticTagService.class);
    private static final Set<String> VALID_CATEGORIES = Set.of(
            "functional", "architectural", "domain", "pattern", "concern");

    private final DuckDBDataSource dataSource;

    public SemanticTagService(DuckDBDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Map<String, Object> writeTags(String artifactId, List<Map<String, Object>> tags) throws SQLException {
        if (tags.size() < 4 || tags.size() > 10) {
            throw new IllegalArgumentException(
                    "Tags count must be between 4 and 10, got " + tags.size());
        }
        for (Map<String, Object> t : tags) {
            String category = (String) t.get("category");
            if (category == null || !VALID_CATEGORIES.contains(category)) {
                throw new IllegalArgumentException(
                        "Invalid category: " + category + ". Valid: " + VALID_CATEGORIES);
            }
        }

        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM artifact_semantic_tags WHERE artifact_id = '" + esc(artifactId) + "'");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO artifact_semantic_tags (artifact_id, tag, category, confidence, rationale, source) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Map<String, Object> t : tags) {
                ps.setString(1, artifactId);
                ps.setString(2, (String) t.get("tag"));
                ps.setString(3, (String) t.get("category"));
                ps.setDouble(4, t.containsKey("confidence") && t.get("confidence") != null
                        ? ((Number) t.get("confidence")).doubleValue() : 1.0);
                ps.setString(5, (String) t.getOrDefault("rationale", null));
                ps.setString(6, (String) t.getOrDefault("source", "llm"));
                ps.executeUpdate();
            }
        }
        return Map.of("artifact_id", artifactId, "tags_count", tags.size());
    }

    public List<Map<String, Object>> findByTag(String tag) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT st.artifact_id, st.tag, st.category, st.confidence, st.rationale, st.source, st.created_at,
                       a.file_name
                FROM artifact_semantic_tags st
                JOIN artifacts a ON st.artifact_id = a.artifact_id
                WHERE st.tag = ?
                ORDER BY st.confidence DESC
                """)) {
            ps.setString(1, tag);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("artifact_id", rs.getString("artifact_id"));
                    row.put("tag", rs.getString("tag"));
                    row.put("category", rs.getString("category"));
                    row.put("confidence", rs.getDouble("confidence"));
                    row.put("rationale", rs.getString("rationale"));
                    row.put("source", rs.getString("source"));
                    row.put("file_name", rs.getString("file_name"));
                    results.add(row);
                }
            }
        }
        return results;
    }

    public List<Map<String, Object>> findByCategory(String category) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT st.artifact_id, st.tag, st.category, st.confidence, st.rationale, st.source,
                       a.file_name
                FROM artifact_semantic_tags st
                JOIN artifacts a ON st.artifact_id = a.artifact_id
                WHERE st.category = ?
                ORDER BY st.confidence DESC
                """)) {
            ps.setString(1, category);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("artifact_id", rs.getString("artifact_id"));
                    row.put("tag", rs.getString("tag"));
                    row.put("category", rs.getString("category"));
                    row.put("confidence", rs.getDouble("confidence"));
                    row.put("file_name", rs.getString("file_name"));
                    results.add(row);
                }
            }
        }
        return results;
    }

    public List<Map<String, Object>> searchByTags(List<String> tags, boolean matchAll) throws SQLException {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) placeholders.append(", ");
            placeholders.append("?");
        }

        String sql;
        if (matchAll) {
            sql = """
                SELECT st.artifact_id, a.file_name,
                       STRING_AGG(st.tag, ', ') as matched_tags,
                       COUNT(DISTINCT st.tag) as match_count
                FROM artifact_semantic_tags st
                JOIN artifacts a ON st.artifact_id = a.artifact_id
                WHERE st.tag IN (%s)
                GROUP BY st.artifact_id, a.file_name
                HAVING COUNT(DISTINCT st.tag) = ?
                ORDER BY match_count DESC
                """.formatted(placeholders);
        } else {
            sql = """
                SELECT st.artifact_id, a.file_name,
                       STRING_AGG(st.tag, ', ') as matched_tags,
                       COUNT(DISTINCT st.tag) as match_count
                FROM artifact_semantic_tags st
                JOIN artifacts a ON st.artifact_id = a.artifact_id
                WHERE st.tag IN (%s)
                GROUP BY st.artifact_id, a.file_name
                ORDER BY match_count DESC
                """.formatted(placeholders);
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String tag : tags) {
                ps.setString(idx++, tag);
            }
            if (matchAll) {
                ps.setInt(idx, tags.size());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(Map.of(
                            "artifact_id", rs.getString("artifact_id"),
                            "file_name", rs.getString("file_name"),
                            "matched_tags", rs.getString("matched_tags"),
                            "match_count", rs.getInt("match_count")));
                }
            }
        }
        return results;
    }

    public List<Map<String, Object>> getTagsForArtifact(String artifactId) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tag, category, confidence, rationale, source FROM artifact_semantic_tags WHERE artifact_id = ?")) {
            ps.setString(1, artifactId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("tag", rs.getString("tag"));
                    row.put("category", rs.getString("category"));
                    row.put("confidence", rs.getDouble("confidence"));
                    row.put("rationale", rs.getString("rationale"));
                    row.put("source", rs.getString("source"));
                    results.add(row);
                }
            }
        }
        return results;
    }

    public Map<String, Object> getTagCloud() throws SQLException {
        Connection conn = dataSource.getConnection();
        Map<String, List<Map<String, Object>>> byCategory = new LinkedHashMap<>();
        int totalTags = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                SELECT tag, category, COUNT(*) as count
                FROM artifact_semantic_tags
                GROUP BY tag, category
                ORDER BY count DESC
                """)) {
            while (rs.next()) {
                String category = rs.getString("category");
                byCategory.computeIfAbsent(category, k -> new ArrayList<>())
                        .add(Map.of(
                                "tag", rs.getString("tag"),
                                "count", rs.getInt("count")));
                totalTags++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categories", byCategory);
        result.put("total_tags", totalTags);
        return result;
    }

    public List<Map<String, Object>> suggestTags(String artifactId) throws SQLException {
        Connection conn = dataSource.getConnection();

        // Step 1: Get tags for this artifact
        List<String> myTags = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tag FROM artifact_semantic_tags WHERE artifact_id = ?")) {
            ps.setString(1, artifactId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    myTags.add(rs.getString("tag"));
                }
            }
        }
        if (myTags.isEmpty()) {
            return List.of();
        }

        // Step 2: Find other artifacts sharing those tags
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < myTags.size(); i++) {
            if (i > 0) placeholders.append(", ");
            placeholders.append("?");
        }

        // Step 3: Get tags from those artifacts that this artifact doesn't have
        String sql = """
            SELECT st.tag, st.category, COUNT(*) as frequency
            FROM artifact_semantic_tags st
            WHERE st.artifact_id IN (
                SELECT DISTINCT artifact_id FROM artifact_semantic_tags
                WHERE tag IN (%s) AND artifact_id != ?
            )
            AND st.tag NOT IN (%s)
            GROUP BY st.tag, st.category
            ORDER BY frequency DESC
            """.formatted(placeholders, placeholders);

        List<Map<String, Object>> suggestions = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String tag : myTags) {
                ps.setString(idx++, tag);
            }
            ps.setString(idx++, artifactId);
            for (String tag : myTags) {
                ps.setString(idx++, tag);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    suggestions.add(Map.of(
                            "tag", rs.getString("tag"),
                            "category", rs.getString("category"),
                            "frequency", rs.getInt("frequency")));
                }
            }
        }
        return suggestions;
    }

    private String esc(String s) {
        return s.replace("'", "''");
    }
}
