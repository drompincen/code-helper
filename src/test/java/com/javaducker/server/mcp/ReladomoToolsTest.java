package com.javaducker.server.mcp;

import com.javaducker.server.service.ReladomoQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReladomoToolsTest {

    @Mock
    private ReladomoQueryService reladomoQueryService;

    @InjectMocks
    private ReladomoTools reladomoTools;

    @Test
    void relationships_delegatesObjectName() throws SQLException {
        Map<String, Object> expected = Map.of("object_name", "Order", "relationships", "[]");
        when(reladomoQueryService.getRelationships("Order")).thenReturn(expected);

        Map<String, Object> result = reladomoTools.relationships("Order");

        assertEquals(expected, result);
        verify(reladomoQueryService).getRelationships("Order");
    }

    @Test
    void relationships_returnsErrorOnException() throws SQLException {
        when(reladomoQueryService.getRelationships("Bad")).thenThrow(new SQLException("db error"));

        Map<String, Object> result = reladomoTools.relationships("Bad");

        assertEquals("db error", result.get("error"));
    }

    @Test
    void graph_passesDepthWithDefault() throws SQLException {
        Map<String, Object> expected = Map.of("nodes", 5);
        when(reladomoQueryService.getGraph("Order", 3)).thenReturn(expected);

        Map<String, Object> result = reladomoTools.graph("Order", null);

        assertEquals(expected, result);
        verify(reladomoQueryService).getGraph("Order", 3);
    }

    @Test
    void graph_passesExplicitDepth() throws SQLException {
        Map<String, Object> expected = Map.of("nodes", 10);
        when(reladomoQueryService.getGraph("Order", 5)).thenReturn(expected);

        Map<String, Object> result = reladomoTools.graph("Order", 5);

        assertEquals(expected, result);
        verify(reladomoQueryService).getGraph("Order", 5);
    }

    @Test
    void path_passesFromAndTo() throws SQLException {
        Map<String, Object> expected = Map.of("path", "Order -> OrderItem -> Product");
        when(reladomoQueryService.getPath("Order", "Product")).thenReturn(expected);

        Map<String, Object> result = reladomoTools.path("Order", "Product");

        assertEquals(expected, result);
        verify(reladomoQueryService).getPath("Order", "Product");
    }

    @Test
    void schema_delegatesObjectName() throws SQLException {
        Map<String, Object> expected = Map.of("columns", 8);
        when(reladomoQueryService.getSchema("Order")).thenReturn(expected);

        Map<String, Object> result = reladomoTools.schema("Order");

        assertEquals(expected, result);
        verify(reladomoQueryService).getSchema("Order");
    }

    @Test
    void objectFiles_delegatesObjectName() throws SQLException {
        Map<String, Object> expected = Map.of("files", 3);
        when(reladomoQueryService.getObjectFiles("Order")).thenReturn(expected);

        Map<String, Object> result = reladomoTools.objectFiles("Order");

        assertEquals(expected, result);
        verify(reladomoQueryService).getObjectFiles("Order");
    }

    @Test
    void finders_delegatesObjectName() throws SQLException {
        Map<String, Object> expected = Map.of("patterns", 4);
        when(reladomoQueryService.getFinderPatterns("Order")).thenReturn(expected);

        Map<String, Object> result = reladomoTools.finders("Order");

        assertEquals(expected, result);
        verify(reladomoQueryService).getFinderPatterns("Order");
    }

    @Test
    void deepFetch_delegatesObjectName() throws SQLException {
        Map<String, Object> expected = Map.of("profiles", 2);
        when(reladomoQueryService.getDeepFetchProfiles("Order")).thenReturn(expected);

        Map<String, Object> result = reladomoTools.deepFetch("Order");

        assertEquals(expected, result);
        verify(reladomoQueryService).getDeepFetchProfiles("Order");
    }

    @Test
    void temporal_returnsServiceResults() throws SQLException {
        Map<String, Object> expected = Map.of("temporal_objects", 6);
        when(reladomoQueryService.getTemporalInfo()).thenReturn(expected);

        Map<String, Object> result = reladomoTools.temporal();

        assertEquals(expected, result);
        verify(reladomoQueryService).getTemporalInfo();
    }

    @Test
    void config_withObjectName() throws SQLException {
        Map<String, Object> expected = Map.of("cache_type", "partial");
        when(reladomoQueryService.getConfig("Order")).thenReturn(expected);

        Map<String, Object> result = reladomoTools.config("Order");

        assertEquals(expected, result);
        verify(reladomoQueryService).getConfig("Order");
    }

    @Test
    void config_withNullObjectName() throws SQLException {
        Map<String, Object> expected = Map.of("objects", 12);
        when(reladomoQueryService.getConfig(null)).thenReturn(expected);

        Map<String, Object> result = reladomoTools.config(null);

        assertEquals(expected, result);
        verify(reladomoQueryService).getConfig(null);
    }
}
