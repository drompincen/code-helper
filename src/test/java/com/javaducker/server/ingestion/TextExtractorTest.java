package com.javaducker.server.ingestion;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
}
