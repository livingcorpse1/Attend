package com.veriqra.app.service;

import com.veriqra.app.repository.AuditRepository;
import com.veriqra.app.service.attendance.AttendanceService;
import com.veriqra.app.service.biometric.BiometricService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Background housekeeping.
 *
 * <p>Everything here is safe to run repeatedly and cheap enough to run every minute.
 * The tasks keep the database free of rows that have no further use and make sure a
 * session never stays open past its window.
 */
@Component
public class MaintenanceTasks {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceTasks.class);

    private final AttendanceService attendance;
    private final BiometricService biometrics;
    private final AuditRepository audit;

    public MaintenanceTasks(AttendanceService attendance, BiometricService biometrics, AuditRepository audit) {
        this.attendance = attendance;
        this.biometrics = biometrics;
        this.audit = audit;
    }

    /** Closes attendance sessions that ran past their window. */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void closeExpiredSessions() {
        attendance.expireStaleSessions();
    }

    /** Removes expired challenges and tickets. */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 120_000L)
    public void purgeExpiredBiometricState() {
        int removed = biometrics.purgeExpired();
        if (removed > 0) {
            log.info("Purged {} expired biometric challenges and tickets", removed);
        }
    }

    /** Removes old login attempts so the throttle table stays small. */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L)
    public void purgeOldLoginAttempts() {
        int removed = audit.deleteAttemptsOlderThan(Instant.now().minus(30, ChronoUnit.DAYS));
        if (removed > 0) {
            log.info("Purged {} login attempts older than 30 days", removed);
        }
    }
}
