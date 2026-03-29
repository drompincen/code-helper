package com.javaducker.server.model;

public record SessionTranscript(
    String sessionId,
    String projectPath,
    int messageIndex,
    String role,
    String content,
    String toolName,
    String timestamp,
    int tokenEstimate
) {}
