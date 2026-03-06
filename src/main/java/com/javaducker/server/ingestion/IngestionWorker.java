package com.javaducker.server.ingestion;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.model.ArtifactStatus;
import com.javaducker.server.service.ArtifactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
public class IngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);
    private final DuckDBDataSource dataSource;
    private final ArtifactService artifactService;
    private final TextExtractor textExtractor;
    private final TextNormalizer textNormalizer;
    private final Chunker chunker;
    private final EmbeddingService embeddingService;
    private final AppConfig config;
    private volatile boolean ready = false;

    public IngestionWorker(DuckDBDataSource dataSource, ArtifactService artifactService,
                           TextExtractor textExtractor, TextNormalizer textNormalizer,
                           Chunker chunker, EmbeddingService embeddingService, AppConfig config) {
        this.dataSource = dataSource;
        this.artifactService = artifactService;
        this.textExtractor = textExtractor;
        this.textNormalizer = textNormalizer;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.config = config;
    }

    public void markReady() {
        this.ready = true;
    }

    @Scheduled(fixedDelayString = "${javaducker.ingestion-poll-seconds:5}000")
    public void poll() {
        if (!ready) return;
        try {
            String artifactId = findNextPending();
            if (artifactId != null) {
                processArtifact(artifactId);
            }
        } catch (Exception e) {
            log.error("Ingestion poll error", e);
        }
    }

    private String findNextPending() throws SQLException {
        Connection conn = dataSource.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT artifact_id FROM artifacts WHERE status = ? ORDER BY created_at ASC LIMIT 1")) {
            ps.setString(1, ArtifactStatus.STORED_IN_INTAKE.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("artifact_id");
                }
            }
        }
        return null;
    }

    public void processArtifact(String artifactId) {
        try {
            log.info("Processing artifact: {}", artifactId);

            // Get artifact info
            Connection conn = dataSource.getConnection();
            String intakePath;
            String fileName;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT intake_path, file_name FROM artifacts WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        log.warn("Artifact not found: {}", artifactId);
                        return;
                    }
                    intakePath = rs.getString("intake_path");
                    fileName = rs.getString("file_name");
                }
            }

            // Step 1: Parse / extract text
            artifactService.updateStatus(artifactId, ArtifactStatus.PARSING, null);
            TextExtractor.ExtractionResult extraction = textExtractor.extract(Path.of(intakePath));
            String normalizedText = textNormalizer.normalize(extraction.text());

            // Store extracted text
            storeExtractedText(artifactId, normalizedText, extraction.method());

            // Step 2: Chunk
            artifactService.updateStatus(artifactId, ArtifactStatus.CHUNKED, null);
            List<Chunker.Chunk> chunks = chunker.chunk(normalizedText,
                    config.getChunkSize(), config.getChunkOverlap());
            storeChunks(artifactId, chunks);

            // Step 3: Embed
            artifactService.updateStatus(artifactId, ArtifactStatus.EMBEDDED, null);
            for (Chunker.Chunk chunk : chunks) {
                String chunkId = artifactId + "-" + chunk.index();
                double[] embedding = embeddingService.embed(chunk.text());
                storeEmbedding(chunkId, embedding);
            }

            // Step 4: Mark indexed
            artifactService.updateStatus(artifactId, ArtifactStatus.INDEXED, null);
            log.info("Artifact indexed: {} ({}, {} chunks)", artifactId, fileName, chunks.size());

        } catch (Exception e) {
            log.error("Ingestion failed for artifact {}", artifactId, e);
            try {
                artifactService.updateStatus(artifactId, ArtifactStatus.FAILED, e.getMessage());
            } catch (SQLException ex) {
                log.error("Failed to update status to FAILED", ex);
            }
        }
    }

    private void storeExtractedText(String artifactId, String text, String method) throws SQLException {
        Connection conn = dataSource.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO artifact_text (artifact_id, extracted_text, text_length, extraction_method) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, artifactId);
            ps.setString(2, text);
            ps.setLong(3, text.length());
            ps.setString(4, method);
            ps.executeUpdate();
        }
    }

    private void storeChunks(String artifactId, List<Chunker.Chunk> chunks) throws SQLException {
        Connection conn = dataSource.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO artifact_chunks (chunk_id, artifact_id, chunk_index, chunk_text, char_start, char_end) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Chunker.Chunk chunk : chunks) {
                String chunkId = artifactId + "-" + chunk.index();
                ps.setString(1, chunkId);
                ps.setString(2, artifactId);
                ps.setInt(3, chunk.index());
                ps.setString(4, chunk.text());
                ps.setLong(5, chunk.charStart());
                ps.setLong(6, chunk.charEnd());
                ps.executeUpdate();
            }
        }
    }

    private void storeEmbedding(String chunkId, double[] embedding) throws SQLException {
        Connection conn = dataSource.getConnection();
        // Build array literal for DuckDB
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chunk_embeddings (chunk_id, embedding_model, embedding_dim, embedding) VALUES (?, ?, ?, " + sb + "::DOUBLE[])")) {
            ps.setString(1, chunkId);
            ps.setString(2, "tfidf-hash-v1");
            ps.setInt(3, embedding.length);
            ps.executeUpdate();
        }
    }
}
