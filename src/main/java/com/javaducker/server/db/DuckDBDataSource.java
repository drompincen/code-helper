package com.javaducker.server.db;

import com.javaducker.server.config.AppConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

@Component
public class DuckDBDataSource {

    private static final Logger log = LoggerFactory.getLogger(DuckDBDataSource.class);
    private final String dbUrl;
    private Connection sharedConnection;

    public DuckDBDataSource(AppConfig config) throws IOException, SQLException {
        Path dbPath = Path.of(config.getDbPath());
        Files.createDirectories(dbPath.getParent());
        this.dbUrl = "jdbc:duckdb:" + dbPath.toAbsolutePath();
        this.sharedConnection = DriverManager.getConnection(dbUrl);
        log.info("DuckDB connection opened: {}", dbPath.toAbsolutePath());
    }

    public synchronized Connection getConnection() throws SQLException {
        if (sharedConnection == null || sharedConnection.isClosed()) {
            sharedConnection = DriverManager.getConnection(dbUrl);
        }
        return sharedConnection;
    }

    @PreDestroy
    public void close() {
        try {
            if (sharedConnection != null && !sharedConnection.isClosed()) {
                sharedConnection.close();
                log.info("DuckDB connection closed");
            }
        } catch (SQLException e) {
            log.warn("Error closing DuckDB connection", e);
        }
    }
}
