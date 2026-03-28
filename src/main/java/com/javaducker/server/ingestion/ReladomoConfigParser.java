package com.javaducker.server.ingestion;

import com.javaducker.server.model.ReladomoConfigResult;
import com.javaducker.server.model.ReladomoConfigResult.ConnectionManagerDef;
import com.javaducker.server.model.ReladomoConfigResult.ObjectConfigDef;
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
import java.util.*;

@Component
public class ReladomoConfigParser {

    private static final Logger log = LoggerFactory.getLogger(ReladomoConfigParser.class);

    /**
     * Check if the XML is a MithraRuntime configuration.
     */
    public boolean isReladomoConfig(String xmlText) {
        if (xmlText == null || xmlText.isBlank()) return false;
        try {
            Document doc = parseDocument(xmlText);
            return "MithraRuntime".equals(doc.getDocumentElement().getTagName());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parse a MithraRuntime XML configuration file.
     */
    public ReladomoConfigResult parse(String xmlText, String fileName) {
        try {
            Document doc = parseDocument(xmlText);
            Element root = doc.getDocumentElement();

            List<ConnectionManagerDef> managers = new ArrayList<>();
            List<ObjectConfigDef> configs = new ArrayList<>();

            // Parse ConnectionManager elements
            NodeList cmNodes = root.getElementsByTagName("ConnectionManager");
            for (int i = 0; i < cmNodes.getLength(); i++) {
                Element cmEl = (Element) cmNodes.item(i);
                String className = attrOrNull(cmEl, "className");

                // Parse properties
                Map<String, String> props = new LinkedHashMap<>();
                NodeList propNodes = cmEl.getElementsByTagName("Property");
                for (int j = 0; j < propNodes.getLength(); j++) {
                    Element propEl = (Element) propNodes.item(j);
                    String propName = attrOrNull(propEl, "name");
                    String propValue = propEl.getTextContent() != null ? propEl.getTextContent().trim() : "";
                    if (propName != null) props.put(propName, propValue);
                }

                // Derive manager name from className or properties
                String managerName = className != null
                    ? className.substring(className.lastIndexOf('.') + 1) : "manager-" + i;
                managers.add(new ConnectionManagerDef(managerName, className, props));

                // Parse MithraObjectConfiguration children
                NodeList objNodes = cmEl.getElementsByTagName("MithraObjectConfiguration");
                for (int j = 0; j < objNodes.getLength(); j++) {
                    Element objEl = (Element) objNodes.item(j);
                    String objectName = attrOrNull(objEl, "className");
                    if (objectName != null && objectName.contains(".")) {
                        objectName = objectName.substring(objectName.lastIndexOf('.') + 1);
                    }
                    String cacheType = attrOrNull(objEl, "cacheType");
                    if (cacheType == null) cacheType = "partial";
                    boolean loadOnStartup = "true".equalsIgnoreCase(attrOrNull(objEl, "loadCacheOnStartup"));

                    configs.add(new ObjectConfigDef(objectName, managerName, cacheType, loadOnStartup));
                }
            }

            return new ReladomoConfigResult(managers, configs);
        } catch (Exception e) {
            log.error("Failed to parse Reladomo config: {}", fileName, e);
            throw new RuntimeException("Failed to parse Reladomo config: " + fileName, e);
        }
    }

    private Document parseDocument(String xmlText) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
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
}
