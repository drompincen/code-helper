package com.javaducker.server.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaducker.server.ingestion.FileWatcher;
import com.javaducker.server.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(JavaDuckerRestController.class)
class JavaDuckerRestControllerExtendedTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean UploadService uploadService;
    @MockBean ArtifactService artifactService;
    @MockBean SearchService searchService;
    @MockBean StatsService statsService;
    @MockBean ProjectMapService projectMapService;
    @MockBean StalenessService stalenessService;
    @MockBean DependencyService dependencyService;
    @MockBean FileWatcher fileWatcher;
    @MockBean ReladomoQueryService reladomoQueryService;
    @MockBean ContentIntelligenceService contentIntelligenceService;
    @MockBean ExplainService explainService;
    @MockBean GitBlameService gitBlameService;
    @MockBean CoChangeService coChangeService;
    @MockBean SessionIngestionService sessionIngestionService;
    @MockBean SemanticTagService semanticTagService;
    @MockBean KnowledgeGraphService knowledgeGraphService;

    // ── Search with staleness banner ─────────────────────────────────────

    @Test
    void searchWithStalenessWarning() throws Exception {
        List<Map<String, Object>> results = List.of(
                new java.util.LinkedHashMap<>(Map.of("artifact_id", "abc-123", "file_name", "Test.java",
                        "chunk_index", 0, "score", 0.9, "match_type", "EXACT", "preview", "content")));
        when(searchService.hybridSearch(anyString(), anyInt())).thenReturn(results);
        when(artifactService.getStatus("abc-123")).thenReturn(Map.of(
                "artifact_id", "abc-123", "original_client_path", "/src/Test.java",
                "status", "INDEXED"));
        when(stalenessService.checkStaleness(anyList())).thenReturn(Map.of(
                "stale", List.of(Map.of("original_client_path", "/src/Test.java", "reason", "modified"))));

        String body = objectMapper.writeValueAsString(Map.of("phrase", "test", "mode", "hybrid"));
        mockMvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_results").value(1))
                .andExpect(jsonPath("$.staleness_warning").exists())
                .andExpect(jsonPath("$.results[0].stale").value(true));
    }

    @Test
    void searchExactMode() throws Exception {
        when(searchService.exactSearch(anyString(), anyInt())).thenReturn(List.of());
        String body = objectMapper.writeValueAsString(Map.of("phrase", "test", "mode", "exact"));
        mockMvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_results").value(0));
    }

    @Test
    void searchSemanticMode() throws Exception {
        when(searchService.semanticSearch(anyString(), anyInt())).thenReturn(List.of());
        String body = objectMapper.writeValueAsString(Map.of("phrase", "test", "mode", "semantic"));
        mockMvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_results").value(0));
    }

    // ── Project map ──────────────────────────────────────────────────────

    @Test
    void projectMapReturnsData() throws Exception {
        when(projectMapService.getProjectMap()).thenReturn(Map.of("files", List.of(), "count", 0));
        mockMvc.perform(get("/api/map"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    // ── Staleness endpoints ──────────────────────────────────────────────

    @Test
    void staleSummaryReturnsData() throws Exception {
        when(stalenessService.checkAll()).thenReturn(Map.of("stale", List.of(), "total", 0));
        mockMvc.perform(get("/api/stale/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void checkStaleReturnsResults() throws Exception {
        when(stalenessService.checkStaleness(anyList())).thenReturn(Map.of("stale", List.of(), "total", 0));
        String body = objectMapper.writeValueAsString(Map.of("file_paths", List.of("/src/Main.java")));
        mockMvc.perform(post("/api/stale").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void checkStaleRejectsMissingPaths() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of());
        mockMvc.perform(post("/api/stale").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("file_paths is required"));
    }

    // ── Watch endpoints ──────────────────────────────────────────────────

    @Test
    void watchStartReturnsWatching() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "directory", "/tmp/watch", "extensions", "java,xml"));
        mockMvc.perform(post("/api/watch/start").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("watching"))
                .andExpect(jsonPath("$.directory").value("/tmp/watch"));
    }

    @Test
    void watchStopReturnsStopped() throws Exception {
        mockMvc.perform(post("/api/watch/stop").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("stopped"));
    }

    @Test
    void watchStatusReturnsState() throws Exception {
        when(fileWatcher.isWatching()).thenReturn(true);
        Path watchDir = Path.of("/tmp/watch");
        when(fileWatcher.getWatchedDirectory()).thenReturn(watchDir);
        mockMvc.perform(get("/api/watch/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watching").value(true))
                .andExpect(jsonPath("$.directory").value(watchDir.toString()));
    }

    @Test
    void watchStatusWhenNotWatching() throws Exception {
        when(fileWatcher.isWatching()).thenReturn(false);
        when(fileWatcher.getWatchedDirectory()).thenReturn(null);
        mockMvc.perform(get("/api/watch/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watching").value(false))
                .andExpect(jsonPath("$.directory").value(""));
    }

    // ── Content Intelligence: write endpoint tests ───────────────────────

    @Test
    void saveConceptsReturnsResult() throws Exception {
        when(contentIntelligenceService.saveConcepts(eq("abc-123"), anyList()))
                .thenReturn(Map.of("artifact_id", "abc-123", "concepts_count", 2));
        String body = objectMapper.writeValueAsString(Map.of(
                "artifactId", "abc-123", "concepts", List.of(
                        Map.of("concept", "Kafka", "mentions", 3),
                        Map.of("concept", "REST", "mentions", 1))));
        mockMvc.perform(post("/api/concepts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concepts_count").value(2));
    }

    @Test
    void setFreshnessReturnsResult() throws Exception {
        when(contentIntelligenceService.setFreshness(eq("abc-123"), eq("current"), isNull()))
                .thenReturn(Map.of("artifact_id", "abc-123", "freshness", "current"));
        String body = objectMapper.writeValueAsString(Map.of(
                "artifactId", "abc-123", "freshness", "current"));
        mockMvc.perform(post("/api/freshness").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshness").value("current"));
    }

    @Test
    void setFreshnessNotFound() throws Exception {
        when(contentIntelligenceService.setFreshness(anyString(), anyString(), any())).thenReturn(null);
        String body = objectMapper.writeValueAsString(Map.of(
                "artifactId", "nonexistent", "freshness", "stale"));
        mockMvc.perform(post("/api/freshness").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void synthesizeReturnsResult() throws Exception {
        when(contentIntelligenceService.synthesize(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(Map.of("artifact_id", "abc-123", "status", "synthesized"));
        String body = objectMapper.writeValueAsString(Map.of(
                "artifactId", "abc-123", "summaryText", "A summary",
                "tags", "kafka,async", "keyPoints", "point1", "outcome", "done",
                "originalFilePath", "/src/test.md"));
        mockMvc.perform(post("/api/synthesize").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("synthesized"));
    }

    @Test
    void synthesizeNotFound() throws Exception {
        when(contentIntelligenceService.synthesize(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        String body = objectMapper.writeValueAsString(Map.of(
                "artifactId", "nonexistent", "summaryText", "text"));
        mockMvc.perform(post("/api/synthesize").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void synthesizeConflict() throws Exception {
        when(contentIntelligenceService.synthesize(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("error", "already synthesized"));
        String body = objectMapper.writeValueAsString(Map.of(
                "artifactId", "abc-123", "summaryText", "text"));
        mockMvc.perform(post("/api/synthesize").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("already synthesized"));
    }

    @Test
    void linkConceptsReturnsResult() throws Exception {
        when(contentIntelligenceService.linkConcepts(anyList()))
                .thenReturn(Map.of("linked", 2));
        String body = objectMapper.writeValueAsString(Map.of(
                "links", List.of(Map.of("from", "Kafka", "to", "Async", "relationship", "enables"))));
        mockMvc.perform(post("/api/link-concepts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(2));
    }

    @Test
    void markEnrichedSuccess() throws Exception {
        when(contentIntelligenceService.markEnriched("abc-123"))
                .thenReturn(Map.of("artifact_id", "abc-123", "status", "ENRICHED"));
        String body = objectMapper.writeValueAsString(Map.of("artifactId", "abc-123"));
        mockMvc.perform(post("/api/mark-enriched").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENRICHED"));
    }

    // ── Content Intelligence: read endpoint tests ────────────────────────

    @Test
    void findByTagReturnsResults() throws Exception {
        when(contentIntelligenceService.findByTag("kafka"))
                .thenReturn(List.of(Map.of("artifact_id", "abc-123", "file_name", "adr.md")));
        mockMvc.perform(get("/api/find-by-tag?tag=kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag").value("kafka"))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void findPointsReturnsResults() throws Exception {
        when(contentIntelligenceService.findPoints(eq("DECISION"), isNull()))
                .thenReturn(List.of(Map.of("point_text", "Chose Kafka")));
        mockMvc.perform(get("/api/find-points?pointType=DECISION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.point_type").value("DECISION"))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void conceptTimelineReturnsData() throws Exception {
        when(contentIntelligenceService.getConceptTimeline("Kafka"))
                .thenReturn(Map.of("concept", "Kafka", "timeline", List.of()));
        mockMvc.perform(get("/api/concept-timeline/Kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concept").value("Kafka"));
    }

    @Test
    void synthesisFoundReturnsData() throws Exception {
        when(contentIntelligenceService.getSynthesis("abc-123"))
                .thenReturn(Map.of("artifact_id", "abc-123", "summary", "A summary"));
        mockMvc.perform(get("/api/synthesis/abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("A summary"));
    }

    @Test
    void relatedByConceptReturnsResults() throws Exception {
        when(contentIntelligenceService.getRelatedByConcept("abc-123"))
                .thenReturn(List.of(Map.of("artifact_id", "def-456", "shared_concepts", 2)));
        mockMvc.perform(get("/api/related-by-concept/abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact_id").value("abc-123"))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void conceptHealthReturnsData() throws Exception {
        when(contentIntelligenceService.getConceptHealth())
                .thenReturn(Map.of("total_concepts", 5, "healthy", 4));
        mockMvc.perform(get("/api/concept-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_concepts").value(5));
    }

    @Test
    void searchSynthesisReturnsResults() throws Exception {
        when(contentIntelligenceService.searchSynthesis("kafka"))
                .thenReturn(List.of(Map.of("artifact_id", "abc-123", "summary", "Kafka ADR")));
        mockMvc.perform(get("/api/synthesis/search?keyword=kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyword").value("kafka"))
                .andExpect(jsonPath("$.count").value(1));
    }

    // ── Text and Summary endpoints ───────────────────────────────────────

    @Test
    void getTextReturnsContent() throws Exception {
        when(artifactService.getText("abc-123")).thenReturn(Map.of(
                "artifact_id", "abc-123", "text", "file content here"));
        mockMvc.perform(get("/api/text/abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("file content here"));
    }

    @Test
    void getTextNotFound() throws Exception {
        when(artifactService.getText("nonexistent")).thenReturn(null);
        mockMvc.perform(get("/api/text/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getSummaryReturnsContent() throws Exception {
        when(artifactService.getSummary("abc-123")).thenReturn(Map.of(
                "classes", List.of("App"), "methods", List.of("main")));
        mockMvc.perform(get("/api/summary/abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classes[0]").value("App"));
    }

    @Test
    void getSummaryNotFound() throws Exception {
        when(artifactService.getSummary("nonexistent")).thenReturn(null);
        mockMvc.perform(get("/api/summary/nonexistent"))
                .andExpect(status().isNotFound());
    }

    // ── Dependency endpoints ─────────────────────────────────────────────

    @Test
    void getDependenciesReturnsData() throws Exception {
        when(dependencyService.getDependencies("abc-123"))
                .thenReturn(List.of(Map.of("target_id", "dep-1", "type", "IMPORT")));
        mockMvc.perform(get("/api/dependencies/abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact_id").value("abc-123"))
                .andExpect(jsonPath("$.dependencies[0].target_id").value("dep-1"));
    }

    @Test
    void getDependentsReturnsData() throws Exception {
        when(dependencyService.getDependents("abc-123"))
                .thenReturn(List.of(Map.of("source_id", "dep-1", "type", "IMPORT")));
        mockMvc.perform(get("/api/dependents/abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact_id").value("abc-123"))
                .andExpect(jsonPath("$.dependents[0].source_id").value("dep-1"));
    }

    // ── Session Decision endpoint tests ─────────────────────────────────

    @Test
    void extractDecisionsReturnsResult() throws Exception {
        when(sessionIngestionService.storeDecisions(eq("session-1"), anyList()))
                .thenReturn(Map.of("session_id", "session-1", "decisions_stored", 2));
        String body = objectMapper.writeValueAsString(Map.of(
                "sessionId", "session-1",
                "decisions", List.of(
                        Map.of("decision", "Use Kafka", "tag", "architecture"),
                        Map.of("decision", "PostgreSQL for persistence", "tag", "db"))));
        mockMvc.perform(post("/api/extract-session-decisions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions_stored").value(2));
    }

    @Test
    void extractDecisionsRejectsMissingFields() throws Exception {
        // Missing decisions field
        String body = objectMapper.writeValueAsString(Map.of("sessionId", "session-1"));
        mockMvc.perform(post("/api/extract-session-decisions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("sessionId and decisions are required"));
    }

    @Test
    void extractDecisionsRejectsMissingSessionId() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "decisions", List.of(Map.of("decision", "Use Kafka"))));
        mockMvc.perform(post("/api/extract-session-decisions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void extractDecisionsRejectsEmptyDecisionsList() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "sessionId", "session-1", "decisions", List.of()));
        mockMvc.perform(post("/api/extract-session-decisions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recentDecisionsReturnsData() throws Exception {
        when(sessionIngestionService.getRecentDecisions(eq(5), isNull()))
                .thenReturn(List.of(Map.of("session_id", "s1", "decision", "Use Kafka")));
        mockMvc.perform(get("/api/session-decisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.decisions[0].decision").value("Use Kafka"));
    }

    @Test
    void recentDecisionsWithTagFilter() throws Exception {
        when(sessionIngestionService.getRecentDecisions(eq(3), eq("architecture")))
                .thenReturn(List.of(Map.of("session_id", "s1", "decision", "Use microservices")));
        mockMvc.perform(get("/api/session-decisions?maxSessions=3&tag=architecture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    // ── Related endpoint edge cases ──────────────────────────────────────

    @Test
    void relatedByArtifactWithBlankPathReturnsEmptyList() throws Exception {
        when(artifactService.getStatus("abc-123")).thenReturn(Map.of(
                "artifact_id", "abc-123", "original_client_path", "",
                "status", "INDEXED"));
        mockMvc.perform(get("/api/related/abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact_id").value("abc-123"))
                .andExpect(jsonPath("$.related").isEmpty());
    }

    @Test
    void relatedByPathWithRebuild() throws Exception {
        when(coChangeService.getRelatedFiles(eq("/src/Main.java"), eq(5)))
                .thenReturn(List.of());
        String body = objectMapper.writeValueAsString(Map.of(
                "filePath", "/src/Main.java", "maxResults", 5, "rebuild", true));
        mockMvc.perform(post("/api/related").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    // ── Blame endpoint edge cases ────────────────────────────────────────

    @Test
    void blameByArtifactWithNoSummary() throws Exception {
        when(gitBlameService.blameForArtifact("abc-123")).thenReturn(List.of(
                new GitBlameService.BlameEntry(1, 1, "abcdef1234567890abcdef1234567890abcdef12",
                        "alice", null, "commit msg", "code")));
        when(artifactService.getSummary("abc-123")).thenReturn(null);
        mockMvc.perform(get("/api/blame/abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact_id").value("abc-123"))
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.blame[0].date").doesNotExist());
    }

    // ── Index sessions with maxSessions ──────────────────────────────────

    @Test
    void indexSessionsWithMaxSessions() throws Exception {
        when(sessionIngestionService.indexSessions(anyString(), eq(5)))
                .thenReturn(Map.of("sessions_indexed", 5, "total_messages", 100, "project_path", "/tmp"));
        String body = objectMapper.writeValueAsString(Map.of(
                "projectPath", "/tmp/sessions", "maxSessions", 5));
        mockMvc.perform(post("/api/index-sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions_indexed").value(5));
    }

    // ── Reladomo endpoints ───────────────────────────────────────────────

    @Test
    void reladomoRelationshipsReturnsData() throws Exception {
        when(reladomoQueryService.getRelationships("Order"))
                .thenReturn(Map.of("object", "Order", "relationships", List.of()));
        mockMvc.perform(get("/api/reladomo/relationships/Order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("Order"));
    }

    @Test
    void reladomoGraphReturnsData() throws Exception {
        when(reladomoQueryService.getGraph("Order", 3))
                .thenReturn(Map.of("root", "Order", "nodes", List.of()));
        mockMvc.perform(get("/api/reladomo/graph/Order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root").value("Order"));
    }

    @Test
    void reladomoPathReturnsData() throws Exception {
        when(reladomoQueryService.getPath("Order", "Product"))
                .thenReturn(Map.of("from", "Order", "to", "Product", "path", List.of()));
        mockMvc.perform(get("/api/reladomo/path?from=Order&to=Product"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("Order"));
    }

    @Test
    void reladomoSchemaReturnsData() throws Exception {
        when(reladomoQueryService.getSchema("Order"))
                .thenReturn(Map.of("object", "Order", "attributes", List.of()));
        mockMvc.perform(get("/api/reladomo/schema/Order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("Order"));
    }

    @Test
    void reladomoFilesReturnsData() throws Exception {
        when(reladomoQueryService.getObjectFiles("Order"))
                .thenReturn(Map.of("object", "Order", "files", List.of()));
        mockMvc.perform(get("/api/reladomo/files/Order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("Order"));
    }

    @Test
    void reladomoFindersReturnsData() throws Exception {
        when(reladomoQueryService.getFinderPatterns("Order"))
                .thenReturn(Map.of("object", "Order", "finders", List.of()));
        mockMvc.perform(get("/api/reladomo/finders/Order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("Order"));
    }

    @Test
    void reladomoDeepFetchReturnsData() throws Exception {
        when(reladomoQueryService.getDeepFetchProfiles("Order"))
                .thenReturn(Map.of("object", "Order", "profiles", List.of()));
        mockMvc.perform(get("/api/reladomo/deepfetch/Order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("Order"));
    }

    @Test
    void reladomoTemporalReturnsData() throws Exception {
        when(reladomoQueryService.getTemporalInfo())
                .thenReturn(Map.of("temporal_objects", List.of()));
        mockMvc.perform(get("/api/reladomo/temporal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporal_objects").exists());
    }

    @Test
    void reladomoConfigReturnsData() throws Exception {
        when(reladomoQueryService.getConfig(isNull()))
                .thenReturn(Map.of("config", Map.of()));
        mockMvc.perform(get("/api/reladomo/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config").exists());
    }

    // ── Session Transcript endpoint tests ───────────────────────────────

    @Test
    void indexSessionsReturnsResult() throws Exception {
        when(sessionIngestionService.indexSessions(anyString(), anyInt()))
                .thenReturn(Map.of("sessions_indexed", 3, "sessions_skipped", 0,
                        "total_messages", 42, "project_path", "/tmp/sessions"));
        String body = objectMapper.writeValueAsString(Map.of("projectPath", "/tmp/sessions"));
        mockMvc.perform(post("/api/index-sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions_indexed").value(3))
                .andExpect(jsonPath("$.total_messages").value(42));
    }

    @Test
    void indexSessionsIncrementalReturnsResult() throws Exception {
        when(sessionIngestionService.indexSessionsIncremental(anyString(), anyInt()))
                .thenReturn(Map.of("sessions_indexed", 1, "sessions_skipped", 2,
                        "total_messages", 10, "project_path", "/tmp/sessions"));
        String body = objectMapper.writeValueAsString(Map.of(
                "projectPath", "/tmp/sessions", "incremental", true));
        mockMvc.perform(post("/api/index-sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions_indexed").value(1))
                .andExpect(jsonPath("$.sessions_skipped").value(2));
    }

    @Test
    void listSessionsReturnsData() throws Exception {
        when(sessionIngestionService.getSessionList()).thenReturn(List.of(
                Map.of("session_id", "abc-123", "message_count", 15, "total_tokens", 5000L)));
        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.sessions[0].session_id").value("abc-123"));
    }

    @Test
    void getSessionReturnsMessages() throws Exception {
        when(sessionIngestionService.getSession("abc-123")).thenReturn(List.of(
                Map.of("session_id", "abc-123", "message_index", 0, "role", "user",
                        "content", "hello", "token_estimate", 5)));
        mockMvc.perform(get("/api/session/abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value("abc-123"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.messages[0].role").value("user"));
    }

    @Test
    void getSessionNotFound() throws Exception {
        when(sessionIngestionService.getSession("nonexistent")).thenReturn(List.of());
        mockMvc.perform(get("/api/session/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchSessionsReturnsResults() throws Exception {
        when(sessionIngestionService.searchSessions(anyString(), anyInt())).thenReturn(List.of(
                Map.of("session_id", "abc-123", "message_index", 5, "role", "assistant",
                        "preview", "We decided to use Kafka", "tool_name", "")));
        String body = objectMapper.writeValueAsString(Map.of("phrase", "Kafka"));
        mockMvc.perform(post("/api/search-sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_results").value(1))
                .andExpect(jsonPath("$.results[0].session_id").value("abc-123"));
    }

    @Test
    void searchSessionsEmptyResults() throws Exception {
        when(sessionIngestionService.searchSessions(anyString(), anyInt())).thenReturn(List.of());
        String body = objectMapper.writeValueAsString(Map.of("phrase", "nonexistent topic"));
        mockMvc.perform(post("/api/search-sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_results").value(0));
    }
}
