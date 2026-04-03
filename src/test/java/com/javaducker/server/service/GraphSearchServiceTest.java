package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphSearchServiceTest {

    @TempDir
    static Path tempDir;

    static DuckDBDataSource dataSource;
    static EmbeddingService embeddingService;
    static KnowledgeGraphService kgService;
    static GraphSearchService graphSearchService;

    @BeforeAll
    static void setup() throws Exception {
        AppConfig config = new AppConfig();
        config.setDbPath(tempDir.resolve("test-graphsearch.duckdb").toString());
        config.setIntakeDir(tempDir.resolve("intake").toString());
        dataSource = new DuckDBDataSource(config);
        embeddingService = new EmbeddingService(config);

        ArtifactService artifactService = new ArtifactService(dataSource);
        SearchService searchService = new SearchService(dataSource, embeddingService, config);
        IngestionWorker worker = new IngestionWorker(dataSource, artifactService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                embeddingService, new FileSummarizer(), new ImportParser(),
                new ReladomoXmlParser(), new ReladomoService(dataSource),
                new ReladomoFinderParser(), new ReladomoConfigParser(),
                searchService, config);
        SchemaBootstrap bootstrap = new SchemaBootstrap(dataSource, config, worker);
        bootstrap.createSchema();

        kgService = new KnowledgeGraphService(dataSource, embeddingService);
        graphSearchService = new GraphSearchService(dataSource, embeddingService, kgService);

        seedTestData();
    }

    @AfterAll
    static void teardown() throws Exception {
        dataSource.close();
    }

    static void seedTestData() throws Exception {
        Connection conn = dataSource.getConnection();

        // Seed artifacts
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at)
                VALUES ('gs-art-1', 'SearchService.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at)
                VALUES ('gs-art-2', 'UserRepository.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
        }

        // Seed entities with embeddings via KnowledgeGraphService (which generates embeddings)
        kgService.upsertEntity("SearchService", "class",
                "Service that performs full-text and semantic search across indexed code artifacts",
                "gs-art-1", null);
        kgService.upsertEntity("UserRepository", "class",
                "Repository for accessing user data from the database",
                "gs-art-2", null);
        kgService.upsertEntity("EmbeddingService", "class",
                "Service that generates TF-IDF hash embeddings for text content",
                "gs-art-1", null);

        // Seed relationships with embeddings
        kgService.upsertRelationship("class-searchservice", "class-embeddingservice",
                "uses", "SearchService uses EmbeddingService to generate query embeddings",
                "gs-art-1", null, 1.0);
        kgService.upsertRelationship("class-userrepository", "class-searchservice",
                "depends-on", "UserRepository depends on SearchService for search functionality",
                "gs-art-2", null, 1.0);

        // Seed artifact chunks with embeddings for mix search
        double[] chunkEmb = embeddingService.embed("search service handles full-text queries");
        String embSql = embeddingToSql(chunkEmb);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                INSERT INTO artifact_chunks (chunk_id, artifact_id, chunk_index, chunk_text, line_start, line_end)
                VALUES ('gs-chunk-1', 'gs-art-1', 0, 'public class SearchService implements search functionality for indexed code artifacts', 1, 10)
            """);
            stmt.execute("""
                INSERT INTO artifact_chunks (chunk_id, artifact_id, chunk_index, chunk_text, line_start, line_end)
                VALUES ('gs-chunk-2', 'gs-art-2', 0, 'public class UserRepository provides data access for user entities', 1, 8)
            """);
            stmt.execute("INSERT INTO chunk_embeddings (chunk_id, embedding) VALUES ('gs-chunk-1', "
                    + embSql + ")");
            double[] chunk2Emb = embeddingService.embed("user repository data access entities");
            stmt.execute("INSERT INTO chunk_embeddings (chunk_id, embedding) VALUES ('gs-chunk-2', "
                    + embeddingToSql(chunk2Emb) + ")");
        }
    }

    private static String embeddingToSql(double[] embedding) {
        if (embedding == null) return "NULL";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]::DOUBLE[]");
        return sb.toString();
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void localSearchFindsRelevantEntities() throws Exception {
        List<Map<String, Object>> results = graphSearchService.localSearch("search service", 10);

        assertFalse(results.isEmpty(), "Expected at least one local search result");
        // The top result should be SearchService (most relevant to "search service")
        Map<String, Object> top = results.get(0);
        assertEquals("SearchService", top.get("entity_name"));
        assertEquals("class", top.get("entity_type"));
        assertTrue((double) top.get("score") > 0);
        assertEquals("LOCAL", top.get("match_type"));
        // Should have relationships attached
        assertNotNull(top.get("relationships"));
    }

    @Test
    void globalSearchFindsRelationships() throws Exception {
        List<Map<String, Object>> results = graphSearchService.globalSearch("embedding query generation", 10);

        assertFalse(results.isEmpty(), "Expected at least one global search result");
        Map<String, Object> top = results.get(0);
        assertNotNull(top.get("relationship_id"));
        assertNotNull(top.get("relationship_type"));
        assertTrue((double) top.get("score") > 0);
        assertEquals("GLOBAL", top.get("match_type"));
    }

    @Test
    void hybridGraphSearchCombinesBoth() throws Exception {
        List<Map<String, Object>> results = graphSearchService.hybridGraphSearch("search service embeddings", 10);

        assertFalse(results.isEmpty(), "Expected at least one hybrid graph result");
        // Should contain results from both local and global
        boolean hasHybrid = results.stream()
                .anyMatch(r -> "GRAPH_HYBRID".equals(r.get("match_type")));
        assertTrue(hasHybrid, "Expected GRAPH_HYBRID match type in results");
    }

    @Test
    void mixSearchCombinesGraphAndChunks() throws Exception {
        List<Map<String, Object>> results = graphSearchService.mixSearch("search service", 10);

        assertFalse(results.isEmpty(), "Expected at least one mix search result");
        boolean hasMix = results.stream()
                .anyMatch(r -> "MIX".equals(r.get("match_type")));
        assertTrue(hasMix, "Expected MIX match type in results");
    }

    @Test
    void localSearchReturnsEmptyWhenNoEntitiesHaveEmbeddings() throws Exception {
        // Create a separate service with its own empty database
        AppConfig emptyConfig = new AppConfig();
        emptyConfig.setDbPath(tempDir.resolve("test-empty.duckdb").toString());
        emptyConfig.setIntakeDir(tempDir.resolve("intake2").toString());
        DuckDBDataSource emptyDs = new DuckDBDataSource(emptyConfig);
        EmbeddingService emptyEmb = new EmbeddingService(emptyConfig);

        ArtifactService emptyArtService = new ArtifactService(emptyDs);
        SearchService emptySearch = new SearchService(emptyDs, emptyEmb, emptyConfig);
        IngestionWorker emptyWorker = new IngestionWorker(emptyDs, emptyArtService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                emptyEmb, new FileSummarizer(), new ImportParser(),
                new ReladomoXmlParser(), new ReladomoService(emptyDs),
                new ReladomoFinderParser(), new ReladomoConfigParser(),
                emptySearch, emptyConfig);
        new SchemaBootstrap(emptyDs, emptyConfig, emptyWorker).createSchema();

        KnowledgeGraphService emptyKg = new KnowledgeGraphService(emptyDs, emptyEmb);
        GraphSearchService emptyGraphSearch = new GraphSearchService(emptyDs, emptyEmb, emptyKg);

        List<Map<String, Object>> results = emptyGraphSearch.localSearch("anything", 10);
        assertTrue(results.isEmpty(), "Expected empty results when no entities exist");
        emptyDs.close();
    }

    @Test
    void searchFiltersNullEmbeddings() throws Exception {
        // Insert entity WITHOUT embedding (null description -> null embedding)
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                INSERT INTO entities (entity_id, entity_name, entity_type, description,
                    source_artifact_ids, mention_count, embedding, created_at, updated_at)
                VALUES ('class-noembedding', 'NoEmbedding', 'class', NULL,
                    '["gs-art-1"]', 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
        }

        List<Map<String, Object>> results = graphSearchService.localSearch("search", 100);

        // The entity with null embedding should NOT appear
        boolean hasNullEntity = results.stream()
                .anyMatch(r -> "NoEmbedding".equals(r.get("entity_name")));
        assertFalse(hasNullEntity, "Entity with null embedding should be filtered out");

        // Cleanup
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM entities WHERE entity_id = 'class-noembedding'");
        }
    }

    @Test
    void cosineSimilarityHandlesNulls() {
        assertEquals(0.0, GraphSearchService.cosineSimilarity(null, new double[]{1}));
        assertEquals(0.0, GraphSearchService.cosineSimilarity(new double[]{1}, null));
    }

    @Test
    void cosineSimilarityHandlesLengthMismatch() {
        assertEquals(0.0, GraphSearchService.cosineSimilarity(new double[]{1, 2}, new double[]{1}));
    }

    @Test
    void parseJsonArrayHandlesVariousInputs() {
        assertEquals(List.of(), GraphSearchService.parseJsonArray(null));
        assertEquals(List.of(), GraphSearchService.parseJsonArray(""));
        assertEquals(List.of(), GraphSearchService.parseJsonArray("[]"));
        assertEquals(List.of("a", "b"), GraphSearchService.parseJsonArray("[\"a\",\"b\"]"));
        assertEquals(List.of("single"), GraphSearchService.parseJsonArray("[\"single\"]"));
    }

    @Test
    void localSearchRespectsTopK() throws Exception {
        List<Map<String, Object>> results = graphSearchService.localSearch("service", 1);

        assertTrue(results.size() <= 1, "Should respect topK limit of 1");
    }

    @Test
    void chunkSearchFindsChunks() throws Exception {
        List<Map<String, Object>> results = graphSearchService.chunkSearch("search service", 10);

        assertFalse(results.isEmpty(), "Expected chunk search results");
        Map<String, Object> top = results.get(0);
        assertNotNull(top.get("chunk_id"));
        assertNotNull(top.get("artifact_id"));
        assertEquals("CHUNK", top.get("match_type"));
    }
}
