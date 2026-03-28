package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.model.ReladomoParseResult;
import com.javaducker.server.model.ReladomoParseResult.ReladomoAttribute;
import com.javaducker.server.model.ReladomoParseResult.ReladomoIndex;
import com.javaducker.server.model.ReladomoParseResult.ReladomoRelationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

@Service
public class ReladomoService {

    private static final Logger log = LoggerFactory.getLogger(ReladomoService.class);
    private final DuckDBDataSource dataSource;

    public ReladomoService(DuckDBDataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ── Storage (called during ingestion) ──────────────────────────────────

    public void storeReladomoObject(String artifactId, ReladomoParseResult parsed) throws SQLException {
        dataSource.withConnection(conn -> {
            // DELETE+INSERT pattern (DuckDB ART index bug with UPDATE)
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM reladomo_objects WHERE object_name = ?")) {
                del.setString(1, parsed.objectName());
                del.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO reladomo_objects (object_name, package_name, table_name, object_type, temporal_type, super_class, interfaces, source_attribute_name, source_attribute_type, artifact_id) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, parsed.objectName());
                ps.setString(2, parsed.packageName());
                ps.setString(3, parsed.tableName());
                ps.setString(4, parsed.objectType());
                ps.setString(5, parsed.temporalType());
                ps.setString(6, parsed.superClass());
                ps.setString(7, parsed.interfaces() != null ? String.join(",", parsed.interfaces()) : null);
                ps.setString(8, parsed.sourceAttributeName());
                ps.setString(9, parsed.sourceAttributeType());
                ps.setString(10, artifactId);
                ps.executeUpdate();
            }

            // Attributes
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM reladomo_attributes WHERE object_name = ?")) {
                del.setString(1, parsed.objectName());
                del.executeUpdate();
            }
            if (parsed.attributes() != null && !parsed.attributes().isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO reladomo_attributes (object_name, attribute_name, java_type, column_name, nullable, primary_key, max_length, trim, truncate) VALUES (?,?,?,?,?,?,?,?,?)")) {
                    for (ReladomoAttribute attr : parsed.attributes()) {
                        ps.setString(1, parsed.objectName());
                        ps.setString(2, attr.name());
                        ps.setString(3, attr.javaType());
                        ps.setString(4, attr.columnName());
                        ps.setBoolean(5, attr.nullable());
                        ps.setBoolean(6, attr.primaryKey());
                        if (attr.maxLength() != null) ps.setInt(7, attr.maxLength());
                        else ps.setNull(7, java.sql.Types.INTEGER);
                        ps.setBoolean(8, attr.trim());
                        ps.setBoolean(9, attr.truncate());
                        ps.executeUpdate();
                    }
                }
            }

