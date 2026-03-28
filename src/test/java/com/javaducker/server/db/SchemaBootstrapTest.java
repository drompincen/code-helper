package com.javaducker.server.db;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.ingestion.*;
import com.javaducker.server.service.ArtifactService;
import com.javaducker.server.service.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class SchemaBootstrapTest {

    @TempDir
    Path tempDir;

    private DuckDBDataSource dataSource;
    private AppConfig config;

    @BeforeEach
    void setUp() throws Exception {
        config = new AppConfig();
        config.setDbPath(tempDir.resolve("test.duckdb").toString());
        config.setIntakeDir(tempDir.resolve("intake").toString());
        dataSource = new DuckDBDataSource(config);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    private SchemaBootstrap createBootstrap() {
        ArtifactService artifactService = new ArtifactService(dataSource);
        SearchService searchService = new SearchService(dataSource, new EmbeddingService(config), config);
        IngestionWorker worker = new IngestionWorker(dataSource, artifactService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                new EmbeddingService(config), new FileSummarizer(), new ImportParser(),
                new com.javaducker.server.ingestion.ReladomoXmlParser(),
                new com.javaducker.server.service.ReladomoService(dataSource),
                new com.javaducker.server.ingestion.ReladomoFinderParser(),
                new com.javaducker.server.ingestion.ReladomoConfigParser(),
                searchService, config);
        return new SchemaBootstrap(dataSource, config, worker);
    }

    @Test
    void createsAllTables() throws SQLException {
        SchemaBootstrap bootstrap = createBootstrap();
        bootstrap.createSchema();

        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            assertDoesNotThrow(() -> stmt.executeQuery("SELECT COUNT(*) FROM artifacts"));
            assertDoesNotThrow(() -> stmt.executeQuery("SELECT COUNT(*) FROM artifact_text"));
            assertDoesNotThrow(() -> stmt.executeQuery("SELECT COUNT(*) FROM artifact_chunks"));
            assertDoesNotThrow(() -> stmt.executeQuery("SELECT COUNT(*) FROM chunk_embeddings"));
            assertDoesNotThrow(() -> stmt.executeQuery("SELECT COUNT(*) FROM ingestion_events"));
        }
    }

    @Test
    void createsDeduplicationIndex() throws SQLException {
        SchemaBootstrap bootstrap = createBootstrap();
        bootstrap.createSchema();

        // DuckDB exposes indexes via duckdb_indexes()
        Connection conn = dataSource.getConnection();
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM duckdb_indexes() WHERE index_name = 'idx_artifacts_name_size'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(1, rs.getLong(1), "Dedup index idx_artifacts_name_size should exist");
        }
    }

    @Test
    void createsContentIntelligenceTables() throws SQLException {
        SchemaBootstrap bootstrap = createBootstrap();
        bootstrap.createSchema();

        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            assertDoesNotThrow(() -> stmt.executeQuery("SELECT COUNT(*) FROM artifact_classifications"));
            assertDoesNotThrow(() -> stmt.executeQuery("SELECT COUNT(*) FROM artifact_tags"));
            assertDoesNotThrow(() -> stmt.executeQuery("SELECT COUNT(*) FROM artifact_salient_points"));
            assertDoesNotThrow(() -> stmt.executeQuery("SELECT COUNT(*) FROM artifact_concepts"));
            assertDoesNotThrow(() -> stmt.executeQuery("SELECT COUNT(*) FROM concept_links"));
            assertDoesNotThrow(() -> stmt.executeQuery("SELECT COUNT(*) FROM artifact_synthesis"));
        }
    }

    @Test
    void addsContentIntelligenceColumnsToArtifacts() throws SQLException {
        SchemaBootstrap bootstrap = createBootstrap();
        bootstrap.createSchema();

        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT freshness, superseded_by, freshness_updated_at, enrichment_status FROM artifacts LIMIT 0")) {
            assertEquals("freshness", rs.getMetaData().getColumnName(1).toLowerCase());
            assertEquals("superseded_by", rs.getMetaData().getColumnName(2).toLowerCase());
            assertEquals("freshness_updated_at", rs.getMetaData().getColumnName(3).toLowerCase());
            assertEquals("enrichment_status", rs.getMetaData().getColumnName(4).toLowerCase());
        }
    }

    @Test
    void createsContentIntelligenceIndices() throws SQLException {
        SchemaBootstrap bootstrap = createBootstrap();
        bootstrap.createSchema();

        Connection conn = dataSource.getConnection();
        String[] expectedIndices = {
            "idx_classifications_doc_type", "idx_tags_tag", "idx_salient_points_type",
            "idx_concepts_concept", "idx_artifacts_freshness", "idx_artifacts_enrichment"
        };
        for (String idx : expectedIndices) {
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM duckdb_indexes() WHERE index_name = ?")) {
                ps.setString(1, idx);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(1, rs.getLong(1), "Index " + idx + " should exist");
                }
            }
        }
    }

    @Test
    void schemaIsIdempotent() throws SQLException {
        SchemaBootstrap bootstrap = createBootstrap();

        bootstrap.createSchema();
        bootstrap.createSchema();

        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM artifacts")) {
            rs.next();
            assertEquals(0, rs.getLong(1));
        }
    }
}
