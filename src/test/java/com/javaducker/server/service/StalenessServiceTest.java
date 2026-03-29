package com.javaducker.server.service;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StalenessServiceTest {

    @Test
    void computeStaleSummary_zeroStaleOutOfTen() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stale", List.of());
        result.put("total_checked", 10L);

        StalenessService.computeStaleSummary(result);

        assertEquals(0, result.get("stale_count"));
        assertEquals(0.0, result.get("stale_percentage"));
    }

    @Test
    void computeStaleSummary_threeStaleOutOfTen() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stale", List.of("a", "b", "c"));
        result.put("total_checked", 10L);

        StalenessService.computeStaleSummary(result);

        assertEquals(3, result.get("stale_count"));
        assertEquals(30.0, result.get("stale_percentage"));
    }

    @Test
    void computeStaleSummary_zeroTotal_noDivisionByZero() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stale", List.of());
        result.put("total_checked", 0L);

        StalenessService.computeStaleSummary(result);

        assertEquals(0, result.get("stale_count"));
        assertEquals(0.0, result.get("stale_percentage"));
    }

    @Test
    void computeStaleSummary_nullStaleList() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stale", null);
        result.put("total_checked", 5L);

        StalenessService.computeStaleSummary(result);

        assertEquals(0, result.get("stale_count"));
        assertEquals(0.0, result.get("stale_percentage"));
    }
}
