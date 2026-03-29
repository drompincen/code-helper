package com.javaducker.server.ingestion;

import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor();

    @TempDir
    Path tempDir;

    @Test
    void extractTextFile() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello World");
        TextExtractor.ExtractionResult result = extractor.extract(file);
        assertEquals("Hello World", result.text());
        assertEquals("TEXT_DECODE", result.method());
    }

    @Test
    void extractJavaFile() throws IOException {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, "public class Sample { @Transactional void run() {} }");
        TextExtractor.ExtractionResult result = extractor.extract(file);
        assertTrue(result.text().contains("@Transactional"));
        assertEquals("TEXT_DECODE", result.method());
    }

    @Test
    void extractMarkdown() throws IOException {
        Path file = tempDir.resolve("README.md");
        Files.writeString(file, "# Title\nSome content here");
        TextExtractor.ExtractionResult result = extractor.extract(file);
        assertTrue(result.text().contains("# Title"));
    }

    @Test
    void extractYaml() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "server:\n  port: 8080");
        TextExtractor.ExtractionResult result = extractor.extract(file);
        assertTrue(result.text().contains("port: 8080"));
    }

    @Test
    void unsupportedFileThrows() {
        Path file = tempDir.resolve("image.png");
        assertThrows(IOException.class, () -> extractor.extract(file));
    }

    @Test
    void getExtension() {
        assertEquals(".java", TextExtractor.getExtension("Sample.java"));
        assertEquals(".txt", TextExtractor.getExtension("readme.txt"));
        assertEquals("", TextExtractor.getExtension("Makefile"));
    }

    @Test
    void isSupportedExtension() {
        assertTrue(TextExtractor.isSupportedExtension(".java"));
        assertTrue(TextExtractor.isSupportedExtension(".md"));
        assertTrue(TextExtractor.isSupportedExtension(".pdf"));
        assertFalse(TextExtractor.isSupportedExtension(".png"));
        assertFalse(TextExtractor.isSupportedExtension(".exe"));
    }

    @Test
    void extractPptx() throws Exception {
        var pptx = new XMLSlideShow();
        var slide = pptx.createSlide();
        var tb = slide.createTextBox();
        tb.setText("Hello from slide 1");
        Path file = tempDir.resolve("test.pptx");
        try (var os = java.nio.file.Files.newOutputStream(file)) { pptx.write(os); }
        pptx.close();
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Hello from slide 1"), "PPTX text: " + result.text());
        assertEquals("POI_PPTX", result.method());
    }

    @Test
    void extractDocx() throws Exception {
        var doc = new XWPFDocument();
        doc.createParagraph().createRun().setText("Hello from docx");
        Path file = tempDir.resolve("test.docx");
        try (var os = java.nio.file.Files.newOutputStream(file)) { doc.write(os); }
        doc.close();
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Hello from docx"), "DOCX text: " + result.text());
        assertEquals("POI_DOCX", result.method());
    }

    @Test
    void extractXlsx() throws Exception {
        var wb = new XSSFWorkbook();
        var sheet = wb.createSheet("Data");
        var row = sheet.createRow(0);
        row.createCell(0).setCellValue("Revenue");
        row.createCell(1).setCellValue(42000.0);
        Path file = tempDir.resolve("test.xlsx");
        try (var os = java.nio.file.Files.newOutputStream(file)) { wb.write(os); }
        wb.close();
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Revenue"), "XLSX text: " + result.text());
        assertEquals("POI_XLSX", result.method());
    }

    @Test
    void extractHtml() throws Exception {
        Path file = tempDir.resolve("page.html");
        Files.writeString(file,
            "<html><body><h1>Title</h1><p>Body text</p><script>var x=1;</script></body></html>");
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Title"), "HTML text: " + result.text());
        assertTrue(result.text().contains("Body text"));
        assertFalse(result.text().contains("var x=1"), "Script should be stripped");
        assertEquals("JSOUP_HTML", result.method());
    }

    @Test
    void extractRtf() throws Exception {
        Path file = tempDir.resolve("test.rtf");
        Files.writeString(file,
            "{\\rtf1\\ansi {\\fonttbl {\\f0 Times New Roman;}} {\\f0 Hello RTF world}}");
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Hello RTF world"), "RTF text: " + result.text());
        assertEquals("RTF_EDITOR_KIT", result.method());
    }

    @Test
    void extractZipWithTextEntry() throws Exception {
        Path zipFile = tempDir.resolve("archive.zip");
        try (var zos = new ZipOutputStream(java.nio.file.Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("readme.txt"));
            zos.write("Content inside zip".getBytes());
            zos.closeEntry();
        }
        var result = extractor.extract(zipFile);
        assertTrue(result.text().contains("Content inside zip"), "ZIP text: " + result.text());
        assertEquals("ZIP_RECURSE", result.method());
    }

    @Test
    void isSupportedExtensionIncludesNewTypes() {
        assertTrue(TextExtractor.isSupportedExtension(".pptx"));
        assertTrue(TextExtractor.isSupportedExtension(".docx"));
        assertTrue(TextExtractor.isSupportedExtension(".xlsx"));
        assertTrue(TextExtractor.isSupportedExtension(".doc"));
        assertTrue(TextExtractor.isSupportedExtension(".ppt"));
        assertTrue(TextExtractor.isSupportedExtension(".xls"));
        assertTrue(TextExtractor.isSupportedExtension(".odt"));
        assertTrue(TextExtractor.isSupportedExtension(".html"));
        assertTrue(TextExtractor.isSupportedExtension(".htm"));
        assertTrue(TextExtractor.isSupportedExtension(".epub"));
        assertTrue(TextExtractor.isSupportedExtension(".rtf"));
        assertTrue(TextExtractor.isSupportedExtension(".eml"));
        assertTrue(TextExtractor.isSupportedExtension(".zip"));
        assertFalse(TextExtractor.isSupportedExtension(".exe"));
        assertFalse(TextExtractor.isSupportedExtension(".png"));
    }

    // ── Additional text extension coverage ───────────────────────────────────

    @Test
    void extractJson() throws IOException {
        Path file = tempDir.resolve("data.json");
        Files.writeString(file, "{\"key\": \"value\", \"count\": 42}");
        var result = extractor.extract(file);
        assertTrue(result.text().contains("\"key\""));
        assertEquals("TEXT_DECODE", result.method());
    }

    @Test
    void extractXml() throws IOException {
        Path file = tempDir.resolve("config.xml");
        Files.writeString(file, "<root><item>Hello XML</item></root>");
        var result = extractor.extract(file);
        assertTrue(result.text().contains("<item>Hello XML</item>"));
        assertEquals("TEXT_DECODE", result.method());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "test.properties", "build.gradle", "app.kt", "app.scala",
            "script.py", "app.js", "app.ts", "style.css", "query.sql",
            "run.sh", "run.bat", "data.csv", "app.cfg", "app.ini",
            "config.toml", "schema.proto", "main.go", "main.rs",
            "main.c", "main.cpp", "main.h", "main.hpp", "main.rb",
            "main.php", "main.swift"
    })
    void extractVariousTextExtensions(String fileName) throws IOException {
        Path file = tempDir.resolve(fileName);
        String content = "content of " + fileName;
        Files.writeString(file, content);
        var result = extractor.extract(file);
        assertEquals(content, result.text());
        assertEquals("TEXT_DECODE", result.method());
    }

    @Test
    void extractYamlAlternateExtension() throws IOException {
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, "key: value");
        var result = extractor.extract(file);
        assertEquals("key: value", result.text());
        assertEquals("TEXT_DECODE", result.method());
    }

    // ── Empty file ───────────────────────────────────────────────────────────

    @Test
    void extractEmptyTextFile() throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");
        var result = extractor.extract(file);
        assertEquals("", result.text());
        assertEquals("TEXT_DECODE", result.method());
    }

    // ── Unsupported file that exists on disk ─────────────────────────────────

    @Test
    void unsupportedExistingFileThrows() throws IOException {
        Path file = tempDir.resolve("image.png");
        Files.write(file, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(file));
        assertTrue(ex.getMessage().contains("Unsupported file type"));
    }

    @Test
    void unsupportedNoExtensionThrows() throws IOException {
        Path file = tempDir.resolve("Makefile");
        Files.writeString(file, "all: build");
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(file));
        assertTrue(ex.getMessage().contains("Unsupported file type"));
    }

    // ── HTM extension (separate branch from .html) ───────────────────────────

    @Test
    void extractHtm() throws Exception {
        Path file = tempDir.resolve("page.htm");
        Files.writeString(file, "<html><body><p>HTM content</p><style>body{}</style></body></html>");
        var result = extractor.extract(file);
        assertTrue(result.text().contains("HTM content"));
        assertFalse(result.text().contains("body{}"), "Style should be stripped");
        assertEquals("JSOUP_HTML", result.method());
    }

    @Test
    void extractHtmlWithNoBody() throws Exception {
        Path file = tempDir.resolve("fragment.html");
        Files.writeString(file, "<p>Just a paragraph</p>");
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Just a paragraph"));
        assertEquals("JSOUP_HTML", result.method());
    }

    // ── getExtension edge cases ──────────────────────────────────────────────

    @Test
    void getExtensionMultipleDots() {
        assertEquals(".gz", TextExtractor.getExtension("archive.tar.gz"));
    }

    @Test
    void getExtensionDotFile() {
        assertEquals(".gitignore", TextExtractor.getExtension(".gitignore"));
    }

    // ── isSupportedExtension case insensitivity ──────────────────────────────

    @Test
    void isSupportedExtensionCaseInsensitive() {
        assertTrue(TextExtractor.isSupportedExtension(".JAVA"));
        assertTrue(TextExtractor.isSupportedExtension(".Pdf"));
        assertTrue(TextExtractor.isSupportedExtension(".DOCX"));
        assertTrue(TextExtractor.isSupportedExtension(".HTML"));
        assertTrue(TextExtractor.isSupportedExtension(".ODT"));
        assertTrue(TextExtractor.isSupportedExtension(".DOC"));
    }

    // ── ODF extraction ───────────────────────────────────────────────────────

    @Test
    void extractOdt() throws Exception {
        Path file = tempDir.resolve("document.odt");
        try (var zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry("content.xml"));
            zos.write("<office:document><text:p>Hello ODF world</text:p></office:document>"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Hello ODF world"), "ODF text: " + result.text());
        assertEquals("ODF_XML", result.method());
    }

    @Test
    void extractOdp() throws Exception {
        Path file = tempDir.resolve("presentation.odp");
        try (var zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry("content.xml"));
            zos.write("<office:presentation><text:p>Slide text</text:p></office:presentation>"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Slide text"));
        assertEquals("ODF_XML", result.method());
    }

    @Test
    void extractOds() throws Exception {
        Path file = tempDir.resolve("spreadsheet.ods");
        try (var zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry("content.xml"));
            zos.write("<office:spreadsheet><text:p>Cell data</text:p></office:spreadsheet>"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Cell data"));
        assertEquals("ODF_XML", result.method());
    }

    @Test
    void extractOdfMissingContentXmlThrows() throws Exception {
        Path file = tempDir.resolve("bad.odt");
        try (var zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry("meta.xml"));
            zos.write("<meta/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(file));
        assertTrue(ex.getMessage().contains("content.xml not found"));
    }

    // ── EPUB extraction ──────────────────────────────────────────────────────

    @Test
    void extractEpub() throws Exception {
        Path file = tempDir.resolve("book.epub");
        try (var zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry("chapter1.xhtml"));
            zos.write("<html><body><p>Chapter one text</p></body></html>"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("chapter2.html"));
            zos.write("<html><body><p>Chapter two text</p><script>evil()</script></body></html>"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Chapter one text"), "EPUB text: " + result.text());
        assertTrue(result.text().contains("Chapter two text"));
        assertFalse(result.text().contains("evil()"), "Script should be stripped");
        assertEquals("EPUB_JSOUP", result.method());
    }

    @Test
    void extractEpubWithHtmEntries() throws Exception {
        Path file = tempDir.resolve("book2.epub");
        try (var zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry("page.htm"));
            zos.write("<html><body><p>HTM entry</p></body></html>"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        var result = extractor.extract(file);
        assertTrue(result.text().contains("HTM entry"));
        assertEquals("EPUB_JSOUP", result.method());
    }

    @Test
    void extractEpubNoReadableContentThrows() throws Exception {
        Path file = tempDir.resolve("empty.epub");
        try (var zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry("image.png"));
            zos.write(new byte[]{0x00});
            zos.closeEntry();
        }
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(file));
        assertTrue(ex.getMessage().contains("No readable content found in EPUB"));
    }

    @Test
    void extractEpubWithBlankHtmlContentThrows() throws Exception {
        Path file = tempDir.resolve("blank.epub");
        try (var zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry("blank.xhtml"));
            zos.write("<html><body>   </body></html>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(file));
        assertTrue(ex.getMessage().contains("No readable content found in EPUB"));
    }

    // ── EML extraction ───────────────────────────────────────────────────────

    @Test
    void extractEml() throws Exception {
        Path file = tempDir.resolve("message.eml");
        String eml = "From: sender@example.com\r\n"
                + "To: recipient@example.com\r\n"
                + "Subject: Test Email\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "Hello from the email body";
        Files.writeString(file, eml);
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Test Email"), "EML text: " + result.text());
        assertTrue(result.text().contains("Hello from the email body"));
        assertTrue(result.text().contains("From:"));
        assertEquals("JAKARTA_MAIL", result.method());
    }

    @Test
    void extractEmlHtmlContent() throws Exception {
        Path file = tempDir.resolve("html-message.eml");
        String eml = "From: sender@example.com\r\n"
                + "Subject: HTML Mail\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + "<html><body><p>HTML email body</p></body></html>";
        Files.writeString(file, eml);
        var result = extractor.extract(file);
        assertTrue(result.text().contains("HTML email body"), "EML HTML text: " + result.text());
        assertEquals("JAKARTA_MAIL", result.method());
    }

    @Test
    void extractEmlMultipart() throws Exception {
        Path file = tempDir.resolve("multipart.eml");
        String boundary = "----=_Part_123";
        String eml = "From: sender@example.com\r\n"
                + "Subject: Multipart\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"" + boundary + "\"\r\n"
                + "\r\n"
                + "------=_Part_123\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "Plain text part\r\n"
                + "------=_Part_123\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + "<html><body>HTML part</body></html>\r\n"
                + "------=_Part_123--\r\n";
        Files.writeString(file, eml);
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Plain text part"), "EML multipart: " + result.text());
        assertTrue(result.text().contains("HTML part"));
        assertEquals("JAKARTA_MAIL", result.method());
    }

    // ── ZIP edge cases ───────────────────────────────────────────────────────

    @Test
    void extractZipSkipsDirectories() throws Exception {
        Path zipFile = tempDir.resolve("dirs.zip");
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("folder/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("folder/file.txt"));
            zos.write("nested content".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        var result = extractor.extract(zipFile);
        assertTrue(result.text().contains("nested content"));
        assertEquals("ZIP_RECURSE", result.method());
    }

    @Test
    void extractZipSkipsBinaryEntries() throws Exception {
        Path zipFile = tempDir.resolve("mixed.zip");
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("image.png"));
            zos.write(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("code.java"));
            zos.write("public class Foo {}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        var result = extractor.extract(zipFile);
        assertTrue(result.text().contains("public class Foo"));
        assertFalse(result.text().contains("image.png"), "Binary entry should be skipped");
        assertEquals("ZIP_RECURSE", result.method());
    }

    @Test
    void extractZipEmptyArchive() throws Exception {
        Path zipFile = tempDir.resolve("empty.zip");
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            // empty archive
        }
        var result = extractor.extract(zipFile);
        assertEquals("", result.text());
        assertEquals("ZIP_RECURSE", result.method());
    }

    @Test
    void extractZipWithMultipleTextEntries() throws Exception {
        Path zipFile = tempDir.resolve("multi.zip");
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("a.txt"));
            zos.write("File A".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("b.json"));
            zos.write("{\"b\": true}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("c.md"));
            zos.write("# File C".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        var result = extractor.extract(zipFile);
        assertTrue(result.text().contains("File A"));
        assertTrue(result.text().contains("{\"b\": true}"));
        assertTrue(result.text().contains("# File C"));
        assertEquals("ZIP_RECURSE", result.method());
    }

    @Test
    void extractZipEntryWithNoExtension() throws Exception {
        Path zipFile = tempDir.resolve("noext.zip");
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("Makefile"));
            zos.write("all: build".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("readme.txt"));
            zos.write("Readme".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        var result = extractor.extract(zipFile);
        // Makefile has no extension ("") which is not in TEXT_EXTENSIONS, should be skipped
        assertFalse(result.text().contains("all: build"),
                "Entry with no extension should be skipped");
        assertTrue(result.text().contains("Readme"));
    }

    // ── Case-insensitive file name dispatch ──────────────────────────────────

    @Test
    void extractUpperCaseExtension() throws IOException {
        Path file = tempDir.resolve("DATA.JSON");
        Files.writeString(file, "{\"upper\": true}");
        var result = extractor.extract(file);
        assertTrue(result.text().contains("\"upper\""));
        assertEquals("TEXT_DECODE", result.method());
    }

    @Test
    void extractMixedCaseHtml() throws Exception {
        Path file = tempDir.resolve("Page.HTML");
        Files.writeString(file, "<html><body>Mixed case</body></html>");
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Mixed case"));
        assertEquals("JSOUP_HTML", result.method());
    }

    // ── PDF extraction ──────────────────────────────────────────────────────

    @Test
    void extractPdf() throws Exception {
        // Create a minimal valid PDF using PDFBox
        Path file = tempDir.resolve("test.pdf");
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Hello from PDF");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Hello from PDF"), "PDF text: " + result.text());
        assertEquals("PDFBOX", result.method());
    }

    @Test
    void extractCorruptedPdfThrows() throws Exception {
        Path file = tempDir.resolve("corrupt.pdf");
        Files.write(file, "not a real PDF file at all".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> extractor.extract(file));
    }

    // ── HTML with noscript tag ──────────────────────────────────────────────

    @Test
    void extractHtmlStripsNoscript() throws Exception {
        Path file = tempDir.resolve("noscript.html");
        Files.writeString(file,
            "<html><body><p>Visible</p><noscript>Enable JS</noscript><style>.x{}</style></body></html>");
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Visible"));
        assertFalse(result.text().contains("Enable JS"), "noscript should be stripped");
        assertFalse(result.text().contains(".x{}"), "style should be stripped");
        assertEquals("JSOUP_HTML", result.method());
    }

    // ── ZIP with nested supported types ─────────────────────────────────────

    @Test
    void extractZipWithNestedJavaAndXml() throws Exception {
        Path zipFile = tempDir.resolve("nested-types.zip");
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("src/Main.java"));
            zos.write("public class Main { }".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("config/app.xml"));
            zos.write("<config><key>value</key></config>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("data.csv"));
            zos.write("name,age\nAlice,30\nBob,25".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        var result = extractor.extract(zipFile);
        assertTrue(result.text().contains("public class Main"), "Should extract Java file");
        assertTrue(result.text().contains("<config>"), "Should extract XML file");
        assertTrue(result.text().contains("Alice"), "Should extract CSV file");
        assertEquals("ZIP_RECURSE", result.method());
    }

    // ── Office format error paths (corrupted/invalid files) ─────────────────

    @Test
    void extractCorruptedDocxThrows() throws Exception {
        Path file = tempDir.resolve("bad.docx");
        Files.write(file, "this is not a valid docx".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> extractor.extract(file));
    }

    @Test
    void extractCorruptedXlsxThrows() throws Exception {
        Path file = tempDir.resolve("bad.xlsx");
        Files.write(file, "this is not a valid xlsx".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> extractor.extract(file));
    }

    @Test
    void extractCorruptedPptxThrows() throws Exception {
        Path file = tempDir.resolve("bad.pptx");
        Files.write(file, "this is not a valid pptx".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> extractor.extract(file));
    }

    @Test
    void extractCorruptedDocThrows() throws Exception {
        Path file = tempDir.resolve("bad.doc");
        Files.write(file, "this is not a valid doc".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> extractor.extract(file));
    }

    @Test
    void extractCorruptedXlsThrows() throws Exception {
        Path file = tempDir.resolve("bad.xls");
        Files.write(file, "this is not a valid xls".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> extractor.extract(file));
    }

    @Test
    void extractCorruptedPptThrows() throws Exception {
        Path file = tempDir.resolve("bad.ppt");
        Files.write(file, "this is not a valid ppt".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> extractor.extract(file));
    }

    // ── isSupportedExtension edge cases not covered ─────────────────────────

    @Test
    void isSupportedExtensionEmptyString() {
        assertFalse(TextExtractor.isSupportedExtension(""));
    }

    @Test
    void isSupportedExtensionOdfTypes() {
        assertTrue(TextExtractor.isSupportedExtension(".odp"));
        assertTrue(TextExtractor.isSupportedExtension(".ods"));
        assertTrue(TextExtractor.isSupportedExtension(".ODS"));
    }

    // ── EML with corrupt content ────────────────────────────────────────────

    @Test
    void extractCorruptedEmlThrows() throws Exception {
        Path file = tempDir.resolve("corrupt.eml");
        // Write binary garbage — not valid MIME
        Files.write(file, new byte[]{0x00, 0x01, 0x02, (byte) 0xFF});
        // May parse with empty content or throw
        try {
            var result = extractor.extract(file);
            // If it doesn't throw, it should still have a method set
            assertEquals("JAKARTA_MAIL", result.method());
        } catch (IOException e) {
            // Expected for corrupt EML
            assertTrue(e.getMessage().contains("EML"));
        }
    }

    // ── EPUB with mixed entry types ─────────────────────────────────────────

    @Test
    void extractEpubIgnoresNonHtmlEntries() throws Exception {
        Path file = tempDir.resolve("mixed.epub");
        try (var zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write("<container/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("styles/main.css"));
            zos.write("body { color: black; }".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("content.xhtml"));
            zos.write("<html><body><p>EPUB content here</p></body></html>"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        var result = extractor.extract(file);
        assertTrue(result.text().contains("EPUB content here"));
        assertFalse(result.text().contains("color: black"),
                "CSS file should not be included");
        assertEquals("EPUB_JSOUP", result.method());
    }

    // ── Legacy Office success paths (DOC, XLS, PPT) ────────────────────────

    @Test
    void extractXls() throws Exception {
        var wb = new HSSFWorkbook();
        var sheet = wb.createSheet("Sales");
        var row = sheet.createRow(0);
        row.createCell(0).setCellValue("Product");
        row.createCell(1).setCellValue(99.5);
        Path file = tempDir.resolve("test.xls");
        try (var os = Files.newOutputStream(file)) { wb.write(os); }
        wb.close();
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Product"), "XLS text: " + result.text());
        assertTrue(result.text().contains("[Sheet: Sales]"), "XLS sheet name: " + result.text());
        assertEquals("POI_XLS", result.method());
    }

    @Test
    void extractXlsMultipleSheets() throws Exception {
        var wb = new HSSFWorkbook();
        var sheet1 = wb.createSheet("Sheet1");
        sheet1.createRow(0).createCell(0).setCellValue("Data1");
        var sheet2 = wb.createSheet("Sheet2");
        sheet2.createRow(0).createCell(0).setCellValue("Data2");
        Path file = tempDir.resolve("multi-sheet.xls");
        try (var os = Files.newOutputStream(file)) { wb.write(os); }
        wb.close();
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Data1"));
        assertTrue(result.text().contains("Data2"));
        assertTrue(result.text().contains("[Sheet: Sheet1]"));
        assertTrue(result.text().contains("[Sheet: Sheet2]"));
        assertEquals("POI_XLS", result.method());
    }

    @Test
    void extractPpt() throws Exception {
        var ppt = new HSLFSlideShow();
        var slide = ppt.createSlide();
        var tb = slide.addTitle();
        tb.setText("Legacy PPT slide");
        Path file = tempDir.resolve("test.ppt");
        try (var os = Files.newOutputStream(file)) { ppt.write(os); }
        ppt.close();
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Legacy PPT slide"), "PPT text: " + result.text());
        assertEquals("POI_PPT", result.method());
    }

    @Test
    void extractPptMultipleSlides() throws Exception {
        var ppt = new HSLFSlideShow();
        var slide1 = ppt.createSlide();
        slide1.addTitle().setText("Slide one");
        var slide2 = ppt.createSlide();
        slide2.addTitle().setText("Slide two");
        Path file = tempDir.resolve("multi-slide.ppt");
        try (var os = Files.newOutputStream(file)) { ppt.write(os); }
        ppt.close();
        var result = extractor.extract(file);
        assertTrue(result.text().contains("Slide one"));
        assertTrue(result.text().contains("Slide two"));
        assertTrue(result.text().contains("[Slide 1]"));
        assertTrue(result.text().contains("[Slide 2]"));
        assertEquals("POI_PPT", result.method());
    }

    // ── XLSX multiple sheets ────────────────────────────────────────────────

    @Test
    void extractXlsxMultipleSheets() throws Exception {
        var wb = new XSSFWorkbook();
        var s1 = wb.createSheet("Alpha");
        s1.createRow(0).createCell(0).setCellValue("A1");
        var s2 = wb.createSheet("Beta");
        s2.createRow(0).createCell(0).setCellValue("B1");
        Path file = tempDir.resolve("multi.xlsx");
        try (var os = Files.newOutputStream(file)) { wb.write(os); }
        wb.close();
        var result = extractor.extract(file);
        assertTrue(result.text().contains("[Sheet: Alpha]"));
        assertTrue(result.text().contains("[Sheet: Beta]"));
        assertTrue(result.text().contains("A1"));
        assertTrue(result.text().contains("B1"));
        assertEquals("POI_XLSX", result.method());
    }

    // ── PPTX multiple slides with text shapes ───────────────────────────────

    @Test
    void extractPptxMultipleSlides() throws Exception {
        var pptx = new XMLSlideShow();
        var slide1 = pptx.createSlide();
        slide1.createTextBox().setText("PPTX slide one");
        var slide2 = pptx.createSlide();
        slide2.createTextBox().setText("PPTX slide two");
        Path file = tempDir.resolve("multi.pptx");
        try (var os = Files.newOutputStream(file)) { pptx.write(os); }
        pptx.close();
        var result = extractor.extract(file);
        assertTrue(result.text().contains("PPTX slide one"));
        assertTrue(result.text().contains("PPTX slide two"));
        assertTrue(result.text().contains("[Slide 1]"));
        assertTrue(result.text().contains("[Slide 2]"));
        assertEquals("POI_PPTX", result.method());
    }

    // ── ZIP: max bytes exceeded ─────────────────────────────────────────────

    @Test
    void extractZipStopsAtMaxBytes() throws Exception {
        // Create a ZIP with entries totaling more than 50 MB to hit the limit
        Path zipFile = tempDir.resolve("large.zip");
        byte[] bigContent = new byte[1024 * 1024]; // 1 MB per entry
        java.util.Arrays.fill(bigContent, (byte) 'X');
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (int i = 0; i < 55; i++) {
                zos.putNextEntry(new ZipEntry("file" + i + ".txt"));
                zos.write(bigContent);
                zos.closeEntry();
            }
        }
        // Should not throw, just stop reading
        var result = extractor.extract(zipFile);
        assertEquals("ZIP_RECURSE", result.method());
        // Not all 55 entries should be processed
        assertFalse(result.text().contains("[file54.txt]"),
                "Should stop before processing all entries");
    }

    // ── ZIP: entry whose inner extraction fails (fallback to raw UTF-8) ─────

    @Test
    void extractZipFallsBackToRawTextOnBadPdf() throws Exception {
        Path zipFile = tempDir.resolve("badpdf.zip");
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            // A .pdf entry that is not valid PDF — triggers the catch block
            zos.putNextEntry(new ZipEntry("broken.pdf"));
            zos.write("This is not a real PDF".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        var result = extractor.extract(zipFile);
        // The fallback path reads raw bytes as UTF-8
        assertTrue(result.text().contains("This is not a real PDF"),
                "Should fall back to raw text: " + result.text());
        assertEquals("ZIP_RECURSE", result.method());
    }

    // ── EML: message with no From header ────────────────────────────────────

    @Test
    void extractEmlNoFromHeader() throws Exception {
        Path file = tempDir.resolve("nofrom.eml");
        String eml = "Subject: No From\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "Body without from";
        Files.writeString(file, eml);
        var result = extractor.extract(file);
        assertTrue(result.text().contains("No From"));
        assertTrue(result.text().contains("Body without from"));
        assertFalse(result.text().contains("From:"));
        assertEquals("JAKARTA_MAIL", result.method());
    }

    // ── EML: message with no Subject header ─────────────────────────────────

    @Test
    void extractEmlNoSubjectHeader() throws Exception {
        Path file = tempDir.resolve("nosubject.eml");
        String eml = "From: test@test.com\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "Body without subject";
        Files.writeString(file, eml);
        var result = extractor.extract(file);
        assertTrue(result.text().contains("From:"));
        assertTrue(result.text().contains("Body without subject"));
        assertFalse(result.text().contains("Subject:"));
        assertEquals("JAKARTA_MAIL", result.method());
    }

    // ── DOCX with multiple paragraphs ───────────────────────────────────────

    @Test
    void extractDocxMultipleParagraphs() throws Exception {
        var doc = new XWPFDocument();
        doc.createParagraph().createRun().setText("First paragraph");
        doc.createParagraph().createRun().setText("Second paragraph");
        doc.createParagraph().createRun().setText("Third paragraph");
        Path file = tempDir.resolve("multi-para.docx");
        try (var os = Files.newOutputStream(file)) { doc.write(os); }
        doc.close();
        var result = extractor.extract(file);
        assertTrue(result.text().contains("First paragraph"));
        assertTrue(result.text().contains("Second paragraph"));
        assertTrue(result.text().contains("Third paragraph"));
        assertEquals("POI_DOCX", result.method());
    }

    // ── RTF: extraction with bad location (error path) ──────────────────────

    @Test
    void extractRtfInvalidContent() {
        // Completely empty file — RTFEditorKit may throw or produce empty text
        Path file = tempDir.resolve("empty.rtf");
        try {
            Files.writeString(file, "");
            var result = extractor.extract(file);
            // If it doesn't throw, the text should be empty
            assertNotNull(result.text());
            assertEquals("RTF_EDITOR_KIT", result.method());
        } catch (IOException e) {
            // Expected for invalid RTF
            assertTrue(true);
        }
    }

    // ── PPTX with empty slide (no text shapes) ─────────────────────────────

    @Test
    void extractPptxEmptySlide() throws Exception {
        var pptx = new XMLSlideShow();
        pptx.createSlide(); // slide with no shapes
        Path file = tempDir.resolve("empty-slide.pptx");
        try (var os = Files.newOutputStream(file)) { pptx.write(os); }
        pptx.close();
        var result = extractor.extract(file);
        assertTrue(result.text().contains("[Slide 1]"));
        assertEquals("POI_PPTX", result.method());
    }

    // ── PPT with empty slide (no text shapes) ──────────────────────────────

    @Test
    void extractPptEmptySlide() throws Exception {
        var ppt = new HSLFSlideShow();
        ppt.createSlide(); // slide with no shapes
        Path file = tempDir.resolve("empty-slide.ppt");
        try (var os = Files.newOutputStream(file)) { ppt.write(os); }
        ppt.close();
        var result = extractor.extract(file);
        assertTrue(result.text().contains("[Slide 1]"));
        assertEquals("POI_PPT", result.method());
    }
}
