package com.javaducker.server.ingestion;

import com.javaducker.server.model.ReladomoParseResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReladomoXmlParserTest {

    private final ReladomoXmlParser parser = new ReladomoXmlParser();

    // ── isReladomoXml ──────────────────────────────────────────────────────

    @Test
    void detectsMithraObject() {
        assertTrue(parser.isReladomoXml(SIMPLE_OBJECT));
    }

    @Test
    void detectsMithraInterface() {
        assertTrue(parser.isReladomoXml(INTERFACE_XML));
    }

    @Test
    void rejectsNonReladomoXml() {
        assertFalse(parser.isReladomoXml("<project><module>foo</module></project>"));
    }

    @Test
    void rejectsNullAndBlank() {
        assertFalse(parser.isReladomoXml(null));
        assertFalse(parser.isReladomoXml(""));
        assertFalse(parser.isReladomoXml("   "));
    }

    @Test
    void rejectsMalformedXml() {
        assertFalse(parser.isReladomoXml("<MithraObject not closed"));
    }

    // ── Simple object parsing ──────────────────────────────────────────────

    @Test
    void parsesSimpleTransactionalObject() {
        ReladomoParseResult r = parser.parse(SIMPLE_OBJECT, "OrderMithraObject.xml");
        assertEquals("Order", r.objectName());
        assertEquals("com.gs.fw.sample", r.packageName());
        assertEquals("ORDER_TBL", r.tableName());
        assertEquals("transactional", r.objectType());
        assertEquals("none", r.temporalType());

        assertEquals(3, r.attributes().size());
        var pk = r.attributes().stream().filter(a -> a.name().equals("orderId")).findFirst().orElseThrow();
        assertEquals("int", pk.javaType());
        assertEquals("ORDER_ID", pk.columnName());
        assertTrue(pk.primaryKey());
        assertFalse(pk.nullable());

        var desc = r.attributes().stream().filter(a -> a.name().equals("description")).findFirst().orElseThrow();
        assertEquals("String", desc.javaType());
        assertEquals(Integer.valueOf(200), desc.maxLength());
        assertTrue(desc.nullable());
        assertTrue(desc.trim());
    }

    // ── Bitemporal object ──────────────────────────────────────────────────

    @Test
    void parsesBitemporalObject() {
        ReladomoParseResult r = parser.parse(BITEMPORAL_OBJECT, "PositionMithraObject.xml");
        assertEquals("Position", r.objectName());
        assertEquals("bitemporal", r.temporalType());
        assertEquals("acctId", r.sourceAttributeName());
        assertEquals("int", r.sourceAttributeType());
    }

    // ── Relationships ──────────────────────────────────────────────────────

    @Test
    void parsesRelationships() {
        ReladomoParseResult r = parser.parse(OBJECT_WITH_RELS, "OrderMithraObject.xml");
        assertEquals(2, r.relationships().size());

        var items = r.relationships().stream().filter(rel -> rel.name().equals("items")).findFirst().orElseThrow();
        assertEquals("one-to-many", items.cardinality());
        assertEquals("OrderItem", items.relatedObject());
        assertEquals("order", items.reverseRelationshipName());
        assertNotNull(items.joinExpression());

        var currency = r.relationships().stream().filter(rel -> rel.name().equals("currency")).findFirst().orElseThrow();
        assertEquals("many-to-one", currency.cardinality());
        assertEquals("Currency", currency.relatedObject());
    }

    // ── Indices ────────────────────────────────────────────────────────────

    @Test
    void parsesIndices() {
        ReladomoParseResult r = parser.parse(OBJECT_WITH_INDEX, "OrderMithraObject.xml");
        assertEquals(1, r.indices().size());
        var idx = r.indices().get(0);
        assertEquals("idx_status_date", idx.name());
        assertEquals("status, orderDate", idx.columns());
        assertFalse(idx.unique());
    }

    // ── Interface ──────────────────────────────────────────────────────────

    @Test
    void parsesInterface() {
        ReladomoParseResult r = parser.parse(INTERFACE_XML, "AuditableMithraInterface.xml");
        assertEquals("Auditable", r.objectName());
        assertEquals("interface", r.objectType());
        assertEquals(2, r.attributes().size());
    }

    // ── Object name derivation from filename ───────────────────────────────

    @Test
    void derivesObjectNameFromFilename() {
        String xml = """
            <MithraObject objectType="transactional">
                <Attribute name="id" javaType="int" columnName="ID" primaryKey="true"/>
            </MithraObject>
            """;
        ReladomoParseResult r = parser.parse(xml, "AccountMithraObject.xml");
        assertEquals("Account", r.objectName());
    }

    // ── Business-date only ─────────────────────────────────────────────────

    @Test
    void parsesBusinessDateOnly() {
        ReladomoParseResult r = parser.parse(BUSINESS_DATE_OBJECT, "PriceMithraObject.xml");
        assertEquals("business-date", r.temporalType());
    }

    // ── Test data ──────────────────────────────────────────────────────────

    static final String SIMPLE_OBJECT = """
        <MithraObject objectType="transactional" objectName="Order"
                      packageName="com.gs.fw.sample" tableName="ORDER_TBL">
            <Attribute name="orderId" javaType="int" columnName="ORDER_ID" primaryKey="true"/>
            <Attribute name="amount" javaType="double" columnName="AMOUNT"/>
            <Attribute name="description" javaType="String" columnName="DESCR"
                       nullable="true" maxLength="200" trim="true"/>
        </MithraObject>
        """;

    static final String BITEMPORAL_OBJECT = """
        <MithraObject objectType="transactional" objectName="Position"
                      packageName="com.gs.fw.sample" tableName="POSITION_TBL">
            <SourceAttribute name="acctId" javaType="int"/>
            <AsOfAttribute name="businessDate" fromColumnName="FROM_Z" toColumnName="THRU_Z"
                           toIsInclusive="false" isProcessingDate="false"
                           infinityDate="[com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity()]"/>
            <AsOfAttribute name="processingDate" fromColumnName="IN_Z" toColumnName="OUT_Z"
                           toIsInclusive="false" isProcessingDate="true"
                           infinityDate="[com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity()]"/>
            <Attribute name="positionId" javaType="long" columnName="POSITION_ID" primaryKey="true"/>
            <Attribute name="quantity" javaType="double" columnName="QTY"/>
        </MithraObject>
        """;

    static final String OBJECT_WITH_RELS = """
        <MithraObject objectType="transactional" objectName="Order"
                      packageName="com.gs.fw.sample" tableName="ORDER_TBL">
            <Attribute name="orderId" javaType="int" columnName="ORDER_ID" primaryKey="true"/>
            <Attribute name="currencyCode" javaType="String" columnName="CCY_CODE"/>
            <Relationship name="items" relatedObject="OrderItem" cardinality="one-to-many"
                          reverseRelationshipName="order">
                this.orderId = OrderItem.orderId
            </Relationship>
            <Relationship name="currency" relatedObject="Currency" cardinality="many-to-one">
                this.currencyCode = Currency.code
            </Relationship>
        </MithraObject>
        """;

    static final String OBJECT_WITH_INDEX = """
        <MithraObject objectType="transactional" objectName="Order"
                      packageName="com.gs.fw.sample" tableName="ORDER_TBL">
            <Attribute name="orderId" javaType="int" columnName="ORDER_ID" primaryKey="true"/>
            <Attribute name="status" javaType="String" columnName="STATUS"/>
            <Attribute name="orderDate" javaType="Timestamp" columnName="ORDER_DATE"/>
            <Index name="idx_status_date">status, orderDate</Index>
        </MithraObject>
        """;

    static final String INTERFACE_XML = """
        <MithraInterface objectName="Auditable" packageName="com.gs.fw.sample">
            <Attribute name="createdBy" javaType="String"/>
            <Attribute name="createdAt" javaType="Timestamp"/>
        </MithraInterface>
        """;

    static final String BUSINESS_DATE_OBJECT = """
        <MithraObject objectType="transactional" objectName="Price"
                      packageName="com.gs.fw.sample" tableName="PRICE_TBL">
            <AsOfAttribute name="businessDate" fromColumnName="FROM_Z" toColumnName="THRU_Z"
                           toIsInclusive="false"
                           infinityDate="[com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity()]"/>
            <Attribute name="priceId" javaType="long" columnName="PRICE_ID" primaryKey="true"/>
            <Attribute name="value" javaType="double" columnName="PRICE_VAL"/>
        </MithraObject>
        """;
}
