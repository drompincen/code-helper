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

class ExplainServiceTest {

    // ── DB-backed integration tests ──────────────────────────────────────

    @TempDir
    static Path tempDir;

    static DuckDBDataSource dataSource;
    static ExplainService explainService;

    @BeforeAll
    static void setup() throws Exception {
        AppConfig config = new AppConfig();
        config.setDbPath(tempDir.resolve("test-explain.duckdb").toString());
        config.setIntakeDir(tempDir.resolve("intake").toString());
        dataSource = new DuckDBDataSource(config);
        ArtifactService artifactService = new ArtifactService(dataSource);
        DependencyService dependencyService = new DependencyService(dataSource);
        ContentIntelligenceService ciService = new ContentIntelligenceService(dataSource);
        SearchService searchService = new SearchService(dataSource, new EmbeddingService(config), config);
        IngestionWorker worker = new IngestionWorker(dataSource, artifactService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                new EmbeddingService(config), new FileSummarizer(), new ImportParser(),
                new ReladomoXmlParser(), new ReladomoService(dataSource),
                new ReladomoFinderParser(), new ReladomoConfigParser(),
                searchService, config);
        SchemaBootstrap bootstrap = new SchemaBootstrap(dataSource, config, worker);
        bootstrap.createSchema();

        SemanticTagService semanticTagService = new SemanticTagService(dataSource);
        KnowledgeGraphService knowledgeGraphService = new KnowledgeGraphService(dataSource, new EmbeddingService(config));
        explainService = new ExplainService(artifactService, dependencyService,
                ciService, dataSource, semanticTagService, knowledgeGraphService, null, null);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void explainWithFullData() throws Exception {
        String id = UUID.randomUUID().toString();
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at, indexed_at) " +
                    "VALUES ('" + id + "', 'FullData.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO artifact_summaries (artifact_id, summary_text, class_names, method_names, import_count, line_count) " +
                    "VALUES ('" + id + "', 'A fully documented service', 'FullData', 'doStuff', 3, 100)");
            stmt.execute("INSERT INTO artifact_imports (artifact_id, import_statement, resolved_artifact_id) " +
                    "VALUES ('" + id + "', 'import java.util.List', NULL)");
            stmt.execute("INSERT INTO artifact_classifications (artifact_id, doc_type, confidence, method, classified_at) " +
                    "VALUES ('" + id + "', 'SOURCE_CODE', 0.95, 'llm', CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO artifact_tags (artifact_id, tag, tag_type, source) " +
                    "VALUES ('" + id + "', 'java', 'language', 'auto')");
            stmt.execute("INSERT INTO artifact_salient_points (point_id, artifact_id, point_type, point_text, source) " +
                    "VALUES ('" + UUID.randomUUID() + "', '" + id + "', 'DECISION', 'Use dependency injection', 'llm')");
        }

        Map<String, Object> result = explainService.explain(id);

        assertNotNull(result);
        assertTrue(result.containsKey("file"));
        assertTrue(result.containsKey("summary"));
        assertTrue(result.containsKey("dependencies"));
        assertTrue(result.containsKey("classification"));
        assertTrue(result.containsKey("tags"));
        assertTrue(result.containsKey("salient_points"));
    }

    @Test
    void explainWithMinimalData() throws Exception {
        String id = UUID.randomUUID().toString();
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at) " +
                    "VALUES ('" + id + "', 'Minimal.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        Map<String, Object> result = explainService.explain(id);

        assertNotNull(result);
        assertTrue(result.containsKey("file"));
        // No summary, classification, tags, or salient_points seeded
        assertFalse(result.containsKey("summary"));
        assertFalse(result.containsKey("classification"));
        assertFalse(result.containsKey("tags"));
        assertFalse(result.containsKey("salient_points"));
    }

    @Test
    void explainUnknownArtifact() throws Exception {
        Map<String, Object> result = explainService.explain("nonexistent-" + UUID.randomUUID());
        assertNull(result);
    }

    @Test
    void explainByPathFound() throws Exception {
        String id = UUID.randomUUID().toString();
        String path = "/tmp/test/" + id + "/Foo.java";
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO artifacts (artifact_id, file_name, original_client_path, status, created_at, updated_at, indexed_at) " +
                    "VALUES ('" + id + "', 'Foo.java', '" + path + "', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        Map<String, Object> result = explainService.explainByPath(path);

        assertNotNull(result);
        assertTrue(result.containsKey("file"));
        @SuppressWarnings("unchecked")
        Map<String, String> file = (Map<String, String>) result.get("file");
        assertEquals(id, file.get("artifact_id"));
    }

    @Test
    void explainByPathNotFound() throws Exception {
        Map<String, Object> result = explainService.explainByPath("/nonexistent/" + UUID.randomUUID() + ".java");

        assertNotNull(result);
        assertEquals(false, result.get("indexed"));
        assertTrue(result.containsKey("file_path"));
    }

    @Test
    void explainWithDependentsAndRelated() throws Exception {
        String idA = UUID.randomUUID().toString();
        String idB = UUID.randomUUID().toString();
        String sharedConcept = "SharedConcept-" + UUID.randomUUID().toString().substring(0, 8);

        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            // Create two artifacts
            stmt.execute("INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at, indexed_at) " +
                    "VALUES ('" + idA + "', 'ArtifactA.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at, indexed_at) " +
                    "VALUES ('" + idB + "', 'ArtifactB.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

            // B imports A (so A has B as a dependent)
            stmt.execute("INSERT INTO artifact_imports (artifact_id, import_statement, resolved_artifact_id) " +
                    "VALUES ('" + idB + "', 'import com.example.ArtifactA', '" + idA + "')");

            // Both share a concept (for related_artifacts)
            stmt.execute("INSERT INTO artifact_concepts (concept_id, artifact_id, concept, concept_type, mention_count) " +
                    "VALUES ('" + UUID.randomUUID() + "', '" + idA + "', '" + sharedConcept + "', 'class', 2)");
            stmt.execute("INSERT INTO artifact_concepts (concept_id, artifact_id, concept, concept_type, mention_count) " +
                    "VALUES ('" + UUID.randomUUID() + "', '" + idB + "', '" + sharedConcept + "', 'class', 3)");
        }

        Map<String, Object> result = explainService.explain(idA);

        assertNotNull(result);
        assertTrue(result.containsKey("file"));

        // Verify dependents section: B depends on A
        assertTrue(result.containsKey("dependents"), "dependents section should be present");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> dependents = (List<Map<String, String>>) result.get("dependents");
        assertFalse(dependents.isEmpty());
        boolean foundDependent = dependents.stream()
                .anyMatch(d -> idB.equals(d.get("artifact_id")));
        assertTrue(foundDependent, "ArtifactB should appear as a dependent of ArtifactA");

        // Verify related_artifacts section: linked via shared concept
        assertTrue(result.containsKey("related_artifacts"), "related_artifacts section should be present");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> related = (List<Map<String, Object>>) result.get("related_artifacts");
        assertFalse(related.isEmpty());
        boolean foundRelated = related.stream()
                .anyMatch(r -> idB.equals(r.get("artifact_id")));
        assertTrue(foundRelated, "ArtifactB should appear as related to ArtifactA via shared concept");
    }

    // ── Static helper tests (existing) ───────────────────────────────────

    @Test
    void limitList_truncatesLongList() {
        List<String> input = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
        List<String> result = ExplainService.limitList(input, 5);
        assertEquals(5, result.size());
        assertEquals(List.of("a", "b", "c", "d", "e"), result);
    }

    @Test
    void limitList_nullReturnsEmptyList() {
        List<String> result = ExplainService.limitList(null, 5);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void limitList_shortListReturnedAsIs() {
        List<String> input = List.of("a", "b", "c");
        List<String> result = ExplainService.limitList(input, 5);
        assertEquals(3, result.size());
        assertEquals(List.of("a", "b", "c"), result);
    }

    @Test
    void limitList_exactSizeReturnedAsIs() {
        List<String> input = List.of("a", "b", "c", "d", "e");
        List<String> result = ExplainService.limitList(input, 5);
        assertEquals(5, result.size());
    }

    @Test
    void limitList_emptyListReturnsEmpty() {
        List<String> result = ExplainService.limitList(Collections.emptyList(), 5);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void buildExplainResult_nullSectionsOmitted() {
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("file", Map.of("artifact_id", "art-1", "file_name", "Foo.java"));
        sections.put("summary", null);
        sections.put("dependencies", List.of("dep1", "dep2"));
        sections.put("classification", null);
        sections.put("tags", List.of("spring", "service"));

        Map<String, Object> result = ExplainService.buildExplainResult(sections);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertTrue(result.containsKey("file"));
        assertTrue(result.containsKey("dependencies"));
        assertTrue(result.containsKey("tags"));
        assertFalse(result.containsKey("summary"));
        assertFalse(result.containsKey("classification"));
    }

    @Test
    void buildExplainResult_allSectionsPresent() {
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("file", Map.of("artifact_id", "art-1"));
        sections.put("summary", Map.of("summary_text", "A service class"));
        sections.put("dependencies", List.of("dep1"));

        Map<String, Object> result = ExplainService.buildExplainResult(sections);

        assertNotNull(result);
        assertEquals(3, result.size());
    }

    @Test
    void buildExplainResult_nullInputReturnsNull() {
        assertNull(ExplainService.buildExplainResult(null));
    }

    @Test
    void buildExplainResult_allSectionsNullReturnsEmptyMap() {
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("file", null);
        sections.put("summary", null);

        Map<String, Object> result = ExplainService.buildExplainResult(sections);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
