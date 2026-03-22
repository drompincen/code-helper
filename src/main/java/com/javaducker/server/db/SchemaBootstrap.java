package com.javaducker.server.db;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.ingestion.IngestionWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class SchemaBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SchemaBootstrap.class);
    private final DuckDBDataSource dataSource;
    private final AppConfig config;
    private final IngestionWorker ingestionWorker;

    public SchemaBootstrap(DuckDBDataSource dataSource, AppConfig config, IngestionWorker ingestionWorker) {
        this.dataSource = dataSource;
        this.config = config;
        this.ingestionWorker = ingestionWorker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() throws SQLException, IOException {
        ensureIntakeDirectory();
        createSchema();
        ingestionWorker.markReady();
    }

    private void ensureIntakeDirectory() throws IOException {
        Path intakePath = Path.of(config.getIntakeDir());
        if (!Files.exists(intakePath)) {
            Files.createDirectories(intakePath);
            log.info("Created intake directory: {}", intakePath.toAbsolutePath());
        }
    }

    public void createSchema() throws SQLException {
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS artifacts (
                    artifact_id VARCHAR PRIMARY KEY,
                    file_name VARCHAR NOT NULL,
                    media_type VARCHAR,
                    original_client_path VARCHAR,
                    intake_path VARCHAR,
                    size_bytes BIGINT,
                    sha256 VARCHAR,
                    source_type VARCHAR DEFAULT 'UPLOAD',
                    status VARCHAR NOT NULL DEFAULT 'RECEIVED',
                    error_message VARCHAR,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    indexed_at TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS artifact_text (
                    artifact_id VARCHAR PRIMARY KEY,
                    extracted_text VARCHAR,
                    text_length BIGINT,
                    extraction_method VARCHAR
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS artifact_chunks (
                    chunk_id VARCHAR PRIMARY KEY,
                    artifact_id VARCHAR NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    chunk_text VARCHAR NOT NULL,
                    char_start BIGINT,
                    char_end BIGINT
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chunk_embeddings (
                    chunk_id VARCHAR PRIMARY KEY,
                    embedding_model VARCHAR,
                    embedding_dim INTEGER,
                    embedding DOUBLE[]
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ingestion_events (
                    event_id VARCHAR PRIMARY KEY,
                    artifact_id VARCHAR NOT NULL,
                    state VARCHAR NOT NULL,
                    message VARCHAR,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_artifacts_name_size
                    ON artifacts (file_name, size_bytes)
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_artifacts_client_path
                    ON artifacts (original_client_path)
                """);

            log.info("Database schema created/verified");
        }
    }
}
