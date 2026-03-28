package com.javaducker.server.ingestion;

import com.javaducker.server.model.ReladomoParseResult;
import com.javaducker.server.model.ReladomoParseResult.ReladomoAttribute;
import com.javaducker.server.model.ReladomoParseResult.ReladomoIndex;
import com.javaducker.server.model.ReladomoParseResult.ReladomoRelationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

@Component
public class ReladomoXmlParser {

    private static final Logger log = LoggerFactory.getLogger(ReladomoXmlParser.class);

    private static final String MITHRA_OBJECT = "MithraObject";
    private static final String MITHRA_INTERFACE = "MithraInterface";

    /**
     * Check if the given XML text is a Reladomo definition by inspecting the root element.
     */
    public boolean isReladomoXml(String xmlText) {
        if (xmlText == null || xmlText.isBlank()) return false;
        try {
            Document doc = parseDocument(xmlText);
            String root = doc.getDocumentElement().getTagName();
            return MITHRA_OBJECT.equals(root) || MITHRA_INTERFACE.equals(root);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parse a MithraObject or MithraInterface XML into a structured result.
     */
    public ReladomoParseResult parse(String xmlText, String fileName) {
        try {
            Document doc = parseDocument(xmlText);
            Element root = doc.getDocumentElement();
            String rootTag = root.getTagName();

            boolean isInterface = MITHRA_INTERFACE.equals(rootTag);
            String objectType = isInterface ? "interface" : attrOrNull(root, "objectType");
            if (objectType == null && !isInterface) objectType = "transactional";

            String objectName = attrOrNull(root, "objectName");
            if (objectName == null) {
                objectName = deriveObjectName(fileName);
            }

            String packageName = attrOrNull(root, "packageName");
            String tableName = attrOrNull(root, "tableName");
            String superClass = attrOrNull(root, "superClass");

            // Parse interfaces (MithraInterfaceResource elements)
            List<String> interfaces = parseInterfaces(root);

            // Source attribute
            String sourceAttrName = null;
            String sourceAttrType = null;
            Element sourceAttr = firstChild(root, "SourceAttribute");
            if (sourceAttr != null) {
                sourceAttrName = attrOrNull(sourceAttr, "name");
                sourceAttrType = attrOrNull(sourceAttr, "javaType");
            }

            // Temporal type detection
            String temporalType = detectTemporalType(root);

            // Parse child elements
            List<ReladomoAttribute> attributes = parseAttributes(root);
            List<ReladomoRelationship> relationships = parseRelationships(root);
            List<ReladomoIndex> indices = parseIndices(root);

            return new ReladomoParseResult(
                objectName, packageName, tableName, objectType, temporalType,
                superClass, interfaces, sourceAttrName, sourceAttrType,
                attributes, relationships, indices
            );
        } catch (Exception e) {
            log.error("Failed to parse Reladomo XML: {}", fileName, e);
            throw new RuntimeException("Failed to parse Reladomo XML: " + fileName, e);
        }
    }

    // ── Attribute parsing ──────────────────────────────────────────────────

    private List<ReladomoAttribute> parseAttributes(Element root) {
        List<ReladomoAttribute> result = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName("Attribute");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            result.add(new ReladomoAttribute(
                attrOrNull(el, "name"),
                attrOrNull(el, "javaType"),
                attrOrNull(el, "columnName"),
                "true".equalsIgnoreCase(attrOrNull(el, "nullable")),
                "true".equalsIgnoreCase(attrOrNull(el, "primaryKey")),
                parseIntOrNull(attrOrNull(el, "maxLength")),
                "true".equalsIgnoreCase(attrOrNull(el, "trim")),
                "true".equalsIgnoreCase(attrOrNull(el, "truncate"))
            ));
        }
        return result;
    }

    // ── Relationship parsing ───────────────────────────────────────────────

    private List<ReladomoRelationship> parseRelationships(Element root) {
        List<ReladomoRelationship> result = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName("Relationship");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String joinExpr = el.getTextContent() != null ? el.getTextContent().trim() : null;
            if (joinExpr != null && joinExpr.isEmpty()) joinExpr = null;
            result.add(new ReladomoRelationship(
                attrOrNull(el, "name"),
                attrOrNull(el, "cardinality"),
                attrOrNull(el, "relatedObject"),
                attrOrNull(el, "reverseRelationshipName"),
                attrOrNull(el, "parameters"),
                joinExpr
            ));
        }
        return result;
    }

    // ── Index parsing ──────────────────────────────────────────────────────

    private List<ReladomoIndex> parseIndices(Element root) {
        List<ReladomoIndex> result = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName("Index");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String columns = el.getTextContent() != null ? el.getTextContent().trim() : "";
            result.add(new ReladomoIndex(
                attrOrNull(el, "name"),
                columns,
                "true".equalsIgnoreCase(attrOrNull(el, "unique"))
            ));
        }
        return result;
    }

    // ── Temporal type detection ────────────────────────────────────────────

    private String detectTemporalType(Element root) {
        boolean hasBusiness = false;
        boolean hasProcessing = false;

        NodeList asOfAttrs = root.getElementsByTagName("AsOfAttribute");
        for (int i = 0; i < asOfAttrs.getLength(); i++) {
            Element el = (Element) asOfAttrs.item(i);
            String name = attrOrNull(el, "name");
            if (name == null) continue;
            String nameLower = name.toLowerCase();
            if (nameLower.contains("business") || nameLower.equals("businessdate")) {
                hasBusiness = true;
            } else if (nameLower.contains("processing") || nameLower.equals("processingdate")) {
                hasProcessing = true;
            } else {
                // Generic as-of — check isProcessingDate attribute
                if ("true".equalsIgnoreCase(attrOrNull(el, "isProcessingDate"))) {
                    hasProcessing = true;
                } else {
                    hasBusiness = true;
                }
            }
        }

        if (hasBusiness && hasProcessing) return "bitemporal";
        if (hasBusiness) return "business-date";
        if (hasProcessing) return "processing-date";
        return "none";
    }

    // ── Interface parsing ──────────────────────────────────────────────────

    private List<String> parseInterfaces(Element root) {
        List<String> result = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName("MithraInterfaceResource");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String name = attrOrNull(el, "name");
            if (name != null) result.add(name);
        }
        return result;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Document parseDocument(String xmlText) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Disable DTD loading for security and speed
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xmlText)));
    }

    private static String attrOrNull(Element el, String name) {
        String val = el.getAttribute(name);
        return (val == null || val.isEmpty()) ? null : val;
    }

    private static Element firstChild(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    private static Integer parseIntOrNull(String val) {
        if (val == null) return null;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return null; }
    }

    private static String deriveObjectName(String fileName) {
        if (fileName == null) return "Unknown";
        String name = fileName;
        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        if (name.contains("\\")) name = name.substring(name.lastIndexOf('\\') + 1);
        if (name.endsWith(".xml")) name = name.substring(0, name.length() - 4);
        // Remove common suffixes
        for (String suffix : List.of("MithraObject", "MithraInterface")) {
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        return name;
    }
}
