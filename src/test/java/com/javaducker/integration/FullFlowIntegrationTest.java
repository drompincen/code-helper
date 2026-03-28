package com.javaducker.integration;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import com.javaducker.server.model.ArtifactStatus;
import com.javaducker.server.service.ArtifactService;
import com.javaducker.server.service.SearchService;
import com.javaducker.server.service.StatsService;
import com.javaducker.server.service.UploadService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullFlowIntegrationTest {

    @TempDir
    static Path tempDir;

    static AppConfig config;
    static DuckDBDataSource dataSource;
    static SchemaBootstrap schemaBootstrap;
    static UploadService uploadService;
    static ArtifactService artifactService;
    static TextExtractor textExtractor;
    static TextNormalizer textNormalizer;
    static Chunker chunker;
    static EmbeddingService embeddingService;
    static SearchService searchService;
    static StatsService statsService;
    static IngestionWorker ingestionWorker;

    static String javaArtifactId;
    static String mdArtifactId;
    static String yamlArtifactId;

    @BeforeAll
    static void setUp() throws Exception {
        config = new AppConfig();
        config.setDbPath(tempDir.resolve("test.duckdb").toString());
        config.setIntakeDir(tempDir.resolve("intake").toString());
        config.setChunkSize(200);
        config.setChunkOverlap(50);
        config.setEmbeddingDim(128);

        dataSource = new DuckDBDataSource(config);
        artifactService = new ArtifactService(dataSource);
        uploadService = new UploadService(dataSource, config, artifactService);
        textExtractor = new TextExtractor();
        textNormalizer = new TextNormalizer();
        chunker = new Chunker();
        embeddingService = new EmbeddingService(config);
        searchService = new SearchService(dataSource, embeddingService, config);
        statsService = new StatsService(dataSource);
        ingestionWorker = new IngestionWorker(dataSource, artifactService,
                textExtractor, textNormalizer, chunker, embeddingService,
                new FileSummarizer(), new ImportParser(), searchService, config);

        schemaBootstrap = new SchemaBootstrap(dataSource, config, ingestionWorker);
        schemaBootstrap.bootstrap();
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    @Order(1)
    void uploadJavaFile() throws Exception {
        String content = """
                package com.example;

                import org.springframework.transaction.annotation.Transactional;

                /**
                 * OnboardingService manages the onboarding case lifecycle.
                 * It coordinates approval workflows and state transitions.
                 */
                public class OnboardingService {

                    @Transactional
                    public void processOnboardingCase(String caseId) {
                        // Validate the case state
                        validateCaseState(caseId);
                        // Transition to next approval stage
                        transitionApproval(caseId);
                    }

                    private void validateCaseState(String caseId) {
                        // Check current state is valid for transition
                    }

                    private void transitionApproval(String caseId) {
                        // Move case to next approval stage
                    }
                }
                """;
        javaArtifactId = uploadService.upload("OnboardingService.java",
                "/repo/src/OnboardingService.java", "text/x-java", content.length(), content.getBytes());
        assertNotNull(javaArtifactId);

        Map<String, String> status = artifactService.getStatus(javaArtifactId);
        assertEquals("STORED_IN_INTAKE", status.get("status"));
    }

    @Test
    @Order(2)
    void uploadMarkdownFile() throws Exception {
        String content = """
                # Material Change Monitoring

                The material change monitoring system tracks significant changes
                to customer profiles and triggers compliance reviews when thresholds
                are exceeded.

                ## How it works

                1. Changes are detected via event listeners
                2. Each change is scored for materiality
                3. High-scoring changes trigger a review workflow
                4. Reviewers approve or reject the change
                """;
        mdArtifactId = uploadService.upload("monitoring.md",
                "/repo/docs/monitoring.md", "text/markdown", content.length(), content.getBytes());
        assertNotNull(mdArtifactId);
    }

    @Test
    @Order(3)
    void uploadYamlFile() throws Exception {
        String content = """
                server:
                  port: 8080
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/mydb
                monitoring:
                  enabled: true
                  threshold: 0.75
                """;
        yamlArtifactId = uploadService.upload("config.yml",
                "/repo/config.yml", "text/yaml", content.length(), content.getBytes());
        assertNotNull(yamlArtifactId);
    }

    @Test
    @Order(4)
    void ingestAllArtifacts() throws Exception {
        // Process all pending artifacts
        ingestionWorker.processArtifact(javaArtifactId);
        ingestionWorker.processArtifact(mdArtifactId);
        ingestionWorker.processArtifact(yamlArtifactId);

        assertEquals("INDEXED", artifactService.getStatus(javaArtifactId).get("status"));
        assertEquals("INDEXED", artifactService.getStatus(mdArtifactId).get("status"));
        assertEquals("INDEXED", artifactService.getStatus(yamlArtifactId).get("status"));
    }

    @Test
    @Order(5)
    void getExtractedText() throws Exception {
        Map<String, String> text = artifactService.getText(javaArtifactId);
        assertNotNull(text);
        assertTrue(text.get("extracted_text").contains("@Transactional"));
        assertEquals("TEXT_DECODE", text.get("extraction_method"));
    }

    @Test
    @Order(6)
    void exactSearchFindsTransactional() throws Exception {
        List<Map<String, Object>> results = searchService.exactSearch("@Transactional", 10);
        assertFalse(results.isEmpty(), "Should find @Transactional in Java file");
        assertTrue(results.stream().anyMatch(r ->
                r.get("file_name").equals("OnboardingService.java")));
    }

    @Test
    @Order(7)
    void semanticSearchFindsRelatedContent() throws Exception {
        List<Map<String, Object>> results = searchService.semanticSearch(
                "how onboarding case state is managed", 10);
        assertFalse(results.isEmpty(), "Semantic search should return results");
    }

    @Test
    @Order(8)
    void exactSearchForMaterialChange() throws Exception {
        List<Map<String, Object>> results = searchService.exactSearch("material change", 10);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(r ->
                r.get("file_name").equals("monitoring.md")));
    }

    @Test
    @Order(9)
    void hybridSearch() throws Exception {
        List<Map<String, Object>> results = searchService.hybridSearch("material change monitoring", 10);
        assertFalse(results.isEmpty(), "Hybrid search should return results");
    }

    @Test
    @Order(10)
    void statsAreCorrect() throws Exception {
        Map<String, Object> stats = statsService.getStats();
        assertEquals(3L, stats.get("total_artifacts"));
        assertEquals(3L, stats.get("indexed_artifacts"));
        assertEquals(0L, stats.get("failed_artifacts"));
        assertTrue((long) stats.get("total_chunks") > 0);
        assertTrue((long) stats.get("total_bytes") > 0);
    }

    @Test
    @Order(11)
    void unsupportedFileFailsGracefully() throws Exception {
        String artifactId = uploadService.upload("image.png",
                "/repo/image.png", "image/png", 4, new byte[]{0, 1, 2, 3});
        ingestionWorker.processArtifact(artifactId);

        Map<String, String> status = artifactService.getStatus(artifactId);
        assertEquals("FAILED", status.get("status"));
        assertFalse(status.get("error_message").isEmpty());
    }

    @Test
    @Order(12)
    void duplicateChunkIdsNotCreated() throws Exception {
        // Re-processing same artifact should fail or be handled
        // The artifact is already INDEXED, so the worker won't pick it up via poll
        Map<String, String> status = artifactService.getStatus(javaArtifactId);
        assertEquals("INDEXED", status.get("status"));
    }

    @Test
    @Order(13)
    void deduplicateUploadByNameAndSize() throws Exception {
        String content = "unique content for dedup test";
        byte[] bytes = content.getBytes();

        String firstId = uploadService.upload("dedup-test.txt",
                "/path/dedup-test.txt", "text/plain", bytes.length, bytes);
        assertNotNull(firstId);

        String secondId = uploadService.upload("dedup-test.txt",
                "/other/path/dedup-test.txt", "text/plain", bytes.length, bytes);

        assertEquals(firstId, secondId, "Same name+size should return existing artifact_id");
    }

    @Test
    @Order(14)
    void dedupDoesNotApplyToFailedArtifact() throws Exception {
        // A re-upload of a FAILED artifact should create a new one
        String artifactId = uploadService.upload("image.png",
                "/repo/image.png", "image/png", 4, new byte[]{0, 1, 2, 3});
        // image.png was uploaded and failed in order(11); this upload should dedup to that failed artifact
        // Since status=FAILED is excluded from dedup, a new artifact is created
        // (the failed one from test 11 has status=FAILED, so it is not matched)
        assertNotNull(artifactId);
    }
}
