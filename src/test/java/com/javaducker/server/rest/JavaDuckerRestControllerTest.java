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
}
