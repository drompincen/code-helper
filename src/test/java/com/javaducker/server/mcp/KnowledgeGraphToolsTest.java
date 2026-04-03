package com.javaducker.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaducker.server.service.CommunityDetectionService;
import com.javaducker.server.service.GraphSearchService;
import com.javaducker.server.service.GraphUpdateService;
import com.javaducker.server.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeGraphToolsTest {

    @Mock
    private KnowledgeGraphService service;

    @Mock
    private GraphSearchService graphSearchService;

    @Mock
    private GraphUpdateService graphUpdateService;

    @Mock
    private CommunityDetectionService communityDetectionService;

    private ObjectMapper objectMapper;
    private KnowledgeGraphTools tools;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tools = new KnowledgeGraphTools(service, graphSearchService, graphUpdateService,
                communityDetectionService, objectMapper);
    }

    // ── extractEntities ──────────────────────────────────────────────────────

    @Test
    void extractEntitiesCreatesEntities() throws Exception {
        when(service.upsertEntity(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Map.of("entity_id", "class-foo", "action", "created"));

        String entities = """
            [{"name":"Foo","type":"class","description":"A foo class"},
             {"name":"Bar","type":"interface","description":"A bar interface"}]
            """;
        Map<String, Object> result = tools.extractEntities("art-1", entities, null);

        assertEquals("art-1", result.get("artifact_id"));
        assertEquals(2, result.get("entities_created"));
        assertEquals(0, result.get("entities_merged"));
        verify(service, times(2)).upsertEntity(anyString(), anyString(), anyString(), eq("art-1"), isNull());
    }

    @Test
    void extractEntitiesWithRelationships() throws Exception {
        when(service.upsertEntity(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Map.of("entity_id", "class-foo", "action", "created"));
        when(service.findEntitiesByName("Foo"))
                .thenReturn(List.of(Map.of("entity_id", "class-foo", "entity_name", "Foo")));
        when(service.findEntitiesByName("Bar"))
                .thenReturn(List.of(Map.of("entity_id", "interface-bar", "entity_name", "Bar")));
        when(service.upsertRelationship(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyDouble()))
                .thenReturn(Map.of("relationship_id", "r1", "action", "created"));

        String entities = """
            [{"name":"Foo","type":"class","description":"A foo"},
             {"name":"Bar","type":"interface","description":"A bar"}]
            """;
        String rels = """
            [{"sourceName":"Foo","targetName":"Bar","type":"implements","description":"Foo implements Bar"}]
            """;
        Map<String, Object> result = tools.extractEntities("art-1", entities, rels);

        assertEquals(1, result.get("relationships_created"));
    }

    @Test
    void extractEntitiesRejectsEmptyList() {
        Map<String, Object> result = tools.extractEntities("art-1", "[]", null);
        assertTrue(result.containsKey("error"));
    }

    @Test
    void extractEntitiesHandlesInvalidJson() {
        Map<String, Object> result = tools.extractEntities("art-1", "not json", null);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().startsWith("Invalid JSON:"));
    }

    // ── getEntities ──────────────────────────────────────────────────────────

    @Test
    void getEntitiesByType() throws Exception {
        when(service.findEntitiesByType("class"))
                .thenReturn(List.of(Map.of("entity_id", "class-foo", "entity_name", "Foo")));

        Map<String, Object> result = tools.getEntities("class", null);

        assertEquals(1, result.get("count"));
    }

    @Test
    void getEntitiesByName() throws Exception {
        when(service.findEntitiesByName("Foo"))
                .thenReturn(List.of(Map.of("entity_id", "class-foo", "entity_name", "Foo")));

        Map<String, Object> result = tools.getEntities(null, "Foo");

        assertEquals(1, result.get("count"));
    }

    // ── mergeEntities ────────────────────────────────────────────────────────

    @Test
    void mergeEntitiesDelegates() throws Exception {
        when(service.mergeEntities("a", "b", "merged desc"))
                .thenReturn(Map.of("action", "merged", "target_entity_id", "b"));

        Map<String, Object> result = tools.mergeEntities("a", "b", "merged desc");

        assertEquals("merged", result.get("action"));
    }

    // ── deleteEntities ───────────────────────────────────────────────────────

    @Test
    void deleteEntitiesDelegates() throws Exception {
        when(service.deleteEntitiesForArtifact("art-1"))
                .thenReturn(Map.of("deleted_entities", 2, "deleted_relationships", 1));

        Map<String, Object> result = tools.deleteEntities("art-1");

        assertEquals(2, result.get("deleted_entities"));
    }

    // ── graphStats ───────────────────────────────────────────────────────────

    @Test
    void graphStatsReturnsStats() throws Exception {
        when(service.getStats())
                .thenReturn(Map.of("entity_count", 10, "relationship_count", 5));

        Map<String, Object> result = tools.graphStats();

        assertEquals(10, result.get("entity_count"));
    }

    // ── graphNeighborhood ────────────────────────────────────────────────────

    @Test
    void graphNeighborhoodDefaultDepth() throws Exception {
        when(service.getNeighborhood("e1", 2))
                .thenReturn(Map.of("nodes", List.of(), "edges", List.of()));

        Map<String, Object> result = tools.graphNeighborhood("e1", null);

        assertNotNull(result.get("nodes"));
        verify(service).getNeighborhood("e1", 2);
    }

    @Test
    void graphNeighborhoodCapsDepthAt5() throws Exception {
        when(service.getNeighborhood("e1", 5))
                .thenReturn(Map.of("nodes", List.of(), "edges", List.of()));

        tools.graphNeighborhood("e1", 10);

        verify(service).getNeighborhood("e1", 5);
    }

    // ── graphPath ────────────────────────────────────────────────────────────

    @Test
    void graphPathDelegates() throws Exception {
        when(service.getPath("a", "b"))
                .thenReturn(Map.of("found", true, "path", List.of("a", "b")));

        Map<String, Object> result = tools.graphPath("a", "b");

        assertEquals(true, result.get("found"));
    }

    @Test
    void graphPathHandlesError() throws Exception {
        when(service.getPath("a", "b")).thenThrow(new RuntimeException("db error"));

        Map<String, Object> result = tools.graphPath("a", "b");

        assertTrue(result.containsKey("error"));
    }

    // ── graphSearch ─────────────────────────────────────────────────────────

    @Test
    void graphSearchDefaultsModeToMix() throws Exception {
        when(graphSearchService.mixSearch("test query", 10))
                .thenReturn(List.of(Map.of("entity_id", "e1", "score", 0.9, "match_type", "MIX")));

        Map<String, Object> result = tools.graphSearch("test query", null, null, null);

        assertEquals("mix", result.get("mode"));
        assertEquals(1, result.get("count"));
        verify(graphSearchService).mixSearch("test query", 10);
    }

    @Test
    void graphSearchLocalMode() throws Exception {
        when(graphSearchService.localSearch("test", 5))
                .thenReturn(List.of(Map.of("entity_id", "e1", "score", 0.8, "match_type", "LOCAL")));

        Map<String, Object> result = tools.graphSearch("test", "local", 5, null);

        assertEquals("local", result.get("mode"));
        verify(graphSearchService).localSearch("test", 5);
    }

    @Test
    void graphSearchInvalidModeReturnsError() {
        Map<String, Object> result = tools.graphSearch("test", "invalid", null, null);

        assertTrue(result.containsKey("error"));
    }

    // ── mergeCandidates ─────────────────────────────────────────────────────

    @Test
    void mergeCandidatesReturnsResults() throws Exception {
        List<Map<String, Object>> mockCandidates = List.of(
                Map.of("source_entity_id", "a", "target_entity_id", "b", "confidence", 1.0));
        when(service.findDuplicateCandidates()).thenReturn(mockCandidates);

        Map<String, Object> result = tools.mergeCandidates(null);

        assertEquals(1, result.get("count"));
        verify(service).findDuplicateCandidates();
    }

    @Test
    void mergeCandidatesWithEntityIdDelegates() throws Exception {
        List<Map<String, Object>> mockCandidates = List.of(
                Map.of("entity_id", "b", "entity_name", "Bar", "similarity", 0.95));
        when(service.findMergeCandidates("a")).thenReturn(mockCandidates);

        Map<String, Object> result = tools.mergeCandidates("a");

        assertEquals("a", result.get("entity_id"));
        assertEquals(1, result.get("count"));
    }

    // ── confirmMerge ────────────────────────────────────────────────────────

    @Test
    void confirmMergeDelegates() throws Exception {
        when(service.mergeEntities("src", "tgt", "merged desc"))
                .thenReturn(Map.of("merged_into", "tgt", "source_deleted", "src"));

        Map<String, Object> result = tools.confirmMerge("src", "tgt", "merged desc");

        assertEquals("tgt", result.get("merged_into"));
        verify(service).mergeEntities("src", "tgt", "merged desc");
    }
}
