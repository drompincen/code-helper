package com.javaducker.server.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaducker.server.service.*;
import com.javaducker.server.service.GitBlameService.BlameEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class AnalysisTools {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTools.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ExplainService explainService;
    private final GitBlameService gitBlameService;
    private final CoChangeService coChangeService;
    private final DependencyService dependencyService;
    private final ProjectMapService projectMapService;
    private final StalenessService stalenessService;
    private final ArtifactService artifactService;
    private final SemanticTagService semanticTagService;
    private final KnowledgeGraphService knowledgeGraphService;

    public AnalysisTools(ExplainService explainService,
                         GitBlameService gitBlameService,
                         CoChangeService coChangeService,
                         DependencyService dependencyService,
                         ProjectMapService projectMapService,
                         StalenessService stalenessService,
                         ArtifactService artifactService,
                         SemanticTagService semanticTagService,
                         KnowledgeGraphService knowledgeGraphService) {
        this.explainService = explainService;
        this.gitBlameService = gitBlameService;
        this.coChangeService = coChangeService;
        this.dependencyService = dependencyService;
        this.projectMapService = projectMapService;
        this.stalenessService = stalenessService;
        this.artifactService = artifactService;
        this.semanticTagService = semanticTagService;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    @Tool(name = "javaducker_explain",
            description = "Aggregate everything JavaDucker knows about a file: summary, dependencies, classification, tags, and more")
    public Map<String, Object> explain(
            @ToolParam(description = "File path relative to PROJECT_ROOT", required = true) String file_path) {
        try {
            Map<String, Object> result = explainService.explainByPath(file_path);
            return result != null ? result : Map.of("error", "File not found in index: " + file_path);
        } catch (Exception e) {
            log.error("explain failed for: {}", file_path, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_blame",
            description = "Run git blame on a file, optionally for a specific line range. Returns commit metadata per line group.")
    public Map<String, Object> blame(
            @ToolParam(description = "File path relative to PROJECT_ROOT", required = true) String file_path,
            @ToolParam(description = "Start line number (optional, requires end_line)", required = false) Integer start_line,
            @ToolParam(description = "End line number (optional, requires start_line)", required = false) Integer end_line) {
        try {
            List<BlameEntry> entries;
            if (start_line != null && end_line != null) {
                entries = gitBlameService.blameForLines(file_path, start_line, end_line);
            } else {
                entries = gitBlameService.blame(file_path);
            }

            List<Map<String, Object>> converted = entries.stream()
                    .map(AnalysisTools::blameEntryToMap)
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("file_path", file_path);
            result.put("entry_count", converted.size());
            result.put("entries", converted);
            return result;
        } catch (Exception e) {
            log.error("blame failed for: {}", file_path, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_related",
            description = "Find files that frequently change together with the given file (co-change analysis)")
    public Map<String, Object> related(
            @ToolParam(description = "File path relative to PROJECT_ROOT", required = true) String file_path,
            @ToolParam(description = "Maximum number of related files to return (default: 10)", required = false) Integer max_results) {
        try {
            int effectiveMax = (max_results == null || max_results <= 0) ? 10 : max_results;
            List<Map<String, Object>> related = coChangeService.getRelatedFiles(file_path, effectiveMax);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("file_path", file_path);
            result.put("count", related.size());
            result.put("related_files", related);
            return result;
        } catch (Exception e) {
            log.error("related failed for: {}", file_path, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_dependencies",
            description = "List artifacts that the given artifact depends on")
    public Map<String, Object> dependencies(
            @ToolParam(description = "The artifact ID to query dependencies for", required = true) String artifact_id) {
        try {
            List<Map<String, String>> deps = dependencyService.getDependencies(artifact_id);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("artifact_id", artifact_id);
            result.put("count", deps.size());
            result.put("dependencies", deps);
            return result;
        } catch (Exception e) {
            log.error("dependencies failed for: {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_dependents",
            description = "List artifacts that depend on the given artifact")
    public Map<String, Object> dependents(
            @ToolParam(description = "The artifact ID to query dependents for", required = true) String artifact_id) {
        try {
            List<Map<String, String>> deps = dependencyService.getDependents(artifact_id);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("artifact_id", artifact_id);
            result.put("count", deps.size());
            result.put("dependents", deps);
            return result;
        } catch (Exception e) {
            log.error("dependents failed for: {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_map",
            description = "Return a high-level map of all indexed artifacts and their relationships")
    public Map<String, Object> map() {
        try {
            return projectMapService.getProjectMap();
        } catch (Exception e) {
            log.error("map failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_stale",
            description = "Check which files have changed on disk since they were last indexed")
    public Map<String, Object> stale(
            @ToolParam(description = "JSON array of file paths to check, e.g. [\"src/Foo.java\",\"src/Bar.java\"]", required = false) String file_paths,
            @ToolParam(description = "Git ref to diff against (e.g. HEAD~3, main). Files from git diff --name-only will be checked.", required = false) String git_diff_ref) {
        try {
            List<String> paths = resolveFilePaths(file_paths, git_diff_ref);
            if (paths.isEmpty()) {
                return Map.of("error", "Provide file_paths (JSON array) or git_diff_ref to identify files to check");
            }
            return stalenessService.checkStaleness(paths);
        } catch (Exception e) {
            log.error("stale check failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_index_health",
            description = "Check overall index freshness: how many indexed artifacts are stale vs current")
    public Map<String, Object> indexHealth() {
        try {
            Map<String, Object> result = stalenessService.checkAll();

            int staleCount = result.containsKey("stale_count")
                    ? ((Number) result.get("stale_count")).intValue() : 0;
            long totalChecked = result.containsKey("total_checked")
                    ? ((Number) result.get("total_checked")).longValue() : 0;
            double stalePercentage = result.containsKey("stale_percentage")
                    ? ((Number) result.get("stale_percentage")).doubleValue() : 0.0;

            String healthStatus;
            String recommendation;
            if (staleCount == 0) {
                healthStatus = "healthy";
                recommendation = "All indexed files are up to date.";
            } else if (stalePercentage <= 20.0) {
                healthStatus = "degraded";
                recommendation = staleCount + " of " + totalChecked
                        + " files are stale. Consider re-indexing the stale files.";
            } else {
                healthStatus = "unhealthy";
                recommendation = staleCount + " of " + totalChecked
                        + " files are stale (" + String.format("%.0f%%", stalePercentage)
                        + "). A full re-index is recommended.";
            }
            result.put("health_status", healthStatus);
            result.put("recommendation", recommendation);
            return result;
        } catch (Exception e) {
            log.error("index health check failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_summarize",
            description = "Get the summary of an indexed artifact, with a staleness warning if the file changed on disk")
    public Map<String, Object> summarize(
            @ToolParam(description = "The artifact ID to summarize", required = true) String artifact_id) {
        try {
            Map<String, Object> summary = artifactService.getSummary(artifact_id);
            if (summary == null) {
                return Map.of("error", "No summary found for artifact: " + artifact_id);
            }

            Map<String, Object> result = new LinkedHashMap<>(summary);

            // Check staleness via artifact status path
            try {
                Map<String, String> status = artifactService.getStatus(artifact_id);
                if (status != null) {
                    String clientPath = status.get("original_client_path");
                    if (clientPath == null) {
                        clientPath = status.get("file_name");
                    }
                    if (clientPath != null) {
                        Map<String, Object> staleness = stalenessService.checkStaleness(List.of(clientPath));
                        Object staleList = staleness.get("stale");
                        if (staleList instanceof List<?> list && !list.isEmpty()) {
                            result.put("staleness_warning",
                                    "This file has changed on disk since it was last indexed. Re-index for accurate results.");
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Staleness check failed for summarize, skipping warning", e);
            }

            return result;
        } catch (Exception e) {
            log.error("summarize failed for: {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_find_related",
            description = "Find files related to a given artifact via: shared semantic tags, " +
                    "entity co-occurrence in the knowledge graph, git co-change history. " +
                    "Returns a unified ranked list with relationship explanations")
    public Map<String, Object> findRelated(
            @ToolParam(description = "Artifact ID to find related files for", required = true) String artifact_id,
            @ToolParam(description = "Max results (default 10)", required = false) Integer max_results) {
        try {
            int effectiveMax = (max_results == null || max_results <= 0) ? 10 : max_results;
            Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

            // 1. Shared semantic tags
            try {
                List<Map<String, Object>> myTags = semanticTagService.getTagsForArtifact(artifact_id);
                List<String> tagNames = myTags.stream()
                        .map(t -> (String) t.get("tag"))
                        .filter(java.util.Objects::nonNull)
                        .toList();
                if (!tagNames.isEmpty()) {
                    List<Map<String, Object>> tagMatches = semanticTagService.searchByTags(tagNames, false);
                    for (Map<String, Object> match : tagMatches) {
                        String matchId = (String) match.get("artifact_id");
                        if (matchId.equals(artifact_id)) continue;
                        int sharedCount = ((Number) match.get("match_count")).intValue();
                        Map<String, Object> entry = merged.computeIfAbsent(matchId, k -> newRelatedEntry(matchId, match));
                        double current = ((Number) entry.get("score")).doubleValue();
                        entry.put("score", current + sharedCount);
                        addReason(entry, "shares " + sharedCount + " semantic tag" + (sharedCount > 1 ? "s" : ""));
                    }
                }
            } catch (Exception e) {
                log.warn("findRelated: semantic tag lookup failed for {}: {}", artifact_id, e.getMessage());
            }

            // 2. Shared entities from knowledge graph
            try {
                List<Map<String, Object>> myEntities = knowledgeGraphService.getEntitiesForArtifact(artifact_id);
                for (Map<String, Object> entity : myEntities) {
                    String entityId = (String) entity.get("entity_id");
                    String entityName = (String) entity.get("entity_name");
                    // Find other artifacts sharing this entity by checking source_artifact_ids
                    Map<String, Object> fullEntity = knowledgeGraphService.getEntity(entityId);
                    if (fullEntity == null) continue;
                    String sourceIds = (String) fullEntity.get("source_artifact_ids");
                    if (sourceIds == null) continue;
                    for (String otherId : parseJsonArray(sourceIds)) {
                        if (otherId.equals(artifact_id)) continue;
                        Map<String, Object> entry = merged.computeIfAbsent(otherId, k -> {
                            Map<String, Object> e2 = new LinkedHashMap<>();
                            e2.put("artifact_id", otherId);
                            e2.put("score", 0.0);
                            e2.put("reasons", new ArrayList<String>());
                            return e2;
                        });
                        double current = ((Number) entry.get("score")).doubleValue();
                        entry.put("score", current + 1);
                        addReason(entry, "shares entity " + entityName);
                    }
                }
            } catch (Exception e) {
                log.warn("findRelated: entity lookup failed for {}: {}", artifact_id, e.getMessage());
            }

            // 3. Co-change history
            try {
                Map<String, String> status = artifactService.getStatus(artifact_id);
                String filePath = status != null ? status.get("original_client_path") : null;
                if (filePath == null && status != null) filePath = status.get("file_name");
                if (filePath != null) {
                    List<Map<String, Object>> coChanges = coChangeService.getRelatedFiles(filePath, effectiveMax);
                    for (Map<String, Object> cc : coChanges) {
                        int count = ((Number) cc.get("co_change_count")).intValue();
                        String relatedFile = (String) cc.get("related_file");
                        // Use file path as key since we don't have artifact_id for co-changes
                        Map<String, Object> entry = merged.computeIfAbsent("cochange:" + relatedFile, k -> {
                            Map<String, Object> e2 = new LinkedHashMap<>();
                            e2.put("artifact_id", relatedFile);
                            e2.put("file_name", relatedFile);
                            e2.put("score", 0.0);
                            e2.put("reasons", new ArrayList<String>());
                            return e2;
                        });
                        double current = ((Number) entry.get("score")).doubleValue();
                        entry.put("score", current + count);
                        addReason(entry, "co-changed " + count + " time" + (count > 1 ? "s" : ""));
                    }
                }
            } catch (Exception e) {
                log.warn("findRelated: co-change lookup failed for {}: {}", artifact_id, e.getMessage());
            }

            // Sort by score descending and limit
            List<Map<String, Object>> ranked = new ArrayList<>(merged.values());
            ranked.sort((a, b) -> Double.compare(
                    ((Number) b.get("score")).doubleValue(),
                    ((Number) a.get("score")).doubleValue()));
            if (ranked.size() > effectiveMax) {
                ranked = ranked.subList(0, effectiveMax);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("artifact_id", artifact_id);
            result.put("count", ranked.size());
            result.put("related", ranked);
            return result;
        } catch (Exception e) {
            log.error("findRelated failed for: {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private Map<String, Object> newRelatedEntry(String artifactId, Map<String, Object> match) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("artifact_id", artifactId);
        if (match.containsKey("file_name")) {
            entry.put("file_name", match.get("file_name"));
        }
        entry.put("score", 0.0);
        entry.put("reasons", new ArrayList<String>());
        return entry;
    }

    @SuppressWarnings("unchecked")
    private void addReason(Map<String, Object> entry, String reason) {
        List<String> reasons = (List<String>) entry.get("reasons");
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
    }

    static List<String> parseJsonArray(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) return List.of();
        String stripped = jsonArray.trim();
        if (stripped.equals("[]")) return List.of();
        stripped = stripped.substring(1, stripped.length() - 1);
        List<String> result = new ArrayList<>();
        for (String token : stripped.split(",")) {
            String val = token.trim().replace("\"", "");
            if (!val.isEmpty()) result.add(val);
        }
        return result;
    }

    static Map<String, Object> blameEntryToMap(BlameEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("lineStart", entry.lineStart());
        map.put("lineEnd", entry.lineEnd());
        map.put("commitHash", entry.commitHash());
        map.put("author", entry.author());
        map.put("authorDate", entry.authorDate() != null ? entry.authorDate().toString() : null);
        map.put("commitMessage", entry.commitMessage());
        map.put("content", entry.content());
        return map;
    }

    List<String> resolveFilePaths(String filePathsJson, String gitDiffRef) {
        List<String> paths = new ArrayList<>();

        // Parse JSON array if provided
        if (filePathsJson != null && !filePathsJson.isBlank()) {
            try {
                List<String> parsed = objectMapper.readValue(filePathsJson, new TypeReference<>() {});
                paths.addAll(parsed);
            } catch (Exception e) {
                log.warn("Failed to parse file_paths JSON: {}", e.getMessage());
            }
        }

        // Resolve git diff if provided
        if (gitDiffRef != null && !gitDiffRef.isBlank()) {
            try {
                List<String> gitFiles = runGitDiff(gitDiffRef);
                paths.addAll(gitFiles);
            } catch (Exception e) {
                log.warn("Failed to run git diff for ref {}: {}", gitDiffRef, e.getMessage());
            }
        }

        return paths;
    }

    List<String> runGitDiff(String ref) throws Exception {
        String root = System.getenv("PROJECT_ROOT");
        File projectRoot = new File(root != null ? root : ".").getAbsoluteFile();

        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only", ref);
        pb.directory(projectRoot);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        List<String> files;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            files = reader.lines()
                    .filter(line -> !line.isBlank())
                    .collect(Collectors.toList());
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("git diff failed (exit " + exitCode + ")");
        }
        return files;
    }
}
