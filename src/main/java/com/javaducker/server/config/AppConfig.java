package com.javaducker.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "javaducker")
public class AppConfig {

    private String dbPath = "data/javaducker.duckdb";
    private String intakeDir = "temp/intake";
    private int chunkSize = 1000;
    private int chunkOverlap = 200;
    private int embeddingDim = 256;
    private int ingestionPollSeconds = 5;
    private int maxSearchResults = 20;

    public String getDbPath() { return dbPath; }
    public void setDbPath(String dbPath) { this.dbPath = dbPath; }

    public String getIntakeDir() { return intakeDir; }
    public void setIntakeDir(String intakeDir) { this.intakeDir = intakeDir; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public int getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }

    public int getEmbeddingDim() { return embeddingDim; }
    public void setEmbeddingDim(int embeddingDim) { this.embeddingDim = embeddingDim; }

    public int getIngestionPollSeconds() { return ingestionPollSeconds; }
    public void setIngestionPollSeconds(int ingestionPollSeconds) { this.ingestionPollSeconds = ingestionPollSeconds; }

    public int getMaxSearchResults() { return maxSearchResults; }
    public void setMaxSearchResults(int maxSearchResults) { this.maxSearchResults = maxSearchResults; }
}
