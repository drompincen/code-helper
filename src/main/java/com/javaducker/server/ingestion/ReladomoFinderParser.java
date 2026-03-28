package com.javaducker.server.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReladomoFinderParser {

    // OrderFinder.orderId().eq(42)
    private static final Pattern FINDER_ATTR_OP = Pattern.compile(
        "(\\w+)Finder\\.(\\w+)\\(\\)\\.(?!deepFetch)(\\w+)\\(");

    // OrderFinder.items().productId().eq(...)  — relationship navigation
    private static final Pattern FINDER_REL_NAV = Pattern.compile(
        "(\\w+)Finder\\.(\\w+)\\(\\)\\.(\\w+)\\(\\)\\.(\\w+)\\(");

    // list.deepFetch(OrderFinder.items())
    private static final Pattern DEEP_FETCH_SIMPLE = Pattern.compile(
        "\\.deepFetch\\((\\w+)Finder\\.(\\w+)\\(\\)\\)");

    // list.deepFetch(OrderFinder.items().product())  — chained
    private static final Pattern DEEP_FETCH_CHAINED = Pattern.compile(
        "\\.deepFetch\\((\\w+)Finder\\.((?:\\w+\\(\\)\\.)+\\w+\\(\\))\\)");

    public record FinderUsage(String objectName, String attributeOrPath, String operation, int lineNumber) {}
    public record DeepFetchUsage(String objectName, String fetchPath, int lineNumber) {}

    /**
     * Extract Finder query patterns from Java source text.
     */
    public List<FinderUsage> parseFinderUsages(String javaText, String fileName) {
        List<FinderUsage> results = new ArrayList<>();
        String[] lines = javaText.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            // Relationship navigation (more specific, check first)
            Matcher relMatcher = FINDER_REL_NAV.matcher(line);
            while (relMatcher.find()) {
                String objName = relMatcher.group(1);
                String rel = relMatcher.group(2);
                String attr = relMatcher.group(3);
                String op = relMatcher.group(4);
                results.add(new FinderUsage(objName, rel + "." + attr, op, lineNum));
            }

            // Simple attribute operations (skip lines already matched as relationship nav)
            Matcher attrMatcher = FINDER_ATTR_OP.matcher(line);
            while (attrMatcher.find()) {
                String objName = attrMatcher.group(1);
                String attr = attrMatcher.group(2);
                String op = attrMatcher.group(3);
                // Skip if this is part of a deepFetch call or relationship nav
                if (!"deepFetch".equals(op) && !isRelNavAt(line, attrMatcher.start())) {
                    results.add(new FinderUsage(objName, attr, op, lineNum));
                }
            }
        }
        return results;
    }

    /**
     * Extract deep fetch patterns from Java source text.
     */
    public List<DeepFetchUsage> parseDeepFetchUsages(String javaText, String fileName) {
        List<DeepFetchUsage> results = new ArrayList<>();
        String[] lines = javaText.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            // Chained deep fetch (more specific, check first)
            Matcher chainedMatcher = DEEP_FETCH_CHAINED.matcher(line);
            while (chainedMatcher.find()) {
                String objName = chainedMatcher.group(1);
                String rawPath = chainedMatcher.group(2);
                // "items().product()" -> "items.product"
                String path = rawPath.replaceAll("\\(\\)", "").replace(".", ".");
                results.add(new DeepFetchUsage(objName, path, lineNum));
            }

            // Simple deep fetch
            Matcher simpleMatcher = DEEP_FETCH_SIMPLE.matcher(line);
            while (simpleMatcher.find()) {
                String objName = simpleMatcher.group(1);
                String rel = simpleMatcher.group(2);
                // Skip if already captured as chained
                boolean alreadyCaptured = results.stream()
                    .anyMatch(r -> r.lineNumber() == lineNum && r.objectName().equals(objName)
                        && r.fetchPath().startsWith(rel));
                if (!alreadyCaptured) {
                    results.add(new DeepFetchUsage(objName, rel, lineNum));
                }
            }
        }
        return results;
    }

    private boolean isRelNavAt(String line, int matchStart) {
        // Check if this attribute match is actually part of a relationship navigation match
        Matcher relMatcher = FINDER_REL_NAV.matcher(line);
        while (relMatcher.find()) {
            if (relMatcher.start() == matchStart) return true;
        }
        return false;
    }
}
