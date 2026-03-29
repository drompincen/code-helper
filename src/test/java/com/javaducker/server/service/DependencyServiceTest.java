package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyServiceTest {

    @TempDir
    static Path tempDir;

    static DuckDBDataSource dataSource;
    static DependencyService service;

    @BeforeAll
    static void setup() throws Exception {
        AppConfig config = new AppConfig();
        config.setDbPath(tempDir.resolve("test-dep.duckdb").toString());
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
        service = new DependencyService(dataSource);

        // Seed artifacts
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at)
                VALUES ('art-a', 'Foo.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at)
                VALUES ('art-b', 'Bar.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at)
                VALUES ('art-c', 'Baz.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);

            // art-a imports 3 things: two resolved to art-b, one unresolved
            stmt.execute("""
                INSERT INTO artifact_imports (artifact_id, import_statement, resolved_artifact_id)
                VALUES ('art-a', 'com.example.Bar', 'art-b')
            """);
            stmt.execute("""
                INSERT INTO artifact_imports (artifact_id, import_statement, resolved_artifact_id)
                VALUES ('art-a', 'com.example.Baz', 'art-c')
            """);
            stmt.execute("""
                INSERT INTO artifact_imports (artifact_id, import_statement, resolved_artifact_id)
                VALUES ('art-a', 'com.external.Lib', NULL)
            """);

            // art-b imports art-c (so art-c has dependents: art-a and art-b)
            stmt.execute("""
                INSERT INTO artifact_imports (artifact_id, import_statement, resolved_artifact_id)
                VALUES ('art-b', 'com.example.Baz', 'art-c')
            """);
        }
    }

    @AfterAll
    static void teardown() throws Exception {
        dataSource.close();
    }

    @Test
    void getDependenciesReturnsImports() throws Exception {
        List<java.util.Map<String, String>> results = service.getDependencies("art-a");
        assertEquals(3, results.size());
        assertTrue(results.stream().anyMatch(r -> "com.example.Bar".equals(r.get("import_statement"))));
        assertTrue(results.stream().anyMatch(r -> "com.example.Baz".equals(r.get("import_statement"))));
        assertTrue(results.stream().anyMatch(r -> "com.external.Lib".equals(r.get("import_statement"))));
        // Verify artifact_id field is set correctly on each row
        results.forEach(r -> assertEquals("art-a", r.get("artifact_id")));
    }

    @Test
    void getDependenciesEmptyForUnknown() throws Exception {
        List<java.util.Map<String, String>> results = service.getDependencies("non-existent-id");
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void getDependentsReturnsImporters() throws Exception {
        // art-c is imported by art-a and art-b
        List<java.util.Map<String, String>> results = service.getDependents("art-c");
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> "art-a".equals(r.get("artifact_id"))));
        assertTrue(results.stream().anyMatch(r -> "art-b".equals(r.get("artifact_id"))));
        // Verify file_name is populated from the JOIN
        assertTrue(results.stream().anyMatch(r -> "Foo.java".equals(r.get("file_name"))));
        assertTrue(results.stream().anyMatch(r -> "Bar.java".equals(r.get("file_name"))));
    }

    @Test
    void getDependentsEmptyWhenNoDependents() throws Exception {
        // art-a is not imported by anyone (no resolved_artifact_id points to art-a)
        List<java.util.Map<String, String>> results = service.getDependents("art-a");
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void getDependenciesWithResolvedArtifact() throws Exception {
        List<java.util.Map<String, String>> results = service.getDependencies("art-a");
        var resolved = results.stream()
                .filter(r -> "com.example.Bar".equals(r.get("import_statement")))
                .findFirst()
                .orElseThrow();
        assertEquals("art-b", resolved.get("resolved_artifact_id"));
    }

    @Test
    void getDependenciesWithUnresolvedImport() throws Exception {
        List<java.util.Map<String, String>> results = service.getDependencies("art-a");
        var unresolved = results.stream()
                .filter(r -> "com.external.Lib".equals(r.get("import_statement")))
                .findFirst()
                .orElseThrow();
        assertNull(unresolved.get("resolved_artifact_id"));
    }
}
