package com.veriqra.app.service.storage;

import com.veriqra.app.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Stores objects in a Supabase Storage bucket.
 *
 * <p>The service role key is used for the call. It never leaves the server: the browser
 * talks to the application API, and the application talks to Supabase. Nothing in this
 * class is reachable from the client bundle.
 */
@Component
@ConditionalOnProperty(name = "veriqra.storage.provider", havingValue = "supabase")
public class SupabaseStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseStorageService.class);

    private final String baseUrl;
    private final String bucket;
    private final String serviceRoleKey;
    private final HttpClient httpClient;

    public SupabaseStorageService(AppProperties properties) {
        AppProperties.Storage storage = properties.getStorage();
        if (storage.getSupabaseUrl() == null || storage.getSupabaseUrl().isBlank()) {
            throw new IllegalStateException("veriqra.storage.supabase-url must be set when the Supabase provider is used");
        }
        if (storage.getSupabaseServiceRoleKey() == null || storage.getSupabaseServiceRoleKey().isBlank()) {
            throw new IllegalStateException("veriqra.storage.supabase-service-role-key must be set when the Supabase provider is used");
        }
        this.baseUrl = storage.getSupabaseUrl().replaceAll("/+$", "");
        this.bucket = storage.getBucket();
        this.serviceRoleKey = storage.getSupabaseServiceRoleKey();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        log.info("Supabase storage provider active, bucket {}", bucket);
    }

    @Override
    public String store(String path, byte[] content, String contentType) {
        HttpRequest request = HttpRequest.newBuilder(objectUri(path))
                .header("Authorization", "Bearer " + serviceRoleKey)
                .header("apikey", serviceRoleKey)
                .header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
                .header("x-upsert", "true")
                .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<String> response = send(request, "upload " + path);
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("Supabase rejected the upload of " + path
                    + " with status " + response.statusCode() + ": " + response.body());
        }
        return path;
    }

    @Override
    public Optional<byte[]> read(String path) {
        HttpRequest request = HttpRequest.newBuilder(objectUri(path))
                .header("Authorization", "Bearer " + serviceRoleKey)
                .header("apikey", serviceRoleKey)
                .GET()
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read the object " + path + " from Supabase", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading " + path + " from Supabase", ex);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("Supabase refused to serve " + path
                    + " with status " + response.statusCode());
        }
        return Optional.of(response.body());
    }

    @Override
    public void delete(String path) {
        HttpRequest request = HttpRequest.newBuilder(objectUri(path))
                .header("Authorization", "Bearer " + serviceRoleKey)
                .header("apikey", serviceRoleKey)
                .DELETE()
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<String> response = send(request, "delete " + path);
        if (response.statusCode() >= 300 && response.statusCode() != 404) {
            throw new IllegalStateException("Supabase refused to delete " + path
                    + " with status " + response.statusCode());
        }
    }

    @Override
    public String providerName() {
        return "supabase";
    }

    private URI objectUri(String path) {
        return URI.create(baseUrl + "/storage/v1/object/" + bucket + "/" + path);
    }

    private HttpResponse<String> send(HttpRequest request, String description) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to " + description + " in Supabase", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during " + description + " in Supabase", ex);
        }
    }
}
