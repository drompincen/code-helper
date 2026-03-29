package com.javaducker.server.ingestion;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaducker.server.model.SessionTranscript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Parses JSONL conversation files from ~/.claude/projects/.
 * Each line is a JSON object representing a message in a Claude Code session.
 */
@Component
public class SessionTranscriptParser {

    private static final Logger log = LoggerFactory.getLogger(SessionTranscriptParser.class);
    private static final int MAX_CONTENT_LENGTH = 50_000;

    private final ObjectMapper objectMapper;

    public SessionTranscriptParser() {
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Parse a session JSONL file into a list of SessionTranscript records.
     *
     * @param filePath path to the .jsonl file
     * @return list of parsed transcripts; malformed lines are skipped
     */
    public List<SessionTranscript> parseSessionFile(Path filePath) {
        List<SessionTranscript> results = new ArrayList<>();
        if (filePath == null || !Files.exists(filePath)) {
            return results;
        }

        String fileName = filePath.getFileName().toString();
        String sessionId = fileName.endsWith(".jsonl")
                ? fileName.substring(0, fileName.length() - ".jsonl".length())
                : fileName;
        String projectPath = filePath.getParent() != null
                ? filePath.getParent().toString()
                : "";

        try (Stream<String> lines = Files.lines(filePath)) {
            int[] index = {0};
            lines.forEach(line -> {
                SessionTranscript transcript = parseLine(line, sessionId, projectPath, index[0]);
                if (transcript != null) {
                    results.add(transcript);
                }
                index[0]++;
            });
        } catch (IOException e) {
            log.warn("Failed to read session file {}: {}", filePath, e.getMessage());
        }

        return results;
    }

    /**
     * Parse a single JSONL line into a SessionTranscript.
     *
     * @param line        the raw JSON line
     * @param sessionId   session identifier (derived from file name)
     * @param projectPath parent directory path
     * @param index       message index within the session
     * @return a SessionTranscript, or null if the line should be skipped
     */
    SessionTranscript parseLine(String line, String sessionId, String projectPath, int index) {
        if (line == null || line.isBlank()) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(line);

            String type = getTextOrNull(root, "type");
            String role = extractRole(root, type);
            String content = extractTextContent(root);
            String toolName = extractToolName(root, type);
            String timestamp = getTextOrNull(root, "timestamp");

            // Skip lines with no usable text content
            if (content == null || content.isBlank()) {
                return null;
            }

            // Truncate very large content
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH);
            }

            int tokenEstimate = content.length() / 4;

            return new SessionTranscript(
                    sessionId,
                    projectPath,
                    index,
                    role,
                    content,
                    toolName,
                    timestamp,
                    tokenEstimate
            );
        } catch (Exception e) {
            log.warn("Skipping malformed JSON line at index {} in session {}: {}",
                    index, sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * Find all .jsonl files in the given directory (non-recursive).
     *
     * @param projectDir the directory to scan
     * @return list of paths to .jsonl files
     */
    public List<Path> findSessionFiles(Path projectDir) {
        List<Path> files = new ArrayList<>();
        if (projectDir == null || !Files.isDirectory(projectDir)) {
            return files;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectDir, "*.jsonl")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    files.add(entry);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan directory {}: {}", projectDir, e.getMessage());
        }

        return files;
    }

    private String extractRole(JsonNode root, String type) {
        // Try message.role first
        JsonNode message = root.get("message");
        if (message != null && message.has("role")) {
            return message.get("role").asText();
        }

        // Fall back to type field
        if (type != null) {
            return switch (type) {
                case "human" -> "user";
                case "assistant" -> "assistant";
                case "tool_use" -> "assistant";
                case "tool_result" -> "tool";
                default -> type;
            };
        }

        return "unknown";
    }

    private String extractTextContent(JsonNode root) {
        // Try message.content first
        JsonNode message = root.get("message");
        if (message != null) {
            JsonNode content = message.get("content");
            if (content != null) {
                return extractFromContentNode(content);
            }
        }

        // Try top-level content
        JsonNode content = root.get("content");
        if (content != null) {
            return extractFromContentNode(content);
        }

        return null;
    }

    private String extractFromContentNode(JsonNode contentNode) {
        // Content can be a plain string
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }

        // Content can be an array of content blocks
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : contentNode) {
                String blockType = getTextOrNull(block, "type");

                // Skip image/binary blocks
                if ("image".equals(blockType)) {
                    continue;
                }

                // Extract text from text blocks
                if ("text".equals(blockType) && block.has("text")) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(block.get("text").asText());
                }

                // Extract tool name and input for tool_use blocks
                if ("tool_use".equals(blockType) && block.has("name")) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append("[tool_use: ").append(block.get("name").asText()).append("]");
                }

                // Extract tool_result content
                if ("tool_result".equals(blockType) && block.has("content")) {
                    String nested = extractFromContentNode(block.get("content"));
                    if (nested != null && !nested.isBlank()) {
                        if (!sb.isEmpty()) {
                            sb.append("\n");
                        }
                        sb.append(nested);
                    }
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }

        return null;
    }

    private String extractToolName(JsonNode root, String type) {
        // tool_use type: look for name field
        if ("tool_use".equals(type)) {
            return getTextOrNull(root, "name");
        }

        // Check in message content array for tool_use blocks
        JsonNode message = root.get("message");
        if (message != null) {
            JsonNode content = message.get("content");
            if (content != null && content.isArray()) {
                for (JsonNode block : content) {
                    if ("tool_use".equals(getTextOrNull(block, "type"))) {
                        return getTextOrNull(block, "name");
                    }
                }
            }
        }

        return null;
    }

    private String getTextOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }
}
