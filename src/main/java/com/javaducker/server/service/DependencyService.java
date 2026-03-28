package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DependencyService {

    private final DuckDBDataSource dataSource;

    public DependencyService(DuckDBDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<Map<String, String>> getDependencies(String artifactId) throws SQLException {
        return dataSource.withConnection(conn -> {
            List<Map<String, String>> results = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT artifact_id, import_statement, resolved_artifact_id FROM artifact_imports WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> row = new LinkedHashMap<>();
                        row.put("artifact_id", rs.getString("artifact_id"));
                        row.put("import_statement", rs.getString("import_statement"));
                        row.put("resolved_artifact_id", rs.getString("resolved_artifact_id"));
                        results.add(row);
                    }
                }
            }
            return results;
        });
    }

    public List<Map<String, String>> getDependents(String artifactId) throws SQLException {
        return dataSource.withConnection(conn -> {
            List<Map<String, String>> results = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    """
                    SELECT ai.artifact_id, ai.import_statement, ai.resolved_artifact_id, a.file_name
                    FROM artifact_imports ai
                    JOIN artifacts a ON a.artifact_id = ai.artifact_id
                    WHERE ai.resolved_artifact_id = ?
                    """)) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> row = new LinkedHashMap<>();
                        row.put("artifact_id", rs.getString("artifact_id"));
                        row.put("import_statement", rs.getString("import_statement"));
                        row.put("resolved_artifact_id", rs.getString("resolved_artifact_id"));
                        row.put("file_name", rs.getString("file_name"));
                        results.add(row);
                    }
                }
            }
            return results;
        });
    }
}
