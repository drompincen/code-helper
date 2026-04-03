package com.javaducker.server.mcp;

import com.javaducker.server.service.*;
import com.javaducker.server.service.GitBlameService.BlameEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalysisToolsTest {

    @Mock ExplainService explainService;
    @Mock GitBlameService gitBlameService;
    @Mock CoChangeService coChangeService;
    @Mock DependencyService dependencyService;
    @Mock ProjectMapService projectMapService;
    @Mock StalenessService stalenessService;
    @Mock ArtifactService artifactService;
    @Mock SemanticTagService semanticTagService;
    @Mock KnowledgeGraphService knowledgeGraphService;

    @InjectMocks AnalysisTools tools;

    // ── explain ──────────────────────────────────────────────────────────

    @Test
    void explain_delegatesFilePath() throws Exception {
        Map<String, Object> expected = Map.of("file", "data");
        when(explainService.explainByPath("src/Main.java")).thenReturn(expected);

        Map<String, Object> result = tools.explain("src/Main.java");

        assertEquals(expected, result);
        verify(explainService).explainByPath("src/Main.java");
    }

    @Test
    void explain_returnsErrorWhenNotFound() throws Exception {
        when(explainService.explainByPath("missing.java")).thenReturn(null);

        Map<String, Object> result = tools.explain("missing.java");

        assertTrue(result.containsKey("error"));
    }

    @Test
    void explain_returnsErrorOnException() throws Exception {
        when(explainService.explainByPath(any())).thenThrow(new RuntimeException("db down"));

        Map<String, Object> result = tools.explain("src/Foo.java");

        assertEquals("db down", result.get("error"));
    }

    // ── blame ────────────────────────────────────────────────────────────

    @Test
    void blame_callsBlameWithoutLineRange() throws Exception {
        BlameEntry entry = new BlameEntry(1, 5, "abc123", "alice",
                Instant.parse("2024-01-01T00:00:00Z"), "initial commit", "code");
        when(gitBlameService.blame("src/Foo.java")).thenReturn(List.of(entry));

        Map<String, Object> result = tools.blame("src/Foo.java", null, null);

        assertEquals("src/Foo.java", result.get("file_path"));
        assertEquals(1, result.get("entry_count"));
        verify(gitBlameService).blame("src/Foo.java");
        verify(gitBlameService, never()).blameForLines(anyString(), anyInt(), anyInt());
    }

    @Test
    void blame_callsBlameForLinesWithRange() throws Exception {
        BlameEntry entry = new BlameEntry(10, 15, "def456", "bob",
                Instant.parse("2024-06-01T00:00:00Z"), "fix bug", "fixed code");
        when(gitBlameService.blameForLines("src/Bar.java", 10, 20)).thenReturn(List.of(entry));

        Map<String, Object> result = tools.blame("src/Bar.java", 10, 20);

        assertEquals(1, result.get("entry_count"));
        verify(gitBlameService).blameForLines("src/Bar.java", 10, 20);
        verify(gitBlameService, never()).blame(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void blame_convertsEntriesToMaps() throws Exception {
        Instant date = Instant.parse("2024-03-15T12:00:00Z");
        BlameEntry entry = new BlameEntry(1, 3, "aaa", "carol", date, "msg", "content");
        when(gitBlameService.blame("f.java")).thenReturn(List.of(entry));

        Map<String, Object> result = tools.blame("f.java", null, null);

        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.get("entries");
        assertNotNull(entries);
        assertEquals(1, entries.size());
        Map<String, Object> map = entries.get(0);
        assertEquals(1, map.get("lineStart"));
        assertEquals(3, map.get("lineEnd"));
        assertEquals("aaa", map.get("commitHash"));
        assertEquals("carol", map.get("author"));
        assertEquals(date.toString(), map.get("authorDate"));
        assertEquals("msg", map.get("commitMessage"));
        assertEquals("content", map.get("content"));
    }

    @Test
    void blame_returnsErrorOnException() throws Exception {
        when(gitBlameService.blame(any())).thenThrow(new RuntimeException("not a git repo"));

        Map<String, Object> result = tools.blame("bad.txt", null, null);

        assertEquals("not a git repo", result.get("error"));
    }

    // ── related ──────────────────────────────────────────────────────────

    @Test
    void related_passesMaxResults() throws Exception {
        List<Map<String, Object>> files = List.of(Map.of("file", "Other.java"));
        when(coChangeService.getRelatedFiles("src/Main.java", 5)).thenReturn(files);

        Map<String, Object> result = tools.related("src/Main.java", 5);

        assertEquals(1, result.get("count"));
        verify(coChangeService).getRelatedFiles("src/Main.java", 5);
    }

    @Test
    void related_usesDefaultMaxResultsWhenNull() throws Exception {
        when(coChangeService.getRelatedFiles("f.java", 10)).thenReturn(List.of());

        tools.related("f.java", null);

        verify(coChangeService).getRelatedFiles("f.java", 10);
    }

    // ── dependencies & dependents ────────────────────────────────────────

    @Test
    void dependencies_delegatesByArtifactId() throws Exception {
        List<Map<String, String>> deps = List.of(Map.of("target", "lib-core"));
        when(dependencyService.getDependencies("art-1")).thenReturn(deps);

        Map<String, Object> result = tools.dependencies("art-1");

        assertEquals("art-1", result.get("artifact_id"));
        assertEquals(1, result.get("count"));
        assertEquals(deps, result.get("dependencies"));
    }

    @Test
    void dependents_delegatesByArtifactId() throws Exception {
        List<Map<String, String>> deps = List.of(Map.of("source", "app-main"));
        when(dependencyService.getDependents("art-2")).thenReturn(deps);

        Map<String, Object> result = tools.dependents("art-2");

        assertEquals("art-2", result.get("artifact_id"));
        assertEquals(1, result.get("count"));
        assertEquals(deps, result.get("dependents"));
    }

    // ── map ──────────────────────────────────────────────────────────────

    @Test
    void map_delegatesToProjectMapService() throws Exception {
        Map<String, Object> expected = Map.of("artifacts", List.of());
        when(projectMapService.getProjectMap()).thenReturn(expected);

        Map<String, Object> result = tools.map();

        assertEquals(expected, result);
    }

    // ── stale ────────────────────────────────────────────────────────────

    @Test
    void stale_parsesJsonFilePathsArray() throws Exception {
        List<String> paths = List.of("src/A.java", "src/B.java");
        Map<String, Object> expected = Map.of("stale", List.of(), "current", 2);
        when(stalenessService.checkStaleness(paths)).thenReturn(expected);

        Map<String, Object> result = tools.stale("[\"src/A.java\",\"src/B.java\"]", null);

        assertEquals(expected, result);
        verify(stalenessService).checkStaleness(paths);
    }

    @Test
    void stale_returnsErrorWhenNoPathsProvided() {
        Map<String, Object> result = tools.stale(null, null);

        assertTrue(result.containsKey("error"));
    }

    @Test
    void stale_returnsErrorOnInvalidJson() {
        Map<String, Object> result = tools.stale("not-json", null);

        // Invalid JSON results in empty paths, which triggers the error
        assertTrue(result.containsKey("error"));
    }

    // ── indexHealth ──────────────────────────────────────────────────────

    @Test
    void indexHealth_returnsHealthyWhenNoStale() throws Exception {
        Map<String, Object> checkResult = new LinkedHashMap<>();
        checkResult.put("stale", List.of());
        checkResult.put("current", 10);
        checkResult.put("total_checked", 10L);
        checkResult.put("stale_count", 0);
        checkResult.put("stale_percentage", 0.0);
        when(stalenessService.checkAll()).thenReturn(checkResult);

        Map<String, Object> result = tools.indexHealth();

        assertEquals("healthy", result.get("health_status"));
        assertEquals("All indexed files are up to date.", result.get("recommendation"));
    }

    @Test
    void indexHealth_returnsDegradedWhenSomewhatStale() throws Exception {
        Map<String, Object> checkResult = new LinkedHashMap<>();
        checkResult.put("stale", List.of(Map.of("file", "a.java")));
        checkResult.put("current", 9);
        checkResult.put("total_checked", 10L);
        checkResult.put("stale_count", 1);
        checkResult.put("stale_percentage", 10.0);
        when(stalenessService.checkAll()).thenReturn(checkResult);

        Map<String, Object> result = tools.indexHealth();

        assertEquals("degraded", result.get("health_status"));
        assertTrue(((String) result.get("recommendation")).contains("1 of 10"));
    }

    @Test
    void indexHealth_returnsUnhealthyWhenVeryStale() throws Exception {
        Map<String, Object> checkResult = new LinkedHashMap<>();
        checkResult.put("stale", List.of(Map.of("f", "a"), Map.of("f", "b"), Map.of("f", "c")));
        checkResult.put("current", 1);
        checkResult.put("total_checked", 4L);
        checkResult.put("stale_count", 3);
        checkResult.put("stale_percentage", 75.0);
        when(stalenessService.checkAll()).thenReturn(checkResult);

        Map<String, Object> result = tools.indexHealth();

        assertEquals("unhealthy", result.get("health_status"));
        assertTrue(((String) result.get("recommendation")).contains("75%"));
    }

    // ── summarize ────────────────────────────────────────────────────────

    @Test
    void summarize_delegatesCorrectly() throws Exception {
        Map<String, Object> summary = new HashMap<>();
        summary.put("artifact_id", "art-1");
        summary.put("summary_text", "A service class");
        when(artifactService.getSummary("art-1")).thenReturn(summary);
        when(artifactService.getStatus("art-1")).thenReturn(null);

        Map<String, Object> result = tools.summarize("art-1");

        assertEquals("A service class", result.get("summary_text"));
        verify(artifactService).getSummary("art-1");
    }

    @Test
    void summarize_returnsErrorWhenNotFound() throws Exception {
        when(artifactService.getSummary("missing")).thenReturn(null);

        Map<String, Object> result = tools.summarize("missing");

        assertTrue(result.containsKey("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarize_addsStalenessWarning() throws Exception {
        Map<String, Object> summary = new HashMap<>();
        summary.put("artifact_id", "art-1");
        summary.put("summary_text", "text");
        when(artifactService.getSummary("art-1")).thenReturn(summary);

        Map<String, String> status = new HashMap<>();
        status.put("original_client_path", "src/Foo.java");
        when(artifactService.getStatus("art-1")).thenReturn(status);

        Map<String, Object> staleness = new HashMap<>();
        staleness.put("stale", List.of(Map.of("file", "src/Foo.java")));
        when(stalenessService.checkStaleness(List.of("src/Foo.java"))).thenReturn(staleness);

        Map<String, Object> result = tools.summarize("art-1");

        assertTrue(result.containsKey("staleness_warning"));
    }

    // ── blameEntryToMap ──────────────────────────────────────────────────

    @Test
    void blameEntryToMap_convertsAllFields() {
        Instant date = Instant.parse("2024-01-15T10:30:00Z");
        BlameEntry entry = new BlameEntry(5, 10, "hash", "author", date, "msg", "code");

        Map<String, Object> map = AnalysisTools.blameEntryToMap(entry);

        assertEquals(5, map.get("lineStart"));
        assertEquals(10, map.get("lineEnd"));
        assertEquals("hash", map.get("commitHash"));
        assertEquals("author", map.get("author"));
        assertEquals("2024-01-15T10:30:00Z", map.get("authorDate"));
        assertEquals("msg", map.get("commitMessage"));
        assertEquals("code", map.get("content"));
    }

    @Test
    void blameEntryToMap_handlesNullDate() {
        BlameEntry entry = new BlameEntry(1, 1, "h", "a", null, "m", "c");

        Map<String, Object> map = AnalysisTools.blameEntryToMap(entry);

        assertNull(map.get("authorDate"));
    }

    // ── resolveFilePaths ─────────────────────────────────────────────────

    @Test
    void resolveFilePaths_parsesJsonArray() {
        List<String> result = tools.resolveFilePaths("[\"a.java\",\"b.java\"]", null);

        assertEquals(List.of("a.java", "b.java"), result);
    }

    @Test
    void resolveFilePaths_returnsEmptyOnNullInputs() {
        List<String> result = tools.resolveFilePaths(null, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void resolveFilePaths_returnsEmptyOnInvalidJson() {
        List<String> result = tools.resolveFilePaths("not valid json", null);

        assertTrue(result.isEmpty());
    }

    // ── findRelated ─────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void findRelated_mergesTagsEntitiesAndCoChanges() throws Exception {
        String artId = "art-1";

        // Semantic tags: art-1 has tags "service" and "spring"
        List<Map<String, Object>> myTags = List.of(
                Map.of("tag", "service", "category", "architectural"),
                Map.of("tag", "spring", "category", "domain"));
        when(semanticTagService.getTagsForArtifact(artId)).thenReturn(myTags);

        // searchByTags returns art-2 sharing 2 tags
        List<Map<String, Object>> tagMatches = List.of(
                Map.of("artifact_id", "art-2", "file_name", "Other.java", "matched_tags", "service, spring", "match_count", 2));
        when(semanticTagService.searchByTags(List.of("service", "spring"), false)).thenReturn(tagMatches);

        // Entities: art-1 has entity "SearchService"
        List<Map<String, Object>> myEntities = List.of(
                Map.of("entity_id", "class-searchservice", "entity_name", "SearchService", "entity_type", "CLASS"));
        when(knowledgeGraphService.getEntitiesForArtifact(artId)).thenReturn(myEntities);
        Map<String, Object> fullEntity = new LinkedHashMap<>();
        fullEntity.put("entity_id", "class-searchservice");
        fullEntity.put("source_artifact_ids", "[\"art-1\",\"art-3\"]");
        when(knowledgeGraphService.getEntity("class-searchservice")).thenReturn(fullEntity);

        // Co-change: art-1 path
        Map<String, String> status = new HashMap<>();
        status.put("original_client_path", "src/Service.java");
        when(artifactService.getStatus(artId)).thenReturn(status);
        List<Map<String, Object>> coChanges = List.of(
                Map.of("related_file", "src/ServiceTest.java", "co_change_count", 5, "last_commit_date", "2024-01-01"));
        when(coChangeService.getRelatedFiles("src/Service.java", 10)).thenReturn(coChanges);

        Map<String, Object> result = tools.findRelated(artId, null);

        assertEquals(artId, result.get("artifact_id"));
        List<Map<String, Object>> related = (List<Map<String, Object>>) result.get("related");
        assertNotNull(related);
        assertFalse(related.isEmpty());

        // art-2 should be present (from tags)
        boolean hasArt2 = related.stream()
                .anyMatch(r -> "art-2".equals(r.get("artifact_id")));
        assertTrue(hasArt2, "art-2 should appear from shared tags");

        // art-3 should be present (from shared entity)
        boolean hasArt3 = related.stream()
                .anyMatch(r -> "art-3".equals(r.get("artifact_id")));
        assertTrue(hasArt3, "art-3 should appear from shared entity");

        // co-change entry should be present
        boolean hasCoChange = related.stream()
                .anyMatch(r -> "src/ServiceTest.java".equals(r.get("artifact_id"))
                        || "src/ServiceTest.java".equals(r.get("file_name")));
        assertTrue(hasCoChange, "co-change file should appear");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findRelated_returnsEmptyWhenNoRelationsFound() throws Exception {
        when(semanticTagService.getTagsForArtifact("art-x")).thenReturn(List.of());
        when(knowledgeGraphService.getEntitiesForArtifact("art-x")).thenReturn(List.of());
        when(artifactService.getStatus("art-x")).thenReturn(null);

        Map<String, Object> result = tools.findRelated("art-x", 5);

        assertEquals("art-x", result.get("artifact_id"));
        assertEquals(0, result.get("count"));
        List<Map<String, Object>> related = (List<Map<String, Object>>) result.get("related");
        assertTrue(related.isEmpty());
    }

    @Test
    void findRelated_returnsErrorOnException() throws Exception {
        when(semanticTagService.getTagsForArtifact("bad")).thenThrow(new RuntimeException("db down"));
        when(knowledgeGraphService.getEntitiesForArtifact("bad")).thenThrow(new RuntimeException("db down"));
        when(artifactService.getStatus("bad")).thenThrow(new RuntimeException("db down"));

        // Should still return a result (errors are caught per-section)
        Map<String, Object> result = tools.findRelated("bad", null);

        assertEquals("bad", result.get("artifact_id"));
        assertEquals(0, result.get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findRelated_respectsMaxResults() throws Exception {
        String artId = "art-max";
        // Create many tag matches
        List<Map<String, Object>> myTags = List.of(Map.of("tag", "t1", "category", "functional"));
        when(semanticTagService.getTagsForArtifact(artId)).thenReturn(myTags);

        List<Map<String, Object>> manyMatches = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            manyMatches.add(Map.of("artifact_id", "art-" + i, "file_name", "F" + i + ".java",
                    "matched_tags", "t1", "match_count", 1));
        }
        when(semanticTagService.searchByTags(List.of("t1"), false)).thenReturn(manyMatches);
        when(knowledgeGraphService.getEntitiesForArtifact(artId)).thenReturn(List.of());
        when(artifactService.getStatus(artId)).thenReturn(null);

        Map<String, Object> result = tools.findRelated(artId, 3);

        List<Map<String, Object>> related = (List<Map<String, Object>>) result.get("related");
        assertTrue(related.size() <= 3);
    }
}
