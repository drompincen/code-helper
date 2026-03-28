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
                    char_end BIGINT,
                    line_start INTEGER,
                    line_end INTEGER
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

            // Feature 1: Add line_start/line_end columns to existing DBs
            try (Statement alter = conn.createStatement()) {
                alter.execute("ALTER TABLE artifact_chunks ADD COLUMN line_start INTEGER");
            } catch (Exception ignored) {}
            try (Statement alter = conn.createStatement()) {
                alter.execute("ALTER TABLE artifact_chunks ADD COLUMN line_end INTEGER");
            } catch (Exception ignored) {}

            // Feature 3: File summaries table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS artifact_summaries (
                    artifact_id VARCHAR PRIMARY KEY,
                    summary_text VARCHAR,
                    class_names VARCHAR,
                    method_names VARCHAR,
                    import_count INTEGER,
                    line_count INTEGER
                )
                """);

            // Feature 5: Dependency/import graph table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS artifact_imports (
                    artifact_id VARCHAR NOT NULL,
                    import_statement VARCHAR NOT NULL,
                    resolved_artifact_id VARCHAR
                )
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_artifact_imports_artifact
                    ON artifact_imports (artifact_id)
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_artifact_imports_resolved
                    ON artifact_imports (resolved_artifact_id)
                """);

            log.info("Database schema created/verified");
        }
    }
}
