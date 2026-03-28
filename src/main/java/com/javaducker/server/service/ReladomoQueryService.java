package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Service
public class ReladomoQueryService {

    private final DuckDBDataSource dataSource;

    public ReladomoQueryService(DuckDBDataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ── Relationships (F10) ────────────────────────────────────────────────

    public Map<String, Object> getRelationships(String objectName) throws SQLException {
        return dataSource.withConnection(conn -> {
            Map<String, Object> result = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM reladomo_objects WHERE object_name = ?")) {
                ps.setString(1, objectName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Map.of("error", "Object not found: " + objectName);
                    result.put("object_name", rs.getString("object_name"));
                    result.put("package_name", rs.getString("package_name"));
                    result.put("table_name", rs.getString("table_name"));
                    result.put("object_type", rs.getString("object_type"));
                    result.put("temporal_type", rs.getString("temporal_type"));
                }
            }
            List<Map<String, Object>> attrs = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM reladomo_attributes WHERE object_name = ? ORDER BY primary_key DESC, attribute_name")) {
                ps.setString(1, objectName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> attr = new LinkedHashMap<>();
                        attr.put("name", rs.getString("attribute_name"));
                        attr.put("java_type", rs.getString("java_type"));
                        attr.put("column_name", rs.getString("column_name"));
                        attr.put("primary_key", rs.getBoolean("primary_key"));
                        attr.put("nullable", rs.getBoolean("nullable"));
                        Integer maxLen = rs.getObject("max_length") != null ? rs.getInt("max_length") : null;
                        if (maxLen != null) attr.put("max_length", maxLen);
                        attrs.add(attr);
                    }
                }
            }
            result.put("attributes", attrs);
            List<Map<String, Object>> rels = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM reladomo_relationships WHERE object_name = ? ORDER BY relationship_name")) {
                ps.setString(1, objectName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> rel = new LinkedHashMap<>();
                        rel.put("name", rs.getString("relationship_name"));
                        rel.put("cardinality", rs.getString("cardinality"));
                        rel.put("related_object", rs.getString("related_object"));
                        rel.put("reverse_name", rs.getString("reverse_relationship_name"));
                        rels.add(rel);
                    }
                }
            }
            result.put("relationships", rels);
            return result;
        });
    }

    // ── Graph traversal (F10) ──────────────────────────────────────────────

    private record Edge(String target, String relationshipName, String cardinality) {}

    public Map<String, Object> getGraph(String objectName, int maxDepth) throws SQLException {
        Map<String, List<Edge>> adjacency = loadAdjacencyList();
        if (!adjacency.containsKey(objectName))
            return Map.of("error", "Object not found in graph: " + objectName);

        Set<String> visited = new LinkedHashSet<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Queue<String[]> queue = new ArrayDeque<>();
        queue.add(new String[]{objectName, "0"});
        visited.add(objectName);

        while (!queue.isEmpty()) {
            String[] current = queue.poll();
            String name = current[0];
            int depth = Integer.parseInt(current[1]);
            nodes.add(Map.of("name", name, "depth", depth));
            if (depth >= maxDepth) continue;
            for (Edge edge : adjacency.getOrDefault(name, List.of())) {
                edges.add(Map.of("from", name, "to", edge.target,
                    "relationship", edge.relationshipName,
                    "cardinality", edge.cardinality != null ? edge.cardinality : ""));
                if (!visited.contains(edge.target)) {
                    visited.add(edge.target);
                    queue.add(new String[]{edge.target, String.valueOf(depth + 1)});
                }
            }
        }
        return Map.of("root", objectName, "depth", maxDepth, "nodes", nodes, "edges", edges);
    }

    public Map<String, Object> getPath(String fromObject, String toObject) throws SQLException {
        Map<String, List<Edge>> adjacency = loadAdjacencyList();
        if (!adjacency.containsKey(fromObject))
            return Map.of("error", "Source object not found: " + fromObject);
        if (!adjacency.containsKey(toObject))
            return Map.of("error", "Target object not found: " + toObject);

        Map<String, String[]> parent = new HashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(fromObject);
        parent.put(fromObject, null);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(toObject)) break;
            for (Edge edge : adjacency.getOrDefault(current, List.of())) {
                if (!parent.containsKey(edge.target)) {
                    parent.put(edge.target, new String[]{current, edge.relationshipName, edge.cardinality});
                    queue.add(edge.target);
                }
            }
        }
        if (!parent.containsKey(toObject))
            return Map.of("from", fromObject, "to", toObject, "path", List.of(),
                "message", "No path found between " + fromObject + " and " + toObject);

        List<Map<String, String>> path = new ArrayList<>();
        String current = toObject;
        while (parent.get(current) != null) {
            String[] p = parent.get(current);
            path.add(Map.of("from", p[0], "to", current, "relationship", p[1],
                "cardinality", p[2] != null ? p[2] : ""));
            current = p[0];
        }
        Collections.reverse(path);
        return Map.of("from", fromObject, "to", toObject, "hops", path.size(), "path", path);
    }

    private Map<String, List<Edge>> loadAdjacencyList() throws SQLException {
        return dataSource.withConnection(conn -> {
            Map<String, List<Edge>> adj = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT object_name, relationship_name, cardinality, related_object FROM reladomo_relationships");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    adj.computeIfAbsent(rs.getString("object_name"), k -> new ArrayList<>())
                        .add(new Edge(rs.getString("related_object"), rs.getString("relationship_name"), rs.getString("cardinality")));
                    adj.computeIfAbsent(rs.getString("related_object"), k -> new ArrayList<>());
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT object_name FROM reladomo_objects");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) adj.computeIfAbsent(rs.getString("object_name"), k -> new ArrayList<>());
            }
            return adj;
        });
    }

    // ── Schema DDL (F11) ───────────────────────────────────────────────────

    private static final Map<String, String> TYPE_MAP = Map.ofEntries(
        Map.entry("boolean", "BIT"), Map.entry("int", "INTEGER"), Map.entry("long", "BIGINT"),
        Map.entry("double", "DOUBLE"), Map.entry("float", "REAL"), Map.entry("Timestamp", "TIMESTAMP"),
        Map.entry("Date", "DATE"), Map.entry("BigDecimal", "DECIMAL"), Map.entry("byte[]", "VARBINARY"),
        Map.entry("short", "SMALLINT"), Map.entry("char", "CHAR(1)")
    );

    public Map<String, Object> getSchema(String objectName) throws SQLException {
        return dataSource.withConnection(conn -> {
            String tableName = null, temporalType = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT table_name, temporal_type FROM reladomo_objects WHERE object_name = ?")) {
                ps.setString(1, objectName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Map.of("error", "Object not found: " + objectName);
                    tableName = rs.getString("table_name");
                    temporalType = rs.getString("temporal_type");
                }
            }
            StringBuilder ddl = new StringBuilder();
            ddl.append("CREATE TABLE ").append(tableName != null ? tableName : objectName).append(" (\n");
            List<String> pkCols = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM reladomo_attributes WHERE object_name = ? ORDER BY primary_key DESC, attribute_name")) {
                ps.setString(1, objectName);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) ddl.append(",\n");
                        first = false;
                        String colName = rs.getString("column_name");
                        String javaType = rs.getString("java_type");
                        Integer maxLen = rs.getObject("max_length") != null ? rs.getInt("max_length") : null;
                        boolean nullable = rs.getBoolean("nullable");
                        boolean pk = rs.getBoolean("primary_key");
                        ddl.append("    ").append(colName).append(" ").append(mapType(javaType, maxLen));
                        if (!nullable) ddl.append(" NOT NULL");
                        if (pk) pkCols.add(colName);
                    }
                }
            }
            if ("processing-date".equals(temporalType) || "bitemporal".equals(temporalType)) {
                ddl.append(",\n    IN_Z TIMESTAMP NOT NULL,\n    OUT_Z TIMESTAMP NOT NULL");
            }
            if ("business-date".equals(temporalType) || "bitemporal".equals(temporalType)) {
                ddl.append(",\n    FROM_Z TIMESTAMP NOT NULL,\n    THRU_Z TIMESTAMP NOT NULL");
            }
            if (!pkCols.isEmpty())
                ddl.append(",\n    PRIMARY KEY (").append(String.join(", ", pkCols)).append(")");
            ddl.append("\n);\n");
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM reladomo_indices WHERE object_name = ?")) {
                ps.setString(1, objectName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ddl.append("\nCREATE ").append(rs.getBoolean("is_unique") ? "UNIQUE " : "")
                            .append("INDEX ").append(rs.getString("index_name"))
                            .append(" ON ").append(tableName != null ? tableName : objectName)
                            .append(" (").append(rs.getString("columns")).append(");");
                    }
                }
            }
            return Map.of("object_name", objectName, "table_name", tableName != null ? tableName : "",
                "temporal_type", temporalType != null ? temporalType : "none", "ddl", ddl.toString());
        });
    }

    private String mapType(String javaType, Integer maxLen) {
        if (javaType == null) return "VARCHAR";
        if ("String".equals(javaType)) return "VARCHAR(" + (maxLen != null ? maxLen : 255) + ")";
        return TYPE_MAP.getOrDefault(javaType, "VARCHAR");
    }

    // ── Object files (F12) ─────────────────────────────────────────────────

    public Map<String, Object> getObjectFiles(String objectName) throws SQLException {
        return dataSource.withConnection(conn -> {
            Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT artifact_id, file_name, reladomo_type FROM artifacts WHERE file_name LIKE ? AND reladomo_type != 'none'")) {
                ps.setString(1, "%" + objectName + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        groups.computeIfAbsent(rs.getString("reladomo_type"), k -> new ArrayList<>())
                            .add(Map.of("artifact_id", rs.getString("artifact_id"),
                                        "file_name", rs.getString("file_name")));
                    }
                }
            }
            return Map.of("object_name", objectName, "files", groups);
        });
    }

    // ── Finder patterns (F13) ──────────────────────────────────────────────

    public Map<String, Object> getFinderPatterns(String objectName) throws SQLException {
        return dataSource.withConnection(conn -> {
            List<Map<String, Object>> patterns = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT attribute_or_path, operation, COUNT(*) as freq, " +
                    "ARRAY_AGG(source_file || ':' || line_number) as locations " +
                    "FROM reladomo_finder_usage WHERE object_name = ? " +
                    "GROUP BY attribute_or_path, operation ORDER BY freq DESC")) {
                ps.setString(1, objectName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("attribute_or_path", rs.getString("attribute_or_path"));
                        p.put("operation", rs.getString("operation"));
                        p.put("frequency", rs.getInt("freq"));
                        Object locs = rs.getObject("locations");
                        p.put("locations", locs != null ? locs.toString() : "");
                        patterns.add(p);
                    }
                }
            }
            return Map.of("object_name", objectName, "patterns", patterns);
        });
    }

    // ── Deep fetch profiles (F14) ──────────────────────────────────────────

    public Map<String, Object> getDeepFetchProfiles(String objectName) throws SQLException {
        return dataSource.withConnection(conn -> {
            List<Map<String, Object>> profiles = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT fetch_path, COUNT(*) as freq, " +
                    "ARRAY_AGG(source_file || ':' || line_number) as locations " +
                    "FROM reladomo_deep_fetch WHERE object_name = ? " +
                    "GROUP BY fetch_path ORDER BY freq DESC")) {
                ps.setString(1, objectName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("fetch_path", rs.getString("fetch_path"));
                        p.put("frequency", rs.getInt("freq"));
                        Object locs = rs.getObject("locations");
                        p.put("locations", locs != null ? locs.toString() : "");
                        profiles.add(p);
                    }
                }
            }
            return Map.of("object_name", objectName, "profiles", profiles);
        });
    }

    // ── Temporal info (F15) ────────────────────────────────────────────────

    public Map<String, Object> getTemporalInfo() throws SQLException {
        return dataSource.withConnection(conn -> {
            Map<String, List<String>> groups = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT object_name, temporal_type FROM reladomo_objects ORDER BY temporal_type, object_name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    groups.computeIfAbsent(rs.getString("temporal_type"), k -> new ArrayList<>())
                        .add(rs.getString("object_name"));
            }
            List<Map<String, Object>> classifications = new ArrayList<>();
            for (var entry : groups.entrySet()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("temporal_type", entry.getKey());
                c.put("objects", entry.getValue());
                c.put("count", entry.getValue().size());
                switch (entry.getKey()) {
                    case "bitemporal" -> { c.put("columns", List.of("IN_Z", "OUT_Z", "FROM_Z", "THRU_Z")); c.put("description", "Full bitemporal: tracks both business time and processing time"); }
                    case "business-date" -> { c.put("columns", List.of("FROM_Z", "THRU_Z")); c.put("description", "Business-date temporal: tracks business-effective time ranges"); }
                    case "processing-date" -> { c.put("columns", List.of("IN_Z", "OUT_Z")); c.put("description", "Processing-date temporal: tracks when data was entered/corrected"); }
                    default -> { c.put("columns", List.of()); c.put("description", "Non-temporal: standard CRUD object"); }
                }
                classifications.add(c);
            }
            return Map.of("classifications", classifications,
                "total_objects", groups.values().stream().mapToInt(List::size).sum(),
                "infinity_date", "9999-12-01 23:59:00.000");
        });
    }

    // ── Runtime config (F16) ───────────────────────────────────────────────

    public Map<String, Object> getConfig(String objectName) throws SQLException {
        return dataSource.withConnection(conn -> {
            if (objectName != null && !objectName.isBlank()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("object_name", objectName);
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT oc.*, cm.manager_class, cm.properties FROM reladomo_object_config oc " +
                        "LEFT JOIN reladomo_connection_managers cm ON oc.connection_manager = cm.manager_name AND oc.config_file = cm.config_file " +
                        "WHERE oc.object_name = ?")) {
                    ps.setString(1, objectName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            result.put("connection_manager", rs.getString("connection_manager"));
                            result.put("manager_class", rs.getString("manager_class"));
                            result.put("cache_type", rs.getString("cache_type"));
                            result.put("load_cache_on_startup", rs.getBoolean("load_cache_on_startup"));
                            result.put("config_file", rs.getString("config_file"));
                            result.put("properties", rs.getString("properties"));
                        } else {
                            result.put("message", "No runtime config found for: " + objectName);
                        }
                    }
                }
                return result;
            }
            List<Map<String, Object>> managers = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM reladomo_connection_managers");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    managers.add(Map.of("name", rs.getString("manager_name"),
                        "class", rs.getString("manager_class") != null ? rs.getString("manager_class") : "",
                        "config_file", rs.getString("config_file")));
            }
            List<Map<String, Object>> objects = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM reladomo_object_config ORDER BY object_name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    objects.add(Map.of("object_name", rs.getString("object_name"),
                        "connection_manager", rs.getString("connection_manager"),
                        "cache_type", rs.getString("cache_type") != null ? rs.getString("cache_type") : "partial",
                        "load_cache_on_startup", rs.getBoolean("load_cache_on_startup")));
            }
            return Map.of("connection_managers", managers, "object_configs", objects);
        });
    }
}
