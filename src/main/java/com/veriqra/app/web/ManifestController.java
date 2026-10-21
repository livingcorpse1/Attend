package com.veriqra.app.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Serves the web application manifest with the right media type.
 *
 * <p>The static file handler would send it as an arbitrary binary stream, and a browser
 * that is asked to install an application with a manifest of the wrong type quietly
 * refuses. The manifest is small and rarely changes, so it is served with a short cache.
 */
@RestController
public class ManifestController {

    private static final MediaType MANIFEST_TYPE = MediaType.valueOf("application/manifest+json");

    private final Resource manifest = new ClassPathResource("static/manifest.webmanifest");

    /** The application manifest. */
    @GetMapping(value = "/manifest.webmanifest", produces = "application/manifest+json")
    public ResponseEntity<Resource> manifest() {
        return ResponseEntity.ok()
                .contentType(MANIFEST_TYPE)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(manifest);
    }
}
