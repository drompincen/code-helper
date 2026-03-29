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

    // ── Schema DDL ────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void getSchemaDdl() throws Exception {
        Map<String, Object> result = queryService.getSchema("Order");
        assertEquals("Order", result.get("object_name"));
        assertEquals("ORDER_TBL", result.get("table_name"));
        assertEquals("none", result.get("temporal_type"));

        String ddl = (String) result.get("ddl");
        assertNotNull(ddl);
        assertTrue(ddl.contains("CREATE TABLE ORDER_TBL"), "DDL should reference the table name");
        assertTrue(ddl.contains("ORDER_ID"), "DDL should include the PK column");
        assertTrue(ddl.contains("NOT NULL"), "DDL should mark non-nullable columns");
        assertTrue(ddl.contains("PRIMARY KEY"), "DDL should declare primary key");
        assertTrue(ddl.contains("VARCHAR(200)"), "String column with maxLength should produce VARCHAR(200)");
    }

    @Test
    @Order(11)
    void getSchemaNotFound() throws Exception {
        Map<String, Object> result = queryService.getSchema("NoSuchObject");
        assertNotNull(result.get("error"));
    }

    @Test
    @Order(12)
    void getSchemaWithIndex() throws Exception {
        // Order was stored with idx_status index
        Map<String, Object> result = queryService.getSchema("Order");
        String ddl = (String) result.get("ddl");
        assertTrue(ddl.contains("INDEX idx_status"), "DDL should include the index definition");
    }

    @Test
    @Order(13)
    void getSchemaTemporalBitemporal() throws Exception {
        // Store a bitemporal object
        service.storeReladomoObject("art-bt-1", new ReladomoParseResult(
            "BiTemporalObj", "com.test", "BITEMP_TBL", "transactional", "bitemporal",
            null, List.of(), null, null,
            List.of(new ReladomoAttribute("id", "int", "ID", false, true, null, false, false)),
            List.of(), List.of()
        ));
        Map<String, Object> result = queryService.getSchema("BiTemporalObj");
        String ddl = (String) result.get("ddl");
        assertEquals("bitemporal", result.get("temporal_type"));
        assertTrue(ddl.contains("IN_Z"), "Bitemporal DDL should include IN_Z");
        assertTrue(ddl.contains("OUT_Z"), "Bitemporal DDL should include OUT_Z");
        assertTrue(ddl.contains("FROM_Z"), "Bitemporal DDL should include FROM_Z");
        assertTrue(ddl.contains("THRU_Z"), "Bitemporal DDL should include THRU_Z");
    }

    // ── Object files ──────────────────────────────────────────────────────

    @Test
    @Order(20)
    @SuppressWarnings("unchecked")
    void getObjectFiles() throws Exception {
        // Seed artifacts that reference "Order" in the file_name with reladomo_type set
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO artifacts (artifact_id, file_name, reladomo_type, status) VALUES " +
                    "('art-xml-order', 'Order.xml', 'object-xml', 'INDEXED')");
                stmt.execute("INSERT INTO artifacts (artifact_id, file_name, reladomo_type, status) VALUES " +
                    "('art-java-order', 'OrderList.java', 'generated-list', 'INDEXED')");
                stmt.execute("INSERT INTO artifacts (artifact_id, file_name, reladomo_type, status) VALUES " +
                    "('art-finder-order', 'OrderFinder.java', 'generated-finder', 'INDEXED')");
            }
            return null;
        });

        Map<String, Object> result = queryService.getObjectFiles("Order");
        assertEquals("Order", result.get("object_name"));

        Map<String, List<Map<String, String>>> files =
            (Map<String, List<Map<String, String>>>) result.get("files");
        assertFalse(files.isEmpty(), "Should have file groups");
        assertTrue(files.containsKey("object-xml"), "Should contain object-xml group");
        assertTrue(files.containsKey("generated-list"), "Should contain generated-list group");
        assertTrue(files.containsKey("generated-finder"), "Should contain generated-finder group");
    }

    // ── Finder patterns ───────────────────────────────────────────────────

    @Test
    @Order(30)
    @SuppressWarnings("unchecked")
    void getFinderPatterns() throws Exception {
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO reladomo_finder_usage (object_name, attribute_or_path, operation, source_file, line_number, artifact_id) VALUES " +
                    "('Order', 'orderId', 'eq', 'OrderService.java', 42, 'art-fu-1')");
                stmt.execute("INSERT INTO reladomo_finder_usage (object_name, attribute_or_path, operation, source_file, line_number, artifact_id) VALUES " +
                    "('Order', 'orderId', 'eq', 'OrderDao.java', 100, 'art-fu-2')");
                stmt.execute("INSERT INTO reladomo_finder_usage (object_name, attribute_or_path, operation, source_file, line_number, artifact_id) VALUES " +
                    "('Order', 'amount', 'greaterThan', 'ReportService.java', 55, 'art-fu-3')");
            }
            return null;
        });

        Map<String, Object> result = queryService.getFinderPatterns("Order");
        assertEquals("Order", result.get("object_name"));

        List<Map<String, Object>> patterns = (List<Map<String, Object>>) result.get("patterns");
        assertFalse(patterns.isEmpty(), "Should have finder patterns");
        // orderId.eq appears twice so should be first (highest frequency)
        assertEquals("orderId", patterns.get(0).get("attribute_or_path"));
        assertEquals("eq", patterns.get(0).get("operation"));
        assertEquals(2, patterns.get(0).get("frequency"));
        // amount.greaterThan appears once
        boolean hasAmount = patterns.stream().anyMatch(p ->
            "amount".equals(p.get("attribute_or_path")) && "greaterThan".equals(p.get("operation")));
        assertTrue(hasAmount, "Should include amount greaterThan pattern");
    }

    // ── Deep fetch profiles ───────────────────────────────────────────────

    @Test
    @Order(40)
    @SuppressWarnings("unchecked")
    void getDeepFetchProfiles() throws Exception {
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO reladomo_deep_fetch (object_name, fetch_path, source_file, line_number, artifact_id) VALUES " +
                    "('Order', 'Order.items', 'OrderService.java', 50, 'art-df-1')");
                stmt.execute("INSERT INTO reladomo_deep_fetch (object_name, fetch_path, source_file, line_number, artifact_id) VALUES " +
                    "('Order', 'Order.items', 'OrderBatch.java', 80, 'art-df-2')");
                stmt.execute("INSERT INTO reladomo_deep_fetch (object_name, fetch_path, source_file, line_number, artifact_id) VALUES " +
                    "('Order', 'Order.items.product', 'OrderBatch.java', 81, 'art-df-3')");
            }
            return null;
        });

        Map<String, Object> result = queryService.getDeepFetchProfiles("Order");
        assertEquals("Order", result.get("object_name"));

        List<Map<String, Object>> profiles = (List<Map<String, Object>>) result.get("profiles");
        assertFalse(profiles.isEmpty(), "Should have deep fetch profiles");
        // Order.items appears twice so should be first
        assertEquals("Order.items", profiles.get(0).get("fetch_path"));
        assertEquals(2, profiles.get(0).get("frequency"));
        boolean hasNested = profiles.stream().anyMatch(p ->
            "Order.items.product".equals(p.get("fetch_path")));
        assertTrue(hasNested, "Should include nested deep fetch path");
    }

    // ── Temporal info ─────────────────────────────────────────────────────

    @Test
    @Order(50)
    @SuppressWarnings("unchecked")
    void getTemporalInfo() throws Exception {
        // Seed additional temporal objects
        service.storeReladomoObject("art-pd-1", new ReladomoParseResult(
            "AuditLog", "com.test", "AUDIT_TBL", "transactional", "processing-date",
            null, List.of(), null, null,
            List.of(new ReladomoAttribute("logId", "int", "LOG_ID", false, true, null, false, false)),
            List.of(), List.of()
        ));
        service.storeReladomoObject("art-bd-1", new ReladomoParseResult(
            "Contract", "com.test", "CONTRACT_TBL", "transactional", "business-date",
            null, List.of(), null, null,
            List.of(new ReladomoAttribute("contractId", "int", "CONTRACT_ID", false, true, null, false, false)),
            List.of(), List.of()
        ));

        Map<String, Object> result = queryService.getTemporalInfo();
        assertNotNull(result.get("total_objects"));
        assertTrue((int) result.get("total_objects") >= 3, "Should have at least 3 temporal classifications");
        assertEquals("9999-12-01 23:59:00.000", result.get("infinity_date"));

        List<Map<String, Object>> classifications = (List<Map<String, Object>>) result.get("classifications");
        assertFalse(classifications.isEmpty());

        // Verify each temporal type has correct description and columns
        boolean hasBitemporal = classifications.stream().anyMatch(c ->
            "bitemporal".equals(c.get("temporal_type")) &&
            c.get("description").toString().contains("bitemporal"));
        boolean hasProcessing = classifications.stream().anyMatch(c ->
            "processing-date".equals(c.get("temporal_type")) &&
            ((List<?>) c.get("columns")).contains("IN_Z"));
        boolean hasBusiness = classifications.stream().anyMatch(c ->
            "business-date".equals(c.get("temporal_type")) &&
            ((List<?>) c.get("columns")).contains("FROM_Z"));

        assertTrue(hasBitemporal, "Should classify bitemporal objects");
        assertTrue(hasProcessing, "Should classify processing-date objects with IN_Z/OUT_Z columns");
        assertTrue(hasBusiness, "Should classify business-date objects with FROM_Z/THRU_Z columns");
    }

    // ── Runtime config ────────────────────────────────────────────────────

    @Test
    @Order(60)
    @SuppressWarnings("unchecked")
    void getConfigForObject() throws Exception {
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO reladomo_connection_managers (config_file, manager_name, manager_class, properties, artifact_id) VALUES " +
                    "('ReladomoConfig.xml', 'mainDb', 'com.gs.fw.common.mithra.connectionmanager.XAConnectionManager', " +
                    "'ldapName=section:resource', 'art-cm-1')");
                stmt.execute("INSERT INTO reladomo_object_config (object_name, config_file, connection_manager, cache_type, load_cache_on_startup, artifact_id) VALUES " +
                    "('Order', 'ReladomoConfig.xml', 'mainDb', 'partial', false, 'art-oc-1')");
            }
            return null;
        });

        Map<String, Object> result = queryService.getConfig("Order");
        assertEquals("Order", result.get("object_name"));
        assertEquals("mainDb", result.get("connection_manager"));
        assertEquals("com.gs.fw.common.mithra.connectionmanager.XAConnectionManager", result.get("manager_class"));
        assertEquals("partial", result.get("cache_type"));
        assertEquals(false, result.get("load_cache_on_startup"));
        assertEquals("ReladomoConfig.xml", result.get("config_file"));
    }

    @Test
    @Order(61)
    void getConfigForObjectNotFound() throws Exception {
        Map<String, Object> result = queryService.getConfig("NoSuchConfigObject");
        assertEquals("NoSuchConfigObject", result.get("object_name"));
        assertNotNull(result.get("message"), "Should return message when config not found");
    }

    @Test
    @Order(62)
    @SuppressWarnings("unchecked")
    void getConfigAll() throws Exception {
        // Query without object name returns all managers and configs
        Map<String, Object> result = queryService.getConfig(null);
        List<Map<String, Object>> managers = (List<Map<String, Object>>) result.get("connection_managers");
        List<Map<String, Object>> objects = (List<Map<String, Object>>) result.get("object_configs");

        assertNotNull(managers);
        assertNotNull(objects);
        assertFalse(managers.isEmpty(), "Should return connection managers");
        assertFalse(objects.isEmpty(), "Should return object configs");
        // Verify the manager we inserted is present
        boolean hasMainDb = managers.stream().anyMatch(m -> "mainDb".equals(m.get("name")));
        assertTrue(hasMainDb, "Should include mainDb connection manager");
    }

    @Test
    @Order(63)
    @SuppressWarnings("unchecked")
    void getConfigBlankObjectName() throws Exception {
        // Blank string should behave like null — return all configs
        Map<String, Object> result = queryService.getConfig("  ");
        assertNotNull(result.get("connection_managers"), "Blank name should return all config");
        assertNotNull(result.get("object_configs"));
    }

    // ── Graph edge cases ──────────────────────────────────────────────────

    @Test
    @Order(70)
    @SuppressWarnings("unchecked")
    void getGraphEmptyRelationships() throws Exception {
        // Isolated object with no relationships — graph should return just the root node
        Map<String, Object> graph = queryService.getGraph("Isolated", 2);
        assertEquals("Isolated", graph.get("root"));
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        assertEquals(1, nodes.size(), "Isolated object graph should have only the root node");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");
        assertTrue(edges.isEmpty(), "Isolated object graph should have no edges");
    }

    @Test
    @Order(71)
    void getGraphObjectNotFound() throws Exception {
        Map<String, Object> result = queryService.getGraph("CompletelyNonExistent", 1);
        assertNotNull(result.get("error"), "Non-existent object should return error");
    }

    @Test
    @Order(72)
    void getPathSourceNotFound() throws Exception {
        Map<String, Object> result = queryService.getPath("CompletelyNonExistent", "Order");
        assertNotNull(result.get("error"), "Non-existent source should return error");
    }

    @Test
    @Order(73)
    void getPathTargetNotFound() throws Exception {
        Map<String, Object> result = queryService.getPath("Order", "CompletelyNonExistent");
        assertNotNull(result.get("error"), "Non-existent target should return error");
    }

    // ── Schema type mapping edge cases ────────────────────────────────────

    @Test
    @Order(80)
    void getSchemaProcessingDateTemporal() throws Exception {
        service.storeReladomoObject("art-pd-schema-1", new ReladomoParseResult(
            "ProcDateObj", "com.test", "PROC_TBL", "transactional", "processing-date",
            null, List.of(), null, null,
            List.of(new ReladomoAttribute("id", "int", "ID", false, true, null, false, false)),
            List.of(), List.of()
        ));
        Map<String, Object> result = queryService.getSchema("ProcDateObj");
        String ddl = (String) result.get("ddl");
        assertTrue(ddl.contains("IN_Z"), "Processing-date DDL should include IN_Z");
        assertTrue(ddl.contains("OUT_Z"), "Processing-date DDL should include OUT_Z");
        assertFalse(ddl.contains("FROM_Z"), "Processing-date DDL should NOT include FROM_Z");
    }

    @Test
    @Order(81)
    void getSchemaBusinessDateTemporal() throws Exception {
        service.storeReladomoObject("art-bd-schema-1", new ReladomoParseResult(
            "BizDateObj", "com.test", "BIZ_TBL", "transactional", "business-date",
            null, List.of(), null, null,
            List.of(new ReladomoAttribute("id", "int", "ID", false, true, null, false, false)),
            List.of(), List.of()
        ));
        Map<String, Object> result = queryService.getSchema("BizDateObj");
        String ddl = (String) result.get("ddl");
        assertTrue(ddl.contains("FROM_Z"), "Business-date DDL should include FROM_Z");
        assertTrue(ddl.contains("THRU_Z"), "Business-date DDL should include THRU_Z");
        assertFalse(ddl.contains("IN_Z"), "Business-date DDL should NOT include IN_Z");
    }
}
