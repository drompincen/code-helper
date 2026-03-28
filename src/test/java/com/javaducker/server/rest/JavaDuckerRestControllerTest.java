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

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(JavaDuckerRestController.class)
class JavaDuckerRestControllerTest {

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

    @Test
    void healthReturnsOk() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.version").value("2.0.0"));
    }

    @Test
    void statusNotFoundWhenArtifactMissing() throws Exception {
        when(artifactService.getStatus(anyString())).thenReturn(null);
        mockMvc.perform(get("/api/status/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void statusReturnsArtifactData() throws Exception {
        Map<String, String> data = Map.of(
                "artifact_id", "abc-123",
                "file_name", "Test.java",
                "status", "INDEXED",
                "error_message", "",
                "created_at", "2024-01-01T00:00:00",
                "updated_at", "2024-01-01T00:00:00",
                "indexed_at", "2024-01-01T00:00:01");
        when(artifactService.getStatus("abc-123")).thenReturn(data);
        mockMvc.perform(get("/api/status/abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact_id").value("abc-123"))
                .andExpect(jsonPath("$.status").value("INDEXED"));
    }

    @Test
    void searchReturnsResults() throws Exception {
        List<Map<String, Object>> results = List.of(
                Map.of("artifact_id", "abc-123", "file_name", "Test.java",
                        "chunk_index", 0, "score", 0.9, "match_type", "EXACT", "preview", "test content"));
        when(searchService.hybridSearch(anyString(), anyInt())).thenReturn(results);

        String body = objectMapper.writeValueAsString(Map.of("phrase", "test", "mode", "hybrid"));
        mockMvc.perform(post("/api/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_results").value(1))
                .andExpect(jsonPath("$.results[0].artifact_id").value("abc-123"));
    }

    @Test
    void statsReturnsData() throws Exception {
        Map<String, Object> stats = Map.of(
                "total_artifacts", 10L,
                "indexed_artifacts", 8L,
                "failed_artifacts", 1L,
                "pending_artifacts", 1L,
                "total_chunks", 100L,
                "total_bytes", 50000L);
        when(statsService.getStats()).thenReturn(stats);
        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_artifacts").value(10));
    }

    // ── Content Intelligence: write endpoint tests ───────────────────────

    @Test
    void classifyArtifact() throws Exception {
        when(contentIntelligenceService.classify(eq("abc-123"), eq("ADR"), anyDouble(), eq("llm")))
                .thenReturn(Map.of("artifact_id", "abc-123", "doc_type", "ADR", "confidence", 0.95, "method", "llm"));
        String body = objectMapper.writeValueAsString(Map.of(
                "artifactId", "abc-123", "docType", "ADR", "confidence", 0.95, "method", "llm"));
        mockMvc.perform(post("/api/classify").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doc_type").value("ADR"));
    }

    @Test
    void tagArtifact() throws Exception {
        when(contentIntelligenceService.tag(eq("abc-123"), anyList()))
                .thenReturn(Map.of("artifact_id", "abc-123", "tags_count", 2));
        String body = objectMapper.writeValueAsString(Map.of(
                "artifactId", "abc-123", "tags", List.of(Map.of("tag", "kafka"), Map.of("tag", "async"))));
        mockMvc.perform(post("/api/tag").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags_count").value(2));
    }

    @Test
    void salientPoints() throws Exception {
        when(contentIntelligenceService.extractPoints(eq("abc-123"), anyList()))
                .thenReturn(Map.of("artifact_id", "abc-123", "points_count", 1));
        String body = objectMapper.writeValueAsString(Map.of(
                "artifactId", "abc-123", "points", List.of(Map.of("point_type", "DECISION", "point_text", "Chose Kafka"))));
        mockMvc.perform(post("/api/salient-points").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points_count").value(1));
    }

    @Test
    void enrichQueueReturnsItems() throws Exception {
        when(contentIntelligenceService.getEnrichQueue(anyInt()))
                .thenReturn(List.of(Map.of("artifact_id", "abc-123", "file_name", "test.md", "status", "INDEXED", "created_at", "2026-01-01")));
        mockMvc.perform(get("/api/enrich-queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void markEnrichedNotFound() throws Exception {
        when(contentIntelligenceService.markEnriched(anyString())).thenReturn(null);
        String body = objectMapper.writeValueAsString(Map.of("artifactId", "nonexistent"));
        mockMvc.perform(post("/api/mark-enriched").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    // ── Content Intelligence: read endpoint tests ────────────────────────

    @Test
    void latestReturnsResult() throws Exception {
        when(contentIntelligenceService.getLatest("kafka"))
                .thenReturn(Map.of("found", true, "artifact_id", "abc-123", "file_name", "adr-kafka.md"));
        mockMvc.perform(get("/api/latest?topic=kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true));
    }

    @Test
    void findByTypeReturnsResults() throws Exception {
        when(contentIntelligenceService.findByType("ADR"))
                .thenReturn(List.of(Map.of("artifact_id", "abc-123", "doc_type", "ADR", "file_name", "adr.md",
                        "freshness", "current", "updated_at", "2026-01-01")));
        mockMvc.perform(get("/api/find-by-type?docType=ADR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void conceptsListReturns() throws Exception {
        when(contentIntelligenceService.listConcepts())
                .thenReturn(List.of(Map.of("concept", "Kafka", "doc_count", 3, "total_mentions", 10)));
        mockMvc.perform(get("/api/concepts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concepts[0].concept").value("Kafka"));
    }

    @Test
    void staleContentReturns() throws Exception {
        when(contentIntelligenceService.getStaleContent()).thenReturn(List.of());
        mockMvc.perform(get("/api/stale-content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void synthesisNotFound() throws Exception {
        when(contentIntelligenceService.getSynthesis("nonexistent")).thenReturn(null);
        mockMvc.perform(get("/api/synthesis/nonexistent"))
                .andExpect(status().isNotFound());
    }
}
