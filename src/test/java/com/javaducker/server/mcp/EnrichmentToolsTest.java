package com.javaducker.server.mcp;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import com.javaducker.server.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnrichmentToolsTest {

    @TempDir
    static Path tempDir;

    static DuckDBDataSource dataSource;
    static EnrichmentTools tools;

    @BeforeAll
    static void setup() throws Exception {
        AppConfig config = new AppConfig();
        config.setDbPath(tempDir.resolve("test-enrich.duckdb").toString());
        config.setIntakeDir(tempDir.resolve("intake").toString());
        dataSource = new DuckDBDataSource(config);
        ArtifactService artifactService = new ArtifactService(dataSource);
        EmbeddingService emb = new EmbeddingService(config);
        SearchService searchService = new SearchService(dataSource, emb, config);
        IngestionWorker worker = new IngestionWorker(dataSource, artifactService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                emb, new FileSummarizer(), new ImportParser(),
                new ReladomoXmlParser(), new ReladomoService(dataSource),
                new ReladomoFinderParser(), new ReladomoConfigParser(),
                searchService, config);
        new SchemaBootstrap(dataSource, config, worker).createSchema();

        KnowledgeGraphService kgService = new KnowledgeGraphService(dataSource, emb);
        CommunityDetectionService cdService = new CommunityDetectionService(dataSource);
        tools = new EnrichmentTools(dataSource, kgService, cdService);

        // Seed artifacts
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, enrichment_status, created_at, updated_at)
                VALUES ('enr-1', 'ServiceA.java', 'INDEXED', 'pending', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, enrichment_status, created_at, updated_at)
                VALUES ('enr-2', 'ServiceB.java', 'INDEXED', 'enriched', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, enrichment_status, created_at, updated_at)
                VALUES ('enr-3', 'ServiceC.java', 'INDEXED', 'pending', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
        }
    }

    @AfterAll
    static void teardown() {
        dataSource.close();
    }

    @Test
    @Order(1)
    void enrichmentPipelineReturnsPendingFiles() {
        Map<String, Object> result = tools.enrichmentPipeline(null);

        assertFalse(result.containsKey("error"));
        assertEquals(2, result.get("pending_count"));
        @SuppressWarnings("unchecked")
        var files = (List<Map<String, Object>>) result.get("pending_files");
        assertEquals(2, files.size());
        assertNotNull(result.get("steps_per_file"));
        assertNotNull(result.get("graph_stats"));
    }

    @Test
    @Order(2)
    void enrichmentPipelineRespectsBatchSize() {
        Map<String, Object> result = tools.enrichmentPipeline(1);

        assertEquals(1, result.get("pending_count"));
    }

    @Test
    @Order(3)
    void enrichmentStatusReturnsCorrectCounts() {
        Map<String, Object> result = tools.enrichmentStatus();

        assertFalse(result.containsKey("error"));
        assertEquals(3L, result.get("total_indexed"));
        assertEquals(1L, result.get("enriched"));
        assertEquals(2L, result.get("pending"));
    }

    @Test
    @Order(4)
    void rebuildGraphClearsAndReturnsArtifacts() {
        Map<String, Object> result = tools.rebuildGraph();

        assertFalse(result.containsKey("error"));
        assertEquals(3, result.get("artifact_count"));
        @SuppressWarnings("unchecked")
        var artifacts = (List<Map<String, Object>>) result.get("artifacts_to_reprocess");
        assertEquals(3, artifacts.size());
    }
}
