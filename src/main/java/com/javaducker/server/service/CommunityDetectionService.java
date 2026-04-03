package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
public class CommunityDetectionService {

    private static final Logger log = LoggerFactory.getLogger(CommunityDetectionService.class);
    private static final int MAX_ITERATIONS = 20;
    private final DuckDBDataSource dataSource;

    public CommunityDetectionService(DuckDBDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Detect communities using label propagation algorithm.
     * 1. Build adjacency list from entity_relationships
     * 2. Initialize each node with its own label
     * 3. Iterate: each node adopts the most frequent label among neighbors
     * 4. Converge when labels stop changing (or max 20 iterations)
     * 5. Group nodes by label -> communities
     * 6. Store in entity_communities table (DELETE existing, INSERT new)
     * 7. Name each community after its most-mentioned entity
     */
    public Map<String, Object> detectCommunities() throws SQLException {
        Connection conn = dataSource.getConnection();

        // 1. Load all entities
        Map<String, String> labels = new LinkedHashMap<>(); // entityId -> label
        Map<String, String> entityNames = new LinkedHashMap<>(); // entityId -> name
        Map<String, Integer> mentionCounts = new LinkedHashMap<>(); // entityId -> mention_count
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT entity_id, entity_name, mention_count FROM entities")) {
            while (rs.next()) {
                String id = rs.getString("entity_id");
                labels.put(id, id); // initialize label = own id
                entityNames.put(id, rs.getString("entity_name"));
                mentionCounts.put(id, rs.getInt("mention_count"));
            }
        }

        if (labels.isEmpty()) {
            return Map.of("communities_detected", 0, "message", "No entities found");
        }

        // 2. Build adjacency list from entity_relationships
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (String id : labels.keySet()) {
            adj.put(id, new ArrayList<>());
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT source_entity_id, target_entity_id FROM entity_relationships")) {
            while (rs.next()) {
                String src = rs.getString("source_entity_id");
                String tgt = rs.getString("target_entity_id");
                if (adj.containsKey(src)) adj.get(src).add(tgt);
                if (adj.containsKey(tgt)) adj.get(tgt).add(src);
            }
        }

        // 3. Label propagation iterations
        int iterations = 0;
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            boolean changed = false;
            List<String> nodeIds = new ArrayList<>(labels.keySet());
            Collections.shuffle(nodeIds);
            for (String nodeId : nodeIds) {
                List<String> neighbors = adj.getOrDefault(nodeId, List.of());
                if (neighbors.isEmpty()) continue;
                // Count labels among neighbors
                Map<String, Integer> labelCounts = new HashMap<>();
                for (String n : neighbors) {
                    String nLabel = labels.get(n);
                    if (nLabel != null) {
                        labelCounts.merge(nLabel, 1, Integer::sum);
                    }
                }
                if (labelCounts.isEmpty()) continue;
                String bestLabel = Collections.max(labelCounts.entrySet(),
                        Map.Entry.comparingByValue()).getKey();
                if (!bestLabel.equals(labels.get(nodeId))) {
                    labels.put(nodeId, bestLabel);
                    changed = true;
                }
            }
            iterations++;
            if (!changed) break;
        }

        // 4. Group by label -> communities
        Map<String, List<String>> communityMap = new LinkedHashMap<>();
        labels.forEach((entityId, label) ->
                communityMap.computeIfAbsent(label, k -> new ArrayList<>()).add(entityId));

        // 5. Filter: only keep communities with >= 2 members
        communityMap.entrySet().removeIf(e -> e.getValue().size() < 2);

