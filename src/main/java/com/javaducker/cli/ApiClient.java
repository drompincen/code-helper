package com.javaducker.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Simple HTTP client wrapping java.net.http.HttpClient for the JavaDucker REST API.
 */
public class ApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String baseUrl;
    private final HttpClient http;

    public ApiClient(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port + "/api";
        this.http = HttpClient.newHttpClient();
    }

    public Map<String, Object> get(String path) throws Exception {
        var resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) return null;
        if (resp.statusCode() >= 400)
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        return MAPPER.readValue(resp.body(), new TypeReference<>() {});
    }

    public Map<String, Object> post(String path, Object body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        var resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400)
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        return MAPPER.readValue(resp.body(), new TypeReference<>() {});
    }

    public Map<String, Object> upload(Path path) throws Exception {
        byte[] content = Files.readAllBytes(path);
        String mediaType = Files.probeContentType(path);
        if (mediaType == null) mediaType = "application/octet-stream";
        String fileName = path.getFileName().toString();
        String boundary = "----JavaDuckerBoundary" + System.currentTimeMillis();

        var baos = new ByteArrayOutputStream();
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n").getBytes());
        baos.write(("Content-Type: " + mediaType + "\r\n\r\n").getBytes());
        baos.write(content);
        baos.write("\r\n".getBytes());
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write("Content-Disposition: form-data; name=\"originalClientPath\"\r\n\r\n".getBytes());
        baos.write(path.toAbsolutePath().toString().getBytes());
        baos.write("\r\n".getBytes());
        baos.write(("--" + boundary + "--\r\n").getBytes());

        var resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl + "/upload"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400)
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        return MAPPER.readValue(resp.body(), new TypeReference<>() {});
    }

    /** Returns true if the server TCP port is reachable. */
    public boolean isReachable() {
        String host = baseUrl.replaceAll("http://([^:]+):.*", "$1");
        int port;
        try {
            port = Integer.parseInt(baseUrl.replaceAll(".*:(\\d+)/.*", "$1"));
        } catch (NumberFormatException e) {
            return false;
        }
        try (var s = new java.net.Socket(host, port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
