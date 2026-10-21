package com.veriqra.app.service.storage;

import com.veriqra.app.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Stores objects on the local file system.
 *
 * <p>This is the default for development and for single node deployments that do not
 * want to depend on Supabase Storage. The directory layout mirrors the bucket layout so
 * switching providers does not change the stored paths.
 */
@Component
@ConditionalOnProperty(name = "veriqra.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final Path root;

    public LocalStorageService(AppProperties properties) {
        this.root = Path.of(properties.getStorage().getLocalDirectory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to create the local storage directory " + root, ex);
        }
        log.info("Local storage provider active, root directory {}", root);
    }

    @Override
    public String store(String path, byte[] content, String contentType) {
        Path target = resolve(path);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return path;
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to store the object at " + path, ex);
        }
    }

    @Override
    public Optional<byte[]> read(String path) {
        Path target = resolve(path);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to read the object at " + path, ex);
        }
    }

    @Override
    public void delete(String path) {
        try {
            Files.deleteIfExists(resolve(path));
        } catch (IOException ex) {
            log.warn("Unable to delete the object at {}", path, ex);
        }
    }

    @Override
    public String providerName() {
        return "local";
    }

    /**
     * Resolves a path inside the storage root and refuses anything that escapes it.
     */
    private Path resolve(String path) {
        Path candidate = root.resolve(path).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Refusing to access a path outside the storage root: " + path);
        }
        return candidate;
    }
}
