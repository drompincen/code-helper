package com.javaducker.client;

import com.javaducker.proto.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Command(name = "javaducker", mixinStandardHelpOptions = true, version = "2.0.0",
        description = "JavaDucker CLI client",
        subcommands = {
                JavaDuckerClient.HealthCmd.class,
                JavaDuckerClient.UploadFileCmd.class,
                JavaDuckerClient.UploadDirCmd.class,
                JavaDuckerClient.FindCmd.class,
                JavaDuckerClient.CatCmd.class,
                JavaDuckerClient.StatusCmd.class,
                JavaDuckerClient.StatsCmd.class,
        })
public class JavaDuckerClient implements Runnable {

    @Option(names = {"--host"}, defaultValue = "localhost", description = "Server host")
    String host;

    @Option(names = {"--port"}, defaultValue = "9090", description = "Server gRPC port")
    int port;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JavaDuckerClient()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    static JavaDuckerGrpc.JavaDuckerBlockingStub createStub(String host, int port) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        return JavaDuckerGrpc.newBlockingStub(channel);
    }

    static void shutdownChannel(JavaDuckerGrpc.JavaDuckerBlockingStub stub) {
        try {
            ((ManagedChannel) stub.getChannel()).shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── health ──────────────────────────────────────────────
    @Command(name = "health", description = "Check server health")
    static class HealthCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Override
        public void run() {
            var stub = createStub(parent.host, parent.port);
            try {
                HealthResponse resp = stub.health(HealthRequest.getDefaultInstance());
                System.out.println("Status:  " + resp.getStatus());
                System.out.println("Version: " + resp.getVersion());
            } catch (StatusRuntimeException e) {
                System.err.println("Error: " + e.getStatus().getDescription());
                System.exit(1);
            } finally {
                shutdownChannel(stub);
            }
        }
    }

    // ── upload-file ─────────────────────────────────────────
    @Command(name = "upload-file", description = "Upload a single file")
    static class UploadFileCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Option(names = {"--file"}, required = true, description = "Path to file")
        String filePath;

        @Override
        public void run() {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                System.err.println("File not found: " + filePath);
                System.exit(1);
            }
            var stub = createStub(parent.host, parent.port);
            try {
                byte[] content = Files.readAllBytes(path);
                String mediaType = Files.probeContentType(path);
                if (mediaType == null) mediaType = "application/octet-stream";

                UploadFileResponse resp = stub.uploadFile(UploadFileRequest.newBuilder()
                        .setFileName(path.getFileName().toString())
                        .setOriginalClientPath(path.toAbsolutePath().toString())
                        .setMediaType(mediaType)
                        .setSizeBytes(content.length)
                        .setContent(ByteString.copyFrom(content))
                        .build());

                System.out.println("Artifact ID: " + resp.getArtifactId());
                System.out.println("Status:      " + resp.getStatus());
            } catch (IOException e) {
                System.err.println("Error reading file: " + e.getMessage());
                System.exit(1);
            } catch (StatusRuntimeException e) {
                System.err.println("Server error: " + e.getStatus().getDescription());
                System.exit(1);
            } finally {
                shutdownChannel(stub);
            }
        }
    }

    // ── upload-dir ──────────────────────────────────────────
    @Command(name = "upload-dir", description = "Upload all matching files from a directory")
    static class UploadDirCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Option(names = {"--root"}, required = true, description = "Root directory to scan")
        String rootDir;

        @Option(names = {"--ext"}, defaultValue = ".java,.xml,.md,.yml,.json,.txt,.pdf",
                description = "Comma-separated file extensions to include")
        String extensions;

        @Override
        public void run() {
            Path root = Path.of(rootDir);
            if (!Files.isDirectory(root)) {
                System.err.println("Not a directory: " + rootDir);
                System.exit(1);
            }

            Set<String> exts = Set.of(extensions.toLowerCase().split(","));
            var stub = createStub(parent.host, parent.port);
            int uploaded = 0, skipped = 0, failed = 0;

            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> files = walk.filter(Files::isRegularFile).toList();
                for (Path file : files) {
                    String name = file.getFileName().toString().toLowerCase();
                    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                    if (!exts.contains(ext)) {
                        skipped++;
                        continue;
                    }

                    try {
                        byte[] content = Files.readAllBytes(file);
                        String mediaType = Files.probeContentType(file);
                        if (mediaType == null) mediaType = "application/octet-stream";

                        UploadFileResponse resp = stub.uploadFile(UploadFileRequest.newBuilder()
                                .setFileName(file.getFileName().toString())
                                .setOriginalClientPath(file.toAbsolutePath().toString())
                                .setMediaType(mediaType)
                                .setSizeBytes(content.length)
                                .setContent(ByteString.copyFrom(content))
                                .build());
                        System.out.println("  Uploaded: " + file + " -> " + resp.getArtifactId());
                        uploaded++;
                    } catch (Exception e) {
                        System.err.println("  Failed:   " + file + " (" + e.getMessage() + ")");
                        failed++;
                    }
                }
            } catch (IOException e) {
                System.err.println("Error scanning directory: " + e.getMessage());
                System.exit(1);
            } finally {
                shutdownChannel(stub);
            }

            System.out.println("\nSummary:");
            System.out.println("  Uploaded: " + uploaded);
            System.out.println("  Skipped:  " + skipped);
            System.out.println("  Failed:   " + failed);
        }
    }

    // ── find ────────────────────────────────────────────────
    @Command(name = "find", description = "Search indexed content")
    static class FindCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Option(names = {"--phrase"}, required = true, description = "Search phrase")
        String phrase;

        @Option(names = {"--mode"}, defaultValue = "hybrid",
                description = "Search mode: exact, semantic, hybrid")
        String mode;

        @Option(names = {"--max"}, defaultValue = "20", description = "Max results")
        int maxResults;

        @Override
        public void run() {
            SearchMode searchMode = switch (mode.toLowerCase()) {
                case "exact" -> SearchMode.EXACT;
                case "semantic" -> SearchMode.SEMANTIC;
                default -> SearchMode.HYBRID;
            };

            var stub = createStub(parent.host, parent.port);
            try {
                FindResponse resp = stub.find(FindRequest.newBuilder()
                        .setPhrase(phrase)
                        .setMode(searchMode)
                        .setMaxResults(maxResults)
                        .build());

                System.out.println("Results: " + resp.getTotalResults());
                System.out.println();
                for (int i = 0; i < resp.getResultsCount(); i++) {
                    SearchResult r = resp.getResults(i);
                    System.out.printf("#%d [%s] score=%.4f  file=%s  chunk=%d%n",
                            i + 1, r.getMatchType(), r.getScore(), r.getFileName(), r.getChunkIndex());
                    System.out.println("    " + r.getPreview().replace("\n", "\n    "));
                    System.out.println();
                }
            } catch (StatusRuntimeException e) {
                System.err.println("Error: " + e.getStatus().getDescription());
                System.exit(1);
            } finally {
                shutdownChannel(stub);
            }
        }
    }

    // ── cat ─────────────────────────────────────────────────
    @Command(name = "cat", description = "Retrieve extracted text for an artifact")
    static class CatCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Option(names = {"--id"}, required = true, description = "Artifact ID")
        String artifactId;

        @Override
        public void run() {
            var stub = createStub(parent.host, parent.port);
            try {
                GetArtifactTextResponse resp = stub.getArtifactText(
                        GetArtifactTextRequest.newBuilder().setArtifactId(artifactId).build());
                System.out.println("Artifact:   " + resp.getArtifactId());
                System.out.println("Method:     " + resp.getExtractionMethod());
                System.out.println("Length:     " + resp.getTextLength());
                System.out.println("---");
                System.out.println(resp.getExtractedText());
            } catch (StatusRuntimeException e) {
                System.err.println("Error: " + e.getStatus().getDescription());
                System.exit(1);
            } finally {
                shutdownChannel(stub);
            }
        }
    }

    // ── status ──────────────────────────────────────────────
    @Command(name = "status", description = "Check artifact ingestion status")
    static class StatusCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Option(names = {"--id"}, required = true, description = "Artifact ID")
        String artifactId;

        @Override
        public void run() {
            var stub = createStub(parent.host, parent.port);
            try {
                GetArtifactStatusResponse resp = stub.getArtifactStatus(
                        GetArtifactStatusRequest.newBuilder().setArtifactId(artifactId).build());
                System.out.println("Artifact: " + resp.getArtifactId());
                System.out.println("File:     " + resp.getFileName());
                System.out.println("Status:   " + resp.getStatus());
                if (!resp.getErrorMessage().isEmpty()) {
                    System.out.println("Error:    " + resp.getErrorMessage());
                }
                System.out.println("Created:  " + resp.getCreatedAt());
                System.out.println("Updated:  " + resp.getUpdatedAt());
                if (!resp.getIndexedAt().isEmpty()) {
                    System.out.println("Indexed:  " + resp.getIndexedAt());
                }
            } catch (StatusRuntimeException e) {
                System.err.println("Error: " + e.getStatus().getDescription());
                System.exit(1);
            } finally {
                shutdownChannel(stub);
            }
        }
    }

    // ── stats ───────────────────────────────────────────────
    @Command(name = "stats", description = "View server statistics")
    static class StatsCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Override
        public void run() {
            var stub = createStub(parent.host, parent.port);
            try {
                StatsResponse resp = stub.stats(StatsRequest.getDefaultInstance());
                System.out.println("Total artifacts:   " + resp.getTotalArtifacts());
                System.out.println("Indexed:           " + resp.getIndexedArtifacts());
                System.out.println("Failed:            " + resp.getFailedArtifacts());
                System.out.println("Pending:           " + resp.getPendingArtifacts());
                System.out.println("Total chunks:      " + resp.getTotalChunks());
                System.out.println("Total bytes:       " + resp.getTotalBytes());
                if (!resp.getArtifactsByStatusMap().isEmpty()) {
                    System.out.println("By status:");
                    resp.getArtifactsByStatusMap().forEach((k, v) ->
                            System.out.println("  " + k + ": " + v));
                }
            } catch (StatusRuntimeException e) {
                System.err.println("Error: " + e.getStatus().getDescription());
                System.exit(1);
            } finally {
                shutdownChannel(stub);
            }
        }
    }
}
