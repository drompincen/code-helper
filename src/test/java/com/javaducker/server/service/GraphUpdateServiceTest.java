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
class GraphUpdateServiceTest {

    @TempDir
    static Path tempDir;

    static DuckDBDataSource dataSource;
    static KnowledgeGraphService kgService;
    static GraphUpdateService service;

    @BeforeAll
    static void setup() throws Exception {
        AppConfig config = new AppConfig();
        config.setDbPath(tempDir.resolve("test-gu.duckdb").toString());
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
        kgService = new KnowledgeGraphService(dataSource, embeddingService);
        service = new GraphUpdateService(dataSource, kgService);

        // Seed test artifacts
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at, indexed_at)
                VALUES ('gu-art-1', 'Service.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at, indexed_at)
                VALUES ('gu-art-2', 'Controller.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
        }
    }

    @AfterAll
    static void teardown() throws Exception {
        dataSource.close();
    }

    @Test
    @Order(1)
    void onArtifactReindexedRemovesExclusiveEntities() throws Exception {
        // Create entity sourced only from gu-art-1
        kgService.upsertEntity("ExclusiveService", "class",
                "Only from art-1", "gu-art-1", null);
        assertNotNull(kgService.getEntity("class-exclusiveservice"));

        // Reindex gu-art-1 - should remove exclusive entity
        Map<String, Object> result = service.onArtifactReindexed("gu-art-1");
        assertTrue(((Number) result.get("deleted_entities")).intValue() >= 1);
        assertNull(kgService.getEntity("class-exclusiveservice"),
                "Entity sourced only from gu-art-1 should be deleted after reindex");
    }

    @Test
    @Order(2)
    void onArtifactReindexedPreservesSharedEntities() throws Exception {
        // Create entity sourced from both artifacts
        kgService.upsertEntity("SharedController", "class",
                "Shared between artifacts", "gu-art-1", null);
        kgService.upsertEntity("SharedController", "class",
                "Shared between artifacts", "gu-art-2", null);

        // Verify mention_count is 2
        var entity = kgService.getEntity("class-sharedcontroller");
        assertNotNull(entity);
        assertEquals(2, ((Number) entity.get("mention_count")).intValue());

        // Reindex gu-art-1 - shared entity should survive
        service.onArtifactReindexed("gu-art-1");

        var surviving = kgService.getEntity("class-sharedcontroller");
        assertNotNull(surviving, "Shared entity should survive reindex");
        assertEquals(1, ((Number) surviving.get("mention_count")).intValue());
        String sources = (String) surviving.get("source_artifact_ids");
        assertFalse(sources.contains("gu-art-1"));
        assertTrue(sources.contains("gu-art-2"));
    }

    @Test
    @Order(3)
    void onArtifactDeletedRemovesEntities() throws Exception {
        // Create entity sourced only from gu-art-2
        kgService.upsertEntity("DeleteTarget", "service",
                "Only from art-2", "gu-art-2", null);
        assertNotNull(kgService.getEntity("service-deletetarget"));

        // Delete gu-art-2 entities
        Map<String, Object> result = service.onArtifactDeleted("gu-art-2");
        assertTrue(((Number) result.get("deleted_entities")).intValue() >= 1);
        assertNull(kgService.getEntity("service-deletetarget"),
                "Entity should be deleted when artifact is deleted");
    }

    @Test
    @Order(4)
    void findStaleGraphEntries() throws Exception {
        // Create a fresh entity
        kgService.upsertEntity("FreshEntity", "class",
                "A fresh entity", "gu-art-1", null);
        assertNotNull(kgService.getEntity("class-freshentity"));

        // Update artifact indexed_at to the future so entity appears stale
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                UPDATE artifacts SET indexed_at = CURRENT_TIMESTAMP + INTERVAL '1' HOUR
                WHERE artifact_id = 'gu-art-1'
            """);
        }

        List<Map<String, Object>> stale = service.findStaleGraphEntries();
        assertFalse(stale.isEmpty(), "Should find stale entries when artifact was re-indexed");
        boolean found = stale.stream().anyMatch(e ->
                "class-freshentity".equals(e.get("entity_id")));
        assertTrue(found, "FreshEntity should appear stale after artifact re-indexed");
    }

    @Test
    @Order(5)
    void noStaleEntriesWhenUpToDate() throws Exception {
        // Reset indexed_at to past so no entity is stale
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                UPDATE artifacts SET indexed_at = CURRENT_TIMESTAMP - INTERVAL '1' HOUR
                WHERE artifact_id = 'gu-art-1'
            """);
        }

        // Delete all entities to start clean, then create one with current timestamp
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM entities");
        }
        kgService.upsertEntity("UpToDateEntity", "class",
                "An up-to-date entity", "gu-art-1", null);

        List<Map<String, Object>> stale = service.findStaleGraphEntries();
        boolean foundUpToDate = stale.stream().anyMatch(e ->
                "class-uptodateentity".equals(e.get("entity_id")));
        assertFalse(foundUpToDate,
                "Entity should not be stale when artifact indexed_at is in the past");
    }
}
