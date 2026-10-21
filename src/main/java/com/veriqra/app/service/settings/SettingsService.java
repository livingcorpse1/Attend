package com.veriqra.app.service.settings;

import com.veriqra.app.repository.AuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * The institution settings.
 *
 * <p>Everything that describes the college lives here and nowhere else: the name, the
 * location, the attendance threshold, the version of the biometric notice. Nothing about
 * the institution is compiled into the application, so a deployment can be pointed at a
 * different college without a code change, and an administrator can correct a value
 * without a restart.
 */
@Service
public class SettingsService {

    /** The name of the institution, shown in the shell and in the exports. */
    public static final String INSTITUTION_NAME = "institution_name";

    /** Where the institution is, shown under its name. */
    public static final String INSTITUTION_LOCATION = "institution_location";

    /** The percentage below which a student counts as at risk. */
    public static final String LOW_ATTENDANCE_THRESHOLD = "low_attendance_threshold";

    /** The version of the biometric notice the student has to accept. */
    public static final String CONSENT_VERSION = "biometric_consent_version";

    /** Whether the demonstration accounts are available. */
    public static final String DEMO_MODE = "demo_mode";

    private final AuditRepository audit;

    public SettingsService(AuditRepository audit) {
        this.audit = audit;
    }

    /** Every setting. */
    public Map<String, String> all() {
        return audit.settings();
    }

    /** One setting, or the fallback when it has never been set. */
    public String get(String key, String fallback) {
        String value = audit.settings().get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** One setting, or an empty string. */
    public String get(String key) {
        return get(key, "");
    }

    /** One setting as a number. */
    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** Writes one setting. */
    @Transactional
    public void set(String key, String value) {
        audit.putSetting(key.trim(), value == null ? "" : value.trim());
    }
}
