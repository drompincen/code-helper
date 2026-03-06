package com.javaducker.server.ingestion;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.service.SearchService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingServiceTest {

    private EmbeddingService createService(int dim) {
        AppConfig config = new AppConfig();
        config.setEmbeddingDim(dim);
        return new EmbeddingService(config);
    }

    @Test
    void embeddingHasCorrectDimension() {
        EmbeddingService svc = createService(128);
        double[] emb = svc.embed("hello world test");
        assertEquals(128, emb.length);
    }

    @Test
    void embeddingIsNormalized() {
        EmbeddingService svc = createService(256);
        double[] emb = svc.embed("some text to embed");
        double norm = 0;
        for (double v : emb) norm += v * v;
        assertEquals(1.0, Math.sqrt(norm), 0.001);
    }

    @Test
    void similarTextsHaveHigherSimilarity() {
        EmbeddingService svc = createService(256);
        double[] a = svc.embed("java spring boot application");
        double[] b = svc.embed("spring boot java application server");
        double[] c = svc.embed("cooking recipes italian pasta");

        double simAB = SearchService.cosineSimilarity(a, b);
        double simAC = SearchService.cosineSimilarity(a, c);
        assertTrue(simAB > simAC, "Similar texts should have higher similarity: AB=" + simAB + " AC=" + simAC);
    }

    @Test
    void emptyTextProducesZeroVector() {
        EmbeddingService svc = createService(64);
        double[] emb = svc.embed("");
        double sum = 0;
        for (double v : emb) sum += Math.abs(v);
        assertEquals(0.0, sum);
    }

    @Test
    void tokenizeFiltersStopWords() {
        var tokens = EmbeddingService.tokenize("the quick brown fox is very fast");
        assertFalse(tokens.contains("the"));
        assertFalse(tokens.contains("is"));
        assertFalse(tokens.contains("very"));
        assertTrue(tokens.contains("quick"));
        assertTrue(tokens.contains("brown"));
        assertTrue(tokens.contains("fox"));
        assertTrue(tokens.contains("fast"));
    }

    @Test
    void tokenizeFiltersSingleChars() {
        var tokens = EmbeddingService.tokenize("a b c hello world");
        assertFalse(tokens.contains("b"));
        assertFalse(tokens.contains("c"));
        assertTrue(tokens.contains("hello"));
        assertTrue(tokens.contains("world"));
    }
}
