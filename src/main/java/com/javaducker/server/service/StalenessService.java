package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class StalenessService {

    private final DuckDBDataSource dataSource;

    public StalenessService(DuckDBDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Map<String, Object> checkStaleness(List<String> filePaths) throws SQLException {
        List<Map<String, Object>> stale = new ArrayList<>();
        int current = 0;
        List<String> notIndexed = new ArrayList<>();

        for (String path : filePaths) {
            if (path == null || path.isBlank()) continue;

            Path filePath = Path.of(path);
            boolean fileExists = Files.exists(filePath);

            List<Map<String, Object>> artifacts = queryArtifacts(path);
            if (artifacts.isEmpty()) {
                notIndexed.add(path);
                continue;
            }

            if (!fileExists) {
                notIndexed.add(path);
                continue;
            }

            Instant fileMtime;
            try {
                fileMtime = Files.getLastModifiedTime(filePath).toInstant();
            } catch (IOException e) {
                notIndexed.add(path);
                continue;
            }

            for (Map<String, Object> artifact : artifacts) {
                Instant indexedAt = (Instant) artifact.get("indexed_at");
                if (indexedAt == null || fileMtime.isAfter(indexedAt)) {
                    artifact.put("file_modified_at", fileMtime.toString());
                    stale.add(artifact);
                } else {
                    current++;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stale", stale);
        result.put("current", current);
        result.put("not_indexed", notIndexed);
        result.put("total_checked", filePaths.stream().filter(p -> p != null && !p.isBlank()).count());
        return result;
    }

    private List<Map<String, Object>> queryArtifacts(String path) throws SQLException {
        String sql = "SELECT artifact_id, file_name, original_client_path, indexed_at "
                + "FROM artifacts WHERE original_client_path = ? AND status = 'INDEXED'";

        return dataSource.withConnection(conn -> {
            List<Map<String, Object>> results = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, path);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("artifact_id", rs.getString("artifact_id"));
                        row.put("file_name", rs.getString("file_name"));
                        row.put("original_client_path", rs.getString("original_client_path"));
                        Timestamp ts = rs.getTimestamp("indexed_at");
                        row.put("indexed_at", ts != null ? ts.toInstant() : null);
                        results.add(row);
                    }
                }
            }
            return results;
        });
    }
}
