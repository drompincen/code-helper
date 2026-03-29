package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import com.javaducker.server.ingestion.ReladomoFinderParser.DeepFetchUsage;
import com.javaducker.server.ingestion.ReladomoFinderParser.FinderUsage;
import com.javaducker.server.model.ReladomoConfigResult;
import com.javaducker.server.model.ReladomoConfigResult.ConnectionManagerDef;
import com.javaducker.server.model.ReladomoConfigResult.ObjectConfigDef;
import com.javaducker.server.model.ReladomoParseResult;
import com.javaducker.server.model.ReladomoParseResult.ReladomoAttribute;
import com.javaducker.server.model.ReladomoParseResult.ReladomoIndex;
import com.javaducker.server.model.ReladomoParseResult.ReladomoRelationship;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
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

    // ── ReladomoService: storeReladomoObject edge cases ──────────────────

    @Test
    @Order(100)
    void storeObjectWithNoAttributesNoRelationshipsNoIndices() throws Exception {
        ReladomoParseResult empty = new ReladomoParseResult(
            "EmptyObj", "com.test", "EMPTY_TBL", "read-only", "none",
            null, null, null, null,
            null, null, null
        );
        service.storeReladomoObject("art-empty-1", empty);

        Map<String, Object> result = queryService.getRelationships("EmptyObj");
        assertEquals("EmptyObj", result.get("object_name"));
        assertEquals("EMPTY_TBL", result.get("table_name"));

        @SuppressWarnings("unchecked")
        List<?> attrs = (List<?>) result.get("attributes");
        assertTrue(attrs == null || attrs.isEmpty(), "Should have no attributes");

        @SuppressWarnings("unchecked")
        List<?> rels = (List<?>) result.get("relationships");
        assertTrue(rels == null || rels.isEmpty(), "Should have no relationships");
    }

    @Test
    @Order(101)
    void storeObjectWithEmptyLists() throws Exception {
        ReladomoParseResult emptyLists = new ReladomoParseResult(
            "EmptyListObj", "com.test", "ELIST_TBL", "transactional", "none",
            null, List.of(), null, null,
            List.of(), List.of(), List.of()
        );
        service.storeReladomoObject("art-elist-1", emptyLists);

        Map<String, Object> result = queryService.getRelationships("EmptyListObj");
        assertEquals("EmptyListObj", result.get("object_name"));
    }

    @Test
    @Order(102)
    void storeObjectWithAllOptionalFields() throws Exception {
        ReladomoParseResult full = new ReladomoParseResult(
            "FullObj", "com.test.full", "FULL_TBL", "transactional", "bitemporal",
            "BaseEntity", List.of("Auditable", "Trackable"), "sourceDb", "String",
            List.of(
                new ReladomoAttribute("id", "int", "ID", false, true, null, false, false),
                new ReladomoAttribute("name", "String", "NAME", true, false, 100, true, true)
            ),
            List.of(
                new ReladomoRelationship("parent", "many-to-one", "ParentObj",
                    "children", "sourceDb=this.sourceDb", "this.parentId = ParentObj.id")
            ),
            List.of(
                new ReladomoIndex("idx_name", "name", false),
                new ReladomoIndex("idx_id_unique", "id", true)
            )
        );
        service.storeReladomoObject("art-full-1", full);

        // Verify object metadata including optional fields
        Map<String, Object> result = queryService.getRelationships("FullObj");
        assertEquals("FullObj", result.get("object_name"));
        assertEquals("com.test.full", result.get("package_name"));

        // Verify relationship with parameters and reverseRelationshipName stored
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rels = (List<Map<String, Object>>) result.get("relationships");
        assertEquals(1, rels.size());
        assertEquals("parent", rels.get(0).get("name"));
        assertEquals("children", rels.get(0).get("reverse_name"));

        // Verify parameters stored via raw SQL (query service does not expose parameters)
        dataSource.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT parameters, reverse_relationship_name FROM reladomo_relationships WHERE object_name = ?")) {
                ps.setString(1, "FullObj");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("sourceDb=this.sourceDb", rs.getString("parameters"));
                    assertEquals("children", rs.getString("reverse_relationship_name"));
                }
            }
            return null;
        });

        // Verify indices stored (query via raw SQL)
        dataSource.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT * FROM reladomo_indices WHERE object_name = ? ORDER BY index_name")) {
                ps.setString(1, "FullObj");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("idx_id_unique", rs.getString("index_name"));
                    assertTrue(rs.getBoolean("is_unique"));
                    assertTrue(rs.next());
                    assertEquals("idx_name", rs.getString("index_name"));
                    assertFalse(rs.getBoolean("is_unique"));
                    assertFalse(rs.next());
                }
            }
            return null;
        });

        // Verify interfaces were joined with comma
        dataSource.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT interfaces, super_class, source_attribute_name, source_attribute_type FROM reladomo_objects WHERE object_name = ?")) {
                ps.setString(1, "FullObj");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("Auditable,Trackable", rs.getString("interfaces"));
                    assertEquals("BaseEntity", rs.getString("super_class"));
                    assertEquals("sourceDb", rs.getString("source_attribute_name"));
                    assertEquals("String", rs.getString("source_attribute_type"));
                }
            }
            return null;
        });
    }

    @Test
    @Order(103)
    void reStoreObjectIsIdempotent() throws Exception {
        // Store an object
        ReladomoParseResult v1 = new ReladomoParseResult(
            "MutableObj", "com.test", "MUT_TBL", "transactional", "none",
            null, List.of(), null, null,
            List.of(new ReladomoAttribute("id", "int", "ID", false, true, null, false, false)),
            List.of(new ReladomoRelationship("child", "one-to-many", "ChildObj", null, null, "this.id = ChildObj.parentId")),
            List.of(new ReladomoIndex("idx_old", "id", false))
        );
        service.storeReladomoObject("art-mut-1", v1);

        // Re-store with different attributes, relationships, and indices
        ReladomoParseResult v2 = new ReladomoParseResult(
            "MutableObj", "com.test.v2", "MUT_TBL_V2", "read-only", "bitemporal",
            "NewBase", List.of("NewIface"), "src", "int",
            List.of(
                new ReladomoAttribute("id", "int", "ID", false, true, null, false, false),
                new ReladomoAttribute("version", "int", "VER", false, false, null, false, false)
            ),
            List.of(),
            List.of(new ReladomoIndex("idx_new", "version", true))
        );
        service.storeReladomoObject("art-mut-2", v2);

        // Verify the new version replaced the old
        Map<String, Object> result = queryService.getRelationships("MutableObj");
        assertEquals("com.test.v2", result.get("package_name"));
        assertEquals("MUT_TBL_V2", result.get("table_name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) result.get("attributes");
        assertEquals(2, attrs.size(), "Should have 2 attributes after re-store");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rels = (List<Map<String, Object>>) result.get("relationships");
        assertTrue(rels == null || rels.isEmpty(), "Should have no relationships after re-store");

        // Verify old index replaced by new index
        dataSource.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT * FROM reladomo_indices WHERE object_name = ?")) {
                ps.setString(1, "MutableObj");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("idx_new", rs.getString("index_name"));
                    assertTrue(rs.getBoolean("is_unique"));
                    assertFalse(rs.next(), "Should only have one index after re-store");
                }
            }
            return null;
        });
    }

    @Test
    @Order(104)
    void storeObjectWithNullInterfaces() throws Exception {
        ReladomoParseResult obj = new ReladomoParseResult(
            "NullIfaceObj", "com.test", "NIFACE_TBL", "transactional", "none",
            null, null, null, null,
            List.of(new ReladomoAttribute("id", "int", "ID", false, true, null, false, false)),
            List.of(), List.of()
        );
        service.storeReladomoObject("art-niface-1", obj);

        dataSource.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT interfaces FROM reladomo_objects WHERE object_name = ?")) {
                ps.setString(1, "NullIfaceObj");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertNull(rs.getString("interfaces"));
                }
            }
            return null;
        });
    }

    @Test
    @Order(105)
    void storeObjectAttributeMaxLengthBranches() throws Exception {
        // Attribute with maxLength=null (setNull path) and maxLength=50 (setInt path)
        ReladomoParseResult obj = new ReladomoParseResult(
            "MaxLenObj", "com.test", "MAXLEN_TBL", "transactional", "none",
            null, List.of(), null, null,
            List.of(
                new ReladomoAttribute("noLen", "String", "NO_LEN", true, false, null, false, false),
                new ReladomoAttribute("withLen", "String", "WITH_LEN", true, false, 50, true, true)
            ),
            List.of(), List.of()
        );
        service.storeReladomoObject("art-maxlen-1", obj);

        dataSource.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT attribute_name, max_length, trim, truncate FROM reladomo_attributes WHERE object_name = ? ORDER BY attribute_name")) {
                ps.setString(1, "MaxLenObj");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("noLen", rs.getString("attribute_name"));
                    assertEquals(0, rs.getInt("max_length"));
                    assertTrue(rs.wasNull(), "max_length should be null for noLen");

                    assertTrue(rs.next());
                    assertEquals("withLen", rs.getString("attribute_name"));
                    assertEquals(50, rs.getInt("max_length"));
                    assertTrue(rs.getBoolean("trim"));
                    assertTrue(rs.getBoolean("truncate"));
                }
            }
            return null;
        });
    }

    // ── ReladomoService: classifyReladomoArtifact ────────────────────────

    @Test
    @Order(110)
    void classifyNullFileName() throws Exception {
        assertEquals("none", service.classifyReladomoArtifact(null));
    }

    @Test
    @Order(111)
    void classifyXmlDefinition() throws Exception {
        assertEquals("xml-definition", service.classifyReladomoArtifact("OrderMithraObject.xml"));
        assertEquals("xml-definition", service.classifyReladomoArtifact("src/main/OrderMithraObject.xml"));
        assertEquals("xml-definition", service.classifyReladomoArtifact("PaymentMithraInterface.xml"));
    }

    @Test
    @Order(112)
    void classifyConfig() throws Exception {
        assertEquals("config", service.classifyReladomoArtifact("MithraRuntimeConfig.xml"));
        assertEquals("config", service.classifyReladomoArtifact("path/to/MithraRuntimeDev.xml"));
    }

    @Test
    @Order(113)
    void classifyGeneratedJavaFiles() throws Exception {
        // Order is a known object from earlier tests
        assertEquals("generated", service.classifyReladomoArtifact("OrderAbstract.java"));
        assertEquals("generated", service.classifyReladomoArtifact("OrderFinder.java"));
        assertEquals("generated", service.classifyReladomoArtifact("OrderList.java"));
        assertEquals("generated", service.classifyReladomoArtifact("OrderListAbstract.java"));
        assertEquals("generated", service.classifyReladomoArtifact("OrderDatabaseObject.java"));
        assertEquals("generated", service.classifyReladomoArtifact("OrderDatabaseObjectAbstract.java"));
        assertEquals("generated", service.classifyReladomoArtifact("OrderData.java"));
    }

    @Test
    @Order(114)
    void classifyHandWrittenJava() throws Exception {
        // "Order" itself is a known object
        assertEquals("hand-written", service.classifyReladomoArtifact("Order.java"));
    }

    @Test
    @Order(115)
    void classifyUnknownJavaFile() throws Exception {
        assertEquals("none", service.classifyReladomoArtifact("SomethingElse.java"));
    }

    @Test
    @Order(116)
    void classifyNonJavaNonXml() throws Exception {
        assertEquals("none", service.classifyReladomoArtifact("readme.txt"));
        assertEquals("none", service.classifyReladomoArtifact("build.gradle"));
    }

    @Test
    @Order(117)
    void classifyWithBackslashPath() throws Exception {
        assertEquals("xml-definition", service.classifyReladomoArtifact("src\\main\\OrderMithraObject.xml"));
        assertEquals("generated", service.classifyReladomoArtifact("com\\gs\\OrderFinder.java"));
    }

    @Test
    @Order(118)
    void classifyConfigNotMatchingPrefix() throws Exception {
        // An XML that ends with .xml but does not start with MithraRuntime
        assertEquals("none", service.classifyReladomoArtifact("SomeConfig.xml"));
    }

    // ── ReladomoService: storeFinderUsages ───────────────────────────────

    @Test
    @Order(120)
    void storeFinderUsages() throws Exception {
        List<FinderUsage> usages = List.of(
            new FinderUsage("TestObj", "fieldA", "eq", 10),
            new FinderUsage("TestObj", "fieldB", "greaterThan", 20)
        );
        service.storeFinderUsages("art-fu-test-1", "TestService.java", usages);

        dataSource.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT * FROM reladomo_finder_usage WHERE artifact_id = ? ORDER BY line_number")) {
                ps.setString(1, "art-fu-test-1");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("TestObj", rs.getString("object_name"));
                    assertEquals("fieldA", rs.getString("attribute_or_path"));
                    assertEquals("eq", rs.getString("operation"));
                    assertEquals("TestService.java", rs.getString("source_file"));
                    assertEquals(10, rs.getInt("line_number"));

                    assertTrue(rs.next());
                    assertEquals("fieldB", rs.getString("attribute_or_path"));
                    assertEquals("greaterThan", rs.getString("operation"));
                    assertEquals(20, rs.getInt("line_number"));

                    assertFalse(rs.next());
                }
            }
            return null;
        });
    }

    @Test
    @Order(121)
    void storeFinderUsagesEmptyList() throws Exception {
        // Should not throw with empty list
        service.storeFinderUsages("art-fu-empty", "Empty.java", List.of());
    }

    // ── ReladomoService: storeDeepFetchUsages ────────────────────────────

    @Test
    @Order(130)
    void storeDeepFetchUsages() throws Exception {
        List<DeepFetchUsage> usages = List.of(
            new DeepFetchUsage("FetchObj", "FetchObj.items", 15),
            new DeepFetchUsage("FetchObj", "FetchObj.items.product", 16)
        );
        service.storeDeepFetchUsages("art-df-test-1", "FetchService.java", usages);

        dataSource.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT * FROM reladomo_deep_fetch WHERE artifact_id = ? ORDER BY line_number")) {
                ps.setString(1, "art-df-test-1");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("FetchObj", rs.getString("object_name"));
                    assertEquals("FetchObj.items", rs.getString("fetch_path"));
                    assertEquals("FetchService.java", rs.getString("source_file"));
                    assertEquals(15, rs.getInt("line_number"));

                    assertTrue(rs.next());
                    assertEquals("FetchObj.items.product", rs.getString("fetch_path"));
                    assertEquals(16, rs.getInt("line_number"));

                    assertFalse(rs.next());
                }
            }
            return null;
        });
    }

    @Test
    @Order(131)
    void storeDeepFetchUsagesEmptyList() throws Exception {
        service.storeDeepFetchUsages("art-df-empty", "Empty.java", List.of());
    }

    // ── ReladomoService: storeConfig ─────────────────────────────────────

    @Test
    @Order(140)
    void storeConfig() throws Exception {
        ReladomoConfigResult config = new ReladomoConfigResult(
            List.of(
                new ConnectionManagerDef("testMgr", "com.test.ConnMgr", Map.of("host", "localhost", "port", "5432")),
                new ConnectionManagerDef("cacheMgr", "com.test.CacheMgr", null)
            ),
            List.of(
                new ObjectConfigDef("ConfigTestObj", "testMgr", "full", true),
                new ObjectConfigDef("ConfigTestObj2", "cacheMgr", "none", false)
            )
        );
        service.storeConfig("art-cfg-1", "TestRuntime.xml", config);

        // Verify connection managers
        dataSource.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT * FROM reladomo_connection_managers WHERE config_file = ? ORDER BY manager_name")) {
                ps.setString(1, "TestRuntime.xml");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("cacheMgr", rs.getString("manager_name"));
                    assertEquals("com.test.CacheMgr", rs.getString("manager_class"));
                    assertNull(rs.getString("properties"), "Null properties should store as null");

                    assertTrue(rs.next());
                    assertEquals("testMgr", rs.getString("manager_name"));
                    assertNotNull(rs.getString("properties"));
                }
            }
            return null;
        });

        // Verify object configs
        dataSource.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT * FROM reladomo_object_config WHERE config_file = ? ORDER BY object_name")) {
                ps.setString(1, "TestRuntime.xml");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("ConfigTestObj", rs.getString("object_name"));
                    assertEquals("testMgr", rs.getString("connection_manager"));
                    assertEquals("full", rs.getString("cache_type"));
                    assertTrue(rs.getBoolean("load_cache_on_startup"));

                    assertTrue(rs.next());
                    assertEquals("ConfigTestObj2", rs.getString("object_name"));
                    assertFalse(rs.getBoolean("load_cache_on_startup"));
                }
            }
            return null;
        });
    }

    @Test
    @Order(141)
    void storeConfigIdempotent() throws Exception {
        // Re-store same config file with different data
        ReladomoConfigResult config = new ReladomoConfigResult(
            List.of(new ConnectionManagerDef("testMgr", "com.test.NewConnMgr", null)),
            List.of(new ObjectConfigDef("ConfigTestObj", "testMgr", "partial", false))
        );
        service.storeConfig("art-cfg-2", "TestRuntime.xml", config);

        // Should have replaced the old entries
        dataSource.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM reladomo_connection_managers WHERE config_file = ? AND manager_name = 'testMgr'")) {
                ps.setString(1, "TestRuntime.xml");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "Should have exactly one testMgr entry after re-store");
                }
            }
            return null;
        });
    }

    // ── ReladomoService: tagArtifact directly ────────────────────────────

    @Test
    @Order(150)
    void tagArtifactDirectly() throws Exception {
        // Insert a test artifact first
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO artifacts (artifact_id, file_name, status) VALUES ('art-tag-1', 'TagTest.xml', 'INDEXED')");
            }
            return null;
        });

        service.tagArtifact("art-tag-1", "config");

        dataSource.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT reladomo_type FROM artifacts WHERE artifact_id = ?")) {
                ps.setString(1, "art-tag-1");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("config", rs.getString("reladomo_type"));
                }
            }
            return null;
        });
    }
}
