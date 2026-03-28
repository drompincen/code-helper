package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Service
public class ProjectMapService {

    private final DuckDBDataSource dataSource;

    public ProjectMapService(DuckDBDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Map<String, Object> getProjectMap() throws SQLException {
        return dataSource.withConnection(conn -> {
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, List<String>> dirFiles = new LinkedHashMap<>();
            long totalFiles = 0;
            long totalBytes = 0;
            List<Map<String, Object>> largest = new ArrayList<>();
            List<Map<String, Object>> recent = new ArrayList<>();

            String sql = "SELECT original_client_path, file_name, size_bytes, indexed_at "
                    + "FROM artifacts "
                    + "WHERE status = 'INDEXED' AND original_client_path IS NOT NULL "
                    + "ORDER BY size_bytes DESC";

            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString("original_client_path");
                    String fileName = rs.getString("file_name");
                    long size = rs.getLong("size_bytes");
                    String indexedAt = rs.getString("indexed_at");

                    totalFiles++;
                    totalBytes += size;

                    String dir = extractParentDir(path);
                    dirFiles.computeIfAbsent(dir, k -> new ArrayList<>()).add(fileName);

                    if (largest.size() < 5) {
                        largest.add(fileEntry(fileName, size, path));
                    }

                    recent.add(recentEntry(fileName, indexedAt, path));
                }
            }

            // Sort recent by indexed_at descending, keep top 5
            recent.sort((a, b) -> String.valueOf(b.get("indexed_at"))
                    .compareTo(String.valueOf(a.get("indexed_at"))));
            if (recent.size() > 5) {
                recent = recent.subList(0, 5);
            }

            // Build directory list sorted by file_count desc
            List<Map<String, Object>> directories = new ArrayList<>();
            for (var entry : dirFiles.entrySet()) {
                Map<String, Object> dirEntry = new LinkedHashMap<>();
                dirEntry.put("path", entry.getKey());
                dirEntry.put("file_count", entry.getValue().size());
                dirEntry.put("files", entry.getValue());
                directories.add(dirEntry);
            }
            directories.sort((a, b) -> Integer.compare(
                    (int) b.get("file_count"), (int) a.get("file_count")));

            result.put("total_files", totalFiles);
            result.put("total_bytes", totalBytes);
            result.put("directories", directories);
            result.put("largest_files", largest);
            result.put("recently_indexed", recent);
            return result;
        });
    }

    private static String extractParentDir(String path) {
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash > 0 ? path.substring(0, lastSlash) : ".";
    }

    private static Map<String, Object> fileEntry(String name, long size, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file_name", name);
        m.put("size_bytes", size);
        m.put("original_client_path", path);
        return m;
    }

    private static Map<String, Object> recentEntry(String name, String indexedAt, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file_name", name);
        m.put("indexed_at", indexedAt);
        m.put("original_client_path", path);
        return m;
    }
}
