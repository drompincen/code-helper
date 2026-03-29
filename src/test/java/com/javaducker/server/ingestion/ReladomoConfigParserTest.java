package com.javaducker.server.ingestion;

import com.javaducker.server.model.ReladomoConfigResult;
import com.javaducker.server.model.ReladomoConfigResult.ConnectionManagerDef;
import com.javaducker.server.model.ReladomoConfigResult.ObjectConfigDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReladomoConfigParserTest {

    private final ReladomoConfigParser parser = new ReladomoConfigParser();

    // ── isReladomoConfig ──────────────────────────────────────────────────

    @Test
    void detectsMithraRuntime() {
        assertTrue(parser.isReladomoConfig(SINGLE_MANAGER_CONFIG));
    }

    @Test
    void rejectsNonMithraRuntimeXml() {
        assertFalse(parser.isReladomoConfig("<project><module>foo</module></project>"));
    }

    @Test
    void rejectsNullAndBlank() {
        assertFalse(parser.isReladomoConfig(null));
        assertFalse(parser.isReladomoConfig(""));
        assertFalse(parser.isReladomoConfig("   "));
    }

    @Test
    void rejectsMalformedXml() {
        assertFalse(parser.isReladomoConfig("<MithraRuntime not closed"));
    }

    // ── parse: connection manager config ──────────────────────────────────

    @Test
    void parsesConnectionManagerClassAndProperties() {
        ReladomoConfigResult result = parser.parse(SINGLE_MANAGER_CONFIG, "test.xml");

        assertEquals(1, result.connectionManagers().size());
        ConnectionManagerDef cm = result.connectionManagers().get(0);
        assertEquals("MyConnectionManager", cm.name());
        assertEquals("com.example.MyConnectionManager", cm.className());
        assertEquals("jdbc:h2:mem:test", cm.properties().get("url"));
        assertEquals("sa", cm.properties().get("user"));
    }

    // ── parse: object config ──────────────────────────────────────────────

    @Test
    void parsesObjectConfiguration() {
        ReladomoConfigResult result = parser.parse(SINGLE_MANAGER_CONFIG, "test.xml");

        assertEquals(1, result.objectConfigs().size());
        ObjectConfigDef obj = result.objectConfigs().get(0);
        assertEquals("OrderFinder", obj.objectName());
        assertEquals("MyConnectionManager", obj.connectionManager());
        assertEquals("partial", obj.cacheType());
        assertFalse(obj.loadCacheOnStartup());
    }

    // ── parse: complete config with multiple objects ──────────────────────

    @Test
    void parsesCompleteConfigWithMultipleObjects() {
        ReladomoConfigResult result = parser.parse(COMPLETE_CONFIG, "complete.xml");

        assertEquals(1, result.connectionManagers().size());
        assertEquals(2, result.objectConfigs().size());

        ObjectConfigDef first = result.objectConfigs().get(0);
        assertEquals("OrderFinder", first.objectName());
        assertEquals("full", first.cacheType());
        assertTrue(first.loadCacheOnStartup());

        ObjectConfigDef second = result.objectConfigs().get(1);
        assertEquals("AccountFinder", second.objectName());
        assertEquals("none", second.cacheType());
        assertFalse(second.loadCacheOnStartup());
    }

    // ── parse: empty config ──────────────────────────────────────────────

    @Test
    void parsesEmptyConfig() {
        String xml = "<MithraRuntime></MithraRuntime>";
        ReladomoConfigResult result = parser.parse(xml, "empty.xml");

        assertTrue(result.connectionManagers().isEmpty());
        assertTrue(result.objectConfigs().isEmpty());
    }

    // ── parse: missing attributes ─────────────────────────────────────────

    @Test
    void handlesConnectionManagerWithoutClassName() {
        String xml = """
                <MithraRuntime>
                    <ConnectionManager>
                        <MithraObjectConfiguration className="com.example.Thing"/>
                    </ConnectionManager>
                </MithraRuntime>
                """;
        ReladomoConfigResult result = parser.parse(xml, "no-class.xml");

        assertEquals(1, result.connectionManagers().size());
        ConnectionManagerDef cm = result.connectionManagers().get(0);
        assertEquals("manager-0", cm.name());
        assertNull(cm.className());
        assertTrue(cm.properties().isEmpty());
    }

    @Test
    void handlesObjectWithoutClassName() {
        String xml = """
                <MithraRuntime>
                    <ConnectionManager className="com.example.Mgr">
                        <MithraObjectConfiguration cacheType="full"/>
                    </ConnectionManager>
                </MithraRuntime>
                """;
        ReladomoConfigResult result = parser.parse(xml, "no-obj-class.xml");

        assertEquals(1, result.objectConfigs().size());
        ObjectConfigDef obj = result.objectConfigs().get(0);
        assertNull(obj.objectName());
        assertEquals("full", obj.cacheType());
    }

    @Test
    void defaultsCacheTypeToPartialWhenMissing() {
        String xml = """
                <MithraRuntime>
                    <ConnectionManager className="com.example.Mgr">
                        <MithraObjectConfiguration className="com.example.Order"/>
                    </ConnectionManager>
                </MithraRuntime>
                """;
        ReladomoConfigResult result = parser.parse(xml, "no-cache.xml");

        assertEquals("partial", result.objectConfigs().get(0).cacheType());
    }

    // ── parse: multiple connection managers ────────────────────────────────

    @Test
    void parsesMultipleConnectionManagers() {
        ReladomoConfigResult result = parser.parse(MULTI_MANAGER_CONFIG, "multi.xml");

        assertEquals(2, result.connectionManagers().size());
        assertEquals("ReadOnlyManager", result.connectionManagers().get(0).name());
        assertEquals("ReadWriteManager", result.connectionManagers().get(1).name());

        assertEquals(2, result.objectConfigs().size());
        assertEquals("ReadOnlyManager", result.objectConfigs().get(0).connectionManager());
        assertEquals("ReadWriteManager", result.objectConfigs().get(1).connectionManager());
    }

    // ── parse: cache configuration ────────────────────────────────────────

    @Test
    void parsesDifferentCacheTypes() {
        ReladomoConfigResult result = parser.parse(COMPLETE_CONFIG, "cache.xml");

        List<ObjectConfigDef> objs = result.objectConfigs();
        assertEquals("full", objs.get(0).cacheType());
        assertEquals("none", objs.get(1).cacheType());
    }

    // ── parse: loadCacheOnStartup ─────────────────────────────────────────

    @Test
    void parsesLoadOnStartupTrue() {
        ReladomoConfigResult result = parser.parse(COMPLETE_CONFIG, "startup.xml");
        assertTrue(result.objectConfigs().get(0).loadCacheOnStartup());
    }

    @Test
    void parsesLoadOnStartupFalse() {
        ReladomoConfigResult result = parser.parse(COMPLETE_CONFIG, "startup.xml");
        assertFalse(result.objectConfigs().get(1).loadCacheOnStartup());
    }

    @Test
    void loadOnStartupDefaultsToFalseWhenMissing() {
        String xml = """
                <MithraRuntime>
                    <ConnectionManager className="com.example.Mgr">
                        <MithraObjectConfiguration className="com.example.Order" cacheType="full"/>
                    </ConnectionManager>
                </MithraRuntime>
                """;
        ReladomoConfigResult result = parser.parse(xml, "no-startup.xml");
        assertFalse(result.objectConfigs().get(0).loadCacheOnStartup());
    }

    // ── parse: simple class name extraction ───────────────────────────────

    @Test
    void extractsSimpleClassNameFromFullyQualified() {
        String xml = """
                <MithraRuntime>
                    <ConnectionManager className="com.example.db.MyConnectionManager">
                        <MithraObjectConfiguration className="com.example.domain.OrderFinder"/>
                    </ConnectionManager>
                </MithraRuntime>
                """;
        ReladomoConfigResult result = parser.parse(xml, "fqn.xml");
        assertEquals("MyConnectionManager", result.connectionManagers().get(0).name());
        assertEquals("OrderFinder", result.objectConfigs().get(0).objectName());
    }

    @Test
    void handlesSimpleClassNameWithoutPackage() {
        String xml = """
                <MithraRuntime>
                    <ConnectionManager className="SimpleManager">
                        <MithraObjectConfiguration className="SimpleObject"/>
                    </ConnectionManager>
                </MithraRuntime>
                """;
        ReladomoConfigResult result = parser.parse(xml, "simple.xml");
        assertEquals("SimpleManager", result.connectionManagers().get(0).name());
        assertEquals("SimpleObject", result.objectConfigs().get(0).objectName());
    }

    // ── parse: error handling ─────────────────────────────────────────────

    @Test
    void throwsOnMalformedXml() {
        assertThrows(RuntimeException.class, () ->
                parser.parse("<MithraRuntime><broken", "bad.xml"));
    }

    // ── parse: properties ─────────────────────────────────────────────────

    @Test
    void parsesMultipleProperties() {
        ReladomoConfigResult result = parser.parse(MULTI_MANAGER_CONFIG, "props.xml");

        ConnectionManagerDef cm = result.connectionManagers().get(0);
        assertEquals("jdbc:h2:mem:readonly", cm.properties().get("url"));
        assertEquals("reader", cm.properties().get("user"));
    }

    @Test
    void handlesPropertyWithNoName() {
        String xml = """
                <MithraRuntime>
                    <ConnectionManager className="com.example.Mgr">
                        <Property>orphanValue</Property>
                    </ConnectionManager>
                </MithraRuntime>
                """;
        ReladomoConfigResult result = parser.parse(xml, "no-prop-name.xml");
        assertTrue(result.connectionManagers().get(0).properties().isEmpty());
    }

    // ── XML test fixtures ─────────────────────────────────────────────────

    private static final String SINGLE_MANAGER_CONFIG = """
            <MithraRuntime>
                <ConnectionManager className="com.example.MyConnectionManager">
                    <Property name="url">jdbc:h2:mem:test</Property>
                    <Property name="user">sa</Property>
                    <MithraObjectConfiguration className="com.example.OrderFinder"
                        cacheType="partial" />
                </ConnectionManager>
            </MithraRuntime>
            """;

    private static final String COMPLETE_CONFIG = """
            <MithraRuntime>
                <ConnectionManager className="com.example.ProductionManager">
                    <Property name="url">jdbc:oracle:thin:@prod:1521:db</Property>
                    <MithraObjectConfiguration className="com.example.OrderFinder"
                        cacheType="full" loadCacheOnStartup="true"/>
                    <MithraObjectConfiguration className="com.example.AccountFinder"
                        cacheType="none" loadCacheOnStartup="false"/>
                </ConnectionManager>
            </MithraRuntime>
            """;

    private static final String MULTI_MANAGER_CONFIG = """
            <MithraRuntime>
                <ConnectionManager className="com.example.ReadOnlyManager">
                    <Property name="url">jdbc:h2:mem:readonly</Property>
                    <Property name="user">reader</Property>
                    <MithraObjectConfiguration className="com.example.ReportFinder"
                        cacheType="full" loadCacheOnStartup="true"/>
                </ConnectionManager>
                <ConnectionManager className="com.example.ReadWriteManager">
                    <Property name="url">jdbc:h2:mem:readwrite</Property>
                    <MithraObjectConfiguration className="com.example.TransactionFinder"
                        cacheType="partial"/>
                </ConnectionManager>
            </MithraRuntime>
            """;
}
