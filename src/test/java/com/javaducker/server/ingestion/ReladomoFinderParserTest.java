package com.javaducker.server.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReladomoFinderParserTest {

    private final ReladomoFinderParser parser = new ReladomoFinderParser();

    @Test
    void parsesSimpleFinderOperation() {
        String code = """
            OrderFinder.orderId().eq(42);
            OrderFinder.status().in(statusSet);
            """;
        var usages = parser.parseFinderUsages(code, "OrderService.java");
        assertTrue(usages.size() >= 2);
        assertTrue(usages.stream().anyMatch(u ->
            u.objectName().equals("Order") && u.attributeOrPath().equals("orderId") && u.operation().equals("eq")));
        assertTrue(usages.stream().anyMatch(u ->
            u.objectName().equals("Order") && u.attributeOrPath().equals("status") && u.operation().equals("in")));
    }

    @Test
    void parsesRelationshipNavigation() {
        String code = "OrderFinder.items().productId().eq(prodId);\n";
        var usages = parser.parseFinderUsages(code, "Test.java");
        assertTrue(usages.stream().anyMatch(u ->
            u.objectName().equals("Order") && u.attributeOrPath().equals("items.productId") && u.operation().equals("eq")));
    }

    @Test
    void parsesSimpleDeepFetch() {
        String code = "list.deepFetch(OrderFinder.items());\n";
        var usages = parser.parseDeepFetchUsages(code, "Test.java");
        assertEquals(1, usages.size());
        assertEquals("Order", usages.get(0).objectName());
        assertEquals("items", usages.get(0).fetchPath());
    }

    @Test
    void parsesChainedDeepFetch() {
        String code = "list.deepFetch(OrderFinder.items().product());\n";
        var usages = parser.parseDeepFetchUsages(code, "Test.java");
        assertTrue(usages.stream().anyMatch(u ->
            u.objectName().equals("Order") && u.fetchPath().contains("items")));
    }

    @Test
    void emptyForNonFinderCode() {
        String code = "String x = \"hello\";\nint y = 42;\n";
        assertTrue(parser.parseFinderUsages(code, "Test.java").isEmpty());
        assertTrue(parser.parseDeepFetchUsages(code, "Test.java").isEmpty());
    }

    @Test
    void tracksLineNumbers() {
        String code = "// line 1\nOrderFinder.orderId().eq(1);\n// line 3\n";
        var usages = parser.parseFinderUsages(code, "Test.java");
        assertFalse(usages.isEmpty());
        assertEquals(2, usages.get(0).lineNumber());
    }
}