        // 6. Store in entity_communities table
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM entity_communities");
        }

        int communityCount = 0;
        for (Map.Entry<String, List<String>> entry : communityMap.entrySet()) {
            List<String> memberIds = entry.getValue();
            communityCount++;
            String communityId = "community-" + communityCount;

            // Name after the most-mentioned entity
            String communityName = memberIds.stream()
                    .max(Comparator.comparingInt(id -> mentionCounts.getOrDefault(id, 0)))
                    .map(entityNames::get)
                    .orElse("Community " + communityCount);

            // Build JSON array of entity IDs
            StringBuilder entityIdsJson = new StringBuilder("[");
            for (int i = 0; i < memberIds.size(); i++) {
                if (i > 0) entityIdsJson.append(",");
                entityIdsJson.append("\"").append(memberIds.get(i)).append("\"");
            }
            entityIdsJson.append("]");

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO entity_communities (community_id, community_name, entity_ids, " +
                            "level, created_at) VALUES (?, ?, ?, 0, CURRENT_TIMESTAMP)")) {
                ps.setString(1, communityId);
                ps.setString(2, communityName);
                ps.setString(3, entityIdsJson.toString());
                ps.executeUpdate();
            }
        }

        log.info("Detected {} communities in {} iterations", communityCount, iterations);
        return Map.of(
                "communities_detected", communityCount,
                "iterations", iterations,
                "total_entities", labels.size());
    }

    /**
     * Get community by ID with member entity details.
     */
    public Map<String, Object> getCommunity(String communityId) throws SQLException {
        Connection conn = dataSource.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM entity_communities WHERE community_id = ?")) {
            ps.setString(1, communityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> community = rowToMap(rs);
                    // Parse entity_ids and fetch entity details
                    String entityIdsStr = (String) community.get("entity_ids");
                    if (entityIdsStr != null && !entityIdsStr.isBlank()) {
                        List<Map<String, Object>> members = new ArrayList<>();
                        List<String> ids = parseJsonArray(entityIdsStr);
                        for (String entityId : ids) {
                            try (PreparedStatement eps = conn.prepareStatement(
                                    "SELECT entity_id, entity_name, entity_type, description, " +
                                            "mention_count FROM entities WHERE entity_id = ?")) {
                                eps.setString(1, entityId);
                                try (ResultSet ers = eps.executeQuery()) {
                                    if (ers.next()) members.add(rowToMap(ers));
                                }
                            }
                        }
                        community.put("members", members);
                    }
                    return community;
                }
            }
        }
        return null;
    }

    /**
     * List all communities with member counts.
     */
    public List<Map<String, Object>> getCommunities() throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT * FROM entity_communities ORDER BY level, community_name")) {
            while (rs.next()) {
                Map<String, Object> community = rowToMap(rs);
                String entityIdsStr = (String) community.get("entity_ids");
                int memberCount = entityIdsStr != null ? parseJsonArray(entityIdsStr).size() : 0;
                community.put("member_count", memberCount);
                results.add(community);
            }
        }
        return results;
    }

    /**
     * Store/update a community summary (Claude generates the text).
     */
    public Map<String, Object> summarizeCommunity(String communityId, String summary)
            throws SQLException {
        Connection conn = dataSource.getConnection();

        // Read existing community
        Map<String, Object> existing = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM entity_communities WHERE community_id = ?")) {
            ps.setString(1, communityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) existing = rowToMap(rs);
            }
        }
        if (existing == null) {
            return Map.of("error", "Community not found: " + communityId);
        }

        // DELETE + INSERT to update summary
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM entity_communities WHERE community_id = '"
                    + esc(communityId) + "'");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO entity_communities (community_id, community_name, summary, " +
                        "entity_ids, level, parent_community_id, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
            ps.setString(1, communityId);
            ps.setString(2, (String) existing.get("community_name"));
            ps.setString(3, summary);
            ps.setString(4, (String) existing.get("entity_ids"));
            ps.setInt(5, existing.get("level") != null
                    ? ((Number) existing.get("level")).intValue() : 0);
            ps.setString(6, (String) existing.get("parent_community_id"));
            ps.executeUpdate();
        }

        return Map.of("community_id", communityId, "summary_stored", true);
    }

    /**
     * Clear all communities for full re-detection.
     */
    public Map<String, Object> rebuildCommunities() throws SQLException {
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM entity_communities");
        }
        return detectCommunities();
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String col = meta.getColumnName(i).toLowerCase();
            map.put(col, rs.getObject(i));
        }
        return map;
    }

    static List<String> parseJsonArray(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.isBlank() || json.equals("[]")) return result;
        // Strip brackets
        String inner = json.substring(1, json.length() - 1);
        for (String token : inner.split(",")) {
            String val = token.trim().replace("\"", "");
            if (!val.isEmpty()) result.add(val);
        }
        return result;
    }

    private String esc(String s) {
        return s.replace("'", "''");
    }
}
