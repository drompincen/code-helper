package com.javaducker.server.db;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.ingestion.*;
import com.javaducker.server.service.ArtifactService;
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
        IngestionWorker worker = new IngestionWorker(dataSource, artifactService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                new EmbeddingService(config), config);
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
