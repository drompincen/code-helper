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

            // Reladomo: reladomo_type column on artifacts
            try (Statement alter = conn.createStatement()) {
                alter.execute("ALTER TABLE artifacts ADD COLUMN reladomo_type VARCHAR DEFAULT 'none'");
            } catch (Exception ignored) {}

            // Reladomo: parsed object definitions
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS reladomo_objects (
                    object_name VARCHAR PRIMARY KEY,
                    package_name VARCHAR,
                    table_name VARCHAR,
                    object_type VARCHAR,
                    temporal_type VARCHAR,
                    super_class VARCHAR,
                    interfaces VARCHAR,
                    source_attribute_name VARCHAR,
                    source_attribute_type VARCHAR,
                    artifact_id VARCHAR NOT NULL
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS reladomo_attributes (
                    object_name VARCHAR NOT NULL,
                    attribute_name VARCHAR NOT NULL,
                    java_type VARCHAR,
                    column_name VARCHAR,
                    nullable BOOLEAN DEFAULT FALSE,
                    primary_key BOOLEAN DEFAULT FALSE,
                    max_length INTEGER,
                    trim BOOLEAN DEFAULT FALSE,
                    truncate BOOLEAN DEFAULT FALSE,
                    PRIMARY KEY (object_name, attribute_name)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS reladomo_relationships (
                    object_name VARCHAR NOT NULL,
                    relationship_name VARCHAR NOT NULL,
                    cardinality VARCHAR,
                    related_object VARCHAR NOT NULL,
                    reverse_relationship_name VARCHAR,
                    parameters VARCHAR,
                    join_expression VARCHAR,
                    PRIMARY KEY (object_name, relationship_name)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS reladomo_indices (
                    object_name VARCHAR NOT NULL,
                    index_name VARCHAR NOT NULL,
                    columns VARCHAR,
                    is_unique BOOLEAN DEFAULT FALSE,
                    PRIMARY KEY (object_name, index_name)
                )
                """);

            // Reladomo: finder usage patterns (F13)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS reladomo_finder_usage (
                    object_name VARCHAR NOT NULL,
                    attribute_or_path VARCHAR NOT NULL,
                    operation VARCHAR,
                    source_file VARCHAR NOT NULL,
                    line_number INTEGER,
                    artifact_id VARCHAR NOT NULL
                )
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_finder_usage_object
                    ON reladomo_finder_usage (object_name)
                """);

            // Reladomo: deep fetch profiles (F14)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS reladomo_deep_fetch (
                    object_name VARCHAR NOT NULL,
                    fetch_path VARCHAR NOT NULL,
                    source_file VARCHAR NOT NULL,
                    line_number INTEGER,
                    artifact_id VARCHAR NOT NULL
                )
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_deep_fetch_object
                    ON reladomo_deep_fetch (object_name)
                """);

            // Reladomo: runtime connection managers (F16)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS reladomo_connection_managers (
                    config_file VARCHAR NOT NULL,
                    manager_name VARCHAR NOT NULL,
                    manager_class VARCHAR,
                    properties VARCHAR,
                    artifact_id VARCHAR NOT NULL,
                    PRIMARY KEY (config_file, manager_name)
                )
                """);

            // Reladomo: object-to-connection config (F16)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS reladomo_object_config (
                    object_name VARCHAR NOT NULL,
                    config_file VARCHAR NOT NULL,
                    connection_manager VARCHAR,
                    cache_type VARCHAR,
                    load_cache_on_startup BOOLEAN DEFAULT FALSE,
                    artifact_id VARCHAR NOT NULL,
                    PRIMARY KEY (object_name, config_file)
                )
                """);

            // Content Intelligence: classification tables (O1)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS artifact_classifications (
                    artifact_id VARCHAR PRIMARY KEY,
                    doc_type VARCHAR,
                    confidence FLOAT,
                    method VARCHAR,
                    classified_at TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS artifact_tags (
                    artifact_id VARCHAR NOT NULL,
                    tag VARCHAR NOT NULL,
                    tag_type VARCHAR,
                    source VARCHAR,
                    PRIMARY KEY (artifact_id, tag)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS artifact_salient_points (
                    point_id VARCHAR PRIMARY KEY,
                    artifact_id VARCHAR NOT NULL,
                    chunk_id VARCHAR,
                    point_type VARCHAR NOT NULL,
                    point_text VARCHAR NOT NULL,
                    source VARCHAR,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS artifact_concepts (
                    concept_id VARCHAR PRIMARY KEY,
                    artifact_id VARCHAR NOT NULL,
                    concept VARCHAR NOT NULL,
                    concept_type VARCHAR,
                    mention_count INTEGER,
                    chunk_ids VARCHAR
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS concept_links (
                    concept VARCHAR NOT NULL,
                    artifact_a VARCHAR NOT NULL,
                    artifact_b VARCHAR NOT NULL,
                    strength FLOAT,
                    PRIMARY KEY (concept, artifact_a, artifact_b)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS artifact_synthesis (
                    artifact_id VARCHAR PRIMARY KEY,
                    summary_text VARCHAR,
                    tags VARCHAR,
                    key_points VARCHAR,
                    outcome VARCHAR,
                    original_file_path VARCHAR,
                    synthesized_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // Content Intelligence: freshness columns on artifacts
            try (Statement alter = conn.createStatement()) {
                alter.execute("ALTER TABLE artifacts ADD COLUMN freshness VARCHAR DEFAULT 'current'");
            } catch (Exception ignored) {}
            try (Statement alter = conn.createStatement()) {
                alter.execute("ALTER TABLE artifacts ADD COLUMN superseded_by VARCHAR");
            } catch (Exception ignored) {}
            try (Statement alter = conn.createStatement()) {
                alter.execute("ALTER TABLE artifacts ADD COLUMN freshness_updated_at TIMESTAMP");
            } catch (Exception ignored) {}

            // Content Intelligence: enrichment status column on artifacts
            try (Statement alter = conn.createStatement()) {
                alter.execute("ALTER TABLE artifacts ADD COLUMN enrichment_status VARCHAR DEFAULT 'pending'");
            } catch (Exception ignored) {}

            // Content Intelligence: indices
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_classifications_doc_type
                    ON artifact_classifications (doc_type)
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_tags_tag
                    ON artifact_tags (tag)
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_salient_points_type
                    ON artifact_salient_points (point_type)
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_concepts_concept
                    ON artifact_concepts (concept)
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_artifacts_freshness
                    ON artifacts (freshness)
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_artifacts_enrichment
                    ON artifacts (enrichment_status)
                """);

            log.info("Database schema created/verified");
        }
    }
}
