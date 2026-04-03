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
class KnowledgeGraphServiceTest {

    @TempDir
    static Path tempDir;

    static DuckDBDataSource dataSource;
    static KnowledgeGraphService service;

    @BeforeAll
    static void setup() throws Exception {
        AppConfig config = new AppConfig();
        config.setDbPath(tempDir.resolve("test-kg.duckdb").toString());
        config.setIntakeDir(tempDir.resolve("intake").toString());
        dataSource = new DuckDBDataSource(config);
        ArtifactService artifactService = new ArtifactService(dataSource);
        SearchService searchService = new SearchService(dataSource, new EmbeddingService(config), config);
        IngestionWorker worker = new IngestionWorker(dataSource, artifactService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                new EmbeddingService(config), new FileSummarizer(), new ImportParser(),
                new ReladomoXmlParser(), new ReladomoService(dataSource),
                new ReladomoFinderParser(), new ReladomoConfigParser(),
                searchService, config);
        SchemaBootstrap bootstrap = new SchemaBootstrap(dataSource, config, worker);
        bootstrap.createSchema();
        EmbeddingService embeddingService = new EmbeddingService(config);
        service = new KnowledgeGraphService(dataSource, embeddingService);

        // Seed test artifacts
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at)
                VALUES ('kg-art-1', 'SearchService.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at)
                VALUES ('kg-art-2', 'SearchController.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
        }
    }

    @AfterAll
    static void teardown() throws Exception {
        dataSource.close();
    }

    @Test
    @Order(1)
    void upsertNewEntity() throws Exception {
        var result = service.upsertEntity("SearchService", "class",
                "Service that handles search operations", "kg-art-1", "chunk-1");
        assertNotNull(result.get("entity_id"));
        assertEquals("SearchService", result.get("entity_name"));
        assertEquals("class", result.get("entity_type"));
        assertEquals("created", result.get("action"));
        assertEquals(1, ((Number) result.get("mention_count")).intValue());
    }

    @Test
    @Order(2)
    void upsertExistingEntityMerges() throws Exception {
        var result = service.upsertEntity("SearchService", "class",
                "Service handling full-text and semantic search operations across artifacts",
                "kg-art-2", "chunk-2");
        assertEquals("merged", result.get("action"));
        assertEquals(2, ((Number) result.get("mention_count")).intValue());

        // Verify source_artifact_ids has both
        var entity = service.getEntity((String) result.get("entity_id"));
        assertNotNull(entity);
        String sources = (String) entity.get("source_artifact_ids");
        assertTrue(sources.contains("kg-art-1"));
        assertTrue(sources.contains("kg-art-2"));
    }

    @Test
    @Order(3)
    void upsertRelationship() throws Exception {
        // Create a second entity first
        service.upsertEntity("SearchController", "class",
                "REST controller for search endpoints", "kg-art-2", null);

        var result = service.upsertRelationship("class-searchservice", "class-searchcontroller",
                "USED_BY", "SearchService is used by SearchController",
                "kg-art-2", null, 1.0);
        assertNotNull(result.get("relationship_id"));
        assertEquals("created", result.get("action"));
    }

    @Test
    @Order(4)
    void getEntityById() throws Exception {
        var entity = service.getEntity("class-searchservice");
        assertNotNull(entity);
        assertEquals("SearchService", entity.get("entity_name"));
        assertEquals("class", entity.get("entity_type"));
    }

