package com.javaducker.server.ingestion;

import org.springframework.stereotype.Component;

@Component
public class TextNormalizer {

    public String normalize(String text) {
        if (text == null) return "";

        // Remove null characters
        text = text.replace("\0", "");

        // Normalize line endings to \n
        text = text.replace("\r\n", "\n").replace("\r", "\n");

        // Collapse runs of more than 3 blank lines to 2
        text = text.replaceAll("\\n{4,}", "\n\n\n");

        // Collapse runs of more than 4 spaces/tabs to single space (within lines)
        text = text.replaceAll("[ \\t]{5,}", "    ");

        // Trim trailing whitespace per line
        text = text.replaceAll("[ \\t]+\\n", "\n");

        // Trim leading/trailing whitespace of entire text
        text = text.strip();

        return text;
    }
}
