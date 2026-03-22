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
            ".ts", ".css", ".sql", ".sh", ".bat", ".csv",
            ".cfg", ".ini", ".toml", ".proto", ".go", ".rs", ".c",
            ".cpp", ".h", ".hpp", ".rb", ".php", ".swift"
    );

    private static final Set<String> OFFICE_OOXML   = Set.of(".docx", ".pptx", ".xlsx");
    private static final Set<String> OFFICE_LEGACY  = Set.of(".doc", ".ppt", ".xls");
    private static final Set<String> ODF_EXTENSIONS = Set.of(".odt", ".odp", ".ods");

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

        if (OFFICE_OOXML.contains(ext))   return extractOfficeOoxml(filePath, ext);
        if (OFFICE_LEGACY.contains(ext))  return extractOfficeLegacy(filePath, ext);
        if (ODF_EXTENSIONS.contains(ext)) return extractOdf(filePath);

        if (".html".equals(ext) || ".htm".equals(ext)) return extractHtml(filePath);
        if (".epub".equals(ext))                        return extractEpub(filePath);
        if (".rtf".equals(ext))                         return extractRtf(filePath);
        if (".eml".equals(ext))                         return extractEml(filePath);
        if (".zip".equals(ext))                         return extractZip(filePath);

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
        String lower = ext.toLowerCase();
        if (TEXT_EXTENSIONS.contains(lower)) return true;
        if (".pdf".equalsIgnoreCase(lower)) return true;
        if (OFFICE_OOXML.contains(lower)) return true;
        if (OFFICE_LEGACY.contains(lower)) return true;
        if (ODF_EXTENSIONS.contains(lower)) return true;
        // Check web/doc formats added by Agent 2
        if (Set.of(".html", ".htm", ".epub", ".rtf", ".eml", ".zip").contains(lower)) return true;
        return false;
    }

    // ── Office OOXML (docx, pptx, xlsx) ──────────────────────────────────────

    private ExtractionResult extractOfficeOoxml(Path filePath, String ext) throws IOException {
        return switch (ext) {
            case ".docx" -> extractDocx(filePath);
            case ".pptx" -> extractPptx(filePath);
            case ".xlsx" -> extractXlsx(filePath);
            default      -> throw new IOException("Unknown OOXML type: " + ext);
        };
    }

    private ExtractionResult extractDocx(Path filePath) throws IOException {
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                java.nio.file.Files.newInputStream(filePath))) {
            var extractor = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc);
            return new ExtractionResult(extractor.getText(), "POI_DOCX");
        }
    }

    private ExtractionResult extractPptx(Path filePath) throws IOException {
        try (var pptx = new org.apache.poi.xslf.usermodel.XMLSlideShow(
                java.nio.file.Files.newInputStream(filePath))) {
            var sb = new StringBuilder();
            var slides = pptx.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                sb.append("[Slide ").append(i + 1).append("]\n");
                for (var shape : slides.get(i).getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape ts) {
                        sb.append(ts.getText()).append("\n");
                    }
                }
                sb.append("\n");
            }
            return new ExtractionResult(sb.toString(), "POI_PPTX");
        }
    }

    private ExtractionResult extractXlsx(Path filePath) throws IOException {
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                java.nio.file.Files.newInputStream(filePath))) {
            var sb = new StringBuilder();
            var fmt = new org.apache.poi.ss.usermodel.DataFormatter();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                var sheet = wb.getSheetAt(s);
                sb.append("[Sheet: ").append(sheet.getSheetName()).append("]\n");
                for (var row : sheet) {
                    var line = new java.util.StringJoiner("\t");
                    for (var cell : row) line.add(fmt.formatCellValue(cell));
                    sb.append(line).append("\n");
                }
                sb.append("\n");
            }
            return new ExtractionResult(sb.toString(), "POI_XLSX");
        }
    }

    // ── Office Legacy (doc, ppt, xls) ─────────────────────────────────────────

    private ExtractionResult extractOfficeLegacy(Path filePath, String ext) throws IOException {
        return switch (ext) {
            case ".doc" -> extractDoc(filePath);
            case ".ppt" -> extractPpt(filePath);
            case ".xls" -> extractXls(filePath);
            default     -> throw new IOException("Unknown legacy Office type: " + ext);
        };
    }

    private ExtractionResult extractDoc(Path filePath) throws IOException {
        try (var fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem(filePath.toFile())) {
            var extractor = new org.apache.poi.hwpf.extractor.WordExtractor(fs);
            return new ExtractionResult(extractor.getText(), "POI_DOC");
        }
    }

    private ExtractionResult extractPpt(Path filePath) throws IOException {
        try (var ppt = new org.apache.poi.hslf.usermodel.HSLFSlideShow(
                java.nio.file.Files.newInputStream(filePath))) {
            var sb = new StringBuilder();
            var slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                sb.append("[Slide ").append(i + 1).append("]\n");
                for (var shape : slides.get(i).getShapes()) {
                    if (shape instanceof org.apache.poi.hslf.usermodel.HSLFTextShape ts) {
                        String t = ts.getText();
                        if (t != null && !t.isBlank()) sb.append(t).append("\n");
                    }
                }
                sb.append("\n");
            }
            return new ExtractionResult(sb.toString(), "POI_PPT");
        }
    }

    private ExtractionResult extractXls(Path filePath) throws IOException {
        try (var fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem(filePath.toFile())) {
            var wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook(fs);
            var sb = new StringBuilder();
            var fmt = new org.apache.poi.ss.usermodel.DataFormatter();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                var sheet = wb.getSheetAt(s);
                sb.append("[Sheet: ").append(sheet.getSheetName()).append("]\n");
                for (var row : sheet) {
                    var line = new java.util.StringJoiner("\t");
                    for (var cell : row) line.add(fmt.formatCellValue(cell));
                    sb.append(line).append("\n");
                }
            }
            wb.close();
            return new ExtractionResult(sb.toString(), "POI_XLS");
        }
    }

    // ── ODF (odt, odp, ods) ───────────────────────────────────────────────────

    private ExtractionResult extractOdf(Path filePath) throws IOException {
        try (var zis = new java.util.zip.ZipInputStream(
                java.nio.file.Files.newInputStream(filePath))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("content.xml".equals(entry.getName())) {
                    String xml = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    String text = xml.replaceAll("<[^>]+>", " ")
                                     .replaceAll("\\s{2,}", "\n")
                                     .strip();
                    return new ExtractionResult(text, "ODF_XML");
                }
            }
        }
        throw new IOException("content.xml not found in ODF archive: " + filePath);
    }

    // ── HTML / HTM ────────────────────────────────────────────────────────────

    private ExtractionResult extractHtml(Path filePath) throws IOException {
        String html = java.nio.file.Files.readString(filePath, java.nio.charset.StandardCharsets.UTF_8);
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        doc.select("script, style, noscript").remove();
        String text = doc.body() != null ? doc.body().text() : doc.text();
        return new ExtractionResult(text, "JSOUP_HTML");
    }

    // ── EPUB ──────────────────────────────────────────────────────────────────

    private ExtractionResult extractEpub(Path filePath) throws IOException {
        // EPUB is a ZIP containing XHTML files; extract and parse each with Jsoup
        var sb = new StringBuilder();
        try (var zis = new java.util.zip.ZipInputStream(
                java.nio.file.Files.newInputStream(filePath))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm")) {
                    String html = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
                    doc.select("script, style").remove();
                    String text = doc.text().strip();
                    if (!text.isBlank()) {
                        sb.append("[").append(entry.getName()).append("]\n");
                        sb.append(text).append("\n\n");
                    }
                }
            }
        }
        if (sb.isEmpty()) throw new IOException("No readable content found in EPUB: " + filePath);
        return new ExtractionResult(sb.toString().strip(), "EPUB_JSOUP");
    }

    // ── RTF ───────────────────────────────────────────────────────────────────

    private ExtractionResult extractRtf(Path filePath) throws IOException {
        try (var is = java.nio.file.Files.newInputStream(filePath)) {
            var kit = new javax.swing.text.rtf.RTFEditorKit();
            var doc = kit.createDefaultDocument();
            kit.read(is, doc, 0);
            String text = doc.getText(0, doc.getLength());
            return new ExtractionResult(text, "RTF_EDITOR_KIT");
        } catch (javax.swing.text.BadLocationException e) {
            throw new IOException("RTF extraction failed: " + e.getMessage(), e);
        }
    }

    // ── EML ───────────────────────────────────────────────────────────────────

    private ExtractionResult extractEml(Path filePath) throws IOException {
        try (var is = java.nio.file.Files.newInputStream(filePath)) {
            var session = jakarta.mail.Session.getDefaultInstance(new java.util.Properties());
            var message = new jakarta.mail.internet.MimeMessage(session, is);
            var sb = new StringBuilder();
            try {
                if (message.getFrom() != null)
                    sb.append("From: ").append(java.util.Arrays.toString(message.getFrom())).append("\n");
                if (message.getSubject() != null)
                    sb.append("Subject: ").append(message.getSubject()).append("\n\n");
                appendMailPart(message, sb);
            } catch (jakarta.mail.MessagingException e) {
                throw new IOException("EML parse error: " + e.getMessage(), e);
            }
            return new ExtractionResult(sb.toString().strip(), "JAKARTA_MAIL");
        } catch (jakarta.mail.MessagingException e) {
            throw new IOException("EML extraction failed: " + e.getMessage(), e);
        }
    }

    private void appendMailPart(jakarta.mail.Part part, StringBuilder sb)
            throws jakarta.mail.MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            sb.append(part.getContent()).append("\n");
        } else if (part.isMimeType("text/html")) {
            String html = (String) part.getContent();
            sb.append(org.jsoup.Jsoup.parse(html).text()).append("\n");
        } else if (part.isMimeType("multipart/*")) {
            var mp = (jakarta.mail.Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                appendMailPart(mp.getBodyPart(i), sb);
            }
        }
    }

    // ── ZIP ───────────────────────────────────────────────────────────────────

    private static final int  ZIP_MAX_ENTRIES = 500;
    private static final long ZIP_MAX_BYTES   = 50L * 1024 * 1024; // 50 MB

    private ExtractionResult extractZip(Path filePath) throws IOException {
        var sb = new StringBuilder();
        int entries = 0;
        long totalBytes = 0;

        try (var zis = new java.util.zip.ZipInputStream(
                java.nio.file.Files.newInputStream(filePath))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null && entries < ZIP_MAX_ENTRIES) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().toLowerCase();
                String innerExt = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                if (!TEXT_EXTENSIONS.contains(innerExt) && !".pdf".equals(innerExt)) {
                    zis.closeEntry();
                    continue;
                }
                byte[] content = zis.readAllBytes();
                totalBytes += content.length;
                if (totalBytes > ZIP_MAX_BYTES) break;

                sb.append("[").append(entry.getName()).append("]\n");
                try {
                    Path tmp = java.nio.file.Files.createTempFile("jd-zip-", innerExt);
                    try {
                        java.nio.file.Files.write(tmp, content);
                        sb.append(extract(tmp).text()).append("\n\n");
                    } finally {
                        java.nio.file.Files.deleteIfExists(tmp);
                    }
                } catch (Exception e) {
                    sb.append(new String(content, java.nio.charset.StandardCharsets.UTF_8)).append("\n\n");
                }
                entries++;
            }
        }
        return new ExtractionResult(sb.toString().strip(), "ZIP_RECURSE");
    }
}