    @Test
    @Order(5)
    void findEntitiesByName() throws Exception {
        var results = service.findEntitiesByName("Search");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(e -> "SearchService".equals(e.get("entity_name"))));
    }

    @Test
    @Order(6)
    void findEntitiesByType() throws Exception {
        var results = service.findEntitiesByType("class");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().allMatch(e -> "class".equals(e.get("entity_type"))));
    }

    @Test
    @Order(7)
    void getRelationshipsForEntity() throws Exception {
        var rels = service.getRelationships("class-searchservice");
        assertFalse(rels.isEmpty());
        assertEquals("USED_BY", rels.get(0).get("relationship_type"));
    }

    @Test
    @Order(8)
    void getNeighborhood() throws Exception {
        // Add a third entity and chain: SearchService -> SearchController -> SearchConfig
        service.upsertEntity("SearchConfig", "class",
                "Configuration for search features", "kg-art-1", null);
        service.upsertRelationship("class-searchcontroller", "class-searchconfig",
                "CONFIGURES", "Controller uses config", "kg-art-1", null, 1.0);

        var neighborhood = service.getNeighborhood("class-searchservice", 2);
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) neighborhood.get("nodes");
        @SuppressWarnings("unchecked")
        var edges = (List<Map<String, Object>>) neighborhood.get("edges");

        // Should find all 3 nodes with depth 2
        assertTrue(nodes.size() >= 3, "Expected at least 3 nodes, got " + nodes.size());
        assertTrue(edges.size() >= 2, "Expected at least 2 edges, got " + edges.size());
    }

    @Test
    @Order(9)
    void getPath() throws Exception {
        var path = service.getPath("class-searchservice", "class-searchconfig");
        assertTrue((Boolean) path.get("found"));
        @SuppressWarnings("unchecked")
        var pathNodes = (List<String>) path.get("path");
        assertEquals("class-searchservice", pathNodes.get(0));
        assertEquals("class-searchconfig", pathNodes.get(pathNodes.size() - 1));
    }

    @Test
    @Order(10)
    void getStats() throws Exception {
        var stats = service.getStats();
        assertTrue(((Number) stats.get("entity_count")).longValue() >= 3);
        assertTrue(((Number) stats.get("relationship_count")).longValue() >= 2);
        assertNotNull(stats.get("top_types"));
    }

    @Test
    @Order(11)
    void mergeEntities() throws Exception {
        // Create entities to merge
        service.upsertEntity("SearchSvc", "class", "Alias for SearchService", "kg-art-1", null);
        var mergeResult = service.mergeEntities("class-searchsvc", "class-searchservice",
                "Unified search service handling all search operations");

        assertEquals("class-searchservice", mergeResult.get("merged_into"));
        assertEquals("class-searchsvc", mergeResult.get("source_deleted"));

        // Source should be gone
        assertNull(service.getEntity("class-searchsvc"));

        // Target should have combined mention count
        var merged = service.getEntity("class-searchservice");
        assertNotNull(merged);
        assertTrue(((Number) merged.get("mention_count")).intValue() >= 3);
    }

    @Test
    @Order(12)
    void deleteEntitiesForArtifact() throws Exception {
        // Create entity sourced only from kg-art-2
        service.upsertEntity("OnlyFromArt2", "class",
                "Entity only from art-2", "kg-art-2", null);

        var result = service.deleteEntitiesForArtifact("kg-art-2");
        assertTrue(((Number) result.get("deleted_entities")).intValue() >= 1);

        // Entity sourced only from kg-art-2 should be gone
        assertNull(service.getEntity("class-onlyfromart2"));
    }

    @Test
    @Order(13)
    void deleteEntitiesSharedAcrossArtifacts() throws Exception {
        // Create entity sourced from both artifacts
        service.upsertEntity("SharedEntity", "class",
                "Shared between artifacts", "kg-art-1", null);
        service.upsertEntity("SharedEntity", "class",
                "Shared between artifacts", "kg-art-2", null);

        // Verify mention_count is 2
        var entity = service.getEntity("class-sharedentity");
        assertNotNull(entity);
        assertEquals(2, ((Number) entity.get("mention_count")).intValue());

        // Delete entities for kg-art-1
        service.deleteEntitiesForArtifact("kg-art-1");

        // Should survive with decremented count
        var surviving = service.getEntity("class-sharedentity");
        assertNotNull(surviving, "Entity shared across artifacts should survive");
        assertEquals(1, ((Number) surviving.get("mention_count")).intValue());
        String sources = (String) surviving.get("source_artifact_ids");
        assertFalse(sources.contains("kg-art-1"));
        assertTrue(sources.contains("kg-art-2"));
    }

    // ── Chapter 5: Duplicate detection tests ──────────────────────────────────

    @Test
    @Order(14)
    void findDuplicateCandidatesExactNameDifferentCase() throws Exception {
        service.upsertEntity("DupService", "class", "A duplicate detection service", "kg-art-1", null);
        service.upsertEntity("dupservice", "service", "A duplicate detection service lowercase", "kg-art-1", null);

        var candidates = service.findDuplicateCandidates();
        boolean found = candidates.stream().anyMatch(c ->
                ("DupService".equals(c.get("source_name")) && "dupservice".equals(c.get("target_name")))
                || ("dupservice".equals(c.get("source_name")) && "DupService".equals(c.get("target_name"))));
        assertTrue(found, "Should detect case-insensitive name match as duplicate candidate");

        var match = candidates.stream().filter(c ->
                "DupService".equalsIgnoreCase((String) c.get("source_name"))
                        && "dupservice".equalsIgnoreCase((String) c.get("target_name"))
                || "dupservice".equalsIgnoreCase((String) c.get("source_name"))
                        && "DupService".equalsIgnoreCase((String) c.get("target_name"))).findFirst();
        assertTrue(match.isPresent());
        assertEquals(1.0, (double) match.get().get("confidence"), 0.001);
    }

    @Test
    @Order(15)
    void findDuplicateCandidatesSimilarNames() throws Exception {
        service.upsertEntity("UserService", "service", "Handles user operations", "kg-art-1", null);
        service.upsertEntity("UserServce", "service", "Handles user operations (typo)", "kg-art-1", null);

        var candidates = service.findDuplicateCandidates();
        boolean found = candidates.stream().anyMatch(c -> {
            String src = (String) c.get("source_name");
            String tgt = (String) c.get("target_name");
            return (src.equals("UserService") && tgt.equals("UserServce"))
                    || (src.equals("UserServce") && tgt.equals("UserService"));
        });
        assertTrue(found, "Should detect Levenshtein-similar names as duplicate candidates");
    }

    @Test
    @Order(16)
    void findMergeCandidatesByEmbedding() throws Exception {
        service.upsertEntity("PaymentProcessor", "service",
                "Processes credit card payments and handles transaction validation", "kg-art-1", null);
        service.upsertEntity("PaymentHandler", "service",
                "Processes credit card payments and handles transaction validation and refunds", "kg-art-1", null);

        var candidates = service.findMergeCandidates("service-paymentprocessor");
        boolean found = candidates.stream().anyMatch(c ->
                "service-paymenthandler".equals(c.get("entity_id")));
        assertTrue(found, "Should find PaymentHandler as a merge candidate for PaymentProcessor");
    }

    @Test
    @Order(17)
    void findNoDuplicatesWhenNoneExist() throws Exception {
        service.upsertEntity("AlphaModule", "module", "First distinct module", "kg-art-1", null);
        service.upsertEntity("ZetaWidget", "module", "Completely different widget", "kg-art-1", null);

        var candidates = service.findDuplicateCandidates();
        boolean falseMatch = candidates.stream().anyMatch(c -> {
            String src = (String) c.get("source_name");
            String tgt = (String) c.get("target_name");
            return (src.equals("AlphaModule") && tgt.equals("ZetaWidget"))
                    || (src.equals("ZetaWidget") && tgt.equals("AlphaModule"));
        });
        assertFalse(falseMatch, "Should not find false duplicate matches between distinct entities");
    }

    @Test
    @Order(18)
    void levenshteinDistanceBasicCases() {
        assertEquals(0, KnowledgeGraphService.levenshteinDistance("abc", "abc"));
        assertEquals(1, KnowledgeGraphService.levenshteinDistance("abc", "ab"));
        assertEquals(1, KnowledgeGraphService.levenshteinDistance("abc", "abx"));
        assertEquals(3, KnowledgeGraphService.levenshteinDistance("abc", "xyz"));
    }

    @Test
    @Order(19)
    void cosineSimilarityBasicCases() {
        double[] a = {1, 0, 0};
        double[] b = {1, 0, 0};
        assertEquals(1.0, KnowledgeGraphService.cosineSimilarity(a, b), 0.001);
        double[] c = {1, 0, 0};
        double[] d = {0, 1, 0};
        assertEquals(0.0, KnowledgeGraphService.cosineSimilarity(c, d), 0.001);
    }
}
