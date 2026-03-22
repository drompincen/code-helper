# Plan: Rich File Type Support

## Goal

Extend `TextExtractor` to handle Office documents, HTML, eBook, and archive formats using
100% Java open-source libraries — no native binaries, no cloud APIs.

## New File Types

| Extension | Format | Library | Extraction strategy |
|-----------|--------|---------|---------------------|
| `.docx` | Word 2007+ | Apache POI `poi-ooxml` | `XWPFWordExtractor` → paragraphs + tables |
| `.pptx` | PowerPoint 2007+ | Apache POI `poi-ooxml` | `XSLFTextShape` per slide → slide text |
| `.xlsx` | Excel 2007+ | Apache POI `poi-ooxml` | `XSSFSheet` → cell values, sheet names |
| `.doc` | Word 97-2003 | Apache POI `poi-scratchpad` | `WordExtractor` |
| `.ppt` | PPT 97-2003 | Apache POI `poi-scratchpad` | `PowerPointExtractor` |
| `.xls` | Excel 97-2003 | Apache POI `poi-scratchpad` | `ExcelExtractor` |
| `.odt` | LibreOffice Writer | Apache ODF Toolkit `odftoolkit` | XML content.xml SAX parse |
| `.odp` | LibreOffice Impress | Apache ODF Toolkit `odftoolkit` | XML content.xml SAX parse |
| `.ods` | LibreOffice Calc | Apache ODF Toolkit `odftoolkit` | XML content.xml SAX parse |
| `.html`, `.htm` | HTML | Jsoup | `Jsoup.parse().text()` — strips tags |
| `.epub` | eBook | epublib-core | Unzip OPS/*.html → Jsoup per chapter |
| `.rtf` | Rich Text | Java built-in `RTFEditorKit` | `javax.swing.text.rtf.RTFEditorKit` |
| `.eml` | Email (RFC 822) | Jakarta Mail `jakarta.mail` | Headers + body text + inline text parts |
| `.zip` | Archive | JDK `java.util.zip` | Recurse into entries, extract each |

## Libraries

```xml
<!-- Apache POI — OOXML (docx, pptx, xlsx) + legacy (doc, ppt, xls) -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.3.0</version>
</dependency>
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-scratchpad</artifactId>
    <version>5.3.0</version>
</dependency>

<!-- Apache ODF Toolkit — odt, odp, ods -->
<dependency>
    <groupId>org.odftoolkit</groupId>
    <artifactId>odfdom-java</artifactId>
    <version>0.12.0</version>
</dependency>

<!-- Jsoup — HTML/HTM -->
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.18.1</version>
</dependency>

<!-- epublib — EPUB -->
<dependency>
    <groupId>io.github.kevinhartman</groupId>
    <artifactId>epublib-core</artifactId>
    <version>4.1</version>
</dependency>

<!-- Jakarta Mail — EML -->
<dependency>
    <groupId>org.eclipse.angus</groupId>
    <artifactId>angus-mail</artifactId>
    <version>2.0.3</version>
</dependency>
```

RTF and ZIP use JDK built-ins — no new dependency.

## Architecture

`TextExtractor.extract(Path)` is the single dispatch point. Pattern stays the same:

```
extract(path)
  ├── .pdf          → extractPdf()            [PDFBox — already exists]
  ├── TEXT_EXTENSIONS → extractText()         [plain UTF-8 — already exists]
  ├── OFFICE_OOXML    → extractOfficeOoxml()  [POI ooxml — Agent 1]
  ├── OFFICE_LEGACY   → extractOfficeLegacy() [POI scratchpad — Agent 1]
  ├── ODF_EXTENSIONS  → extractOdf()          [ODF Toolkit — Agent 1]
  ├── .html/.htm      → extractHtml()         [Jsoup — Agent 2]
  ├── .epub           → extractEpub()         [epublib — Agent 2]
  ├── .rtf            → extractRtf()          [RTFEditorKit — Agent 2]
  ├── .eml            → extractEml()          [Jakarta Mail — Agent 2]
  └── .zip            → extractZip()          [java.util.zip — Agent 2]
```

`isSupportedExtension()` and `TEXT_EXTENSIONS` set updated to include all new extensions.

Default extension lists in `JavaDuckerMcpServer.java`, `run-server.cmd`, `run-server.sh`, and `README.md` are updated in Agent 3.

---

## Parallel Agent Assignments

Agents 1 and 2 work simultaneously on independent extractor branches.
Agent 3 runs in parallel updating config/docs.
Agent 4 runs after all three complete to write tests and run `mvn test`.

---

### Agent 1 — Office formats: DOCX, PPTX, XLSX, DOC, PPT, XLS, ODT, ODP, ODS

**Files to modify:**
- `pom.xml` — add `poi-ooxml`, `poi-scratchpad`, `odfdom-java`
- `TextExtractor.java` — add Office extraction branches

#### pom.xml additions (after pdfbox dependency)
```xml
<!-- Apache POI — OOXML: docx, pptx, xlsx -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.3.0</version>
</dependency>

<!-- Apache POI — Scratchpad: doc, ppt, xls -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-scratchpad</artifactId>
    <version>5.3.0</version>
</dependency>

<!-- Apache ODF Toolkit — odt, odp, ods -->
<dependency>
    <groupId>org.odftoolkit</groupId>
    <artifactId>odfdom-java</artifactId>
    <version>0.12.0</version>
</dependency>
```

#### TextExtractor.java additions

Add to constants:
```java
private static final Set<String> OFFICE_OOXML = Set.of(".docx", ".pptx", ".xlsx");
private static final Set<String> OFFICE_LEGACY = Set.of(".doc", ".ppt", ".xls");
private static final Set<String> ODF_EXTENSIONS = Set.of(".odt", ".odp", ".ods");
```

Add to `extract()` dispatch (before the `throw` at end):
```java
if (OFFICE_OOXML.contains(ext))   return extractOfficeOoxml(filePath, ext);
if (OFFICE_LEGACY.contains(ext))  return extractOfficeLegacy(filePath, ext);
if (ODF_EXTENSIONS.contains(ext)) return extractOdf(filePath);
```

Update `isSupportedExtension()` to include all new sets.

#### DOCX extraction
```java
private ExtractionResult extractDocx(Path filePath) throws IOException {
    try (var pkg = org.apache.poi.xwpf.usermodel.XWPFDocument(
            java.io.Files.newInputStream(filePath))) {
        var extractor = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(pkg);
        return new ExtractionResult(extractor.getText(), "POI_DOCX");
    }
}
```

#### PPTX extraction — slide-by-slide, prepend slide number
```java
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
```

#### XLSX extraction — sheet name + cell values
```java
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
                for (var cell : row) {
                    line.add(fmt.formatCellValue(cell));
                }
                sb.append(line).append("\n");
            }
            sb.append("\n");
        }
        return new ExtractionResult(sb.toString(), "POI_XLSX");
    }
}
```

#### DOC extraction (legacy Word 97-2003)
```java
private ExtractionResult extractDoc(Path filePath) throws IOException {
    try (var fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem(filePath.toFile())) {
        var extractor = new org.apache.poi.hwpf.extractor.WordExtractor(fs);
        return new ExtractionResult(extractor.getText(), "POI_DOC");
    }
}
```

#### PPT extraction (legacy PowerPoint 97-2003)
```java
private ExtractionResult extractPpt(Path filePath) throws IOException {
    try (var fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem(filePath.toFile())) {
        var extractor = new org.apache.poi.hslf.extractor.PowerPointExtractor(fs);
        return new ExtractionResult(extractor.getText(), "POI_PPT");
    }
}
```

#### XLS extraction (legacy Excel 97-2003)
```java
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
```

#### extractOfficeOoxml() dispatcher
```java
private ExtractionResult extractOfficeOoxml(Path p, String ext) throws IOException {
    return switch (ext) {
        case ".docx" -> extractDocx(p);
        case ".pptx" -> extractPptx(p);
        case ".xlsx" -> extractXlsx(p);
        default      -> throw new IOException("Unknown OOXML type: " + ext);
    };
}
```

#### extractOfficeLegacy() dispatcher
```java
private ExtractionResult extractOfficeLegacy(Path p, String ext) throws IOException {
    return switch (ext) {
        case ".doc" -> extractDoc(p);
        case ".ppt" -> extractPpt(p);
        case ".xls" -> extractXls(p);
        default     -> throw new IOException("Unknown legacy Office type: " + ext);
    };
}
```

#### ODF extraction (ODT/ODP/ODS share the same structure)
```java
private ExtractionResult extractOdf(Path filePath) throws IOException {
    // ODF files are ZIP archives; content.xml holds all text in draw:text-box/text:p elements
    try (var zis = new java.util.zip.ZipInputStream(
            java.nio.file.Files.newInputStream(filePath))) {
        java.util.zip.ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if ("content.xml".equals(entry.getName())) {
                String xml = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                // Strip XML tags; text content sits between tags
                String text = xml.replaceAll("<[^>]+>", " ")
                                 .replaceAll("\\s{2,}", "\n")
                                 .strip();
                return new ExtractionResult(text, "ODF_XML");
            }
        }
    }
    throw new IOException("content.xml not found in ODF archive: " + filePath);
}
```

---

### Agent 2 — HTML, EPUB, RTF, EML, ZIP

**Files to modify:**
- `pom.xml` — add `jsoup`, `epublib-core`, `angus-mail`
- `TextExtractor.java` — add HTML/EPUB/RTF/EML/ZIP extraction branches

#### pom.xml additions
```xml
<!-- Jsoup — HTML/HTM -->
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.18.1</version>
</dependency>

<!-- epublib — EPUB -->
<dependency>
    <groupId>io.github.kevinhartman</groupId>
    <artifactId>epublib-core</artifactId>
    <version>4.1</version>
</dependency>

<!-- Jakarta Mail — EML -->
<dependency>
    <groupId>org.eclipse.angus</groupId>
    <artifactId>angus-mail</artifactId>
    <version>2.0.3</version>
</dependency>
```

#### TextExtractor.java additions

Add to dispatch:
```java
if (".html".equals(ext) || ".htm".equals(ext)) return extractHtml(filePath);
if (".epub".equals(ext))                        return extractEpub(filePath);
if (".rtf".equals(ext))                         return extractRtf(filePath);
if (".eml".equals(ext))                         return extractEml(filePath);
if (".zip".equals(ext))                         return extractZip(filePath);
```

#### HTML extraction
```java
private ExtractionResult extractHtml(Path filePath) throws IOException {
    String html = java.nio.file.Files.readString(filePath, java.nio.charset.StandardCharsets.UTF_8);
    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
    // Remove script and style elements
    doc.select("script, style, noscript").remove();
    String text = doc.body() != null ? doc.body().text() : doc.text();
    return new ExtractionResult(text, "JSOUP_HTML");
}
```

#### EPUB extraction
```java
private ExtractionResult extractEpub(Path filePath) throws IOException {
    try (var is = java.nio.file.Files.newInputStream(filePath)) {
        nl.siegmann.epublib.epub.EpubReader reader = new nl.siegmann.epublib.epub.EpubReader();
        nl.siegmann.epublib.domain.Book book = reader.readEpub(is);
        var sb = new StringBuilder();
        // Title
        sb.append(book.getTitle()).append("\n\n");
        // Spine order (reading order)
        for (nl.siegmann.epublib.domain.SpineReference ref : book.getSpine().getSpineReferences()) {
            byte[] content = ref.getResource().getData();
            String html = new String(content, java.nio.charset.StandardCharsets.UTF_8);
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
            doc.select("script, style").remove();
            sb.append(doc.text()).append("\n\n");
        }
        return new ExtractionResult(sb.toString().strip(), "EPUBLIB");
    }
}
```

Note: EPUB depends on Jsoup for HTML parsing of chapter content, so Jsoup must be present.

#### RTF extraction (JDK built-in — no extra dependency)
```java
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
```

#### EML extraction
```java
private ExtractionResult extractEml(Path filePath) throws IOException {
    try (var is = java.nio.file.Files.newInputStream(filePath)) {
        var session = jakarta.mail.Session.getDefaultInstance(new java.util.Properties());
        var message = new jakarta.mail.internet.MimeMessage(session, is);
        var sb = new StringBuilder();
        // Headers
        sb.append("From: ").append(java.util.Arrays.toString(message.getFrom())).append("\n");
        sb.append("Subject: ").append(message.getSubject()).append("\n\n");
        // Body
        appendMailPart(message, sb);
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
```

#### ZIP extraction (recursive — respects depth limit to avoid zip bombs)
```java
private static final int ZIP_MAX_ENTRIES = 500;
private static final long ZIP_MAX_BYTES  = 50 * 1024 * 1024; // 50 MB total extracted

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
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";

            // Only extract text-like files inside the ZIP
            if (!TEXT_EXTENSIONS.contains(ext) && !".pdf".equals(ext)) {
                zis.closeEntry();
                continue;
            }

            byte[] content = zis.readAllBytes();
            totalBytes += content.length;
            if (totalBytes > ZIP_MAX_BYTES) break;

            sb.append("[").append(entry.getName()).append("]\n");
            try {
                // Recursively extract if it's a supported type
                Path tmp = java.nio.file.Files.createTempFile("jd-zip-", ext);
                try {
                    java.nio.file.Files.write(tmp, content);
                    ExtractionResult inner = extract(tmp);
                    sb.append(inner.text()).append("\n\n");
                } finally {
                    java.nio.file.Files.deleteIfExists(tmp);
                }
            } catch (Exception e) {
                // Fallback: try UTF-8 decode
                sb.append(new String(content, java.nio.charset.StandardCharsets.UTF_8)).append("\n\n");
            }
            entries++;
        }
    }
    return new ExtractionResult(sb.toString().strip(), "ZIP_RECURSE");
}
```

---

### Agent 3 — Config, extension lists, docs (runs in parallel with Agents 1 & 2)

**Files to modify:**
- `JavaDuckerMcpServer.java` — update default extensions string
- `JavaDuckerClient.java` — update `--ext` default value description
- `README.md` — update supported file types table
- `run-server.cmd` / `run-server.sh` — no change needed (extensions are client-side defaults)

#### JavaDuckerMcpServer.java
Find the string `".java,.xml,.md,.yml,.json,.txt,.pdf"` (appears in `javaducker_index_directory` tool description and `indexDirectory()` default). Update to:
```
".java,.xml,.md,.yml,.json,.txt,.pdf,.docx,.pptx,.xlsx,.doc,.ppt,.xls,.odt,.odp,.ods,.html,.htm,.epub,.rtf,.eml"
```

#### JavaDuckerClient.java
Find the `--ext` option default value and update similarly.

#### README.md
Add or update a "Supported File Types" section:

```markdown
## Supported File Types

| Category | Extensions | Library |
|----------|-----------|---------|
| Source code | `.java` `.kt` `.scala` `.py` `.js` `.ts` `.go` `.rs` `.c` `.cpp` `.h` `.rb` `.php` `.swift` | JDK (UTF-8) |
| Config / data | `.xml` `.json` `.yml` `.yaml` `.toml` `.properties` `.sql` `.csv` `.ini` `.cfg` | JDK (UTF-8) |
| Docs | `.md` `.txt` `.rst` | JDK (UTF-8) |
| PDF | `.pdf` | Apache PDFBox |
| Word | `.docx` `.doc` | Apache POI |
| PowerPoint | `.pptx` `.ppt` | Apache POI |
| Excel | `.xlsx` `.xls` | Apache POI |
| LibreOffice | `.odt` `.odp` `.ods` | Apache ODF Toolkit |
| HTML | `.html` `.htm` | Jsoup |
| eBook | `.epub` | epublib + Jsoup |
| Rich Text | `.rtf` | JDK RTFEditorKit |
| Email | `.eml` | Jakarta Mail |
| Archive | `.zip` | JDK (recurse into text entries) |
```

---

### Agent 4 — Tests + mvn test (runs after Agents 1, 2, 3 complete)

**File to modify:** `src/test/java/com/javaducker/server/ingestion/TextExtractorTest.java`

Add test methods for each new format. Since creating real binary Office/EPUB files in a unit test is complex, use the following strategies:

**PPTX test** — create a real minimal PPTX using POI:
```java
@Test
void extractPptx() throws Exception {
    var pptx = new org.apache.poi.xslf.usermodel.XMLSlideShow();
    var slide = pptx.createSlide();
    var tb = slide.createTextBox();
    tb.setText("Hello from slide 1");
    Path file = tempDir.resolve("test.pptx");
    try (var os = java.nio.file.Files.newOutputStream(file)) { pptx.write(os); }
    var result = extractor.extract(file);
    assertTrue(result.text().contains("Hello from slide 1"));
    assertEquals("POI_PPTX", result.method());
}
```

**DOCX test** — create a real minimal DOCX using POI:
```java
@Test
void extractDocx() throws Exception {
    var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument();
    doc.createParagraph().createRun().setText("Hello from docx");
    Path file = tempDir.resolve("test.docx");
    try (var os = java.nio.file.Files.newOutputStream(file)) { doc.write(os); }
    var result = extractor.extract(file);
    assertTrue(result.text().contains("Hello from docx"));
    assertEquals("POI_DOCX", result.method());
}
```

**XLSX test** — create a real minimal XLSX using POI:
```java
@Test
void extractXlsx() throws Exception {
    var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
    var sheet = wb.createSheet("Data");
    sheet.createRow(0).createCell(0).setCellValue("Revenue");
    sheet.getRow(0).createCell(1).setCellValue(42000.0);
    Path file = tempDir.resolve("test.xlsx");
    try (var os = java.nio.file.Files.newOutputStream(file)) { wb.write(os); }
    var result = extractor.extract(file);
    assertTrue(result.text().contains("Revenue"));
    assertEquals("POI_XLSX", result.method());
}
```

**HTML test**:
```java
@Test
void extractHtml() throws Exception {
    Path file = tempDir.resolve("page.html");
    Files.writeString(file, "<html><body><h1>Title</h1><p>Body text</p><script>var x=1;</script></body></html>");
    var result = extractor.extract(file);
    assertTrue(result.text().contains("Title"));
    assertTrue(result.text().contains("Body text"));
    assertFalse(result.text().contains("var x=1"));  // script stripped
    assertEquals("JSOUP_HTML", result.method());
}
```

**RTF test**:
```java
@Test
void extractRtf() throws Exception {
    Path file = tempDir.resolve("test.rtf");
    // Minimal valid RTF document
    Files.writeString(file, "{\\rtf1\\ansi {\\fonttbl {\\f0 Times New Roman;}} {\\f0 Hello RTF world}}");
    var result = extractor.extract(file);
    assertTrue(result.text().contains("Hello RTF world"));
    assertEquals("RTF_EDITOR_KIT", result.method());
}
```

**ZIP test** — ZIP containing a .txt file:
```java
@Test
void extractZip() throws Exception {
    Path zipFile = tempDir.resolve("archive.zip");
    try (var zos = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(zipFile))) {
        zos.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
        zos.write("Content inside zip".getBytes());
        zos.closeEntry();
    }
    var result = extractor.extract(zipFile);
    assertTrue(result.text().contains("Content inside zip"));
    assertEquals("ZIP_RECURSE", result.method());
}
```

**isSupportedExtension test update**:
```java
@Test
void isSupportedExtensionIncludesNewTypes() {
    assertTrue(TextExtractor.isSupportedExtension(".pptx"));
    assertTrue(TextExtractor.isSupportedExtension(".docx"));
    assertTrue(TextExtractor.isSupportedExtension(".xlsx"));
    assertTrue(TextExtractor.isSupportedExtension(".html"));
    assertTrue(TextExtractor.isSupportedExtension(".epub"));
    assertTrue(TextExtractor.isSupportedExtension(".rtf"));
    assertTrue(TextExtractor.isSupportedExtension(".eml"));
    assertTrue(TextExtractor.isSupportedExtension(".zip"));
    assertFalse(TextExtractor.isSupportedExtension(".exe"));
    assertFalse(TextExtractor.isSupportedExtension(".png"));
}
```

After writing tests, run:
```
mvn test
```
Fix any failures (likely `ClassNotFoundException` for missing deps or wrong import paths in POI 5.x).

**Known POI 5.x import note:** In POI 5.x, `XWPFWordExtractor` is in `org.apache.poi.xwpf.extractor` and takes an `XWPFDocument`. Verify exact constructor signatures if the compiler complains.

---

## File Change Summary

| File | Agent | Change |
|------|-------|--------|
| `pom.xml` | 1 + 2 | Add poi-ooxml, poi-scratchpad, odfdom-java, jsoup, epublib-core, angus-mail |
| `TextExtractor.java` | 1 + 2 | Add 13 new extraction methods + dispatch branches + constants |
| `JavaDuckerMcpServer.java` | 3 | Update default extensions string |
| `JavaDuckerClient.java` | 3 | Update `--ext` default description |
| `README.md` | 3 | Add supported file types table |
| `TextExtractorTest.java` | 4 | Add 7 new test methods |

## Verification Checklist

- [ ] `mvn test` passes (all existing 58 tests + 7 new = 65+ tests)
- [ ] PPTX: slide text extracted with `[Slide N]` headers
- [ ] DOCX: paragraph text extracted
- [ ] XLSX: cell values + sheet names extracted
- [ ] HTML: tags stripped, script/style removed
- [ ] RTF: plain text extracted
- [ ] ZIP: text file contents extracted recursively
- [ ] `.exe`, `.png` still throw `IOException("Unsupported file type")`
- [ ] `isSupportedExtension(".pptx")` returns `true`
- [ ] MCP `javaducker_index_directory` default extensions include `.pptx`, `.docx`, `.xlsx`, `.html`

## Notes on epublib

The `epublib-core` artifact is on Maven Central under `io.github.kevinhartman:epublib-core:4.1`.
If this artifact is unavailable (old fork not on central), alternative: `nl.siegmann.epublib:epublib-core:3.1` from the original author.
EPUB test is harder to write without a real `.epub` file — skip unit test for EPUB in Agent 4 and note it as a manual verification item.
