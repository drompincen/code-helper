package com.javaducker.server.service;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SearchServiceTest {

    @Test
    void cosineSimilarityIdentical() {
        double[] a = {1, 0, 0, 1};
        assertEquals(1.0, SearchService.cosineSimilarity(a, a), 0.001);
    }

    @Test
    void cosineSimilarityOrthogonal() {
        double[] a = {1, 0, 0, 0};
        double[] b = {0, 1, 0, 0};
        assertEquals(0.0, SearchService.cosineSimilarity(a, b), 0.001);
    }

    @Test
    void cosineSimilarityWithZeroVector() {
        double[] a = {1, 2, 3};
        double[] b = {0, 0, 0};
        assertEquals(0.0, SearchService.cosineSimilarity(a, b), 0.001);
    }

    @Test
    void exactScoreCountsOccurrences() {
        double score = SearchService.computeExactScore("hello world hello there hello", "hello");
        assertTrue(score > 0);
    }

    @Test
    void exactScoreZeroForNoMatch() {
        double score = SearchService.computeExactScore("the quick brown fox", "elephant");
        assertEquals(0, score, 0.001);
    }

    @Test
    void truncatePreviewCentersOnMatch() {
        String text = "a".repeat(100) + "@Transactional" + "b".repeat(100);
        String preview = SearchService.truncatePreview(text, "@Transactional");
        assertTrue(preview.contains("@Transactional"));
        assertTrue(preview.startsWith("..."));
    }

    @Test
    void mergeAndRankCombinesResults() {
        List<Map<String, Object>> exact = new ArrayList<>();
        exact.add(createHit("chunk-1", "file1.java", 0.8));

        List<Map<String, Object>> semantic = new ArrayList<>();
        semantic.add(createHit("chunk-1", "file1.java", 0.9));
        semantic.add(createHit("chunk-2", "file2.java", 0.7));

        List<Map<String, Object>> merged = SearchService.mergeAndRank(exact, semantic, 10);

        // chunk-1 appears in both, should be HYBRID
        assertFalse(merged.isEmpty());
        Map<String, Object> first = merged.get(0);
        assertEquals("chunk-1", first.get("chunk_id"));
        assertEquals("HYBRID", first.get("match_type"));
    }

    @Test
    void mergeAndRankRespectsLimit() {
        List<Map<String, Object>> exact = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            exact.add(createHit("chunk-e-" + i, "file" + i + ".java", 0.5 + i * 0.01));
        }

        List<Map<String, Object>> semantic = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            semantic.add(createHit("chunk-s-" + i, "file" + i + ".java", 0.4 + i * 0.01));
        }

        List<Map<String, Object>> merged = SearchService.mergeAndRank(exact, semantic, 5);
        assertEquals(5, merged.size());
    }

    private Map<String, Object> createHit(String chunkId, String fileName, double score) {
        Map<String, Object> hit = new HashMap<>();
        hit.put("chunk_id", chunkId);
        hit.put("artifact_id", "art-1");
        hit.put("chunk_index", 0);
        hit.put("preview", "some preview");
        hit.put("file_name", fileName);
        hit.put("score", score);
        hit.put("match_type", "EXACT");
        return hit;
    }
}
