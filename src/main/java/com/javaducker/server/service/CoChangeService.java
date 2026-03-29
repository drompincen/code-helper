package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CoChangeService {

    private static final Logger log = LoggerFactory.getLogger(CoChangeService.class);
    private static final int MAX_FILES_PER_COMMIT = 30;
    private static final double FREQUENT_FILE_THRESHOLD = 0.50;

    private final DuckDBDataSource dataSource;
    private final Path projectRoot;

    public CoChangeService(DuckDBDataSource dataSource) {
        this.dataSource = dataSource;
        String root = System.getenv("PROJECT_ROOT");
        this.projectRoot = Path.of(root != null ? root : ".");
    }

    /**
     * Rebuild the co-change index from git history. Idempotent: deletes all existing data first.
     */
    public void buildCoChangeIndex() throws Exception {
        String gitOutput = runGitLog();
        buildCoChangeIndexFromOutput(gitOutput);
    }

    /**
     * Build co-change index from pre-parsed git log output. Package-private for testing.
     */
    void buildCoChangeIndexFromOutput(String gitOutput) throws Exception {
        Map<String, List<String>> commits = parseGitLog(gitOutput);
        Map<String, List<String>> filtered = filterNoisyCommits(commits);
        Set<String> frequentFiles = findFrequentFiles(filtered);
        Map<String, Map<String, Integer>> coChanges = computeCoChanges(filtered, frequentFiles);

        dataSource.withConnection(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM cochange_cache");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO cochange_cache (file_a, file_b, co_change_count, last_commit_date) VALUES (?, ?, ?, ?)")) {
                for (Map.Entry<String, Map<String, Integer>> outer : coChanges.entrySet()) {
                    String fileA = outer.getKey();
                    for (Map.Entry<String, Integer> inner : outer.getValue().entrySet()) {
                        String fileB = inner.getKey();
                        int count = inner.getValue();
                        ps.setString(1, fileA);
                        ps.setString(2, fileB);
                        ps.setInt(3, count);
                        ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }

            log.info("Co-change index rebuilt: {} file pairs", coChanges.values().stream()
                    .mapToInt(Map::size).sum());
            return null;
        });
    }

    /**
     * Query co-change cache for files related to the given file path.
     */
    public List<Map<String, Object>> getRelatedFiles(String filePath, int maxResults) throws SQLException {
        return dataSource.withConnection(conn -> {
            List<Map<String, Object>> results = new ArrayList<>();
            String sql = """
                SELECT file_b AS related_file, co_change_count, last_commit_date
                FROM cochange_cache WHERE file_a = ?
                UNION
                SELECT file_a AS related_file, co_change_count, last_commit_date
                FROM cochange_cache WHERE file_b = ?
                ORDER BY co_change_count DESC LIMIT ?
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, filePath);
                ps.setString(2, filePath);
                ps.setInt(3, maxResults);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("related_file", rs.getString("related_file"));
                        row.put("co_change_count", rs.getInt("co_change_count"));
                        row.put("last_commit_date", rs.getTimestamp("last_commit_date"));
                        results.add(row);
                    }
                }
            }
            return results;
        });
    }

    private String runGitLog() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "git", "log", "--name-only", "--pretty=format:COMMIT:%H", "--since=6 months ago");
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.warn("git log exited with code {}: {}", exitCode, output);
        }
        return output;
    }

    /**
     * Parse git log output into a map of commitHash to list of file paths.
     * Package-private for testing.
     */
    Map<String, List<String>> parseGitLog(String output) {
        Map<String, List<String>> commits = new LinkedHashMap<>();
        if (output == null || output.isBlank()) {
            return commits;
        }

        String currentCommit = null;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("COMMIT:")) {
                currentCommit = trimmed.substring("COMMIT:".length());
                commits.put(currentCommit, new ArrayList<>());
            } else if (!trimmed.isEmpty() && currentCommit != null) {
                commits.get(currentCommit).add(trimmed);
            }
        }
        return commits;
    }

    /**
     * Filter out commits with more than MAX_FILES_PER_COMMIT files.
     * Package-private for testing.
     */
    Map<String, List<String>> filterNoisyCommits(Map<String, List<String>> commits) {
        Map<String, List<String>> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : commits.entrySet()) {
            if (entry.getValue().size() <= MAX_FILES_PER_COMMIT) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    /**
     * Find files that appear in more than 50% of all commits (noisy files).
     */
    Set<String> findFrequentFiles(Map<String, List<String>> commits) {
        if (commits.isEmpty()) {
            return Collections.emptySet();
        }
        Map<String, Integer> fileCounts = new HashMap<>();
        for (List<String> files : commits.values()) {
            for (String file : files) {
                fileCounts.merge(file, 1, Integer::sum);
            }
        }
        int threshold = (int) (commits.size() * FREQUENT_FILE_THRESHOLD);
        Set<String> frequent = new HashSet<>();
        for (Map.Entry<String, Integer> entry : fileCounts.entrySet()) {
            if (entry.getValue() > threshold) {
                frequent.add(entry.getKey());
            }
        }
        return frequent;
    }

    /**
     * Compute co-change counts for all file pairs. Only stores the pair once (fileA < fileB).
     * Package-private for testing.
     */
    Map<String, Map<String, Integer>> computeCoChanges(Map<String, List<String>> commits) {
        return computeCoChanges(commits, Collections.emptySet());
    }

    Map<String, Map<String, Integer>> computeCoChanges(Map<String, List<String>> commits, Set<String> excludeFiles) {
        Map<String, Map<String, Integer>> coChanges = new HashMap<>();

        for (List<String> files : commits.values()) {
            List<String> filtered = files.stream()
                    .filter(f -> !excludeFiles.contains(f))
                    .sorted()
                    .distinct()
                    .toList();

            for (int i = 0; i < filtered.size(); i++) {
                for (int j = i + 1; j < filtered.size(); j++) {
                    String a = filtered.get(i);
                    String b = filtered.get(j);
                    coChanges.computeIfAbsent(a, k -> new HashMap<>())
                            .merge(b, 1, Integer::sum);
                }
            }
        }
        return coChanges;
    }
}
