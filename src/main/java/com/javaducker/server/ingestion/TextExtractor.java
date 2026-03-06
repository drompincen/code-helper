package com.javaducker.server.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

@Component
public class TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".txt", ".md", ".java", ".xml", ".json", ".yml", ".yaml",
            ".properties", ".gradle", ".kt", ".scala", ".py", ".js",
            ".ts", ".html", ".css", ".sql", ".sh", ".bat", ".csv",
            ".cfg", ".ini", ".toml", ".proto", ".go", ".rs", ".c",
            ".cpp", ".h", ".hpp", ".rb", ".php", ".swift"
    );

    public record ExtractionResult(String text, String method) {}

    public ExtractionResult extract(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString().toLowerCase();
        String ext = getExtension(fileName);

        if (".pdf".equals(ext)) {
            return extractPdf(filePath);
        }

        if (TEXT_EXTENSIONS.contains(ext)) {
            return extractText(filePath);
        }

        throw new IOException("Unsupported file type: " + ext);
    }

    private ExtractionResult extractText(Path filePath) throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        return new ExtractionResult(content, "TEXT_DECODE");
    }

    private ExtractionResult extractPdf(Path filePath) throws IOException {
        try (PDDocument doc = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            return new ExtractionResult(text, "PDFBOX");
        }
    }

    public static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : "";
    }

    public static boolean isSupportedExtension(String ext) {
        return TEXT_EXTENSIONS.contains(ext.toLowerCase()) || ".pdf".equalsIgnoreCase(ext);
    }
}
