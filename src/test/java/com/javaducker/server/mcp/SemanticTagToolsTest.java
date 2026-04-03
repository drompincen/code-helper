package com.javaducker.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaducker.server.service.SemanticTagService;
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
class SemanticTagToolsTest {

    @Mock
    private SemanticTagService service;

    private ObjectMapper objectMapper;
    private SemanticTagTools tools;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tools = new SemanticTagTools(service, objectMapper);
    }

    // ── synthesizeTags ────────────────────────────────────────────────────────

    @Test
    void synthesizeTagsParsesJsonAndDelegates() throws Exception {
        String json = """
            [{"tag":"auth","category":"domain","confidence":0.9,"rationale":"handles auth"},
             {"tag":"spring","category":"architectural","confidence":0.8},
             {"tag":"crud","category":"pattern","confidence":0.7},
             {"tag":"security","category":"concern","confidence":0.6}]
            """;
        when(service.writeTags(eq("a1"), anyList()))
                .thenReturn(Map.of("artifact_id", "a1", "tags_count", 4));

        Map<String, Object> result = tools.synthesizeTags("a1", json);

        assertEquals("a1", result.get("artifact_id"));
        assertEquals(4, result.get("tags_count"));
        verify(service).writeTags(eq("a1"), argThat(list -> list.size() == 4));
    }

    @Test
    void synthesizeTagsReturnsMalformedJsonError() {
        Map<String, Object> result = tools.synthesizeTags("a1", "not json");

        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().startsWith("Invalid JSON:"));
    }

    @Test
    void synthesizeTagsReturnsValidationError() throws Exception {
        when(service.writeTags(eq("a1"), anyList()))
                .thenThrow(new IllegalArgumentException("Tags count must be between 4 and 10, got 2"));

        String json = "[{\"tag\":\"a\",\"category\":\"domain\"},{\"tag\":\"b\",\"category\":\"functional\"}]";
        Map<String, Object> result = tools.synthesizeTags("a1", json);

        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("Tags count"));
    }

    @Test
    void synthesizeTagsReturnsErrorOnSqlException() throws Exception {
        when(service.writeTags(anyString(), anyList()))
                .thenThrow(new SQLException("db down"));

        String json = """
            [{"tag":"a","category":"domain"},{"tag":"b","category":"functional"},
             {"tag":"c","category":"pattern"},{"tag":"d","category":"concern"}]
            """;
        Map<String, Object> result = tools.synthesizeTags("a1", json);

        assertEquals("db down", result.get("error"));
    }

    // ── searchByTags ──────────────────────────────────────────────────────────

    @Test
    void searchByTagsDefaultsToAny() throws Exception {
        when(service.searchByTags(anyList(), eq(false)))
                .thenReturn(List.of(Map.of("artifact_id", "a1", "file_name", "Test.java",
                        "matched_tags", "auth", "match_count", 1)));

        Map<String, Object> result = tools.searchByTags("[\"auth\"]", null, null);

        assertEquals(1, result.get("count"));
        verify(service).searchByTags(argThat(l -> l.size() == 1 && "auth".equals(l.get(0))), eq(false));
    }

    @Test
    void searchByTagsMatchAll() throws Exception {
        when(service.searchByTags(anyList(), eq(true)))
                .thenReturn(List.of());

        Map<String, Object> result = tools.searchByTags("[\"auth\",\"crud\"]", "all", null);

        assertEquals(0, result.get("count"));
        verify(service).searchByTags(anyList(), eq(true));
    }

    @Test
    void searchByTagsWithCategoryFilter() throws Exception {
        when(service.searchByTags(anyList(), eq(false)))
                .thenReturn(List.of(
                        Map.of("artifact_id", "a1", "file_name", "A.java",
                                "matched_tags", "auth", "match_count", 1),
                        Map.of("artifact_id", "a2", "file_name", "B.java",
                                "matched_tags", "auth", "match_count", 1)));
        when(service.findByCategory("domain"))
                .thenReturn(List.of(Map.of("artifact_id", "a1", "tag", "auth", "category", "domain")));

        Map<String, Object> result = tools.searchByTags("[\"auth\"]", null, "domain");

        assertEquals(1, result.get("count"), "Only a1 should match domain filter");
    }

    @Test
    void searchByTagsReturnsMalformedJsonError() {
        Map<String, Object> result = tools.searchByTags("bad", null, null);

        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().startsWith("Invalid JSON:"));
    }

    // ── tagCloud ──────────────────────────────────────────────────────────────

    @Test
    void tagCloudDelegates() throws Exception {
        when(service.getTagCloud())
                .thenReturn(Map.of("categories", Map.of(), "total_tags", 0));

        Map<String, Object> result = tools.tagCloud();

        assertEquals(0, result.get("total_tags"));
        verify(service).getTagCloud();
    }

    @Test
    void tagCloudReturnsErrorOnException() throws Exception {
        when(service.getTagCloud()).thenThrow(new SQLException("db down"));

        Map<String, Object> result = tools.tagCloud();

        assertEquals("db down", result.get("error"));
    }

    // ── suggestTags ───────────────────────────────────────────────────────────

    @Test
    void suggestTagsDelegates() throws Exception {
        when(service.suggestTags("a1"))
                .thenReturn(List.of(Map.of("tag", "security", "category", "concern", "frequency", 3)));

        Map<String, Object> result = tools.suggestTags("a1");

        assertEquals("a1", result.get("artifact_id"));
        assertEquals(1, result.get("count"));
        verify(service).suggestTags("a1");
    }

    @Test
    void suggestTagsReturnsErrorOnException() throws Exception {
        when(service.suggestTags("a1")).thenThrow(new SQLException("db down"));

        Map<String, Object> result = tools.suggestTags("a1");

        assertEquals("db down", result.get("error"));
    }
}
