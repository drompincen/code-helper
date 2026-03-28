package com.javaducker.server.ingestion;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FileSummarizer {

    private record LangDef(String language, List<Pattern> classPatterns,
                           List<Pattern> methodPatterns, Pattern importPattern) {}

    private final Map<String, LangDef> langDefs = new HashMap<>();

    public FileSummarizer() {
        langDefs.put("java", new LangDef("Java",
                List.of(Pattern.compile("class\\s+(\\w+)"), Pattern.compile("interface\\s+(\\w+)")),
                List.of(Pattern.compile("(?:public|private|protected|static|\\s)+[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\(")),
                Pattern.compile("import\\s+[\\w.]+;")));

        langDefs.put("js", new LangDef("JavaScript",
                List.of(Pattern.compile("class\\s+(\\w+)")),
                List.of(Pattern.compile("function\\s+(\\w+)"),
                        Pattern.compile("(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s*)?\\(")),
                Pattern.compile("(?:import|require)\\s*\\(")));

        langDefs.put("ts", new LangDef("TypeScript",
                List.of(Pattern.compile("class\\s+(\\w+)")),
                List.of(Pattern.compile("function\\s+(\\w+)"),
                        Pattern.compile("(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s*)?\\(")),
                Pattern.compile("(?:import|require)\\s*\\(")));

        langDefs.put("py", new LangDef("Python",
                List.of(Pattern.compile("class\\s+(\\w+)")),
                List.of(Pattern.compile("def\\s+(\\w+)")),
                Pattern.compile("(?:import|from)\\s+\\w+")));

        langDefs.put("go", new LangDef("Go",
                List.of(Pattern.compile("type\\s+(\\w+)\\s+struct")),
                List.of(Pattern.compile("func\\s+(?:\\([^)]+\\)\\s+)?(\\w+)")),
                Pattern.compile("import\\s+")));

        langDefs.put("rs", new LangDef("Rust",
                List.of(Pattern.compile("(?:pub\\s+)?struct\\s+(\\w+)")),
                List.of(Pattern.compile("(?:pub\\s+)?fn\\s+(\\w+)")),
                Pattern.compile("use\\s+[\\w:]+")));

        // Aliases
        langDefs.put("jsx", langDefs.get("js"));
        langDefs.put("tsx", langDefs.get("ts"));
        langDefs.put("mjs", langDefs.get("js"));
    }

    public Map<String, Object> summarize(String text, String fileName) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (text == null) text = "";
        if (fileName == null) fileName = "";

        String ext = extractExtension(fileName);
        LangDef lang = langDefs.get(ext);
        int lineCount = text.isEmpty() ? 0 : text.split("\n", -1).length;

        result.put("file_type", ext.isEmpty() ? "unknown" : ext);
        result.put("language", lang != null ? lang.language() : humanLanguage(ext));
        result.put("line_count", lineCount);

        List<String> classNames = new ArrayList<>();
        List<String> methodNames = new ArrayList<>();
        List<String> imports = new ArrayList<>();

        if (lang != null) {
            for (Pattern p : lang.classPatterns()) {
                extractAll(p, text, classNames);
            }
            for (Pattern p : lang.methodPatterns()) {
                extractAll(p, text, methodNames);
            }
            extractImports(lang.importPattern(), text, imports, 10);
        }

        result.put("class_names", classNames);
        result.put("method_names", methodNames);
        result.put("imports", imports);
        result.put("summary_text", buildSummary(fileName, lang, lineCount, classNames, methodNames, imports));

        return result;
    }

    private String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase();
    }

    private String humanLanguage(String ext) {
        return switch (ext) {
            case "java" -> "Java";
            case "js", "jsx", "mjs" -> "JavaScript";
            case "ts", "tsx" -> "TypeScript";
            case "py" -> "Python";
            case "go" -> "Go";
            case "rs" -> "Rust";
            case "rb" -> "Ruby";
            case "cpp", "cc", "cxx" -> "C++";
            case "c", "h" -> "C";
            case "cs" -> "C#";
            case "kt" -> "Kotlin";
            case "scala" -> "Scala";
            case "swift" -> "Swift";
            case "php" -> "PHP";
            case "sh", "bash" -> "Shell";
            case "yml", "yaml" -> "YAML";
            case "json" -> "JSON";
            case "xml" -> "XML";
            case "md" -> "Markdown";
            case "sql" -> "SQL";
            case "html", "htm" -> "HTML";
            case "css" -> "CSS";
            default -> "Unknown";
        };
    }

    private void extractAll(Pattern pattern, String text, List<String> dest) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (name != null && !dest.contains(name)) {
                dest.add(name);
            }
        }
    }

    private void extractImports(Pattern pattern, String text, List<String> dest, int limit) {
        Matcher m = pattern.matcher(text);
        while (m.find() && dest.size() < limit) {
            dest.add(m.group().strip());
        }
    }

    private String buildSummary(String fileName, LangDef lang, int lineCount,
                                List<String> classNames, List<String> methodNames,
                                List<String> imports) {
        StringBuilder sb = new StringBuilder();
        sb.append(fileName);

        if (lang != null) {
            sb.append(" is a ").append(lang.language()).append(" file");
        } else {
            sb.append(" is a file");
        }

        sb.append(" with ").append(lineCount).append(" lines.");

        if (!classNames.isEmpty()) {
            sb.append(" It defines ");
            sb.append(classNames.size() == 1 ? "class " : "classes ");
            sb.append(String.join(", ", classNames)).append(".");
        }

        if (!methodNames.isEmpty()) {
            sb.append(" It contains ").append(methodNames.size());
            sb.append(methodNames.size() == 1 ? " method: " : " methods: ");
            List<String> shown = methodNames.size() > 8
                    ? methodNames.subList(0, 8) : methodNames;
            sb.append(String.join(", ", shown));
            if (methodNames.size() > 8) sb.append(" and others");
            sb.append(".");
        }

        if (!imports.isEmpty()) {
            sb.append(" It has ").append(imports.size()).append(" import(s).");
        }

        return sb.toString();
    }
}
