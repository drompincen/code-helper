package com.javaducker.server.ingestion;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.model.ArtifactStatus;
import com.javaducker.server.service.ArtifactService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private final ThreadPoolExecutor threadPool;
    private volatile boolean ready = false;

    // Progress tracking
    private final AtomicLong lastIndexedCount   = new AtomicLong(0);
    private final AtomicLong lastCompletedTasks = new AtomicLong(0);
    private volatile long lastProgressLogMs = System.currentTimeMillis();

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
        this.threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(config.getIngestionWorkerThreads());
    }

    public void markReady() {
        this.ready = true;
    }

    @PreDestroy
    public void shutdown() {
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(fixedDelayString = "${javaducker.ingestion-poll-seconds:5}000")
    public void poll() {
        if (!ready) return;
        try {
            List<String> pending = findNextPending(config.getIngestionWorkerThreads());
            for (String artifactId : pending) {
                threadPool.submit(() -> processArtifact(artifactId));
            }
        } catch (Exception e) {
            log.error("Ingestion poll error", e);
        }
    }

    @Scheduled(fixedDelay = 10_000)
    public void logProgress() {
        if (!ready) return;
        try {
            Map<ArtifactStatus, Long> counts = countsByStatus();

            long queued      = counts.getOrDefault(ArtifactStatus.STORED_IN_INTAKE, 0L);
            long parsing     = counts.getOrDefault(ArtifactStatus.PARSING, 0L)
                             + counts.getOrDefault(ArtifactStatus.CHUNKED, 0L)
                             + counts.getOrDefault(ArtifactStatus.EMBEDDED, 0L);
            long indexed     = counts.getOrDefault(ArtifactStatus.INDEXED, 0L);
            long failed      = counts.getOrDefault(ArtifactStatus.FAILED, 0L);
            long total       = counts.values().stream().mapToLong(Long::longValue).sum();
            long pending     = total - indexed - failed;

            // Nothing to report when idle
            if (pending == 0 && threadPool.getActiveCount() == 0) return;

            // Throughput: files indexed since last log
            long now        = System.currentTimeMillis();
            long elapsedMs  = now - lastProgressLogMs;
            long prevIndexed = lastIndexedCount.getAndSet(indexed);
            double filesPerMin = elapsedMs > 0
                ? (indexed - prevIndexed) * 60_000.0 / elapsedMs
                : 0.0;
            lastProgressLogMs = now;

            double pct = total > 0 ? indexed * 100.0 / total : 0.0;
            int poolSize      = threadPool.getMaximumPoolSize();
            int activeNow     = threadPool.getActiveCount();
            long completed    = threadPool.getCompletedTaskCount();
            long doneInWindow = completed - lastCompletedTasks.getAndSet(completed);

            log.info("[Indexing] {}/{} indexed ({}%) | queued: {} | processing: {} | failed: {} | {} files/min | pool: {} done in 10s, {} active / {} threads",
                indexed, total, String.format("%.1f", pct),
                queued, parsing, failed,
                String.format("%.1f", filesPerMin),
                doneInWindow, activeNow, poolSize);

        } catch (Exception e) {
            log.warn("Progress log error", e);
        }
    }

    private Map<ArtifactStatus, Long> countsByStatus() throws SQLException {
        return dataSource.withConnection(conn -> {
            Map<ArtifactStatus, Long> counts = new EnumMap<>(ArtifactStatus.class);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, COUNT(*) FROM artifacts GROUP BY status");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        ArtifactStatus s = ArtifactStatus.valueOf(rs.getString(1));
                        counts.put(s, rs.getLong(2));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
            return counts;
        });
    }

    private List<String> findNextPending(int limit) throws SQLException {
        return dataSource.withConnection(conn -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT artifact_id FROM artifacts WHERE status = ? ORDER BY created_at ASC LIMIT ?")) {
                ps.setString(1, ArtifactStatus.STORED_IN_INTAKE.name());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString("artifact_id"));
                    }
                }
            }
            return ids;
        });
    }

    public void processArtifact(String artifactId) {
        try {
            log.info("Processing artifact: {}", artifactId);

            // Get artifact info
            String[] info = dataSource.withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT intake_path, file_name FROM artifacts WHERE artifact_id = ?")) {
                    ps.setString(1, artifactId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return null;
                        return new String[]{rs.getString("intake_path"), rs.getString("file_name")};
                    }
                }
            });

            if (info == null) {
                log.warn("Artifact not found: {}", artifactId);
                return;
            }
            String intakePath = info[0];
            String fileName = info[1];

            // Step 1: Parse / extract text (CPU work — runs outside DB lock)
            artifactService.updateStatus(artifactId, ArtifactStatus.PARSING, null);
            TextExtractor.ExtractionResult extraction = textExtractor.extract(Path.of(intakePath));
            String normalizedText = textNormalizer.normalize(extraction.text());

            dataSource.withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO artifact_text (artifact_id, extracted_text, text_length, extraction_method) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, artifactId);
                    ps.setString(2, normalizedText);
                    ps.setLong(3, normalizedText.length());
                    ps.setString(4, extraction.method());
                    ps.executeUpdate();
                }
                return null;
            });

            // Step 2: Chunk (CPU work — runs outside DB lock)
            artifactService.updateStatus(artifactId, ArtifactStatus.CHUNKED, null);
            List<Chunker.Chunk> chunks = chunker.chunk(normalizedText,
                    config.getChunkSize(), config.getChunkOverlap());

            dataSource.withConnection(conn -> {
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
                return null;
            });

            // Step 3: Embed (CPU work — compute all vectors outside DB lock, then write in one pass)
            artifactService.updateStatus(artifactId, ArtifactStatus.EMBEDDED, null);
            record ChunkEmbedding(String chunkId, double[] embedding) {}
            List<ChunkEmbedding> embeddings = new ArrayList<>(chunks.size());
            for (Chunker.Chunk chunk : chunks) {
                embeddings.add(new ChunkEmbedding(artifactId + "-" + chunk.index(), embeddingService.embed(chunk.text())));
            }

            dataSource.withConnection(conn -> {
                for (ChunkEmbedding ce : embeddings) {
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < ce.embedding().length; i++) {
                        if (i > 0) sb.append(",");
                        sb.append(ce.embedding()[i]);
                    }
                    sb.append("]");
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO chunk_embeddings (chunk_id, embedding_model, embedding_dim, embedding) VALUES (?, ?, ?, " + sb + "::DOUBLE[])")) {
                        ps.setString(1, ce.chunkId());
                        ps.setString(2, "tfidf-hash-v1");
                        ps.setInt(3, ce.embedding().length);
                        ps.executeUpdate();
                    }
                }
                return null;
            });

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
}
