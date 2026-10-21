package com.veriqra.app.web;

import com.veriqra.app.service.biometric.FaceRecognitionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liveness probe for the platform, the biometric pipeline and the database.
 *
 * <p>The database is checked for a reason: the connection to a managed pooler is
 * dropped from time to time, and a health endpoint that only says the process is alive
 * hides that until somebody tries to sign in.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final FaceRecognitionService face;
    private final JdbcTemplate db;

    public HealthController(FaceRecognitionService face, JdbcTemplate db) {
        this.face = face;
        this.db = db;
    }

    /** Reports that the service is up, and whether the two dependencies work. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("time", Instant.now());

        body.put("database", databaseState());
        body.put("biometricAvailable", face.isAvailable());
        if (!face.isAvailable()) {
            body.put("biometricMessage", face.unavailableReason());
        }
        return body;
    }

    private Map<String, Object> databaseState() {
        Map<String, Object> state = new LinkedHashMap<>();
        long started = System.nanoTime();
        try {
            db.queryForObject("select 1", Integer.class);
            state.put("status", "UP");
            state.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException ex) {
            state.put("status", "DOWN");
            state.put("message", ex.getMessage());
        }
        return state;
    }
}
