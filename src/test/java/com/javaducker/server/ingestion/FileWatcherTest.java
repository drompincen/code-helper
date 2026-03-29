package com.javaducker.server.ingestion;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.service.ArtifactService;
import com.javaducker.server.service.ReladomoService;
import com.javaducker.server.service.SearchService;
import com.javaducker.server.service.UploadService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileWatcherTest {

    @TempDir
    Path tempDir;

    private DuckDBDataSource dataSource;
    private UploadService uploadService;
    private FileWatcher fileWatcher;

    @BeforeEach
    void setUp() throws Exception {
        AppConfig config = new AppConfig();
        config.setDbPath(tempDir.resolve("test.duckdb").toString());
        config.setIntakeDir(tempDir.resolve("intake").toString());
        config.setChunkSize(200);
        config.setChunkOverlap(50);
        config.setEmbeddingDim(64);
        config.setIngestionWorkerThreads(1);

        dataSource = new DuckDBDataSource(config);
        ArtifactService artifactService = new ArtifactService(dataSource);
        uploadService = new UploadService(dataSource, config, artifactService);
        SearchService searchService = new SearchService(dataSource, new EmbeddingService(config), config);
        IngestionWorker worker = new IngestionWorker(dataSource, artifactService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                new EmbeddingService(config), new FileSummarizer(), new ImportParser(),
                new ReladomoXmlParser(), new ReladomoService(dataSource),
                new ReladomoFinderParser(), new ReladomoConfigParser(),
                searchService, config);

        SchemaBootstrap bootstrap = new SchemaBootstrap(dataSource, config, worker);
        bootstrap.bootstrap();

        fileWatcher = new FileWatcher(uploadService);
    }

    @AfterEach
    void tearDown() {
        fileWatcher.stopWatching();
        dataSource.close();
    }

    @Test
    void isWatchingInitiallyFalse() {
        assertFalse(fileWatcher.isWatching());
    }

    @Test
    void getWatchedDirectoryInitiallyNull() {
        assertNull(fileWatcher.getWatchedDirectory());
    }

    @Test
    void startAndStop() throws IOException {
        Path watchDir = tempDir.resolve("watched");
        Files.createDirectory(watchDir);

        fileWatcher.startWatching(watchDir, Set.of());

        assertTrue(fileWatcher.isWatching(), "Should be watching after startWatching");
        assertEquals(watchDir, fileWatcher.getWatchedDirectory());

        fileWatcher.stopWatching();

        assertFalse(fileWatcher.isWatching(), "Should not be watching after stopWatching");
        assertNull(fileWatcher.getWatchedDirectory(), "Watched directory should be null after stop");
    }

    @Test
    void startWatchingSetsDirectory() throws IOException {
        Path watchDir = tempDir.resolve("watched2");
        Files.createDirectory(watchDir);

        fileWatcher.startWatching(watchDir, Set.of(".java", ".txt"));

        assertEquals(watchDir, fileWatcher.getWatchedDirectory());
    }

    @Test
    void stopWhenNotWatching() {
        assertDoesNotThrow(() -> fileWatcher.stopWatching());
        assertFalse(fileWatcher.isWatching());
    }

    @Test
    void startWatchingTwiceRestartsCleanly() throws IOException {
        Path dir1 = tempDir.resolve("dir1");
        Path dir2 = tempDir.resolve("dir2");
        Files.createDirectory(dir1);
        Files.createDirectory(dir2);

        fileWatcher.startWatching(dir1, Set.of());
        assertTrue(fileWatcher.isWatching());
        assertEquals(dir1, fileWatcher.getWatchedDirectory());

        fileWatcher.startWatching(dir2, Set.of(".java"));
        assertTrue(fileWatcher.isWatching());
        assertEquals(dir2, fileWatcher.getWatchedDirectory());
    }

    @Test
    void startWatchingWithEmptyExtensions() throws IOException {
        Path watchDir = tempDir.resolve("watched3");
        Files.createDirectory(watchDir);

        fileWatcher.startWatching(watchDir, Set.of());
        assertTrue(fileWatcher.isWatching());
    }

    @Test
    void stopWatchingClearsState() throws IOException {
        Path watchDir = tempDir.resolve("watched4");
        Files.createDirectory(watchDir);

        fileWatcher.startWatching(watchDir, Set.of());
        assertTrue(fileWatcher.isWatching());
        assertNotNull(fileWatcher.getWatchedDirectory());

        fileWatcher.stopWatching();
        assertFalse(fileWatcher.isWatching());
        assertNull(fileWatcher.getWatchedDirectory());
    }

    @Test
    void watcherDetectsNewFile() throws Exception {
        Path watchDir = tempDir.resolve("detect");
        Files.createDirectory(watchDir);

        fileWatcher.startWatching(watchDir, Set.of());

        // Create a file in the watched directory
        Path testFile = watchDir.resolve("test.txt");
        Files.writeString(testFile, "Hello, FileWatcher!");

        // Give the watcher thread time to pick up the event and process it
        Thread.sleep(3000);

        // Verify an artifact was created in the database
        long count = dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM artifacts WHERE file_name = 'test.txt'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        assertTrue(count >= 1, "FileWatcher should have uploaded the detected file, artifact count: " + count);
    }

    @Test
    void watcherSkipsExcludedDirs() throws Exception {
        Path watchDir = tempDir.resolve("excluded");
        Files.createDirectory(watchDir);
        Path gitDir = watchDir.resolve(".git");
        Files.createDirectories(gitDir);

        fileWatcher.startWatching(watchDir, Set.of());

        // Create a file inside an excluded directory
        Files.writeString(gitDir.resolve("config"), "excluded content");

        Thread.sleep(2000);

        // File inside .git should not trigger upload
        long count = dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM artifacts WHERE file_name = 'config'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        assertEquals(0, count, "File inside .git should not be uploaded");
    }

    @Test
    void watcherRespectsExtensionFilter() throws Exception {
        Path watchDir = tempDir.resolve("filter");
        Files.createDirectory(watchDir);

        fileWatcher.startWatching(watchDir, Set.of(".java"));

        Files.writeString(watchDir.resolve("ignored.txt"), "should be ignored");
        Files.writeString(watchDir.resolve("Main.java"), "public class Main {}");

        Thread.sleep(3000);

        long txtCount = dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM artifacts WHERE file_name = 'ignored.txt'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });

        long javaCount = dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM artifacts WHERE file_name = 'Main.java'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });

        assertEquals(0, txtCount, ".txt file should be filtered out when only .java is allowed");
        assertTrue(javaCount >= 1, ".java file should be uploaded when .java extension is allowed");
    }
}