            // Relationships
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM reladomo_relationships WHERE object_name = ?")) {
                del.setString(1, parsed.objectName());
                del.executeUpdate();
            }
            if (parsed.relationships() != null && !parsed.relationships().isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO reladomo_relationships (object_name, relationship_name, cardinality, related_object, reverse_relationship_name, parameters, join_expression) VALUES (?,?,?,?,?,?,?)")) {
                    for (ReladomoRelationship rel : parsed.relationships()) {
                        ps.setString(1, parsed.objectName());
                        ps.setString(2, rel.name());
                        ps.setString(3, rel.cardinality());
                        ps.setString(4, rel.relatedObject());
                        ps.setString(5, rel.reverseRelationshipName());
                        ps.setString(6, rel.parameters());
                        ps.setString(7, rel.joinExpression());
                        ps.executeUpdate();
                    }
                }
            }

            // Indices
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM reladomo_indices WHERE object_name = ?")) {
                del.setString(1, parsed.objectName());
                del.executeUpdate();
            }
            if (parsed.indices() != null && !parsed.indices().isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO reladomo_indices (object_name, index_name, columns, is_unique) VALUES (?,?,?,?)")) {
                    for (ReladomoIndex idx : parsed.indices()) {
                        ps.setString(1, parsed.objectName());
                        ps.setString(2, idx.name());
                        ps.setString(3, idx.columns());
                        ps.setBoolean(4, idx.unique());
                        ps.executeUpdate();
                    }
                }
            }

            return null;
        });

        tagArtifact(artifactId, "xml-definition");
        log.info("Stored Reladomo object: {} ({})", parsed.objectName(), parsed.temporalType());
    }

    public void tagArtifact(String artifactId, String reladomoType) throws SQLException {
        dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE artifacts SET reladomo_type = ? WHERE artifact_id = ?")) {
                ps.setString(1, reladomoType);
                ps.setString(2, artifactId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ── Classification (F12) ───────────────────────────────────────────────

    public String classifyReladomoArtifact(String fileName) throws SQLException {
        if (fileName == null) return "none";
        String baseName = fileName.contains("/") ? fileName.substring(fileName.lastIndexOf('/') + 1) : fileName;
        baseName = baseName.contains("\\") ? baseName.substring(baseName.lastIndexOf('\\') + 1) : baseName;

        if (baseName.endsWith("MithraObject.xml") || baseName.endsWith("MithraInterface.xml")) return "xml-definition";
        if (baseName.startsWith("MithraRuntime") && baseName.endsWith(".xml")) return "config";

        if (baseName.endsWith(".java")) {
            String className = baseName.substring(0, baseName.length() - 5);
            Set<String> knownObjects = getKnownObjectNames();

            if (className.endsWith("Abstract") && knownObjects.contains(className.replace("Abstract", ""))) return "generated";
            if (className.endsWith("Finder") && knownObjects.contains(className.replace("Finder", ""))) return "generated";
            if (className.endsWith("List") && knownObjects.contains(className.replace("List", ""))) return "generated";
            if (className.endsWith("ListAbstract") && knownObjects.contains(className.replace("ListAbstract", ""))) return "generated";
            if (className.endsWith("DatabaseObject") && knownObjects.contains(className.replace("DatabaseObject", ""))) return "generated";
            if (className.endsWith("DatabaseObjectAbstract") && knownObjects.contains(className.replace("DatabaseObjectAbstract", ""))) return "generated";
            if (className.endsWith("Data") && knownObjects.contains(className.replace("Data", ""))) return "generated";
            if (knownObjects.contains(className)) return "hand-written";
        }
        return "none";
    }

    private Set<String> getKnownObjectNames() throws SQLException {
        return dataSource.withConnection(conn -> {
            Set<String> names = new HashSet<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT object_name FROM reladomo_objects");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString("object_name"));
            }
            return names;
        });
    }

    // ── Finder storage (F13) ───────────────────────────────────────────────

    public void storeFinderUsages(String artifactId, String fileName,
            java.util.List<com.javaducker.server.ingestion.ReladomoFinderParser.FinderUsage> usages) throws SQLException {
        dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO reladomo_finder_usage (object_name, attribute_or_path, operation, source_file, line_number, artifact_id) VALUES (?,?,?,?,?,?)")) {
                for (var u : usages) {
                    ps.setString(1, u.objectName());
                    ps.setString(2, u.attributeOrPath());
                    ps.setString(3, u.operation());
                    ps.setString(4, fileName);
                    ps.setInt(5, u.lineNumber());
                    ps.setString(6, artifactId);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // ── Deep fetch storage (F14) ───────────────────────────────────────────

    public void storeDeepFetchUsages(String artifactId, String fileName,
            java.util.List<com.javaducker.server.ingestion.ReladomoFinderParser.DeepFetchUsage> usages) throws SQLException {
        dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO reladomo_deep_fetch (object_name, fetch_path, source_file, line_number, artifact_id) VALUES (?,?,?,?,?)")) {
                for (var u : usages) {
                    ps.setString(1, u.objectName());
                    ps.setString(2, u.fetchPath());
                    ps.setString(3, fileName);
                    ps.setInt(4, u.lineNumber());
                    ps.setString(5, artifactId);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // ── Config storage (F16) ───────────────────────────────────────────────

    public void storeConfig(String artifactId, String fileName,
            com.javaducker.server.model.ReladomoConfigResult config) throws SQLException {
        dataSource.withConnection(conn -> {
            for (var cm : config.connectionManagers()) {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM reladomo_connection_managers WHERE config_file = ? AND manager_name = ?")) {
                    del.setString(1, fileName);
                    del.setString(2, cm.name());
                    del.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO reladomo_connection_managers (config_file, manager_name, manager_class, properties, artifact_id) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, fileName);
                    ps.setString(2, cm.name());
                    ps.setString(3, cm.className());
                    ps.setString(4, cm.properties() != null ? cm.properties().toString() : null);
                    ps.setString(5, artifactId);
                    ps.executeUpdate();
                }
            }
            for (var oc : config.objectConfigs()) {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM reladomo_object_config WHERE object_name = ? AND config_file = ?")) {
                    del.setString(1, oc.objectName());
                    del.setString(2, fileName);
                    del.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO reladomo_object_config (object_name, config_file, connection_manager, cache_type, load_cache_on_startup, artifact_id) VALUES (?,?,?,?,?,?)")) {
                    ps.setString(1, oc.objectName());
                    ps.setString(2, fileName);
                    ps.setString(3, oc.connectionManager());
                    ps.setString(4, oc.cacheType());
                    ps.setBoolean(5, oc.loadCacheOnStartup());
                    ps.setString(6, artifactId);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }
}
