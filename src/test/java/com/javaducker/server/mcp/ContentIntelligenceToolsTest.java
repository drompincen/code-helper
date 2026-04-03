package com.javaducker.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaducker.server.service.ContentIntelligenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContentIntelligenceToolsTest {

    @Mock
    private ContentIntelligenceService service;

    private ObjectMapper objectMapper;
    private ContentIntelligenceTools tools;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tools = new ContentIntelligenceTools(service, objectMapper);
    }

    // ── classify ───────────────────────────────────────────────────────────────

    @Test
    void classifyDelegatesAllParams() throws Exception {
        when(service.classify("a1", "code", 0.9, "rule"))
                .thenReturn(Map.of("artifact_id", "a1", "doc_type", "code"));

        Map<String, Object> result = tools.classify("a1", "code", 0.9, "rule");

        assertEquals("a1", result.get("artifact_id"));
        verify(service).classify("a1", "code", 0.9, "rule");
    }

    @Test
    void classifyUsesDefaults() throws Exception {
        when(service.classify("a1", "doc", 1.0, "llm"))
                .thenReturn(Map.of("artifact_id", "a1"));

        tools.classify("a1", "doc", null, null);

        verify(service).classify("a1", "doc", 1.0, "llm");
    }

    @Test
    void classifyReturnsErrorOnException() throws Exception {
        when(service.classify(anyString(), anyString(), anyDouble(), anyString()))
                .thenThrow(new SQLException("db down"));

        Map<String, Object> result = tools.classify("a1", "code", 1.0, "llm");

        assertEquals("db down", result.get("error"));
    }

    // ── tag ────────────────────────────────────────────────────────────────────

    @Test
    void tagParsesJsonAndDelegates() throws Exception {
        String json = "[{\"tag\":\"java\",\"tag_type\":\"lang\",\"source\":\"llm\"}]";
        when(service.tag(eq("a1"), anyList()))
                .thenReturn(Map.of("artifact_id", "a1", "tags_added", 1));

        Map<String, Object> result = tools.tag("a1", json);

        assertEquals("a1", result.get("artifact_id"));
        verify(service).tag(eq("a1"), argThat(list -> list.size() == 1 && "java".equals(list.get(0).get("tag"))));
    }

    @Test
    void tagReturnsMalformedJsonError() {
        Map<String, Object> result = tools.tag("a1", "not json");

        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().startsWith("Invalid JSON:"));
    }

    // ── extractPoints ──────────────────────────────────────────────────────────

    @Test
    void extractPointsParsesJsonAndDelegates() throws Exception {
        String json = "[{\"point_type\":\"decision\",\"point_text\":\"Use DuckDB\"}]";
        when(service.extractPoints(eq("a1"), anyList()))
                .thenReturn(Map.of("artifact_id", "a1", "points_added", 1));

        Map<String, Object> result = tools.extractPoints("a1", json);

        assertEquals("a1", result.get("artifact_id"));
        verify(service).extractPoints(eq("a1"), argThat(list ->
                list.size() == 1 && "decision".equals(list.get(0).get("point_type"))));
    }

    @Test
    void extractPointsReturnsMalformedJsonError() {
        Map<String, Object> result = tools.extractPoints("a1", "{bad}");

        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().startsWith("Invalid JSON:"));
    }

    // ── setFreshness ───────────────────────────────────────────────────────────

    @Test
    void setFreshnessDelegatesWithSupersededBy() throws Exception {
        when(service.setFreshness("a1", "superseded", "a2"))
                .thenReturn(Map.of("artifact_id", "a1", "freshness", "superseded"));

        Map<String, Object> result = tools.setFreshness("a1", "superseded", "a2");

        assertEquals("superseded", result.get("freshness"));
        verify(service).setFreshness("a1", "superseded", "a2");
    }

    @Test
    void setFreshnessDelegatesWithoutSupersededBy() throws Exception {
        when(service.setFreshness("a1", "current", null))
                .thenReturn(Map.of("artifact_id", "a1", "freshness", "current"));

        Map<String, Object> result = tools.setFreshness("a1", "current", null);

        assertEquals("current", result.get("freshness"));
        verify(service).setFreshness("a1", "current", null);
    }

    // ── synthesize ─────────────────────────────────────────────────────────────

    @Test
    void synthesizeDelegatesAllFields() throws Exception {
        when(service.synthesize("a1", "summary", "t1,t2", "kp", "ok", "/path"))
                .thenReturn(Map.of("artifact_id", "a1"));

        Map<String, Object> result = tools.synthesize("a1", "summary", "t1,t2", "kp", "ok", "/path");

        assertEquals("a1", result.get("artifact_id"));
        verify(service).synthesize("a1", "summary", "t1,t2", "kp", "ok", "/path");
    }

    @Test
    void synthesizeDelegatesWithNullOptionals() throws Exception {
        when(service.synthesize("a1", "summary", null, null, null, null))
                .thenReturn(Map.of("artifact_id", "a1"));

        tools.synthesize("a1", "summary", null, null, null, null);

        verify(service).synthesize("a1", "summary", null, null, null, null);
    }

    // ── linkConcepts ───────────────────────────────────────────────────────────

    @Test
    void linkConceptsParsesJsonAndDelegates() throws Exception {
        String json = "[{\"concept\":\"auth\",\"artifact_a\":\"a1\",\"artifact_b\":\"a2\",\"strength\":0.8}]";
        when(service.linkConcepts(anyList()))
                .thenReturn(Map.of("links_created", 1));

        Map<String, Object> result = tools.linkConcepts(json);

        assertEquals(1, result.get("links_created"));
        verify(service).linkConcepts(argThat(list -> list.size() == 1 && "auth".equals(list.get(0).get("concept"))));
    }

    @Test
    void linkConceptsReturnsMalformedJsonError() {
        Map<String, Object> result = tools.linkConcepts("[bad]");

        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().startsWith("Invalid JSON:"));
    }

    // ── enrichQueue ────────────────────────────────────────────────────────────

    @Test
    void enrichQueuePassesLimitWithDefault() throws Exception {
        when(service.getEnrichQueue(50))
                .thenReturn(List.of(Map.of("artifact_id", "a1")));

        Map<String, Object> result = tools.enrichQueue(null);

        assertEquals(1, result.get("count"));
        verify(service).getEnrichQueue(50);
    }

    @Test
    void enrichQueuePassesExplicitLimit() throws Exception {
        when(service.getEnrichQueue(10))
                .thenReturn(List.of());

        Map<String, Object> result = tools.enrichQueue(10);

        assertEquals(0, result.get("count"));
        verify(service).getEnrichQueue(10);
    }

    // ── markEnriched ───────────────────────────────────────────────────────────

    @Test
    void markEnrichedDelegatesArtifactId() throws Exception {
        when(service.markEnriched("a1"))
                .thenReturn(Map.of("artifact_id", "a1", "status", "enriched"));

        Map<String, Object> result = tools.markEnriched("a1");

        assertEquals("enriched", result.get("status"));
        verify(service).markEnriched("a1");
    }

    // ── latest ─────────────────────────────────────────────────────────────────

    @Test
    void latestDelegatesTopic() throws Exception {
        when(service.getLatest("auth"))
                .thenReturn(Map.of("artifact_id", "a1", "topic", "auth"));

        Map<String, Object> result = tools.latest("auth");

        assertEquals("auth", result.get("topic"));
        verify(service).getLatest("auth");
    }

    // ── findByType ─────────────────────────────────────────────────────────────

    @Test
    void findByTypeDelegatesAndWraps() throws Exception {
        when(service.findByType("code"))
                .thenReturn(List.of(Map.of("artifact_id", "a1"), Map.of("artifact_id", "a2")));

        Map<String, Object> result = tools.findByType("code");

        assertEquals(2, result.get("count"));
        assertInstanceOf(List.class, result.get("results"));
        verify(service).findByType("code");
    }

    // ── findByTag ──────────────────────────────────────────────────────────────

    @Test
    void findByTagDelegatesAndWraps() throws Exception {
        when(service.findByTag("java"))
                .thenReturn(List.of(Map.of("artifact_id", "a1")));

        Map<String, Object> result = tools.findByTag("java");

        assertEquals(1, result.get("count"));
        verify(service).findByTag("java");
    }

    // ── findPoints ─────────────────────────────────────────────────────────────

    @Test
    void findPointsDelegatesWithOptionalTag() throws Exception {
        when(service.findPoints("decision", "auth"))
                .thenReturn(List.of(Map.of("point_text", "use JWT")));

        Map<String, Object> result = tools.findPoints("decision", "auth");

        assertEquals(1, result.get("count"));
        verify(service).findPoints("decision", "auth");
    }

    @Test
    void findPointsDelegatesWithNullTag() throws Exception {
        when(service.findPoints("decision", null))
                .thenReturn(List.of());

        Map<String, Object> result = tools.findPoints("decision", null);

        assertEquals(0, result.get("count"));
        verify(service).findPoints("decision", null);
    }

    // ── synthesis ──────────────────────────────────────────────────────────────

    @Test
    void synthesisRoutesByArtifactId() throws Exception {
        when(service.getSynthesis("a1"))
                .thenReturn(Map.of("artifact_id", "a1", "summary", "test"));

        Map<String, Object> result = tools.synthesis("a1", null);

        assertEquals("test", result.get("summary"));
        verify(service).getSynthesis("a1");
        verify(service, never()).searchSynthesis(anyString());
    }

    @Test
    void synthesisRoutesByKeyword() throws Exception {
        when(service.searchSynthesis("auth"))
                .thenReturn(List.of(Map.of("artifact_id", "a1")));

        Map<String, Object> result = tools.synthesis(null, "auth");

        assertEquals(1, result.get("count"));
        verify(service).searchSynthesis("auth");
        verify(service, never()).getSynthesis(anyString());
    }

    @Test
    void synthesisReturnsErrorWhenNeitherParamGiven() {
        Map<String, Object> result = tools.synthesis(null, null);

        assertEquals("Either artifact_id or keyword must be provided", result.get("error"));
    }

    @Test
    void synthesisReturnsErrorWhenBothBlank() {
        Map<String, Object> result = tools.synthesis("  ", "  ");

        assertEquals("Either artifact_id or keyword must be provided", result.get("error"));
    }

    // ── concepts ───────────────────────────────────────────────────────────────

    @Test
    void conceptsWrapsListResult() throws Exception {
        when(service.listConcepts())
                .thenReturn(List.of(Map.of("concept", "auth")));

        Map<String, Object> result = tools.concepts();

        assertEquals(1, result.get("count"));
        verify(service).listConcepts();
    }

    // ── conceptTimeline ────────────────────────────────────────────────────────

    @Test
    void conceptTimelineDelegates() throws Exception {
        when(service.getConceptTimeline("auth"))
                .thenReturn(Map.of("concept", "auth", "entries", List.of()));

        Map<String, Object> result = tools.conceptTimeline("auth");

        assertEquals("auth", result.get("concept"));
        verify(service).getConceptTimeline("auth");
    }

    // ── conceptHealth ──────────────────────────────────────────────────────────

    @Test
    void conceptHealthDelegates() throws Exception {
        when(service.getConceptHealth())
                .thenReturn(Map.of("total_concepts", 5));

        Map<String, Object> result = tools.conceptHealth();

        assertEquals(5, result.get("total_concepts"));
        verify(service).getConceptHealth();
    }

    // ── staleContent ───────────────────────────────────────────────────────────

    @Test
    void staleContentWrapsListResult() throws Exception {
        when(service.getStaleContent())
                .thenReturn(List.of(Map.of("artifact_id", "a1")));

        Map<String, Object> result = tools.staleContent();

        assertEquals(1, result.get("count"));
        verify(service).getStaleContent();
    }
}
