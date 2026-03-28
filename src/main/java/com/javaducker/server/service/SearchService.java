package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.ingestion.EmbeddingService;
import com.javaducker.server.ingestion.HnswIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private final DuckDBDataSource dataSource;
    private final EmbeddingService embeddingService;
    private final AppConfig config;
    private volatile HnswIndex hnswIndex;

    public SearchService(DuckDBDataSource dataSource, EmbeddingService embeddingService, AppConfig config) {
        this.dataSource = dataSource;
        this.embeddingService = embeddingService;
        this.config = config;
    }

    public void setHnswIndex(HnswIndex index) {
        this.hnswIndex = index;
    }

    public HnswIndex getHnswIndex() {
        return hnswIndex;
    }

    public List<Map<String, Object>> exactSearch(String phrase, int maxResults) throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT ac.chunk_id, ac.artifact_id, ac.chunk_index, ac.chunk_text,
                       ac.line_start, ac.line_end, a.file_name
                FROM artifact_chunks ac
                JOIN artifacts a ON ac.artifact_id = a.artifact_id
                WHERE a.status = 'INDEXED'
                AND LOWER(ac.chunk_text) LIKE LOWER('%' || ? || '%')
                LIMIT ?
                """)) {
            ps.setString(1, phrase);
            ps.setInt(2, maxResults > 0 ? maxResults : config.getMaxSearchResults());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> hit = new HashMap<>();
                    hit.put("chunk_id", rs.getString("chunk_id"));
                    hit.put("artifact_id", rs.getString("artifact_id"));
                    hit.put("chunk_index", rs.getInt("chunk_index"));
                    hit.put("preview", truncatePreview(rs.getString("chunk_text"), phrase));
                    hit.put("file_name", rs.getString("file_name"));
                    hit.put("line_start", rs.getObject("line_start"));
                    hit.put("line_end", rs.getObject("line_end"));
                    hit.put("score", computeExactScore(rs.getString("chunk_text"), phrase));
                    hit.put("match_type", "EXACT");
                    results.add(hit);
                }
            }
        }

        results.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        return results;
    }

    public List<Map<String, Object>> semanticSearch(String phrase, int maxResults) throws SQLException {
        double[] queryEmbedding = embeddingService.embed(phrase);
        int limit = maxResults > 0 ? maxResults : config.getMaxSearchResults();

        // HNSW fast path
        if (hnswIndex != null && !hnswIndex.isEmpty()) {
            List<HnswIndex.Result> annResults = hnswIndex.search(queryEmbedding, limit);
            List<Map<String, Object>> results = new ArrayList<>();
            Connection conn = dataSource.getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT ac.chunk_id, ac.artifact_id, ac.chunk_index, ac.chunk_text,
                           ac.line_start, ac.line_end, a.file_name
                    FROM artifact_chunks ac
                    JOIN artifacts a ON ac.artifact_id = a.artifact_id
                    WHERE ac.chunk_id = ?
                    """)) {
                for (HnswIndex.Result annResult : annResults) {
                    ps.setString(1, annResult.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            Map<String, Object> hit = new HashMap<>();
                            hit.put("chunk_id", rs.getString("chunk_id"));
                            hit.put("artifact_id", rs.getString("artifact_id"));
                            hit.put("chunk_index", rs.getInt("chunk_index"));
                            String text = rs.getString("chunk_text");
                            hit.put("preview", text.length() > 200 ? text.substring(0, 200) + "..." : text);
                            hit.put("file_name", rs.getString("file_name"));
                            hit.put("line_start", rs.getObject("line_start"));
                            hit.put("line_end", rs.getObject("line_end"));
                            hit.put("score", 1.0 - annResult.distance());
                            hit.put("match_type", "SEMANTIC");
                            results.add(hit);
                        }
                    }
                }
            }
            return results;
        }

        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> results = new ArrayList<>();

        // Load all chunk embeddings and compute similarity in Java (brute force for v2)
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT ce.chunk_id, ce.embedding, ac.artifact_id, ac.chunk_index, ac.chunk_text,
                       ac.line_start, ac.line_end, a.file_name
                FROM chunk_embeddings ce
                JOIN artifact_chunks ac ON ce.chunk_id = ac.chunk_id
                JOIN artifacts a ON ac.artifact_id = a.artifact_id
                WHERE a.status = 'INDEXED'
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double[] chunkEmbedding = extractEmbedding(rs);
                    if (chunkEmbedding == null) continue;

                    double similarity = cosineSimilarity(queryEmbedding, chunkEmbedding);
                    if (similarity > 0.01) {
                        Map<String, Object> hit = new HashMap<>();
                        hit.put("chunk_id", rs.getString("chunk_id"));
                        hit.put("artifact_id", rs.getString("artifact_id"));
                        hit.put("chunk_index", rs.getInt("chunk_index"));
                        hit.put("preview", rs.getString("chunk_text").length() > 200
                                ? rs.getString("chunk_text").substring(0, 200) + "..."
                                : rs.getString("chunk_text"));
                        hit.put("file_name", rs.getString("file_name"));
                        hit.put("line_start", rs.getObject("line_start"));
                        hit.put("line_end", rs.getObject("line_end"));
                        hit.put("score", similarity);
                        hit.put("match_type", "SEMANTIC");
                        results.add(hit);
                    }
                }
            }
        }

        results.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        return results.size() > limit ? results.subList(0, limit) : results;
    }

    public List<Map<String, Object>> hybridSearch(String phrase, int maxResults) throws SQLException {
        int limit = maxResults > 0 ? maxResults : config.getMaxSearchResults();

        List<Map<String, Object>> exactResults = exactSearch(phrase, limit * 2);
        List<Map<String, Object>> semanticResults = semanticSearch(phrase, limit * 2);

        return mergeAndRank(exactResults, semanticResults, limit);
    }

    public static List<Map<String, Object>> mergeAndRank(List<Map<String, Object>> exactResults,
                                                   List<Map<String, Object>> semanticResults,
                                                   int limit) {
        // Normalize exact scores
        double maxExact = exactResults.stream()
                .mapToDouble(r -> (double) r.get("score")).max().orElse(1.0);
        if (maxExact == 0) maxExact = 1.0;

        double maxSemantic = semanticResults.stream()
                .mapToDouble(r -> (double) r.get("score")).max().orElse(1.0);
        if (maxSemantic == 0) maxSemantic = 1.0;

        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

        for (Map<String, Object> hit : exactResults) {
            String chunkId = (String) hit.get("chunk_id");
            double normalizedScore = (double) hit.get("score") / maxExact;
            Map<String, Object> entry = new HashMap<>(hit);
            entry.put("exact_score", normalizedScore);
            entry.put("semantic_score", 0.0);
            merged.put(chunkId, entry);
        }

        for (Map<String, Object> hit : semanticResults) {
            String chunkId = (String) hit.get("chunk_id");
            double normalizedScore = (double) hit.get("score") / maxSemantic;
            if (merged.containsKey(chunkId)) {
                merged.get(chunkId).put("semantic_score", normalizedScore);
                merged.get(chunkId).put("match_type", "HYBRID");
            } else {
                Map<String, Object> entry = new HashMap<>(hit);
                entry.put("exact_score", 0.0);
                entry.put("semantic_score", normalizedScore);
                merged.put(chunkId, entry);
            }
        }

        // Combined score: 0.3 * exact + 0.7 * semantic
        for (Map<String, Object> entry : merged.values()) {
            double exact = (double) entry.getOrDefault("exact_score", 0.0);
            double semantic = (double) entry.getOrDefault("semantic_score", 0.0);
            entry.put("score", 0.3 * exact + 0.7 * semantic);
        }

        List<Map<String, Object>> results = new ArrayList<>(merged.values());
        results.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        return results.size() > limit ? results.subList(0, limit) : results;
    }

    private double[] extractEmbedding(ResultSet rs) throws SQLException {
        Object embObj = rs.getObject("embedding");
        if (embObj == null) return null;

        if (embObj instanceof double[] arr) {
            return arr;
        }

        // DuckDB may return arrays as Object[] or other types
        if (embObj instanceof Object[] objArr) {
            double[] result = new double[objArr.length];
            for (int i = 0; i < objArr.length; i++) {
                result[i] = ((Number) objArr[i]).doubleValue();
            }
            return result;
        }

        // Try java.sql.Array
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

    public static double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    static double computeExactScore(String chunkText, String phrase) {
        String lower = chunkText.toLowerCase();
        String phraseLower = phrase.toLowerCase();
        int count = 0;
        int idx = 0;
        while ((idx = lower.indexOf(phraseLower, idx)) != -1) {
            count++;
            idx += phraseLower.length();
        }
        // Score based on occurrence count and density
        double density = (double) (count * phraseLower.length()) / lower.length();
        return count + density;
    }

    static String truncatePreview(String chunkText, String phrase) {
        int idx = chunkText.toLowerCase().indexOf(phrase.toLowerCase());
        if (idx < 0) {
            return chunkText.length() > 200 ? chunkText.substring(0, 200) + "..." : chunkText;
        }
        int start = Math.max(0, idx - 50);
        int end = Math.min(chunkText.length(), idx + phrase.length() + 150);
        String preview = chunkText.substring(start, end);
        if (start > 0) preview = "..." + preview;
        if (end < chunkText.length()) preview = preview + "...";
        return preview;
    }
}
