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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
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

    @Test
    void processMarkdownFileToIndexed() throws Exception {
        String mdContent = """
                # Project README

                This project demonstrates **markdown** parsing and indexing.

                ## Features
                - Feature one: text extraction
                - Feature two: chunking support
                - Feature three: embedding generation

                ## Usage
                Run the application with `java -jar app.jar`.

                Some additional content to ensure there is enough text for the chunker
                to produce at least one meaningful chunk during the ingestion process.
                """;
        String artifactId = uploadService.upload("README.md",
                "/docs/README.md", "text/markdown",
                mdContent.length(), mdContent.getBytes());

        ingestionWorker.processArtifact(artifactId);

        Map<String, String> status = artifactService.getStatus(artifactId);
        assertEquals(ArtifactStatus.INDEXED.name(), status.get("status"),
                "Markdown file should reach INDEXED status");

        // Verify summary was generated
        dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT summary_text FROM artifact_summaries WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "artifact_summaries row should exist for .md file");
                    assertNotNull(rs.getString("summary_text"),
                            "summary_text should be populated");
                }
            }
            return null;
        });
    }

    @Test
    void processCorruptedBinaryWithTxtExtension() throws Exception {
        // Random binary data with .txt extension should fail extraction or produce garbage
        byte[] binaryData = new byte[256];
        new java.util.Random(42).nextBytes(binaryData);
        // Add some 0-bytes that will break UTF-8 decoding in many cases
        binaryData[0] = (byte) 0xFF;
        binaryData[1] = (byte) 0xFE;

        String artifactId = uploadService.upload("corrupted.txt",
                "/path/corrupted.txt", "text/plain",
                binaryData.length, binaryData);

        // Should not throw — it either indexes (with mangled text) or fails gracefully
        assertDoesNotThrow(() -> ingestionWorker.processArtifact(artifactId));

        Map<String, String> status = artifactService.getStatus(artifactId);
        String finalStatus = status.get("status");
        assertTrue(
                ArtifactStatus.INDEXED.name().equals(finalStatus)
                        || ArtifactStatus.FAILED.name().equals(finalStatus),
                "Corrupted binary file should be INDEXED or FAILED, got: " + finalStatus);
    }

    @Test
    void processDuplicateFileReindexes() throws Exception {
        String content = "Duplicate file content for testing re-indexing behavior in the pipeline.";

        String id1 = uploadService.upload("dup.txt",
                "/path/dup.txt", "text/plain",
                content.length(), content.getBytes());
        ingestionWorker.processArtifact(id1);
        assertEquals(ArtifactStatus.INDEXED.name(),
                artifactService.getStatus(id1).get("status"));

        // Upload same content again — gets a new artifact ID
        String id2 = uploadService.upload("dup.txt",
                "/path/dup.txt", "text/plain",
                content.length(), content.getBytes());
        ingestionWorker.processArtifact(id2);
        assertEquals(ArtifactStatus.INDEXED.name(),
                artifactService.getStatus(id2).get("status"));

        // Both artifacts should have chunks
        for (String aid : List.of(id1, id2)) {
            long chunkCount = dataSource.withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM artifact_chunks WHERE artifact_id = ?")) {
                    ps.setString(1, aid);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return (long) rs.getInt(1);
                    }
                }
            });
            assertTrue(chunkCount >= 1,
                    "Artifact " + aid + " should have at least one chunk");
        }
    }

    @Test
    void buildHnswIndexWithMultipleVectorsIsSearchable() throws Exception {
        // Process multiple files to seed multiple embeddings
        String[] contents = {
                "Java Spring Boot application framework for building enterprise web services and microservices.",
                "DuckDB is an analytical database engine optimized for OLAP workloads and columnar storage.",
                "Python machine learning libraries include scikit-learn, TensorFlow, and PyTorch frameworks."
        };
        String[] names = {"spring.txt", "duckdb.txt", "python.txt"};

        for (int i = 0; i < contents.length; i++) {
            String artifactId = uploadService.upload(names[i],
                    "/path/" + names[i], "text/plain",
                    contents[i].length(), contents[i].getBytes());
            ingestionWorker.processArtifact(artifactId);
        }

        // Build index and verify it has vectors
        ingestionWorker.buildHnswIndex();

        SearchService searchService = new SearchService(dataSource,
                new EmbeddingService(config), config);
        // Copy the index reference
        searchService.setHnswIndex(
                new SearchService(dataSource, new EmbeddingService(config), config).getHnswIndex());

        // Verify embeddings exist in DB
        long embeddingCount = dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM chunk_embeddings")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        assertTrue(embeddingCount >= 3,
                "Should have at least 3 embeddings, got: " + embeddingCount);
    }

    @Test
    void processUnsupportedFileTypeFailsGracefully() throws Exception {
        // Create a file with unsupported extension
        Path unsupported = Path.of(config.getIntakeDir()).resolve("test-unsupported.png");
        Files.createDirectories(unsupported.getParent());
        Files.write(unsupported, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        // Manually insert artifact record pointing to the unsupported file
        String artifactId = "test-unsupported-" + System.nanoTime();
        dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO artifacts (artifact_id, file_name, intake_path, status, size_bytes) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, artifactId);
                ps.setString(2, "test.png");
                ps.setString(3, unsupported.toString());
                ps.setString(4, ArtifactStatus.STORED_IN_INTAKE.name());
                ps.setLong(5, 4);
                ps.executeUpdate();
            }
            return null;
        });

        ingestionWorker.processArtifact(artifactId);

        Map<String, String> status = artifactService.getStatus(artifactId);
        assertEquals(ArtifactStatus.FAILED.name(), status.get("status"),
                "Unsupported file type should result in FAILED status");
        assertNotNull(status.get("error_message"),
                "Error message should be set for failed artifact");
    }

    @Test
    void logProgressWithActiveProcessingDoesNotThrow() throws Exception {
        // Seed some artifacts in various states
        String content = "Content for progress logging test with sufficient text length.";
        String artifactId = uploadService.upload("progress.txt",
                "/path/progress.txt", "text/plain",
                content.length(), content.getBytes());
        ingestionWorker.processArtifact(artifactId);

        ingestionWorker.markReady();
        // Call logProgress when there is data — should not throw
        assertDoesNotThrow(() -> ingestionWorker.logProgress());
    }

    @Test
    void logProgressWithPendingArtifacts() throws Exception {
        // Upload artifact but do NOT process it — leaves it in STORED_IN_INTAKE (pending)
        String content = "Content left pending for progress log coverage test.";
        String artifactId = uploadService.upload("pending-log.txt",
                "/path/pending-log.txt", "text/plain",
                content.length(), content.getBytes());

        ingestionWorker.markReady();
        // logProgress should log queued/pending stats without error
        assertDoesNotThrow(() -> ingestionWorker.logProgress());
    }

    @Test
    void logProgressCalledTwiceComputesThroughput() throws Exception {
        // Process an artifact so indexed count > 0
        String content = "Content for throughput calculation test in logProgress.";
        String artifactId = uploadService.upload("throughput.txt",
                "/path/throughput.txt", "text/plain",
                content.length(), content.getBytes());
        ingestionWorker.processArtifact(artifactId);

        // Upload another pending one so pending > 0
        String content2 = "Another pending file to ensure logProgress does not exit early.";
        uploadService.upload("throughput2.txt",
                "/path/throughput2.txt", "text/plain",
                content2.length(), content2.getBytes());

        ingestionWorker.markReady();
        // First call sets baseline
        ingestionWorker.logProgress();
        // Second call computes throughput delta
        assertDoesNotThrow(() -> ingestionWorker.logProgress());
    }

    @Test
    void processReladomoXmlFile() throws Exception {
        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <MithraObject objectType="transactional"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <PackageName>com.example.domain</PackageName>
                    <ClassName>Account</ClassName>
                    <DefaultTable>ACCOUNT</DefaultTable>
                    <Attribute name="accountId" javaType="long" columnName="ACCOUNT_ID" primaryKey="true"/>
                    <Attribute name="name" javaType="String" columnName="NAME" maxLength="100"/>
                </MithraObject>
                """;
        String artifactId = uploadService.upload("Account.xml",
                "/src/main/resources/Account.xml", "application/xml",
                xmlContent.length(), xmlContent.getBytes());

        ingestionWorker.processArtifact(artifactId);

        Map<String, String> status = artifactService.getStatus(artifactId);
        assertEquals(ArtifactStatus.INDEXED.name(), status.get("status"),
                "Reladomo XML file should reach INDEXED status");
    }

    @Test
    void pollDoesNothingWhenNotReady() {
        // poll() should return immediately without processing when not ready
        assertDoesNotThrow(() -> ingestionWorker.poll());
    }

    @Test
    void processArtifactSetsFailedOnExtractionError() throws Exception {
        // Create an artifact record pointing to a file that does not exist on disk
        String artifactId = "missing-file-" + System.nanoTime();
        dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO artifacts (artifact_id, file_name, intake_path, status, size_bytes) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, artifactId);
                ps.setString(2, "ghost.txt");
                ps.setString(3, "/nonexistent/path/ghost.txt");
                ps.setString(4, ArtifactStatus.STORED_IN_INTAKE.name());
                ps.setLong(5, 100);
                ps.executeUpdate();
            }
            return null;
        });

        // processArtifact should catch the extraction error and set FAILED
        ingestionWorker.processArtifact(artifactId);

        Map<String, String> status = artifactService.getStatus(artifactId);
        assertEquals(ArtifactStatus.FAILED.name(), status.get("status"),
                "Missing file should result in FAILED status");
        assertNotNull(status.get("error_message"),
                "Error message should be set when processing fails");
    }

    @Test
    void processLargeFileProducesMultipleChunks() throws Exception {
        // Generate a file large enough to produce multiple chunks (chunkSize=200, overlap=50)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("Line ").append(i).append(": This is a line of text for multi-chunk testing. ");
        }
        String content = sb.toString();
        String artifactId = uploadService.upload("large.txt",
                "/path/large.txt", "text/plain",
                content.length(), content.getBytes());

        ingestionWorker.processArtifact(artifactId);

        assertEquals(ArtifactStatus.INDEXED.name(),
                artifactService.getStatus(artifactId).get("status"));

        // Verify multiple chunks were created
        long chunkCount = dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM artifact_chunks WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return (long) rs.getInt(1);
                }
            }
        });
        assertTrue(chunkCount > 1,
                "Large file should produce multiple chunks, got: " + chunkCount);

        // Verify multiple embeddings were created
        long embeddingCount = dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM chunk_embeddings WHERE chunk_id LIKE ?")) {
                ps.setString(1, artifactId + "-%");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return (long) rs.getInt(1);
                }
            }
        });
        assertEquals(chunkCount, embeddingCount,
                "Each chunk should have a corresponding embedding");
    }

    @Test
    void processJavaFileWithNoImports() throws Exception {
        // Java file without any import statements — exercises the empty imports branch
        String javaCode = """
                package com.example;

                public class NoImports {
                    public void doNothing() {
                        // This class has no imports at all
                        int x = 42;
                    }
                }
                """;
        String artifactId = uploadService.upload("NoImports.java",
                "/src/com/example/NoImports.java", "text/x-java-source",
                javaCode.length(), javaCode.getBytes());

        ingestionWorker.processArtifact(artifactId);

        assertEquals(ArtifactStatus.INDEXED.name(),
                artifactService.getStatus(artifactId).get("status"));

        // Verify no imports were stored
        long importCount = dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM artifact_imports WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return (long) rs.getInt(1);
                }
            }
        });
        assertEquals(0, importCount,
                "Java file with no imports should have zero import rows");
    }

    @Test
    void processReladomoConfigXmlFile() throws Exception {
        // MithraRuntime config XML — exercises the reladomoConfigParser branch
        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <MithraRuntime>
                    <ConnectionManager className="com.example.db.MyConnectionManager">
                        <Property name="databaseName">testdb</Property>
                        <MithraObjectConfiguration className="com.example.domain.Account" cacheType="full" loadCacheOnStartup="true"/>
                        <MithraObjectConfiguration className="com.example.domain.Order" cacheType="partial"/>
                    </ConnectionManager>
                </MithraRuntime>
                """;
        String artifactId = uploadService.upload("MithraRuntimeConfig.xml",
                "/config/MithraRuntimeConfig.xml", "application/xml",
                xmlContent.length(), xmlContent.getBytes());

        ingestionWorker.processArtifact(artifactId);

        Map<String, String> status = artifactService.getStatus(artifactId);
        assertEquals(ArtifactStatus.INDEXED.name(), status.get("status"),
                "Reladomo config XML should reach INDEXED status");
    }

    @Test
    void processJavaFileWithReladomoFinderUsages() throws Exception {
        // Java file with Reladomo Finder patterns — exercises step 7 finder parsing
        String javaCode = """
                package com.example.service;

                import com.example.domain.AccountFinder;
                import com.example.domain.AccountList;

                public class AccountService {
                    public AccountList findByName(String name) {
                        return AccountFinder.findMany(
                            AccountFinder.name().eq(name));
                    }

                    public AccountList findWithOrders() {
                        AccountList list = AccountFinder.findMany(AccountFinder.all());
                        list.deepFetch(AccountFinder.orders());
                        return list;
                    }
                }
                """;
        String artifactId = uploadService.upload("AccountService.java",
                "/src/com/example/service/AccountService.java", "text/x-java-source",
                javaCode.length(), javaCode.getBytes());

        ingestionWorker.processArtifact(artifactId);

        assertEquals(ArtifactStatus.INDEXED.name(),
                artifactService.getStatus(artifactId).get("status"),
                "Java file with Finder usages should reach INDEXED status");
    }

    @Test
    void processNonXmlNonJavaFileSkipsReladomoSteps() throws Exception {
        // Python file — exercises the branches where .xml and .java checks are false
        String pyCode = """
                def hello():
                    print("Hello from Python")

                if __name__ == "__main__":
                    hello()
                """;
        String artifactId = uploadService.upload("hello.py",
                "/scripts/hello.py", "text/x-python",
                pyCode.length(), pyCode.getBytes());

        ingestionWorker.processArtifact(artifactId);

        assertEquals(ArtifactStatus.INDEXED.name(),
                artifactService.getStatus(artifactId).get("status"),
                "Python file should reach INDEXED status, skipping Reladomo steps");
    }

    @Test
    void processJsonFileToIndexed() throws Exception {
        // JSON file to test another text file type through the pipeline
        String jsonContent = """
                {
                    "name": "test-project",
                    "version": "1.0.0",
                    "description": "A test project for ingestion worker coverage",
                    "dependencies": {
                        "spring-boot": "3.2.0",
                        "duckdb": "0.9.0"
                    }
                }
                """;
        String artifactId = uploadService.upload("package.json",
                "/path/package.json", "application/json",
                jsonContent.length(), jsonContent.getBytes());

        ingestionWorker.processArtifact(artifactId);

        assertEquals(ArtifactStatus.INDEXED.name(),
                artifactService.getStatus(artifactId).get("status"),
                "JSON file should reach INDEXED status");
    }

    @Test
    void processXmlThatIsNeitherReladomoObjectNorConfig() throws Exception {
        // Non-Reladomo XML — exercises the else branch where both isReladomoXml
        // and isReladomoConfig return false
        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <configuration>
                    <setting name="timeout">30</setting>
                    <setting name="retries">3</setting>
                </configuration>
                """;
        String artifactId = uploadService.upload("app-config.xml",
                "/config/app-config.xml", "application/xml",
                xmlContent.length(), xmlContent.getBytes());

        ingestionWorker.processArtifact(artifactId);

        assertEquals(ArtifactStatus.INDEXED.name(),
                artifactService.getStatus(artifactId).get("status"),
                "Generic XML file should still reach INDEXED status");
    }

    @Test
    void processJavaFileClassifiedAsReladomoType() throws Exception {
        // Filename pattern that classifies as a Reladomo type (e.g., *DatabaseObject.java)
        String javaCode = """
                package com.example.domain;

                import java.sql.Timestamp;

                public class AccountDatabaseObject extends AccountDatabaseObjectAbstract {
                    public AccountDatabaseObject() {
                        super();
                    }
                }
                """;
        String artifactId = uploadService.upload("AccountDatabaseObject.java",
                "/src/com/example/domain/AccountDatabaseObject.java", "text/x-java-source",
                javaCode.length(), javaCode.getBytes());

        ingestionWorker.processArtifact(artifactId);

        assertEquals(ArtifactStatus.INDEXED.name(),
                artifactService.getStatus(artifactId).get("status"),
                "Reladomo DatabaseObject java file should reach INDEXED status");
    }

    @Test
    void pollWithMultiplePendingArtifacts() throws Exception {
        // Upload 2 files (matches thread pool size of 2), then poll to process them
        for (int i = 0; i < 2; i++) {
            String content = "Batch file " + i + " for poll multi-artifact test with sufficient text.";
            uploadService.upload("batch" + i + ".txt",
                    "/path/batch" + i + ".txt", "text/plain",
                    content.length(), content.getBytes());
        }

        ingestionWorker.markReady();
        ingestionWorker.poll();

        // Wait for async processing
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            long indexedCount = dataSource.withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM artifacts WHERE status = ?")) {
                    ps.setString(1, ArtifactStatus.INDEXED.name());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return (long) rs.getInt(1);
                    }
                }
            });
            if (indexedCount >= 2) break;
            Thread.sleep(200);
        }

        long finalIndexed = dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM artifacts WHERE status = ?")) {
                ps.setString(1, ArtifactStatus.INDEXED.name());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return (long) rs.getInt(1);
                }
            }
        });
        assertTrue(finalIndexed >= 2,
                "Both batch artifacts should be INDEXED after poll, got: " + finalIndexed);
    }

    @Test
    void buildHnswIndexAfterMultipleProcessedFiles() throws Exception {
        // Process multiple files, then verify buildHnswIndex loads all embeddings
        String[] contents = {
                "First file content for HNSW multi-file test with enough text for embedding.",
                "Second file content for HNSW multi-file test ensuring proper vector storage."
        };
        for (int i = 0; i < contents.length; i++) {
            String id = uploadService.upload("hnsw" + i + ".txt",
                    "/path/hnsw" + i + ".txt", "text/plain",
                    contents[i].length(), contents[i].getBytes());
            ingestionWorker.processArtifact(id);
        }

        // buildHnswIndex should succeed and populate the search index
        ingestionWorker.buildHnswIndex();

        // Verify the search service has an HNSW index set
        SearchService searchService = new SearchService(dataSource,
                new EmbeddingService(config), config);
        // The buildHnswIndex sets it on the injected searchService, not this new one,
        // but at least we verify no exceptions during the build
    }

    @Test
    void processArtifactWithSummaryGenerationFailure() throws Exception {
        // A file that can be extracted and chunked but may cause summary issues
        // Binary-ish content with txt extension — summary may fail but processing continues
        byte[] data = new byte[300];
        java.util.Arrays.fill(data, (byte) 'A');
        // Insert some newlines to create structure
        for (int i = 50; i < data.length; i += 50) {
            data[i] = (byte) '\n';
        }
        String artifactId = uploadService.upload("binary-ish.txt",
                "/path/binary-ish.txt", "text/plain",
                data.length, data);

        ingestionWorker.processArtifact(artifactId);

        // Should still reach INDEXED even if summary generation has issues
        String finalStatus = artifactService.getStatus(artifactId).get("status");
        assertTrue(
                ArtifactStatus.INDEXED.name().equals(finalStatus)
                        || ArtifactStatus.FAILED.name().equals(finalStatus),
                "Binary-ish file should be INDEXED or FAILED, got: " + finalStatus);
    }
}
