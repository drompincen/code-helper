package com.javaducker.server.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaducker.server.service.ContentIntelligenceService;
import com.javaducker.server.service.SearchService;
import com.javaducker.server.service.SessionIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SessionTools {

    private static final Logger log = LoggerFactory.getLogger(SessionTools.class);

    private final SessionIngestionService sessionIngestionService;
    private final SearchService searchService;
    private final ContentIntelligenceService contentIntelligenceService;
    private final ObjectMapper objectMapper;

    public SessionTools(SessionIngestionService sessionIngestionService,
                        SearchService searchService,
                        ContentIntelligenceService contentIntelligenceService,
                        ObjectMapper objectMapper) {
        this.sessionIngestionService = sessionIngestionService;
        this.searchService = searchService;
        this.contentIntelligenceService = contentIntelligenceService;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "javaducker_index_sessions",
            description = "Index Claude session transcripts from a project directory into JavaDucker")
    public Map<String, Object> indexSessions(
            @ToolParam(description = "Absolute path to the project root containing .claude/ sessions", required = true) String project_path,
            @ToolParam(description = "Maximum number of sessions to index (default: all)", required = false) Integer max_sessions,
            @ToolParam(description = "Use incremental indexing to skip already-indexed sessions (default: false)", required = false) String incremental) {
        try {
            int effectiveMax = (max_sessions == null || max_sessions <= 0) ? Integer.MAX_VALUE : max_sessions;
            boolean isIncremental = "true".equalsIgnoreCase(incremental);

            if (isIncremental) {
                return sessionIngestionService.indexSessionsIncremental(project_path, effectiveMax);
            } else {
                return sessionIngestionService.indexSessions(project_path, effectiveMax);
            }
        } catch (Exception e) {
            log.error("Failed to index sessions from: {}", project_path, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_search_sessions",
            description = "Search indexed session transcripts by phrase")
    public Map<String, Object> searchSessions(
            @ToolParam(description = "Search phrase", required = true) String phrase,
            @ToolParam(description = "Maximum number of results (default: 20)", required = false) Integer max_results) {
        try {
            int effectiveMax = (max_results == null || max_results <= 0) ? 20 : max_results;
            List<Map<String, Object>> results = sessionIngestionService.searchSessions(phrase, effectiveMax);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("results", results);
            response.put("count", results.size());
            return response;
        } catch (Exception e) {
            log.error("Failed to search sessions for: {}", phrase, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_session_context",
            description = "Get combined session and semantic search context for a topic")
    public Map<String, Object> sessionContext(
            @ToolParam(description = "Topic to search for across sessions and indexed code", required = true) String topic) {
        try {
            List<Map<String, Object>> sessionResults = sessionIngestionService.searchSessions(topic, 10);
            List<Map<String, Object>> semanticResults = searchService.semanticSearch(topic, 5);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("session_results", sessionResults);
            response.put("semantic_results", semanticResults);
            return response;
        } catch (Exception e) {
            log.error("Failed to get session context for: {}", topic, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_extract_decisions",
            description = "Store architectural decisions extracted from a session transcript")
    public Map<String, Object> extractDecisions(
            @ToolParam(description = "Session ID the decisions were extracted from", required = true) String session_id,
            @ToolParam(description = "JSON array of decisions, each with text, context, and tags fields", required = true) String decisions) {
        try {
            List<Map<String, String>> decisionList = objectMapper.readValue(
                    decisions, new TypeReference<>() {});
            return sessionIngestionService.storeDecisions(session_id, decisionList);
        } catch (Exception e) {
            log.error("Failed to extract decisions for session: {}", session_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_recent_decisions",
            description = "Get recent architectural decisions, optionally filtered by tag")
    public Map<String, Object> recentDecisions(
            @ToolParam(description = "Maximum number of sessions to scan (default: 5)", required = false) Integer max_sessions,
            @ToolParam(description = "Filter decisions by tag", required = false) String tag) {
        try {
            int effectiveMax = (max_sessions == null || max_sessions <= 0) ? 5 : max_sessions;
            List<Map<String, Object>> results = sessionIngestionService.getRecentDecisions(effectiveMax, tag);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("results", results);
            response.put("count", results.size());
            return response;
        } catch (Exception e) {
            log.error("Failed to get recent decisions", e);
            return Map.of("error", e.getMessage());
        }
    }
}
