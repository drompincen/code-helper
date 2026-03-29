package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SearchServiceIntegrationTest {

    @TempDir
    static Path tempDir;

    static DuckDBDataSource dataSource;
    static SearchService service;
    static EmbeddingService embeddingService;
    static AppConfig config;

    @BeforeAll
    static void setup() throws Exception {
        config = new AppConfig();
        config.setDbPath(tempDir.resolve("test-search.duckdb").toString());
        config.setIntakeDir(tempDir.resolve("intake").toString());
        config.setEmbeddingDim(64);
        config.setMaxSearchResults(20);

        dataSource = new DuckDBDataSource(config);
        embeddingService = new EmbeddingService(config);
        service = new SearchService(dataSource, embeddingService, config);

        ArtifactService artifactService = new ArtifactService(dataSource);
        IngestionWorker worker = new IngestionWorker(dataSource, artifactService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                new EmbeddingService(config), new FileSummarizer(), new ImportParser(),
                new ReladomoXmlParser(), new ReladomoService(dataSource),
                new ReladomoFinderParser(), new ReladomoConfigParser(),
                service, config);
        SchemaBootstrap bootstrap = new SchemaBootstrap(dataSource, config, worker);
        bootstrap.createSchema();

        seedTestData();
    }

    static void seedTestData() throws Exception {
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            // Artifact 1: indexed, current
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, freshness, created_at, updated_at)
                VALUES ('art-s1', 'UserService.java', 'INDEXED', 'current', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            // Artifact 2: indexed, current
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, freshness, created_at, updated_at)
                VALUES ('art-s2', 'OrderService.java', 'INDEXED', 'current', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            // Artifact 3: indexed, superseded (should be excluded from search)
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, freshness, created_at, updated_at)
                VALUES ('art-s3', 'LegacyAuth.java', 'INDEXED', 'superseded', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);

            // Chunks for art-s1
            stmt.execute("""
                INSERT INTO artifact_chunks (chunk_id, artifact_id, chunk_index, chunk_text, char_start, char_end, line_start, line_end)
                VALUES ('chunk-s1-0', 'art-s1', 0, 'public class UserService implements Authentication with OAuth2 token validation', 0, 80, 1, 15)
            """);
            stmt.execute("""
                INSERT INTO artifact_chunks (chunk_id, artifact_id, chunk_index, chunk_text, char_start, char_end, line_start, line_end)
                VALUES ('chunk-s1-1', 'art-s1', 1, 'private void validateUserCredentials using bcrypt password hashing algorithm', 80, 160, 16, 30)
            """);

            // Chunks for art-s2
            stmt.execute("""
                INSERT INTO artifact_chunks (chunk_id, artifact_id, chunk_index, chunk_text, char_start, char_end, line_start, line_end)
                VALUES ('chunk-s2-0', 'art-s2', 0, 'public class OrderService processes customer orders and payments', 0, 65, 1, 20)
            """);
            stmt.execute("""
                INSERT INTO artifact_chunks (chunk_id, artifact_id, chunk_index, chunk_text, char_start, char_end, line_start, line_end)
                VALUES ('chunk-s2-1', 'art-s2', 1, 'OAuth2 token validation is used for order authentication', 65, 120, 21, 40)
            """);

            // Chunk for art-s3 (superseded -- should NOT appear in searches)
            stmt.execute("""
                INSERT INTO artifact_chunks (chunk_id, artifact_id, chunk_index, chunk_text, char_start, char_end, line_start, line_end)
                VALUES ('chunk-s3-0', 'art-s3', 0, 'Legacy OAuth2 authentication module for old system', 0, 50, 1, 10)
            """);

            // Embeddings for art-s1 and art-s2 chunks (using real embeddings from EmbeddingService)
            insertEmbedding(conn, "chunk-s1-0", "public class UserService implements Authentication with OAuth2 token validation");
            insertEmbedding(conn, "chunk-s1-1", "private void validateUserCredentials using bcrypt password hashing algorithm");
            insertEmbedding(conn, "chunk-s2-0", "public class OrderService processes customer orders and payments");
            insertEmbedding(conn, "chunk-s2-1", "OAuth2 token validation is used for order authentication");
            // Also insert embedding for superseded chunk
            insertEmbedding(conn, "chunk-s3-0", "Legacy OAuth2 authentication module for old system");
        }
    }

    static void insertEmbedding(Connection conn, String chunkId, String text) throws Exception {
        double[] embedding = embeddingService.embed(text);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO chunk_embeddings (chunk_id, embedding_model, embedding_dim, embedding) " +
                    "VALUES ('" + chunkId + "', 'tfidf', " + config.getEmbeddingDim() +
                    ", " + sb + "::DOUBLE[])");
        }
    }

    @AfterAll
    static void teardown() {
        dataSource.close();
    }

    // --- exactSearch tests ---

    @Test
    @Order(1)
    void exactSearchFindsMatchingPhrase() throws Exception {
        List<Map<String, Object>> results = service.exactSearch("OAuth2", 10);
        assertFalse(results.isEmpty(), "Should find chunks containing 'OAuth2'");
        for (Map<String, Object> hit : results) {
            assertNotNull(hit.get("file_name"));
            assertTrue((double) hit.get("score") > 0, "Score should be positive");
            assertEquals("EXACT", hit.get("match_type"));
        }
    }

    @Test
    @Order(2)
    void exactSearchPreviewContainsPhrase() throws Exception {
        List<Map<String, Object>> results = service.exactSearch("OAuth2", 10);
        assertFalse(results.isEmpty());
        for (Map<String, Object> hit : results) {
            String preview = (String) hit.get("preview");
            assertTrue(preview.toLowerCase().contains("oauth2"),
                    "Preview should contain the search phrase");
        }
    }

    @Test
    @Order(3)
    void exactSearchNoMatch() throws Exception {
        List<Map<String, Object>> results = service.exactSearch("xyzNonExistentTerm123", 10);
        assertTrue(results.isEmpty(), "Should return empty for non-matching phrase");
    }

    @Test
    @Order(4)
    void exactSearchRespectsMaxResults() throws Exception {
        // "OAuth2" appears in chunk-s1-0 and chunk-s2-1 (at least 2 matches)
        // Request maxResults=1
        List<Map<String, Object>> results = service.exactSearch("OAuth2", 1);
        assertEquals(1, results.size(), "Should respect maxResults limit");
    }

    @Test
    @Order(5)
    void exactSearchWithLineNumbers() throws Exception {
        List<Map<String, Object>> results = service.exactSearch("UserService", 10);
        assertFalse(results.isEmpty());
        Map<String, Object> hit = results.get(0);
        assertEquals("UserService.java", hit.get("file_name"));
        assertNotNull(hit.get("line_start"), "Should include line_start");
        assertNotNull(hit.get("line_end"), "Should include line_end");
        assertEquals(1, hit.get("line_start"));
        assertEquals(15, hit.get("line_end"));
    }

    @Test
    @Order(6)
    void exactSearchExcludesSuperseded() throws Exception {
        // "Legacy" only appears in chunk-s3-0 which belongs to superseded art-s3
        List<Map<String, Object>> results = service.exactSearch("Legacy", 10);
        assertTrue(results.isEmpty(),
                "Should exclude chunks from superseded artifacts");
    }

    // --- semanticSearch brute-force tests ---

    @Test
    @Order(10)
    void semanticSearchBruteForceFindsResults() throws Exception {
        // Search for something semantically similar to seeded text
        List<Map<String, Object>> results = service.semanticSearch("OAuth2 authentication token", 10);
        assertFalse(results.isEmpty(), "Semantic search should find related chunks");
        for (Map<String, Object> hit : results) {
            assertEquals("SEMANTIC", hit.get("match_type"));
            assertTrue((double) hit.get("score") > 0);
            assertNotNull(hit.get("file_name"));
        }
    }

    @Test
    @Order(11)
    void semanticSearchBruteForceOrderedBySimilarity() throws Exception {
        List<Map<String, Object>> results = service.semanticSearch("OAuth2 token validation", 10);
        assertFalse(results.isEmpty());
        // Verify descending order of scores
        for (int i = 0; i < results.size() - 1; i++) {
            double current = (double) results.get(i).get("score");
            double next = (double) results.get(i + 1).get("score");
            assertTrue(current >= next,
                    "Results should be ordered by descending similarity score");
        }
    }

    @Test
    @Order(12)
    void semanticSearchBruteForceRespectsMaxResults() throws Exception {
        List<Map<String, Object>> results = service.semanticSearch("authentication", 1);
        assertTrue(results.size() <= 1, "Should respect maxResults limit");
    }

    @Test
    @Order(13)
    void semanticSearchExcludesSuperseded() throws Exception {
        List<Map<String, Object>> results = service.semanticSearch("Legacy authentication module", 10);
        for (Map<String, Object> hit : results) {
            assertNotEquals("art-s3", hit.get("artifact_id"),
                    "Should exclude superseded artifacts from semantic search");
        }
    }

    @Test
    @Order(14)
    void semanticSearchBruteForceHasPreview() throws Exception {
        List<Map<String, Object>> results = service.semanticSearch("customer orders payments", 10);
        assertFalse(results.isEmpty());
        for (Map<String, Object> hit : results) {
            String preview = (String) hit.get("preview");
            assertNotNull(preview, "Each result should have a preview");
            assertFalse(preview.isEmpty(), "Preview should not be empty");
        }
    }

    // --- semanticSearch with HNSW index ---

    @Test
    @Order(20)
    void semanticSearchWithHnswIndex() throws Exception {
        // Use same text for query and one chunk to guarantee high similarity
        String queryText = "OAuth2 token validation authentication";

        HnswIndex index = new HnswIndex(config.getEmbeddingDim(), 4, 16, 10);

        // Insert vectors for the non-superseded chunks
        index.insert("chunk-s1-0", embeddingService.embed(
                "public class UserService implements Authentication with OAuth2 token validation"));
        index.insert("chunk-s1-1", embeddingService.embed(
                "private void validateUserCredentials using bcrypt password hashing algorithm"));
        index.insert("chunk-s2-0", embeddingService.embed(
                "public class OrderService processes customer orders and payments"));
        index.insert("chunk-s2-1", embeddingService.embed(
                "OAuth2 token validation is used for order authentication"));

        assertEquals(4, index.size());

        service.setHnswIndex(index);
        try {
            List<Map<String, Object>> results = service.semanticSearch(queryText, 5);
            assertFalse(results.isEmpty(), "HNSW search should return results");
            for (Map<String, Object> hit : results) {
                assertEquals("SEMANTIC", hit.get("match_type"));
                assertNotNull(hit.get("score"));
                assertNotNull(hit.get("file_name"));
                assertNotNull(hit.get("chunk_id"));
            }
        } finally {
            service.setHnswIndex(null);
        }
    }

    @Test
    @Order(21)
    void semanticSearchHnswRespectsMaxResults() throws Exception {
        HnswIndex index = new HnswIndex(config.getEmbeddingDim(), 4, 16, 10);
        index.insert("chunk-s1-0", embeddingService.embed(
                "public class UserService implements Authentication with OAuth2 token validation"));
        index.insert("chunk-s1-1", embeddingService.embed(
                "private void validateUserCredentials using bcrypt password hashing algorithm"));
        index.insert("chunk-s2-0", embeddingService.embed(
                "public class OrderService processes customer orders and payments"));
        index.insert("chunk-s2-1", embeddingService.embed(
                "OAuth2 token validation is used for order authentication"));

        service.setHnswIndex(index);
        try {
            List<Map<String, Object>> results = service.semanticSearch("authentication", 2);
            assertTrue(results.size() <= 2, "HNSW path should respect maxResults");
        } finally {
            service.setHnswIndex(null);
        }
    }

    @Test
    @Order(22)
    void semanticSearchHnswIncludesLineNumbers() throws Exception {
        HnswIndex index = new HnswIndex(config.getEmbeddingDim(), 4, 16, 10);
        index.insert("chunk-s1-0", embeddingService.embed(
                "public class UserService implements Authentication with OAuth2 token validation"));

        service.setHnswIndex(index);
        try {
            List<Map<String, Object>> results = service.semanticSearch("UserService Authentication", 5);
            assertFalse(results.isEmpty());
            Map<String, Object> hit = results.get(0);
            assertEquals(1, hit.get("line_start"));
            assertEquals(15, hit.get("line_end"));
        } finally {
            service.setHnswIndex(null);
        }
    }

    // --- hybridSearch tests ---

    @Test
    @Order(30)
    void hybridSearchReturnsMergedResults() throws Exception {
        List<Map<String, Object>> results = service.hybridSearch("OAuth2 token validation", 10);
        assertFalse(results.isEmpty(), "Hybrid search should return results");

        // At least some results should exist from exact and/or semantic
        boolean hasResults = !results.isEmpty();
        assertTrue(hasResults);

        // Verify scores are present and positive
        for (Map<String, Object> hit : results) {
            assertTrue((double) hit.get("score") > 0);
            assertNotNull(hit.get("file_name"));
        }
    }

    @Test
    @Order(31)
    void hybridSearchRespectsMaxResults() throws Exception {
        List<Map<String, Object>> results = service.hybridSearch("OAuth2", 2);
        assertTrue(results.size() <= 2, "Hybrid search should respect maxResults");
    }

    @Test
    @Order(32)
    void hybridSearchOrderedByScore() throws Exception {
        List<Map<String, Object>> results = service.hybridSearch("authentication validation", 10);
        for (int i = 0; i < results.size() - 1; i++) {
            double current = (double) results.get(i).get("score");
            double next = (double) results.get(i + 1).get("score");
            assertTrue(current >= next,
                    "Hybrid results should be ordered by descending combined score");
        }
    }

    // --- exactSearch with default maxResults (uses config) ---

    @Test
    @Order(40)
    void exactSearchWithZeroMaxResultsUsesConfigDefault() throws Exception {
        // maxResults=0 should fall back to config.getMaxSearchResults()
        List<Map<String, Object>> results = service.exactSearch("OAuth2", 0);
        assertFalse(results.isEmpty(), "Should use config default when maxResults is 0");
    }

    @Test
    @Order(41)
    void semanticSearchWithZeroMaxResultsUsesConfigDefault() throws Exception {
        List<Map<String, Object>> results = service.semanticSearch("authentication", 0);
        assertFalse(results.isEmpty(), "Should use config default when maxResults is 0");
    }

    // --- extractEmbedding through semanticSearch (covers double[] path from DuckDB) ---

    @Test
    @Order(50)
    void semanticSearchHandlesDuckDbEmbeddingTypes() throws Exception {
        // The embeddings were inserted as DOUBLE[] arrays in DuckDB
        // This exercises the extractEmbedding method through the brute-force path
        // DuckDB returns DOUBLE[] which may come as double[], Object[], or java.sql.Array
        List<Map<String, Object>> results = service.semanticSearch("password hashing bcrypt", 5);
        assertFalse(results.isEmpty(),
                "Should correctly extract embeddings from DuckDB and compute similarity");
        // The top result should be the chunk about password hashing
        Map<String, Object> top = results.get(0);
        assertEquals("chunk-s1-1", top.get("chunk_id"),
                "Best match for 'password hashing bcrypt' should be the bcrypt chunk");
    }

    // --- Additional many-chunks test for maxResults ---

    @Test
    @Order(60)
    void exactSearchMaxResultsWithManyChunks() throws Exception {
        // Seed 10 additional chunks all matching "SpecialKeyword"
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, freshness, created_at, updated_at)
                VALUES ('art-bulk', 'Bulk.java', 'INDEXED', 'current', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            for (int i = 0; i < 10; i++) {
                stmt.execute("INSERT INTO artifact_chunks (chunk_id, artifact_id, chunk_index, chunk_text, char_start, char_end) " +
                        "VALUES ('chunk-bulk-" + i + "', 'art-bulk', " + i +
                        ", 'SpecialKeyword appears in chunk number " + i + "', 0, 50)");
            }
        }

        List<Map<String, Object>> results = service.exactSearch("SpecialKeyword", 3);
        assertEquals(3, results.size(), "Should return exactly maxResults when more matches exist");

        // Cleanup
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM artifact_chunks WHERE artifact_id = 'art-bulk'");
            stmt.execute("DELETE FROM artifacts WHERE artifact_id = 'art-bulk'");
        }
    }
}
