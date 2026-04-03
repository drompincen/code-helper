package com.javaducker.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaducker.server.service.ContentIntelligenceService;
import com.javaducker.server.service.SearchService;
import com.javaducker.server.service.SessionIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionToolsTest {

    @Mock
    private SessionIngestionService sessionIngestionService;

    @Mock
    private SearchService searchService;

    @Mock
    private ContentIntelligenceService contentIntelligenceService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private SessionTools sessionTools;

    @Test
    void indexSessions_callsIndexSessionsWithMaxSessions() throws SQLException {
        Map<String, Object> expected = Map.of("indexed", 5);
        when(sessionIngestionService.indexSessions("/project", 10)).thenReturn(expected);

        Map<String, Object> result = sessionTools.indexSessions("/project", 10, null);

        assertEquals(expected, result);
        verify(sessionIngestionService).indexSessions("/project", 10);
    }

    @Test
    void indexSessions_defaultMaxSessionsIsMaxValue() throws SQLException {
        Map<String, Object> expected = Map.of("indexed", 100);
        when(sessionIngestionService.indexSessions("/project", Integer.MAX_VALUE)).thenReturn(expected);

        Map<String, Object> result = sessionTools.indexSessions("/project", null, null);

        assertEquals(expected, result);
        verify(sessionIngestionService).indexSessions("/project", Integer.MAX_VALUE);
    }

    @Test
    void indexSessions_incrementalCallsIncrementalMethod() throws SQLException {
        Map<String, Object> expected = Map.of("indexed", 2, "skipped", 8);
        when(sessionIngestionService.indexSessionsIncremental("/project", Integer.MAX_VALUE))
                .thenReturn(expected);

        Map<String, Object> result = sessionTools.indexSessions("/project", null, "true");

        assertEquals(expected, result);
        verify(sessionIngestionService).indexSessionsIncremental("/project", Integer.MAX_VALUE);
        verify(sessionIngestionService, never()).indexSessions(anyString(), anyInt());
    }

    @Test
    void indexSessions_returnsErrorOnException() throws SQLException {
        when(sessionIngestionService.indexSessions("/bad", Integer.MAX_VALUE))
                .thenThrow(new SQLException("db error"));

        Map<String, Object> result = sessionTools.indexSessions("/bad", null, null);

        assertEquals("db error", result.get("error"));
    }

    @Test
    void searchSessions_passesPhraseAndMaxResults() throws SQLException {
        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "s1", "snippet", "found it"));
        when(sessionIngestionService.searchSessions("reladomo", 20)).thenReturn(sessions);

        Map<String, Object> result = sessionTools.searchSessions("reladomo", null);

        assertEquals(1, result.get("count"));
        assertEquals(sessions, result.get("results"));
        verify(sessionIngestionService).searchSessions("reladomo", 20);
    }

    @Test
    void searchSessions_passesExplicitMaxResults() throws SQLException {
        List<Map<String, Object>> sessions = List.of();
        when(sessionIngestionService.searchSessions("query", 5)).thenReturn(sessions);

        Map<String, Object> result = sessionTools.searchSessions("query", 5);

        assertEquals(0, result.get("count"));
        verify(sessionIngestionService).searchSessions("query", 5);
    }

    @Test
    void sessionContext_combinesSessionAndSemanticResults() throws Exception {
        List<Map<String, Object>> sessionResults = List.of(Map.of("id", "s1"));
        List<Map<String, Object>> semanticResults = List.of(Map.of("id", "a1"));
        when(sessionIngestionService.searchSessions("caching", 10)).thenReturn(sessionResults);
        when(searchService.semanticSearch("caching", 5)).thenReturn(semanticResults);

        Map<String, Object> result = sessionTools.sessionContext("caching");

        assertEquals(sessionResults, result.get("session_results"));
        assertEquals(semanticResults, result.get("semantic_results"));
        verify(sessionIngestionService).searchSessions("caching", 10);
        verify(searchService).semanticSearch("caching", 5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractDecisions_parsesJsonAndDelegates() throws Exception {
        String json = "[{\"text\":\"Use DuckDB\",\"context\":\"storage layer\",\"tags\":\"architecture\"}]";
        Map<String, Object> expected = Map.of("stored", 1);
        when(sessionIngestionService.storeDecisions(eq("session-1"), anyList())).thenReturn(expected);

        Map<String, Object> result = sessionTools.extractDecisions("session-1", json);

        assertEquals(expected, result);
        verify(sessionIngestionService).storeDecisions(eq("session-1"), argThat(list -> {
            List<Map<String, String>> decisions = (List<Map<String, String>>) list;
            return decisions.size() == 1
                    && "Use DuckDB".equals(decisions.get(0).get("text"))
                    && "storage layer".equals(decisions.get(0).get("context"))
                    && "architecture".equals(decisions.get(0).get("tags"));
        }));
    }

    @Test
    void extractDecisions_returnsErrorOnBadJson() {
        Map<String, Object> result = sessionTools.extractDecisions("session-1", "not-json");

        assertNotNull(result.get("error"));
    }

    @Test
    void recentDecisions_passesMaxSessionsAndTag() throws SQLException {
        List<Map<String, Object>> decisions = List.of(Map.of("text", "Use caching"));
        when(sessionIngestionService.getRecentDecisions(3, "performance")).thenReturn(decisions);

        Map<String, Object> result = sessionTools.recentDecisions(3, "performance");

        assertEquals(1, result.get("count"));
        assertEquals(decisions, result.get("results"));
        verify(sessionIngestionService).getRecentDecisions(3, "performance");
    }

    @Test
    void recentDecisions_defaultMaxSessionsIs5() throws SQLException {
        List<Map<String, Object>> decisions = List.of();
        when(sessionIngestionService.getRecentDecisions(5, null)).thenReturn(decisions);

        Map<String, Object> result = sessionTools.recentDecisions(null, null);

        assertEquals(0, result.get("count"));
        verify(sessionIngestionService).getRecentDecisions(5, null);
    }
}
