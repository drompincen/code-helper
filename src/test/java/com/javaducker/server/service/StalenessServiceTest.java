package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StalenessServiceTest {

    // ── Static helper tests (no DB needed) ──────────────────────────────

    @Test
    void computeStaleSummary_zeroStaleOutOfTen() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stale", List.of());
        result.put("total_checked", 10L);

        StalenessService.computeStaleSummary(result);

        assertEquals(0, result.get("stale_count"));
        assertEquals(0.0, result.get("stale_percentage"));
    }

    @Test
    void computeStaleSummary_threeStaleOutOfTen() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stale", List.of("a", "b", "c"));
        result.put("total_checked", 10L);

        StalenessService.computeStaleSummary(result);

        assertEquals(3, result.get("stale_count"));
        assertEquals(30.0, result.get("stale_percentage"));
    }

    @Test
    void computeStaleSummary_zeroTotal_noDivisionByZero() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stale", List.of());
        result.put("total_checked", 0L);

        StalenessService.computeStaleSummary(result);

        assertEquals(0, result.get("stale_count"));
        assertEquals(0.0, result.get("stale_percentage"));
    }

    @Test
    void computeStaleSummary_nullStaleList() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stale", null);
        result.put("total_checked", 5L);

        StalenessService.computeStaleSummary(result);

        assertEquals(0, result.get("stale_count"));
        assertEquals(0.0, result.get("stale_percentage"));
    }

    // ── Integration tests (real DuckDB + temp files) ────────────────────

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class IntegrationTests {

        @TempDir
        static Path tempDir;

        static DuckDBDataSource dataSource;
        static StalenessService service;

        @BeforeAll
        static void setup() throws Exception {
            AppConfig config = new AppConfig();
            config.setDbPath(tempDir.resolve("test-staleness.duckdb").toString());
            config.setIntakeDir(tempDir.resolve("intake").toString());
            dataSource = new DuckDBDataSource(config);
            ArtifactService artifactService = new ArtifactService(dataSource);
            SearchService searchService = new SearchService(dataSource, new EmbeddingService(config), config);
            IngestionWorker worker = new IngestionWorker(dataSource, artifactService,
                    new TextExtractor(), new TextNormalizer(), new Chunker(),
                    new EmbeddingService(config), new FileSummarizer(), new ImportParser(),
                    new ReladomoXmlParser(), new ReladomoService(dataSource),
                    new ReladomoFinderParser(), new ReladomoConfigParser(),
                    searchService, config);
            SchemaBootstrap bootstrap = new SchemaBootstrap(dataSource, config, worker);
            bootstrap.createSchema();
            service = new StalenessService(dataSource);
        }

        @AfterAll
        static void teardown() {
            dataSource.close();
        }

        /** Helper: insert an artifact with a given path and indexed_at timestamp. */
        private static void seedArtifact(String id, String fileName, String clientPath,
                                         String indexedAtSql) throws SQLException {
            Connection conn = dataSource.getConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO artifacts (artifact_id, file_name, original_client_path, "
                        + "status, indexed_at) VALUES ('"
                        + id + "', '" + fileName + "', '" + clientPath + "', 'INDEXED', "
                        + indexedAtSql + ")");
            }
        }

        /** Helper: clear all artifacts between tests to keep them independent. */
        @AfterEach
        void clearArtifacts() throws SQLException {
            Connection conn = dataSource.getConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM artifacts");
            }
        }

        @Test
        void checkStalenessWithCurrentFile() throws Exception {
            Path file = tempDir.resolve("current.java");
            Files.writeString(file, "class Current {}");

            // indexed_at far in the future so the file is considered current
            seedArtifact("art-current", "current.java", file.toString(),
                    "TIMESTAMP '2099-01-01 00:00:00'");

            Map<String, Object> result = service.checkStaleness(List.of(file.toString()));

            assertEquals(1, result.get("current"));
            assertTrue(((List<?>) result.get("stale")).isEmpty());
            assertTrue(((List<?>) result.get("not_indexed")).isEmpty());
        }

        @Test
        void checkStalenessWithStaleFile() throws Exception {
            Path file = tempDir.resolve("stale.java");
            Files.writeString(file, "class Stale {}");

            // indexed_at in the distant past so the file modification time is newer
            seedArtifact("art-stale", "stale.java", file.toString(),
                    "TIMESTAMP '2020-01-01 00:00:00'");

            Map<String, Object> result = service.checkStaleness(List.of(file.toString()));

            List<?> staleList = (List<?>) result.get("stale");
            assertEquals(1, staleList.size());
            assertEquals(0, result.get("current"));
        }

        @Test
        void checkStalenessWithMissingFile() throws Exception {
            String fakePath = "/nonexistent/file.java";
            seedArtifact("art-missing", "file.java", fakePath,
                    "TIMESTAMP '2024-06-01 00:00:00'");

            Map<String, Object> result = service.checkStaleness(List.of(fakePath));

            List<String> notIndexed = castList(result.get("not_indexed"));
            assertTrue(notIndexed.contains(fakePath));
            assertTrue(((List<?>) result.get("stale")).isEmpty());
            assertEquals(0, result.get("current"));
        }

        @Test
        void checkStalenessWithMultipleFiles() throws Exception {
            // 1. current file
            Path currentFile = tempDir.resolve("multi-current.java");
            Files.writeString(currentFile, "class A {}");
            seedArtifact("art-m1", "multi-current.java", currentFile.toString(),
                    "TIMESTAMP '2099-01-01 00:00:00'");

            // 2. stale file
            Path staleFile = tempDir.resolve("multi-stale.java");
            Files.writeString(staleFile, "class B {}");
            seedArtifact("art-m2", "multi-stale.java", staleFile.toString(),
                    "TIMESTAMP '2020-01-01 00:00:00'");

            // 3. missing file
            String missingPath = "/does/not/exist.java";
            seedArtifact("art-m3", "exist.java", missingPath,
                    "TIMESTAMP '2024-06-01 00:00:00'");

            List<String> paths = List.of(
                    currentFile.toString(), staleFile.toString(), missingPath);

            Map<String, Object> result = service.checkStaleness(paths);

            assertEquals(1, result.get("current"));
            assertEquals(1, ((List<?>) result.get("stale")).size());
            assertEquals(1, ((List<?>) result.get("not_indexed")).size());
            assertEquals(3L, result.get("total_checked"));
        }

        @Test
        void checkAllReturnsEnrichedSummary() throws Exception {
            // 1. current file
            Path currentFile = tempDir.resolve("all-current.java");
            Files.writeString(currentFile, "class X {}");
            seedArtifact("art-a1", "all-current.java", currentFile.toString(),
                    "TIMESTAMP '2099-01-01 00:00:00'");

            // 2. stale file
            Path staleFile = tempDir.resolve("all-stale.java");
            Files.writeString(staleFile, "class Y {}");
            seedArtifact("art-a2", "all-stale.java", staleFile.toString(),
                    "TIMESTAMP '2020-01-01 00:00:00'");

            Map<String, Object> result = service.checkAll();

            assertEquals(2L, result.get("total_checked"));
            assertEquals(1, result.get("stale_count"));
            assertEquals(50.0, result.get("stale_percentage"));
        }

        @Test
        void checkAllEmptyIndex() throws Exception {
            // DB is empty (clearArtifacts runs after each, and this is a fresh test)
            Map<String, Object> result = service.checkAll();

            assertEquals(0L, result.get("total_checked"));
            assertEquals(0, result.get("stale_count"));
            assertEquals(0.0, result.get("stale_percentage"));
        }

        @Test
        void checkStalenessBlankPath() throws Exception {
            List<String> paths = new ArrayList<>();
            paths.add("");
            paths.add(null);
            paths.add("   ");

            Map<String, Object> result = service.checkStaleness(paths);

            assertEquals(0, result.get("current"));
            assertTrue(((List<?>) result.get("stale")).isEmpty());
            assertTrue(((List<?>) result.get("not_indexed")).isEmpty());
            assertEquals(0L, result.get("total_checked"));
        }

        @SuppressWarnings("unchecked")
        private static <T> List<T> castList(Object obj) {
            return (List<T>) obj;
        }
    }
}
