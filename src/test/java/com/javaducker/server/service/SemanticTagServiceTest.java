package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SemanticTagServiceTest {

    @TempDir
    static Path tempDir;

    static DuckDBDataSource dataSource;
    static SemanticTagService service;

    @BeforeAll
    static void setup() throws Exception {
        AppConfig config = new AppConfig();
        config.setDbPath(tempDir.resolve("test-st.duckdb").toString());
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
        service = new SemanticTagService(dataSource);

        // Seed test artifacts
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at)
                VALUES ('tag-art-1', 'UserService.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at)
                VALUES ('tag-art-2', 'AuthController.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at)
                VALUES ('tag-art-3', 'PaymentService.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
        }
    }

    @AfterAll
    static void teardown() throws Exception {
        dataSource.close();
    }

    @Test
    @Order(1)
    void writeTagsForArtifact() throws Exception {
        List<Map<String, Object>> tags = List.of(
                Map.of("tag", "user-management", "category", "functional", "confidence", 0.95),
                Map.of("tag", "spring-service", "category", "architectural", "confidence", 0.9),
                Map.of("tag", "crud-operations", "category", "pattern", "confidence", 0.85),
                Map.of("tag", "authentication", "category", "domain", "confidence", 0.8),
                Map.of("tag", "data-access", "category", "concern", "confidence", 0.75));
        var result = service.writeTags("tag-art-1", tags);
        assertEquals("tag-art-1", result.get("artifact_id"));
        assertEquals(5, result.get("tags_count"));
    }

    @Test
    @Order(2)
    void rejectTooFewTags() {
        List<Map<String, Object>> tags = List.of(
                Map.of("tag", "a", "category", "functional"),
                Map.of("tag", "b", "category", "domain"));
        assertThrows(IllegalArgumentException.class, () -> service.writeTags("tag-art-1", tags));
    }

    @Test
    @Order(3)
    void rejectTooManyTags() {
        List<Map<String, Object>> tags = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tags.add(Map.of("tag", "tag-" + i, "category", "functional"));
        }
        assertThrows(IllegalArgumentException.class, () -> service.writeTags("tag-art-1", tags));
    }

    @Test
    @Order(4)
    void findByTag() throws Exception {
        var results = service.findByTag("user-management");
        assertEquals(1, results.size());
        assertEquals("tag-art-1", results.get(0).get("artifact_id"));
        assertEquals("UserService.java", results.get(0).get("file_name"));
    }

    @Test
    @Order(5)
    void findByCategory() throws Exception {
        var results = service.findByCategory("architectural");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(r -> "spring-service".equals(r.get("tag"))));
    }

    @Test
    @Order(6)
    void searchByTagsAny() throws Exception {
        // Write tags for art-2 to have some overlap
        service.writeTags("tag-art-2", List.of(
                Map.of("tag", "authentication", "category", "domain", "confidence", 0.9),
                Map.of("tag", "spring-controller", "category", "architectural", "confidence", 0.85),
                Map.of("tag", "rest-api", "category", "pattern", "confidence", 0.8),
                Map.of("tag", "security", "category", "concern", "confidence", 0.75)));

        var results = service.searchByTags(List.of("authentication", "crud-operations"), false);
        assertTrue(results.size() >= 2, "Both art-1 and art-2 have 'authentication'");
    }

    @Test
    @Order(7)
    void searchByTagsAll() throws Exception {
        var results = service.searchByTags(List.of("authentication", "crud-operations"), true);
        assertEquals(1, results.size(), "Only art-1 has both tags");
        assertEquals("tag-art-1", results.get(0).get("artifact_id"));
    }

    @Test
    @Order(8)
    void tagCloud() throws Exception {
        var cloud = service.getTagCloud();
        assertNotNull(cloud.get("categories"));
        assertTrue((int) cloud.get("total_tags") >= 5);
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> categories =
                (Map<String, List<Map<String, Object>>>) cloud.get("categories");
        assertTrue(categories.containsKey("domain"));
        assertTrue(categories.containsKey("architectural"));
    }

    @Test
    @Order(9)
    void suggestTags() throws Exception {
        // art-1 and art-2 share "authentication"
        // Suggestions for art-1 should include art-2's unique tags
        var suggestions = service.suggestTags("tag-art-1");
        assertFalse(suggestions.isEmpty());
        var suggestedTagNames = suggestions.stream()
                .map(s -> (String) s.get("tag")).toList();
        // art-2 has "spring-controller", "rest-api", "security" that art-1 doesn't
        assertTrue(suggestedTagNames.contains("spring-controller")
                || suggestedTagNames.contains("rest-api")
                || suggestedTagNames.contains("security"));
    }

    @Test
    @Order(10)
    void overwriteTagsOnRewrite() throws Exception {
        // Write new set of tags for art-1
        List<Map<String, Object>> newTags = List.of(
                Map.of("tag", "new-tag-1", "category", "functional"),
                Map.of("tag", "new-tag-2", "category", "domain"),
                Map.of("tag", "new-tag-3", "category", "architectural"),
                Map.of("tag", "new-tag-4", "category", "pattern"));
        service.writeTags("tag-art-1", newTags);

        // Old tags should be gone
        var oldResults = service.findByTag("user-management");
        assertTrue(oldResults.isEmpty(), "Old tag 'user-management' should be gone");

        // New tags should be present
        var newResults = service.findByTag("new-tag-1");
        assertEquals(1, newResults.size());
        assertEquals("tag-art-1", newResults.get(0).get("artifact_id"));
    }
}
