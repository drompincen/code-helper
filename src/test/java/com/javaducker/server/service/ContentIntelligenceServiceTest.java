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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContentIntelligenceServiceTest {

    @TempDir
    static Path tempDir;

    static DuckDBDataSource dataSource;
    static ContentIntelligenceService service;

    @BeforeAll
    static void setup() throws Exception {
        AppConfig config = new AppConfig();
        config.setDbPath(tempDir.resolve("test-ci.duckdb").toString());
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
        service = new ContentIntelligenceService(dataSource);

        // Seed two artifacts
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at)
                VALUES ('art-1', 'plan-v1.md', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            stmt.execute("""
                INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at)
                VALUES ('art-2', 'plan-v2.md', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            stmt.execute("""
                INSERT INTO artifact_text (artifact_id, extracted_text, text_length, extraction_method)
                VALUES ('art-1', 'Old plan for auth rewrite', 25, 'test')
            """);
            stmt.execute("""
                INSERT INTO artifact_chunks (chunk_id, artifact_id, chunk_index, chunk_text, char_start, char_end)
                VALUES ('art-1-0', 'art-1', 0, 'Old plan for auth rewrite', 0, 25)
            """);
        }
    }

    @AfterAll
    static void teardown() throws Exception {
        dataSource.close();
    }

    @Test
    @Order(1)
    void classifyArtifact() throws Exception {
        var result = service.classify("art-1", "PLAN", 0.95, "llm");
        assertEquals("art-1", result.get("artifact_id"));
        assertEquals("PLAN", result.get("doc_type"));
    }

    @Test
    @Order(2)
    void tagArtifact() throws Exception {
        var result = service.tag("art-1", List.of(
                Map.of("tag", "auth", "tag_type", "topic"),
                Map.of("tag", "rewrite", "tag_type", "topic")));
        assertEquals(2, result.get("tags_count"));
    }

    @Test
    @Order(3)
    void extractSalientPoints() throws Exception {
        var result = service.extractPoints("art-1", List.of(
                Map.of("point_type", "DECISION", "point_text", "Use OAuth2 for auth"),
                Map.of("point_type", "RISK", "point_text", "Migration may break existing sessions")));
        assertEquals(2, result.get("points_count"));
    }

    @Test
    @Order(4)
    void saveConcepts() throws Exception {
        var result = service.saveConcepts("art-1", List.of(
                Map.of("concept", "AuthMiddleware", "concept_type", "system", "mention_count", 3),
                Map.of("concept", "OAuth2", "concept_type", "term", "mention_count", 2)));
        assertEquals(2, result.get("concepts_count"));

        // Also add a concept to art-2 to test cross-document linking
        service.saveConcepts("art-2", List.of(
                Map.of("concept", "AuthMiddleware", "concept_type", "system", "mention_count", 5)));
    }

    @Test
    @Order(5)
    void findByType() throws Exception {
        service.classify("art-2", "PLAN", 0.9, "llm");
        var results = service.findByType("PLAN");
        assertEquals(2, results.size());
    }

    @Test
    @Order(6)
    void findByTag() throws Exception {
        var results = service.findByTag("auth");
        assertEquals(1, results.size());
        assertEquals("art-1", results.get(0).get("artifact_id"));
    }

    @Test
    @Order(7)
    void findPoints() throws Exception {
        var results = service.findPoints("DECISION", null);
        assertEquals(1, results.size());
        assertEquals("Use OAuth2 for auth", results.get(0).get("point_text"));
    }

    @Test
    @Order(8)
    void listConcepts() throws Exception {
        var results = service.listConcepts();
        assertTrue(results.size() >= 2);
        var authConcept = results.stream()
                .filter(c -> "AuthMiddleware".equals(c.get("concept"))).findFirst();
        assertTrue(authConcept.isPresent());
        assertEquals(2, authConcept.get().get("doc_count"));
    }

    @Test
    @Order(9)
    void relatedByConcept() throws Exception {
        var results = service.getRelatedByConcept("art-1");
        assertFalse(results.isEmpty());
        assertEquals("art-2", results.get(0).get("artifact_id"));
    }

    @Test
    @Order(10)
    void conceptTimeline() throws Exception {
        var result = service.getConceptTimeline("AuthMiddleware");
        assertEquals("AuthMiddleware", result.get("concept"));
        assertEquals(2, result.get("total_docs"));
    }

    @Test
    @Order(11)
    void setFreshnessSuperseded() throws Exception {
        var result = service.setFreshness("art-1", "superseded", "art-2");
        assertNotNull(result);
        assertEquals("superseded", result.get("freshness"));
    }

    @Test
    @Order(12)
    void staleContentShowsSuperseded() throws Exception {
        var results = service.getStaleContent();
        assertEquals(1, results.size());
        assertEquals("art-1", results.get(0).get("artifact_id"));
    }

    @Test
    @Order(13)
    void synthesizeSupersededArtifact() throws Exception {
        var result = service.synthesize("art-1",
                "Old auth plan, superseded by v2",
                "auth,rewrite", "OAuth2 decision", "Superseded by plan-v2",
                "/path/to/plan-v1.md");
        assertNotNull(result);
        assertTrue((Boolean) result.get("synthesized"));

        // Verify text and chunks are pruned
        Connection conn = dataSource.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM artifact_text WHERE artifact_id = 'art-1'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getLong(1), "Text should be pruned");
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM artifact_chunks WHERE artifact_id = 'art-1'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getLong(1), "Chunks should be pruned");
            }
        }
    }

    @Test
    @Order(14)
    void getSynthesisRecord() throws Exception {
        var result = service.getSynthesis("art-1");
        assertNotNull(result);
        assertEquals("Old auth plan, superseded by v2", result.get("summary_text"));
        assertEquals("/path/to/plan-v1.md", result.get("original_file_path"));
    }

    @Test
    @Order(15)
    void synthesizeCurrentArtifactBlocked() throws Exception {
        var result = service.synthesize("art-2",
                "Should not work", "", "", "", "");
        assertNotNull(result);
        assertTrue(result.containsKey("error"), "Should reject synthesizing current artifact");
    }

    @Test
    @Order(16)
    void enrichQueue() throws Exception {
        var queue = service.getEnrichQueue(50);
        // art-1 and art-2 both have enrichment_status='pending' by default
        assertTrue(queue.size() >= 1);
    }

    @Test
    @Order(17)
    void markEnriched() throws Exception {
        var result = service.markEnriched("art-2");
        assertNotNull(result);
        assertEquals("enriched", result.get("enrichment_status"));

        // Should no longer appear in queue
        var queue = service.getEnrichQueue(50);
        boolean found = queue.stream().anyMatch(q -> "art-2".equals(q.get("artifact_id")));
        assertFalse(found);
    }

    @Test
    @Order(18)
    void conceptHealth() throws Exception {
        var result = service.getConceptHealth();
        assertNotNull(result);
        assertTrue((int) result.get("total") >= 1);
    }

    @Test
    @Order(19)
    void searchSynthesis() throws Exception {
        var results = service.searchSynthesis("auth");
        assertFalse(results.isEmpty());
        assertEquals("art-1", results.get(0).get("artifact_id"));
    }

    @Test
    @Order(20)
    void linkConcepts() throws Exception {
        var result = service.linkConcepts(List.of(
                Map.of("concept", "AuthMiddleware", "artifact_a", "art-1",
                        "artifact_b", "art-2", "strength", 0.85)));
        assertEquals(1, result.get("links_created"));
    }

    @Test
    @Order(21)
    void getLatest() throws Exception {
        // art-2 is current, art-1 is superseded — latest for "AuthMiddleware" should be art-2
        service.tag("art-2", List.of(Map.of("tag", "auth", "tag_type", "topic")));
        var result = service.getLatest("AuthMiddleware");
        assertTrue((Boolean) result.get("found"));
        assertEquals("art-2", result.get("artifact_id"));
    }
}
