package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import com.javaducker.server.model.ReladomoParseResult;
import com.javaducker.server.model.ReladomoParseResult.ReladomoAttribute;
import com.javaducker.server.model.ReladomoParseResult.ReladomoIndex;
import com.javaducker.server.model.ReladomoParseResult.ReladomoRelationship;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReladomoServiceTest {

    @TempDir
    static Path tempDir;

    static DuckDBDataSource dataSource;
    static ReladomoService service;
    static ReladomoQueryService queryService;

    @BeforeAll
    static void setUp() throws Exception {
        AppConfig config = new AppConfig();
        config.setDbPath(tempDir.resolve("test.duckdb").toString());
        config.setIntakeDir(tempDir.resolve("intake").toString());

        dataSource = new DuckDBDataSource(config);

        ArtifactService artifactService = new ArtifactService(dataSource);
        SearchService searchService = new SearchService(dataSource, new EmbeddingService(config), config);
        IngestionWorker worker = new IngestionWorker(dataSource, artifactService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                new EmbeddingService(config), new FileSummarizer(), new ImportParser(),
                new ReladomoXmlParser(), new ReladomoService(dataSource),
                new ReladomoFinderParser(), new ReladomoConfigParser(),
                searchService, config);
        SchemaBootstrap bootstrap = new SchemaBootstrap(dataSource, config, worker);
        bootstrap.bootstrap();

        service = new ReladomoService(dataSource);
        queryService = new ReladomoQueryService(dataSource);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    // ── Store and retrieve ─────────────────────────────────────────────────

    @Test
    @Order(1)
    void storeAndRetrieveObject() throws Exception {
        ReladomoParseResult order = new ReladomoParseResult(
            "Order", "com.gs.fw.sample", "ORDER_TBL", "transactional", "none",
            null, List.of(), null, null,
            List.of(
                new ReladomoAttribute("orderId", "int", "ORDER_ID", false, true, null, false, false),
                new ReladomoAttribute("amount", "double", "AMOUNT", false, false, null, false, false),
                new ReladomoAttribute("description", "String", "DESCR", true, false, 200, true, false)
            ),
            List.of(
                new ReladomoRelationship("items", "one-to-many", "OrderItem", "order", null,
                    "this.orderId = OrderItem.orderId"),
                new ReladomoRelationship("currency", "many-to-one", "Currency", null, null,
                    "this.currencyCode = Currency.code")
            ),
            List.of(new ReladomoIndex("idx_status", "status", false))
        );
        service.storeReladomoObject("art-order-1", order);

        Map<String, Object> result = queryService.getRelationships("Order");
        assertEquals("Order", result.get("object_name"));
        assertEquals("com.gs.fw.sample", result.get("package_name"));
        assertEquals("ORDER_TBL", result.get("table_name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) result.get("attributes");
        assertEquals(3, attrs.size());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rels = (List<Map<String, Object>>) result.get("relationships");
        assertEquals(2, rels.size());
    }

    @Test
    @Order(2)
    void storeRelatedObjects() throws Exception {
        // OrderItem
        service.storeReladomoObject("art-item-1", new ReladomoParseResult(
            "OrderItem", "com.gs.fw.sample", "ORDER_ITEM_TBL", "transactional", "none",
            null, List.of(), null, null,
            List.of(new ReladomoAttribute("itemId", "int", "ITEM_ID", false, true, null, false, false)),
            List.of(
                new ReladomoRelationship("order", "many-to-one", "Order", "items", null, "this.orderId = Order.orderId"),
                new ReladomoRelationship("product", "many-to-one", "Product", null, null, "this.productId = Product.productId")
            ),
            List.of()
        ));
        // Product
        service.storeReladomoObject("art-prod-1", new ReladomoParseResult(
            "Product", "com.gs.fw.sample", "PRODUCT_TBL", "transactional", "none",
            null, List.of(), null, null,
            List.of(new ReladomoAttribute("productId", "int", "PRODUCT_ID", false, true, null, false, false)),
            List.of(
                new ReladomoRelationship("pricingCurrency", "many-to-one", "Currency", null, null, "this.ccyCode = Currency.code")
            ),
            List.of()
        ));
        // Currency
        service.storeReladomoObject("art-ccy-1", new ReladomoParseResult(
            "Currency", "com.gs.fw.sample", "CURRENCY_TBL", "read-only", "none",
            null, List.of(), null, null,
            List.of(new ReladomoAttribute("code", "String", "CCY_CODE", false, true, 3, false, false)),
            List.of(),
            List.of()
        ));
    }

    // ── Graph traversal ────────────────────────────────────────────────────

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void graphTraversalDepth1() throws Exception {
        Map<String, Object> graph = queryService.getGraph("Order", 1);
        assertEquals("Order", graph.get("root"));

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        // Order + OrderItem + Currency = 3 nodes at depth 1
        assertTrue(nodes.size() >= 3, "Expected at least 3 nodes, got " + nodes.size());

        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");
        assertTrue(edges.size() >= 2, "Expected at least 2 edges, got " + edges.size());
    }

    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void graphTraversalDepth2() throws Exception {
        Map<String, Object> graph = queryService.getGraph("Order", 2);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        // Depth 2 should also reach Product (via OrderItem)
        boolean hasProduct = nodes.stream().anyMatch(n -> "Product".equals(n.get("name")));
        assertTrue(hasProduct, "Product should be reachable at depth 2");
    }

    // ── Shortest path ──────────────────────────────────────────────────────

    @Test
    @Order(5)
    @SuppressWarnings("unchecked")
    void shortestPathDirect() throws Exception {
        Map<String, Object> result = queryService.getPath("Order", "OrderItem");
        List<Map<String, String>> path = (List<Map<String, String>>) result.get("path");
        assertEquals(1, path.size());
        assertEquals("Order", path.get(0).get("from"));
        assertEquals("OrderItem", path.get(0).get("to"));
    }

    @Test
    @Order(6)
    @SuppressWarnings("unchecked")
    void shortestPathTwoHops() throws Exception {
        Map<String, Object> result = queryService.getPath("Order", "Product");
        assertEquals(2, result.get("hops"));
        List<Map<String, String>> path = (List<Map<String, String>>) result.get("path");
        assertEquals(2, path.size());
        assertEquals("Order", path.get(0).get("from"));
        assertEquals("Product", path.get(1).get("to"));
    }

    @Test
    @Order(7)
    void objectNotFoundReturnsError() throws Exception {
        Map<String, Object> result = queryService.getRelationships("NonExistent");
        assertNotNull(result.get("error"));
    }

    @Test
    @Order(8)
    void pathNotFoundReturnsEmpty() throws Exception {
        // Store an isolated object with no relationships
        service.storeReladomoObject("art-iso-1", new ReladomoParseResult(
            "Isolated", "com.test", "ISO_TBL", "transactional", "none",
            null, List.of(), null, null,
            List.of(new ReladomoAttribute("id", "int", "ID", false, true, null, false, false)),
            List.of(), List.of()
        ));
        Map<String, Object> result = queryService.getPath("Isolated", "Order");
        @SuppressWarnings("unchecked")
        List<?> path = (List<?>) result.get("path");
        assertTrue(path.isEmpty());
    }
}
