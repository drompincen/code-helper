package com.javaducker.server.ingestion;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.model.ArtifactStatus;
import com.javaducker.server.service.ArtifactService;
import com.javaducker.server.service.SearchService;
import com.javaducker.server.service.UploadService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IngestionWorkerParallelTest {

    @TempDir
    Path tempDir;

    private AppConfig config;
    private DuckDBDataSource dataSource;
    private UploadService uploadService;
    private ArtifactService artifactService;
    private IngestionWorker ingestionWorker;

    @BeforeEach
    void setUp() throws Exception {
        config = new AppConfig();
        config.setDbPath(tempDir.resolve("test.duckdb").toString());
        config.setIntakeDir(tempDir.resolve("intake").toString());
        config.setChunkSize(200);
        config.setChunkOverlap(50);
        config.setEmbeddingDim(64);
        config.setIngestionWorkerThreads(4);

        dataSource = new DuckDBDataSource(config);
        artifactService = new ArtifactService(dataSource);
        uploadService = new UploadService(dataSource, config, artifactService);
        SearchService searchService = new SearchService(dataSource, new EmbeddingService(config), config);
        ingestionWorker = new IngestionWorker(dataSource, artifactService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                new EmbeddingService(config), new FileSummarizer(), new ImportParser(),
                new com.javaducker.server.ingestion.ReladomoXmlParser(),
                new com.javaducker.server.service.ReladomoService(dataSource),
                new com.javaducker.server.ingestion.ReladomoFinderParser(),
                new com.javaducker.server.ingestion.ReladomoConfigParser(),
                searchService, config);

        SchemaBootstrap bootstrap = new SchemaBootstrap(dataSource, config, ingestionWorker);
        bootstrap.bootstrap();
    }

    @AfterEach
    void tearDown() {
        ingestionWorker.shutdown();
        dataSource.close();
    }

    @Test
    void parallelPollProcessesMultipleArtifactsConcurrently() throws Exception {
        // Upload more files than the poll would handle in single-threaded mode
        List<String> artifactIds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String content = "File content number " + i + " with some unique text to index properly.";
            String id = uploadService.upload("file" + i + ".txt",
                    "/path/file" + i + ".txt", "text/plain",
                    content.length(), content.getBytes());
            artifactIds.add(id);
        }

        // Confirm all are in STORED_IN_INTAKE
        for (String id : artifactIds) {
            assertEquals(ArtifactStatus.STORED_IN_INTAKE.name(),
                    artifactService.getStatus(id).get("status"));
        }

        // poll() fetches up to workerThreads (4) artifacts and submits to the pool
        ingestionWorker.poll();

        // Wait for the pool to finish (poll submits async tasks)
        waitForIndexed(artifactIds.subList(0, 4), 10_000);

        for (String id : artifactIds.subList(0, 4)) {
            assertEquals(ArtifactStatus.INDEXED.name(),
                    artifactService.getStatus(id).get("status"),
                    "Artifact " + id + " should be INDEXED after parallel poll");
        }
    }

    @Test
    void processArtifactDirectlyIsThreadSafe() throws Exception {
        // Upload 4 files and process them directly from multiple threads
        List<String> artifactIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String content = "Thread safety test content for artifact " + i + " with enough text.";
            String id = uploadService.upload("concurrent" + i + ".txt",
                    "/path/concurrent" + i + ".txt", "text/plain",
                    content.length(), content.getBytes());
            artifactIds.add(id);
        }

        List<Thread> threads = new ArrayList<>();
        for (String id : artifactIds) {
            threads.add(Thread.ofVirtual().start(() -> ingestionWorker.processArtifact(id)));
        }
        for (Thread t : threads) {
            t.join(10_000);
        }

        for (String id : artifactIds) {
            Map<String, String> status = artifactService.getStatus(id);
            assertEquals(ArtifactStatus.INDEXED.name(), status.get("status"),
                    "Artifact " + id + " should be INDEXED after concurrent processing");
        }
    }

    private void waitForIndexed(List<String> ids, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allDone = ids.stream().allMatch(id -> {
                try {
                    String status = artifactService.getStatus(id).get("status");
                    return ArtifactStatus.INDEXED.name().equals(status)
                            || ArtifactStatus.FAILED.name().equals(status);
                } catch (Exception e) {
                    return false;
                }
            });
            if (allDone) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for artifacts to be indexed");
    }
}
