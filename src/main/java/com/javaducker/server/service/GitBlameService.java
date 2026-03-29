package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Service
public class GitBlameService {

    private static final Logger log = LoggerFactory.getLogger(GitBlameService.class);

    public record BlameEntry(
            int lineStart,
            int lineEnd,
            String commitHash,
            String author,
            Instant authorDate,
            String commitMessage,
            String content
    ) {}

    private final DuckDBDataSource dataSource;
    private final File projectRoot;
    private final LinkedHashMap<String, List<BlameEntry>> cache;

    public GitBlameService(DuckDBDataSource dataSource) {
        this.dataSource = dataSource;
        String root = System.getenv("PROJECT_ROOT");
        this.projectRoot = new File(root != null ? root : ".").getAbsoluteFile();
        this.cache = new LinkedHashMap<>(50, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<BlameEntry>> eldest) {
                return size() > 50;
            }
        };
    }

    public List<BlameEntry> blame(String filePath) throws IOException, InterruptedException {
        validatePath(filePath);
        String absPath = projectRoot.toPath().resolve(filePath).toAbsolutePath().normalize().toString();
        List<BlameEntry> cached = cache.get(absPath);
        if (cached != null) {
            return cached;
        }
        List<String> cmd = List.of("git", "blame", "--porcelain", filePath);
        String output = runGitCommand(cmd);
        List<BlameEntry> entries = parseBlameOutput(output);
        cache.put(absPath, entries);
        return entries;
    }

    public List<BlameEntry> blameForLines(String filePath, int startLine, int endLine) throws IOException, InterruptedException {
        validatePath(filePath);
        List<String> cmd = List.of("git", "blame", "--porcelain", "-L", startLine + "," + endLine, filePath);
        String output = runGitCommand(cmd);
        return parseBlameOutput(output);
    }

    public List<BlameEntry> blameForArtifact(String artifactId) throws SQLException, IOException, InterruptedException {
        String clientPath = dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT original_client_path FROM artifacts WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("original_client_path");
                    }
                }
            }
            return null;
        });
        if (clientPath == null) {
            throw new IllegalArgumentException("Artifact not found: " + artifactId);
        }
        return blame(clientPath);
    }

    private void validatePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("File path must not be null or blank");
        }
        Path resolved = projectRoot.toPath().resolve(filePath).normalize();
        if (!resolved.startsWith(projectRoot.toPath())) {
            throw new IllegalArgumentException("File path is outside PROJECT_ROOT: " + filePath);
        }
    }

    private String runGitCommand(List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectRoot);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().reduce("", (a, b) -> a + "\n" + b);
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git blame failed (exit " + exitCode + "): " + output.trim());
        }
        return output;
    }

    List<BlameEntry> parseBlameOutput(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<BlameEntry> entries = new ArrayList<>();
        String[] lines = output.split("\n");

        String currentHash = null;
        int currentLine = -1;
        Map<String, String> authors = new HashMap<>();
        Map<String, String> authorTimes = new HashMap<>();
        Map<String, String> summaries = new HashMap<>();
        StringBuilder contentBuilder = new StringBuilder();

        // Track for grouping consecutive lines by same commit
        String prevHash = null;
        int rangeStart = -1;
        int rangeEnd = -1;
        String rangeContent = null;

        for (String line : lines) {
            if (line.isEmpty()) continue;

            if (line.length() >= 40 && line.substring(0, 40).matches("[0-9a-f]{40}")) {
                // Commit header line: <hash> <orig_line> <final_line> [<num_lines>]
                String[] parts = line.split("\\s+");
                currentHash = parts[0];
                currentLine = Integer.parseInt(parts[2]);
            } else if (line.startsWith("author ")) {
                authors.putIfAbsent(currentHash, line.substring(7));
            } else if (line.startsWith("author-time ")) {
                authorTimes.putIfAbsent(currentHash, line.substring(12));
            } else if (line.startsWith("summary ")) {
                summaries.putIfAbsent(currentHash, line.substring(8));
            } else if (line.startsWith("\t")) {
                // Content line — this marks the end of one blame block
                String contentLine = line.substring(1);

                if (prevHash != null && prevHash.equals(currentHash) && currentLine == rangeEnd + 1) {
                    // Extend the current range
                    rangeEnd = currentLine;
                    rangeContent = rangeContent + "\n" + contentLine;
                } else {
                    // Flush previous range if any
                    if (prevHash != null) {
                        entries.add(buildEntry(prevHash, rangeStart, rangeEnd, rangeContent,
                                authors, authorTimes, summaries));
                    }
                    rangeStart = currentLine;
                    rangeEnd = currentLine;
                    rangeContent = contentLine;
                }
                prevHash = currentHash;
            }
        }
        // Flush last range
        if (prevHash != null) {
            entries.add(buildEntry(prevHash, rangeStart, rangeEnd, rangeContent,
                    authors, authorTimes, summaries));
        }
        return entries;
    }

    private BlameEntry buildEntry(String hash, int start, int end, String content,
                                  Map<String, String> authors, Map<String, String> authorTimes,
                                  Map<String, String> summaries) {
        Instant date = null;
        String timeStr = authorTimes.get(hash);
        if (timeStr != null) {
            try {
                date = Instant.ofEpochSecond(Long.parseLong(timeStr.trim()));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse author-time for {}: {}", hash, timeStr);
            }
        }
        return new BlameEntry(
                start, end, hash,
                authors.getOrDefault(hash, "unknown"),
                date,
                summaries.getOrDefault(hash, ""),
                content
        );
    }
}
