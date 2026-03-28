package com.javaducker.server.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ImportParser {

    private static final Pattern JAVA_IMPORT = Pattern.compile("import\\s+([\\w.]+);");
    private static final Pattern JS_IMPORT_FROM = Pattern.compile("import\\s+.*?from\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern JS_REQUIRE = Pattern.compile("require\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Pattern PYTHON_IMPORT = Pattern.compile("import\\s+([\\w.]+)");
    private static final Pattern PYTHON_FROM = Pattern.compile("from\\s+([\\w.]+)\\s+import");
    private static final Pattern GO_IMPORT = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern RUST_USE = Pattern.compile("use\\s+([\\w:]+)");

    public List<String> parseImports(String text, String fileName) {
        if (text == null || fileName == null) {
            return List.of();
        }

        String ext = getExtension(fileName).toLowerCase();
        Set<String> imports = new LinkedHashSet<>();

        switch (ext) {
            case "java" -> extractAll(JAVA_IMPORT, text, imports);
            case "js", "jsx", "ts", "tsx", "mjs", "cjs" -> {
                extractAll(JS_IMPORT_FROM, text, imports);
                extractAll(JS_REQUIRE, text, imports);
            }
            case "py" -> {
                extractAll(PYTHON_IMPORT, text, imports);
                extractAll(PYTHON_FROM, text, imports);
            }
            case "go" -> extractGoImports(text, imports);
            case "rs" -> extractAll(RUST_USE, text, imports);
            default -> { /* unsupported language */ }
        }

        return new ArrayList<>(imports);
    }

    private void extractAll(Pattern pattern, String text, Set<String> results) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String match = matcher.group(1).trim();
            if (!match.isEmpty()) {
                results.add(match);
            }
        }
    }

    private void extractGoImports(String text, Set<String> results) {
        // Match single imports: import "path"
        // Match block imports: import ( "path1" \n "path2" )
        Pattern blockPattern = Pattern.compile("import\\s*\\(([^)]+)\\)", Pattern.DOTALL);
        Matcher blockMatcher = blockPattern.matcher(text);
        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);
            Matcher quoteMatcher = GO_IMPORT.matcher(block);
            while (quoteMatcher.find()) {
                results.add(quoteMatcher.group(1));
            }
        }

        // Single-line imports
        Pattern singleImport = Pattern.compile("import\\s+\"([^\"]+)\"");
        Matcher singleMatcher = singleImport.matcher(text);
        while (singleMatcher.find()) {
            results.add(singleMatcher.group(1));
        }
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }
}
