package com.javaducker.server.service;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExplainServiceTest {

    @Test
    void limitList_truncatesLongList() {
        List<String> input = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
        List<String> result = ExplainService.limitList(input, 5);
        assertEquals(5, result.size());
        assertEquals(List.of("a", "b", "c", "d", "e"), result);
    }

    @Test
    void limitList_nullReturnsEmptyList() {
        List<String> result = ExplainService.limitList(null, 5);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void limitList_shortListReturnedAsIs() {
        List<String> input = List.of("a", "b", "c");
        List<String> result = ExplainService.limitList(input, 5);
        assertEquals(3, result.size());
        assertEquals(List.of("a", "b", "c"), result);
    }

    @Test
    void limitList_exactSizeReturnedAsIs() {
        List<String> input = List.of("a", "b", "c", "d", "e");
        List<String> result = ExplainService.limitList(input, 5);
        assertEquals(5, result.size());
    }

    @Test
    void limitList_emptyListReturnsEmpty() {
        List<String> result = ExplainService.limitList(Collections.emptyList(), 5);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void buildExplainResult_nullSectionsOmitted() {
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("file", Map.of("artifact_id", "art-1", "file_name", "Foo.java"));
        sections.put("summary", null);
        sections.put("dependencies", List.of("dep1", "dep2"));
        sections.put("classification", null);
        sections.put("tags", List.of("spring", "service"));

        Map<String, Object> result = ExplainService.buildExplainResult(sections);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertTrue(result.containsKey("file"));
        assertTrue(result.containsKey("dependencies"));
        assertTrue(result.containsKey("tags"));
        assertFalse(result.containsKey("summary"));
        assertFalse(result.containsKey("classification"));
    }

    @Test
    void buildExplainResult_allSectionsPresent() {
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("file", Map.of("artifact_id", "art-1"));
        sections.put("summary", Map.of("summary_text", "A service class"));
        sections.put("dependencies", List.of("dep1"));

        Map<String, Object> result = ExplainService.buildExplainResult(sections);

        assertNotNull(result);
        assertEquals(3, result.size());
    }

    @Test
    void buildExplainResult_nullInputReturnsNull() {
        assertNull(ExplainService.buildExplainResult(null));
    }

    @Test
    void buildExplainResult_allSectionsNullReturnsEmptyMap() {
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("file", null);
        sections.put("summary", null);

        Map<String, Object> result = ExplainService.buildExplainResult(sections);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
