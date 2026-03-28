package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.model.ArtifactStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private final DuckDBDataSource dataSource;
    private final AppConfig config;
    private final ArtifactService artifactService;

    public UploadService(DuckDBDataSource dataSource, AppConfig config, ArtifactService artifactService) {
        this.dataSource = dataSource;
        this.config = config;
        this.artifactService = artifactService;
    }

    public String upload(String fileName, String originalClientPath, String mediaType,
                         long sizeBytes, byte[] content) throws IOException, SQLException {
        String existing = findExisting(fileName, originalClientPath, sizeBytes);
        if (existing != null) {
            log.info("Re-indexing existing artifact {} for {} ({} bytes)", existing, fileName, sizeBytes);
            artifactService.deleteArtifactData(existing);

            Path intakePath = storeInIntake(existing, fileName, content);
            String sha256 = computeChecksum(content);

            updateArtifactForReindex(existing, sha256, sizeBytes, intakePath.toString(),
                    fileName, mediaType, originalClientPath);
            log.info("Reset artifact {} for re-ingestion ({}), {} bytes", existing, fileName, sizeBytes);
            return existing;
        }

        String artifactId = UUID.randomUUID().toString();

        Path intakePath = storeInIntake(artifactId, fileName, content);
        String sha256 = computeChecksum(content);

        createArtifactRecord(artifactId, fileName, mediaType, originalClientPath,
                intakePath.toString(), sizeBytes, sha256);

        log.info("Uploaded artifact {} ({}), {} bytes", artifactId, fileName, sizeBytes);
        return artifactId;
    }

    private String findExisting(String fileName, String originalClientPath, long sizeBytes) throws SQLException {
        Connection conn = dataSource.getConnection();
        // Primary dedup: same absolute path already indexed (catches re-runs on same codebase)
        if (originalClientPath != null && !originalClientPath.isBlank()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT artifact_id FROM artifacts WHERE original_client_path = ? AND status != 'FAILED' LIMIT 1")) {
                ps.setString(1, originalClientPath);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString("artifact_id");
                }
            }
        }
        // Fallback dedup: same name + size (catches identical files at different paths)
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT artifact_id FROM artifacts WHERE file_name = ? AND size_bytes = ? AND status != 'FAILED' LIMIT 1")) {
            ps.setString(1, fileName);
            ps.setLong(2, sizeBytes);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("artifact_id") : null;
            }
        }
    }

    private Path storeInIntake(String artifactId, String fileName, byte[] content) throws IOException {
        Path artifactDir = Path.of(config.getIntakeDir(), artifactId);
        Files.createDirectories(artifactDir);
        Path filePath = artifactDir.resolve(fileName);
        Files.write(filePath, content);
        return filePath;
    }

    public static String computeChecksum(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private void updateArtifactForReindex(String artifactId, String sha256, long sizeBytes,
                                          String intakePath, String fileName, String mediaType,
                                          String originalClientPath) throws SQLException {
        // DuckDB UPDATE can fail with PK constraint on ART index — use DELETE+INSERT
        dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM artifacts WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO artifacts (artifact_id, file_name, media_type, original_client_path,
                        intake_path, size_bytes, sha256, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, artifactId);
                ps.setString(2, fileName);
                ps.setString(3, mediaType);
                ps.setString(4, originalClientPath);
                ps.setString(5, intakePath);
                ps.setLong(6, sizeBytes);
                ps.setString(7, sha256);
                ps.setString(8, ArtifactStatus.STORED_IN_INTAKE.name());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void createArtifactRecord(String artifactId, String fileName, String mediaType,
                                       String originalClientPath, String intakePath,
                                       long sizeBytes, String sha256) throws SQLException {
        Connection conn = dataSource.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO artifacts (artifact_id, file_name, media_type, original_client_path,
                    intake_path, size_bytes, sha256, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, artifactId);
            ps.setString(2, fileName);
            ps.setString(3, mediaType);
            ps.setString(4, originalClientPath);
            ps.setString(5, intakePath);
            ps.setLong(6, sizeBytes);
            ps.setString(7, sha256);
            ps.setString(8, ArtifactStatus.STORED_IN_INTAKE.name());
            ps.executeUpdate();
        }
    }
}
