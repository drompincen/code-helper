package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProjectMapServiceTest {

    @TempDir
    Path tempDir;

    DuckDBDataSource dataSource;
    ProjectMapService service;

    @BeforeEach
    void setup() throws Exception {
        AppConfig config = new AppConfig();
        config.setDbPath(tempDir.resolve("test-projectmap.duckdb").toString());
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
        service = new ProjectMapService(dataSource);
    }

    @AfterEach
    void teardown() throws Exception {
        dataSource.close();
    }

    private void seedArtifact(String id, String fileName, String path, long sizeBytes, String indexedAt)
            throws SQLException {
        Connection conn = dataSource.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO artifacts (artifact_id, file_name, original_client_path, size_bytes, "
                        + "status, indexed_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'INDEXED', ?::TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            ps.setString(1, id);
            ps.setString(2, fileName);
            ps.setString(3, path);
            ps.setLong(4, sizeBytes);
            ps.setString(5, indexedAt);
            ps.executeUpdate();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void getProjectMapWithArtifacts() throws Exception {
        seedArtifact("a1", "Foo.java", "src/main/Foo.java", 5000, "2026-03-28 10:00:00");
        seedArtifact("a2", "Bar.java", "src/main/Bar.java", 3000, "2026-03-28 09:00:00");
        seedArtifact("a3", "Baz.java", "src/test/Baz.java", 2000, "2026-03-27 08:00:00");
        seedArtifact("a4", "Qux.java", "src/test/Qux.java", 1000, "2026-03-26 07:00:00");
        seedArtifact("a5", "App.java", "src/main/App.java", 4000, "2026-03-28 11:00:00");

        Map<String, Object> result = service.getProjectMap();

        assertEquals(5L, result.get("total_files"));
        assertEquals(15000L, result.get("total_bytes"));

        List<Map<String, Object>> dirs = (List<Map<String, Object>>) result.get("directories");
        assertFalse(dirs.isEmpty());

        List<Map<String, Object>> largest = (List<Map<String, Object>>) result.get("largest_files");
        assertEquals(5, largest.size());
        // Largest first
        assertEquals("Foo.java", largest.get(0).get("file_name"));

        List<Map<String, Object>> recent = (List<Map<String, Object>>) result.get("recently_indexed");
        assertFalse(recent.isEmpty());
        assertTrue(recent.size() <= 5);
        // Most recent first
        assertEquals("App.java", recent.get(0).get("file_name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getProjectMapEmpty() throws Exception {
        Map<String, Object> result = service.getProjectMap();

        assertEquals(0L, result.get("total_files"));
        assertEquals(0L, result.get("total_bytes"));
        assertTrue(((List<?>) result.get("directories")).isEmpty());
        assertTrue(((List<?>) result.get("largest_files")).isEmpty());
        assertTrue(((List<?>) result.get("recently_indexed")).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getProjectMapGroupsByDirectory() throws Exception {
        seedArtifact("a1", "Foo.java", "src/main/Foo.java", 100, "2026-03-28 10:00:00");
        seedArtifact("a2", "Bar.java", "src/main/Bar.java", 200, "2026-03-28 09:00:00");
        seedArtifact("a3", "FooTest.java", "src/test/FooTest.java", 150, "2026-03-27 08:00:00");

        Map<String, Object> result = service.getProjectMap();

        List<Map<String, Object>> dirs = (List<Map<String, Object>>) result.get("directories");
        assertEquals(2, dirs.size());

        // src/main has 2 files, src/test has 1 — sorted by file_count desc
        assertEquals("src/main", dirs.get(0).get("path"));
        assertEquals(2, dirs.get(0).get("file_count"));
        assertEquals("src/test", dirs.get(1).get("path"));
        assertEquals(1, dirs.get(1).get("file_count"));
    }
}
