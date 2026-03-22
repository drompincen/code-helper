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

    public UploadService(DuckDBDataSource dataSource, AppConfig config) {
        this.dataSource = dataSource;
        this.config = config;
    }

    public String upload(String fileName, String originalClientPath, String mediaType,
                         long sizeBytes, byte[] content) throws IOException, SQLException {
        String existing = findExisting(fileName, sizeBytes);
        if (existing != null) {
            log.info("Dedup: returning existing artifact {} for {} ({} bytes)", existing, fileName, sizeBytes);
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

    private String findExisting(String fileName, long sizeBytes) throws SQLException {
        Connection conn = dataSource.getConnection();
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
