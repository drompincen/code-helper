package com.javaducker.server.mcp;

import com.javaducker.server.service.ReladomoQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ReladomoTools {

    private static final Logger log = LoggerFactory.getLogger(ReladomoTools.class);

    private final ReladomoQueryService reladomoQueryService;

    public ReladomoTools(ReladomoQueryService reladomoQueryService) {
        this.reladomoQueryService = reladomoQueryService;
    }

    @Tool(name = "javaducker_reladomo_relationships",
            description = "Get relationships for a Reladomo object including related objects and cardinality")
    public Map<String, Object> relationships(
            @ToolParam(description = "Reladomo object name", required = true) String object_name) {
        try {
            return reladomoQueryService.getRelationships(object_name);
        } catch (Exception e) {
            log.error("Failed to get relationships for: {}", object_name, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_reladomo_graph",
            description = "Get the relationship graph for a Reladomo object up to a specified depth")
    public Map<String, Object> graph(
            @ToolParam(description = "Reladomo object name", required = true) String object_name,
            @ToolParam(description = "Maximum traversal depth (default: 3)", required = false) Integer depth) {
        try {
            int effectiveDepth = (depth == null || depth <= 0) ? 3 : depth;
            return reladomoQueryService.getGraph(object_name, effectiveDepth);
        } catch (Exception e) {
            log.error("Failed to get graph for: {}", object_name, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_reladomo_path",
            description = "Find the relationship path between two Reladomo objects")
    public Map<String, Object> path(
            @ToolParam(description = "Source Reladomo object name", required = true) String from_object,
            @ToolParam(description = "Target Reladomo object name", required = true) String to_object) {
        try {
            return reladomoQueryService.getPath(from_object, to_object);
        } catch (Exception e) {
            log.error("Failed to find path from {} to {}", from_object, to_object, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_reladomo_schema",
            description = "Get the database schema (columns, types, keys) for a Reladomo object")
    public Map<String, Object> schema(
            @ToolParam(description = "Reladomo object name", required = true) String object_name) {
        try {
            return reladomoQueryService.getSchema(object_name);
        } catch (Exception e) {
            log.error("Failed to get schema for: {}", object_name, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_reladomo_object_files",
            description = "Get the source files associated with a Reladomo object (XML config, generated classes)")
    public Map<String, Object> objectFiles(
            @ToolParam(description = "Reladomo object name", required = true) String object_name) {
        try {
            return reladomoQueryService.getObjectFiles(object_name);
        } catch (Exception e) {
            log.error("Failed to get object files for: {}", object_name, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_reladomo_finders",
            description = "Get common Finder patterns and usage examples for a Reladomo object")
    public Map<String, Object> finders(
            @ToolParam(description = "Reladomo object name", required = true) String object_name) {
        try {
            return reladomoQueryService.getFinderPatterns(object_name);
        } catch (Exception e) {
            log.error("Failed to get finder patterns for: {}", object_name, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_reladomo_deepfetch",
            description = "Get deep-fetch profiles for a Reladomo object to optimize batch loading")
    public Map<String, Object> deepFetch(
            @ToolParam(description = "Reladomo object name", required = true) String object_name) {
        try {
            return reladomoQueryService.getDeepFetchProfiles(object_name);
        } catch (Exception e) {
            log.error("Failed to get deep-fetch profiles for: {}", object_name, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_reladomo_temporal",
            description = "Get temporal configuration info for all indexed Reladomo objects")
    public Map<String, Object> temporal() {
        try {
            return reladomoQueryService.getTemporalInfo();
        } catch (Exception e) {
            log.error("Failed to get temporal info", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_reladomo_config",
            description = "Get Reladomo runtime configuration for a specific object or all objects")
    public Map<String, Object> config(
            @ToolParam(description = "Reladomo object name (omit for all objects)", required = false) String object_name) {
        try {
            return reladomoQueryService.getConfig(object_name);
        } catch (Exception e) {
            log.error("Failed to get config for: {}", object_name, e);
            return Map.of("error", e.getMessage());
        }
    }
}
