package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.ingestion.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Service
public class GraphSearchService {

    private static final Logger log = LoggerFactory.getLogger(GraphSearchService.class);
    private final DuckDBDataSource dataSource;
    private final EmbeddingService embeddingService;
    private final KnowledgeGraphService knowledgeGraphService;

    public GraphSearchService(DuckDBDataSource dataSource,
                              EmbeddingService embeddingService,
                              KnowledgeGraphService knowledgeGraphService) {
        this.dataSource = dataSource;
        this.embeddingService = embeddingService;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /**
     * Local search: entity-centric retrieval.
     * Embeds the query, scans entities table, computes cosine similarity,
     * returns top-K entities with descriptions, connected relationships, source chunks.
     */
    public List<Map<String, Object>> localSearch(String query, int topK) throws SQLException {
        double[] queryEmb = embeddingService.embed(query);
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> scored = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT entity_id, entity_name, entity_type, description, embedding, source_artifact_ids "
                        + "FROM entities WHERE embedding IS NOT NULL")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double[] emb = extractEmbedding(rs);
                    if (emb == null) continue;
                    double sim = cosineSimilarity(queryEmb, emb);
                    if (sim > 0.01) {
                        Map<String, Object> hit = new LinkedHashMap<>();
                        hit.put("entity_id", rs.getString("entity_id"));
                        hit.put("entity_name", rs.getString("entity_name"));
                        hit.put("entity_type", rs.getString("entity_type"));
                        hit.put("description", rs.getString("description"));
                        hit.put("score", sim);
                        hit.put("source_files", rs.getString("source_artifact_ids"));
                        hit.put("match_type", "LOCAL");
                        scored.add(hit);
                    }
                }
            }
        }

        scored.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        List<Map<String, Object>> topResults = scored.size() > topK
                ? new ArrayList<>(scored.subList(0, topK)) : new ArrayList<>(scored);

        // Enrich each top entity with its relationships
        for (Map<String, Object> hit : topResults) {
            try {
                List<Map<String, Object>> rels = knowledgeGraphService
                        .getRelationships((String) hit.get("entity_id"));
                hit.put("relationships", rels);
            } catch (SQLException e) {
                log.warn("Failed to fetch relationships for {}", hit.get("entity_id"), e);
                hit.put("relationships", List.of());
            }
        }

        return topResults;
    }

    /**
     * Global search: relationship-centric retrieval.
     * Embeds the query, scans entity_relationships table, computes cosine similarity,
     * returns top-K relationships with source/target entity info.
     */
    public List<Map<String, Object>> globalSearch(String query, int topK) throws SQLException {
        double[] queryEmb = embeddingService.embed(query);
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> scored = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT relationship_id, source_entity_id, target_entity_id, "
                        + "relationship_type, description, embedding, source_artifact_ids "
                        + "FROM entity_relationships WHERE embedding IS NOT NULL")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double[] emb = extractEmbedding(rs);
                    if (emb == null) continue;
                    double sim = cosineSimilarity(queryEmb, emb);
                    if (sim > 0.01) {
                        Map<String, Object> hit = new LinkedHashMap<>();
                        hit.put("relationship_id", rs.getString("relationship_id"));
                        hit.put("source_entity_id", rs.getString("source_entity_id"));
                        hit.put("target_entity_id", rs.getString("target_entity_id"));
                        hit.put("relationship_type", rs.getString("relationship_type"));
                        hit.put("description", rs.getString("description"));
                        hit.put("score", sim);
                        hit.put("source_files", rs.getString("source_artifact_ids"));
                        hit.put("match_type", "GLOBAL");
                        scored.add(hit);
                    }
                }
            }
        }

        scored.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        List<Map<String, Object>> topResults = scored.size() > topK
                ? new ArrayList<>(scored.subList(0, topK)) : new ArrayList<>(scored);

        // Enrich with entity names
        for (Map<String, Object> hit : topResults) {
            enrichRelationshipWithEntityNames(hit);
        }

        return topResults;
    }

    /**
     * Hybrid graph search: combine local + global results.
     * Local entities weighted 0.6, global relationships weighted 0.4.
     * Deduplicates by entity_id, takes topK.
     */
    public List<Map<String, Object>> hybridGraphSearch(String query, int topK) throws SQLException {
        List<Map<String, Object>> local = localSearch(query, topK);
        List<Map<String, Object>> global = globalSearch(query, topK);

        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

        // Add local entity results with weight 0.6
        for (Map<String, Object> hit : local) {
            String entityId = (String) hit.get("entity_id");
            Map<String, Object> entry = new LinkedHashMap<>(hit);
            entry.put("score", (double) hit.get("score") * 0.6);
            entry.put("match_type", "GRAPH_HYBRID");
            merged.put(entityId, entry);
        }

        // Add global relationship endpoints with weight 0.4
        for (Map<String, Object> hit : global) {
            double weightedScore = (double) hit.get("score") * 0.4;
            String sourceId = (String) hit.get("source_entity_id");
            String targetId = (String) hit.get("target_entity_id");

            mergeEntityFromRelationship(merged, sourceId, weightedScore, hit);
            mergeEntityFromRelationship(merged, targetId, weightedScore, hit);
        }

        List<Map<String, Object>> results = new ArrayList<>(merged.values());
        results.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        return results.size() > topK ? results.subList(0, topK) : results;
    }

    /**
     * Mix search: combine graph search + chunk vector search.
     * Graph results weighted 0.5, chunk results weighted 0.5.
     * Deduplicates by artifact_id.
     */
    public List<Map<String, Object>> mixSearch(String query, int topK) throws SQLException {
        List<Map<String, Object>> graphResults = hybridGraphSearch(query, topK);
        List<Map<String, Object>> chunkResults = chunkSearch(query, topK);

        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

        // Graph results contribute via source_artifact_ids
        for (Map<String, Object> hit : graphResults) {
            String sourceFiles = (String) hit.get("source_files");
            if (sourceFiles == null) continue;
            double weightedScore = (double) hit.get("score") * 0.5;
            // Parse artifact IDs from JSON array string
            for (String artId : parseJsonArray(sourceFiles)) {
                if (merged.containsKey(artId)) {
                    Map<String, Object> existing = merged.get(artId);
                    existing.put("score", (double) existing.get("score") + weightedScore);
                    existing.put("match_type", "MIX");
                } else {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("artifact_id", artId);
                    entry.put("score", weightedScore);
                    entry.put("match_type", "MIX");
                    entry.put("graph_entity", hit.get("entity_name"));
                    entry.put("graph_description", hit.get("description"));
                    merged.put(artId, entry);
                }
            }
        }

        // Chunk results contribute directly
        for (Map<String, Object> hit : chunkResults) {
            String artId = (String) hit.get("artifact_id");
            double weightedScore = (double) hit.get("score") * 0.5;
            if (merged.containsKey(artId)) {
                Map<String, Object> existing = merged.get(artId);
                existing.put("score", (double) existing.get("score") + weightedScore);
                existing.put("match_type", "MIX");
                if (!existing.containsKey("preview")) {
                    existing.put("preview", hit.get("preview"));
                    existing.put("file_name", hit.get("file_name"));
                }
            } else {
                Map<String, Object> entry = new LinkedHashMap<>(hit);
                entry.put("score", weightedScore);
                entry.put("match_type", "MIX");
                merged.put(artId, entry);
            }
        }

        List<Map<String, Object>> results = new ArrayList<>(merged.values());
        results.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        return results.size() > topK ? results.subList(0, topK) : results;
    }

    // ── Private helpers ────────────────────────────────────────────────────

    List<Map<String, Object>> chunkSearch(String query, int topK) throws SQLException {
        double[] queryEmb = embeddingService.embed(query);
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> scored = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT ce.chunk_id, ce.embedding, ac.chunk_text, ac.artifact_id,
                       ac.line_start, ac.line_end, a.file_name
                FROM chunk_embeddings ce
                JOIN artifact_chunks ac ON ce.chunk_id = ac.chunk_id
                JOIN artifacts a ON ac.artifact_id = a.artifact_id
                WHERE a.status = 'INDEXED'
                AND COALESCE(a.freshness, 'current') != 'superseded'
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double[] emb = extractEmbedding(rs);
                    if (emb == null) continue;
                    double sim = cosineSimilarity(queryEmb, emb);
                    if (sim > 0.01) {
                        Map<String, Object> hit = new LinkedHashMap<>();
                        hit.put("chunk_id", rs.getString("chunk_id"));
                        hit.put("artifact_id", rs.getString("artifact_id"));
                        String text = rs.getString("chunk_text");
                        hit.put("preview", text != null && text.length() > 200
                                ? text.substring(0, 200) + "..." : text);
                        hit.put("file_name", rs.getString("file_name"));
                        hit.put("line_start", rs.getObject("line_start"));
                        hit.put("line_end", rs.getObject("line_end"));
                        hit.put("score", sim);
                        hit.put("match_type", "CHUNK");
                        scored.add(hit);
                    }
                }
            }
        }

        scored.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        return scored.size() > topK ? scored.subList(0, topK) : scored;
    }

    private void mergeEntityFromRelationship(Map<String, Map<String, Object>> merged,
                                              String entityId, double weightedScore,
                                              Map<String, Object> relHit) {
        if (entityId == null) return;
        if (merged.containsKey(entityId)) {
            Map<String, Object> existing = merged.get(entityId);
            existing.put("score", (double) existing.get("score") + weightedScore);
            existing.put("match_type", "GRAPH_HYBRID");
        } else {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("entity_id", entityId);
            entry.put("score", weightedScore);
            entry.put("match_type", "GRAPH_HYBRID");
            entry.put("source_files", relHit.get("source_files"));
            // Try to fetch entity details
            try {
                Map<String, Object> entity = knowledgeGraphService.getEntity(entityId);
                if (entity != null) {
                    entry.put("entity_name", entity.get("entity_name"));
                    entry.put("entity_type", entity.get("entity_type"));
                    entry.put("description", entity.get("description"));
                }
            } catch (SQLException e) {
                log.warn("Failed to fetch entity {}", entityId, e);
            }
            merged.put(entityId, entry);
        }
    }

    private void enrichRelationshipWithEntityNames(Map<String, Object> hit) {
        try {
            String sourceId = (String) hit.get("source_entity_id");
            String targetId = (String) hit.get("target_entity_id");
            Map<String, Object> sourceEntity = knowledgeGraphService.getEntity(sourceId);
            Map<String, Object> targetEntity = knowledgeGraphService.getEntity(targetId);
            if (sourceEntity != null) {
                hit.put("source_entity_name", sourceEntity.get("entity_name"));
                hit.put("source_entity_type", sourceEntity.get("entity_type"));
            }
            if (targetEntity != null) {
                hit.put("target_entity_name", targetEntity.get("entity_name"));
                hit.put("target_entity_type", targetEntity.get("entity_type"));
            }
        } catch (SQLException e) {
            log.warn("Failed to enrich relationship entity names", e);
        }
    }

    static List<String> parseJsonArray(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) return List.of();
        // Simple JSON array parser: ["val1","val2"]
        String stripped = jsonArray.trim();
        if (stripped.equals("[]")) return List.of();
        stripped = stripped.substring(1, stripped.length() - 1); // remove [ ]
        List<String> result = new ArrayList<>();
        for (String token : stripped.split(",")) {
            String val = token.trim().replace("\"", "");
            if (!val.isEmpty()) result.add(val);
        }
        return result;
    }

    private double[] extractEmbedding(ResultSet rs) throws SQLException {
        Object embObj = rs.getObject("embedding");
        if (embObj == null) return null;

        if (embObj instanceof double[] arr) {
            return arr;
        }

        if (embObj instanceof Object[] objArr) {
            double[] result = new double[objArr.length];
            for (int i = 0; i < objArr.length; i++) {
                result[i] = ((Number) objArr[i]).doubleValue();
            }
            return result;
        }

        if (embObj instanceof java.sql.Array sqlArray) {
            Object[] arr = (Object[]) sqlArray.getArray();
            double[] result = new double[arr.length];
            for (int i = 0; i < arr.length; i++) {
                result[i] = ((Number) arr[i]).doubleValue();
            }
            return result;
        }

        log.warn("Unexpected embedding type: {}", embObj.getClass());
        return null;
    }

    static double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
