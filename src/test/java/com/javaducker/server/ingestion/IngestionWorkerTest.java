package com.javaducker.server.ingestion;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.model.ArtifactStatus;
import com.javaducker.server.service.ArtifactService;
import com.javaducker.server.service.ReladomoService;
import com.javaducker.server.service.SearchService;
import com.javaducker.server.service.UploadService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IngestionWorkerTest {

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
        config.setIngestionWorkerThreads(2);

        dataSource = new DuckDBDataSource(config);
        artifactService = new ArtifactService(dataSource);
        uploadService = new UploadService(dataSource, config, artifactService);
        SearchService searchService = new SearchService(dataSource, new EmbeddingService(config), config);
        ingestionWorker = new IngestionWorker(dataSource, artifactService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                new EmbeddingService(config), new FileSummarizer(), new ImportParser(),
                new ReladomoXmlParser(), new ReladomoService(dataSource),
                new ReladomoFinderParser(), new ReladomoConfigParser(),
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
    void processTextFileToIndexed() throws Exception {
        String content = "This is a plain text file with enough content to be chunked and indexed properly.";
        String artifactId = uploadService.upload("readme.txt",
                "/original/readme.txt", "text/plain",
                content.length(), content.getBytes());

        assertEquals(ArtifactStatus.STORED_IN_INTAKE.name(),
                artifactService.getStatus(artifactId).get("status"));

        ingestionWorker.processArtifact(artifactId);

        Map<String, String> status = artifactService.getStatus(artifactId);
        assertEquals(ArtifactStatus.INDEXED.name(), status.get("status"),
                "Text file should reach INDEXED status");

        // Verify extracted text was stored
        dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT text_length FROM artifact_text WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "artifact_text row should exist");
                    assertTrue(rs.getLong("text_length") > 0, "text_length should be positive");
                }
            }
            return null;
        });

        // Verify chunks were created
        dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM artifact_chunks WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertTrue(rs.getInt(1) >= 1, "At least one chunk should exist");
                }
            }
            return null;
        });
    }

    @Test
    void processJavaFileToIndexed() throws Exception {
        String javaCode = """
                package com.example;

                import java.util.List;
                import java.util.Map;

                public class HelloWorld {
                    private final String name;

                    public HelloWorld(String name) {
                        this.name = name;
                    }

                    public String greet() {
                        return "Hello, " + name + "!";
                    }

                    public static void main(String[] args) {
                        HelloWorld hw = new HelloWorld("World");
                        System.out.println(hw.greet());
                    }
                }
                """;
        String artifactId = uploadService.upload("HelloWorld.java",
                "/src/com/example/HelloWorld.java", "text/x-java-source",
                javaCode.length(), javaCode.getBytes());

        ingestionWorker.processArtifact(artifactId);

        Map<String, String> status = artifactService.getStatus(artifactId);
        assertEquals(ArtifactStatus.INDEXED.name(), status.get("status"),
                "Java file should reach INDEXED status");

        // Verify summary was generated with class/method names
        dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT class_names, method_names FROM artifact_summaries WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "artifact_summaries row should exist for .java file");
                    String classNames = rs.getString("class_names");
                    assertNotNull(classNames);
                    assertTrue(classNames.contains("HelloWorld"),
                            "class_names should contain HelloWorld, got: " + classNames);
                }
            }
            return null;
        });

        // Verify imports were parsed
        dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM artifact_imports WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertTrue(rs.getInt(1) >= 1, "Imports should be parsed from java file");
                }
            }
            return null;
        });
    }

    @Test
    void processEmptyFileHandledGracefully() throws Exception {
        String artifactId = uploadService.upload("empty.txt",
                "/path/empty.txt", "text/plain",
                0, new byte[0]);

        ingestionWorker.processArtifact(artifactId);

        Map<String, String> status = artifactService.getStatus(artifactId);
        // Empty file should either be INDEXED (with zero chunks) or FAILED
        String finalStatus = status.get("status");
        assertTrue(
                ArtifactStatus.INDEXED.name().equals(finalStatus)
                        || ArtifactStatus.FAILED.name().equals(finalStatus),
                "Empty file should be INDEXED or FAILED, got: " + finalStatus);
    }

    @Test
    void processNonExistentArtifactIdHandledGracefully() {
        // Should not throw — the method logs a warning and returns early
        assertDoesNotThrow(() -> ingestionWorker.processArtifact("non-existent-id"));
    }

    @Test
    void processXmlFileToIndexed() throws Exception {
        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0</version>
                </project>
                """;
        String artifactId = uploadService.upload("pom.xml",
                "/path/pom.xml", "application/xml",
                xmlContent.length(), xmlContent.getBytes());

        ingestionWorker.processArtifact(artifactId);

        Map<String, String> status = artifactService.getStatus(artifactId);
        assertEquals(ArtifactStatus.INDEXED.name(), status.get("status"),
                "XML file should reach INDEXED status");
    }

    @Test
    void pollProcessesPendingWhenReady() throws Exception {
        String content = "Content to process via poll with enough text for proper indexing.";
        String artifactId = uploadService.upload("pollready.txt",
                "/path/pollready.txt", "text/plain",
                content.length(), content.getBytes());

        ingestionWorker.markReady();
        ingestionWorker.poll();

        // Wait for async processing
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String st = artifactService.getStatus(artifactId).get("status");
            if (ArtifactStatus.INDEXED.name().equals(st) || ArtifactStatus.FAILED.name().equals(st)) {
                break;
            }
            Thread.sleep(100);
        }

        String finalStatus = artifactService.getStatus(artifactId).get("status");
        assertEquals(ArtifactStatus.INDEXED.name(), finalStatus,
                "Artifact should be INDEXED after poll when worker is ready");
    }

    @Test
    void markReadyEnablesProcessing() {
        assertDoesNotThrow(() -> ingestionWorker.markReady());
    }

    @Test
    void logProgressDoesNotThrowWhenNotReady() {
        assertDoesNotThrow(() -> ingestionWorker.logProgress());
    }

    @Test
    void logProgressDoesNotThrowWhenReady() throws Exception {
        ingestionWorker.markReady();
        assertDoesNotThrow(() -> ingestionWorker.logProgress());
    }

    @Test
    void shutdownIsIdempotent() {
        ingestionWorker.shutdown();
        // Second call should not throw
        assertDoesNotThrow(() -> ingestionWorker.shutdown());
    }

    @Test
    void buildHnswIndexWithNoData() throws Exception {
        // Should succeed with empty database — zero vectors
        assertDoesNotThrow(() -> ingestionWorker.buildHnswIndex());
    }

    @Test
    void buildHnswIndexAfterProcessing() throws Exception {
        String content = "Content for HNSW index building test with enough text to generate embeddings.";
        String artifactId = uploadService.upload("hnsw.txt",
                "/path/hnsw.txt", "text/plain",
                content.length(), content.getBytes());

        ingestionWorker.processArtifact(artifactId);

        // buildHnswIndex should load the embeddings we just created
        assertDoesNotThrow(() -> ingestionWorker.buildHnswIndex());
    }
}
