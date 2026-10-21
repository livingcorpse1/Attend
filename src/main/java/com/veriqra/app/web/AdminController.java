package com.veriqra.app.web;

import com.veriqra.app.domain.dto.AccountDtos.AccountProfile;
import com.veriqra.app.repository.AcademicRepository;
import com.veriqra.app.repository.AttendanceRepository;
import com.veriqra.app.repository.AuditRepository;
import com.veriqra.app.repository.BiometricRepository;
import com.veriqra.app.repository.UserRepository;
import com.veriqra.app.security.ApiException;
import com.veriqra.app.security.CurrentAccount;
import com.veriqra.app.security.Role;
import com.veriqra.app.service.storage.StorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Institution administration: accounts, classes, enrolments, settings and the audit trail.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserRepository users;
    private final AcademicRepository academic;
    private final AuditRepository audit;
    private final AttendanceRepository attendance;
    private final BiometricRepository biometrics;
    private final PasswordEncoder passwordEncoder;
    private final CurrentAccount currentAccount;
    private final StorageService storage;

    public AdminController(UserRepository users,
                           AcademicRepository academic,
                           AuditRepository audit,
                           AttendanceRepository attendance,
                           BiometricRepository biometrics,
                           PasswordEncoder passwordEncoder,
                           CurrentAccount currentAccount,
                           StorageService storage) {
        this.users = users;
        this.academic = academic;
        this.audit = audit;
        this.attendance = attendance;
        this.biometrics = biometrics;
        this.passwordEncoder = passwordEncoder;
        this.currentAccount = currentAccount;
        this.storage = storage;
    }

    /** Every account. */
    @GetMapping("/users")
    public List<AccountProfile> users() {
        return users.listAll().stream().map(AccountProfile::from).toList();
    }

    /** Creates a faculty or administrator account. */
    @PostMapping("/users")
    public ResponseEntity<AccountProfile> createUser(@RequestBody Map<String, String> body,
                                                      org.springframework.security.core.Authentication authentication) {
        String email = body.getOrDefault("email", "").trim().toLowerCase(Locale.ROOT);
        String fullName = body.getOrDefault("fullName", "").trim();
        String password = body.getOrDefault("password", "");
        String roleText = body.getOrDefault("role", "FACULTY").trim().toUpperCase(Locale.ROOT);

        if (email.isBlank() || fullName.isBlank()) {
            throw ApiException.badRequest("MISSING_FIELDS", "Email and full name are required.");
        }
        if (password.length() < 10) {
            throw ApiException.badRequest("PASSWORD_TOO_SHORT", "Choose a password of at least 10 characters.");
        }
        Role role;
        try {
            role = Role.valueOf(roleText);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_ROLE", "Role must be ADMIN or FACULTY.");
        }
        if (role == Role.STUDENT) {
            throw ApiException.badRequest("INVALID_ROLE",
                    "Student accounts are created by the students themselves.");
        }
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account already exists for that email address.");
        }

        long id = users.create(email, passwordEncoder.encode(password), fullName, role, null);
        audit.record(currentAccount.id(authentication), "ACCOUNT_CREATED", "user=" + id, "ADMIN_ACTION",
                "Created a " + role.name() + " account", null, email);
        return ResponseEntity.status(201).body(users.findById(id).map(AccountProfile::from).orElseThrow());
    }

    /** Activates or deactivates an account. */
    @PutMapping("/users/{id}/active")
    public AccountProfile setActive(@PathVariable long id, @RequestParam boolean active) {
        users.setActive(id, active);
        audit.record(id, "ACCOUNT_STATUS_CHANGED", "user=" + id, "ADMIN_ACTION",
                active ? "Account activated" : "Account deactivated", null, Boolean.toString(active));
        return users.findById(id).map(AccountProfile::from).orElseThrow(
                () -> ApiException.notFound("USER_NOT_FOUND", "That account does not exist."));
    }

    /** Every subject. */
    @GetMapping("/subjects")
    public List<Map<String, Object>> subjects() {
        return academic.listAllSubjects().stream()
                .map(view -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", view.id());
                    row.put("code", view.code());
                    row.put("name", view.name());
                    row.put("facultyId", view.facultyId());
                    row.put("facultyName", view.facultyName());
                    return row;
                })
                .toList();
    }

    /** Creates a subject and assigns it to a teacher. */
    @PostMapping("/subjects")
    public ResponseEntity<Map<String, Object>> createSubject(@RequestBody Map<String, String> body,
                                                             org.springframework.security.core.Authentication authentication) {
        String code = body.getOrDefault("code", "").trim().toUpperCase(Locale.ROOT);
        String name = body.getOrDefault("name", "").trim();
        Long facultyId = parseOptionalLong(body.get("facultyId"), "facultyId");

        if (code.isBlank() || name.isBlank()) {
            throw ApiException.badRequest("MISSING_FIELDS", "The subject code and name are required.");
        }
        if (facultyId == null) {
            throw ApiException.badRequest("FACULTY_REQUIRED", "A subject has to belong to a teacher.");
        }
        boolean taken = academic.listAllSubjects().stream()
                .anyMatch(subject -> subject.code().equalsIgnoreCase(code));
        if (taken) {
            throw ApiException.conflict("SUBJECT_CODE_TAKEN", "A subject with that code already exists.");
        }

        long id = academic.createSubject(code, name, facultyId);
        audit.record(currentAccount.id(authentication), "SUBJECT_CREATED", "subject=" + id, "ADMIN_ACTION",
                "Subject created", null, code);
        return ResponseEntity.status(201).body(Map.of("id", id, "code", code, "name", name));
    }

    /** Every class section. */
    @GetMapping("/classes")
    public List<Map<String, Object>> classes() {
        return academic.listAllClasses().stream()
                .map(view -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", view.id());
                    row.put("subjectId", view.subjectId());
                    row.put("subjectCode", view.subjectCode());
                    row.put("subjectName", view.subjectName());
                    row.put("section", view.section());
                    row.put("room", view.room());
                    row.put("facultyName", view.facultyName());
                    row.put("enrolled", view.enrolled());
                    return row;
                })
                .toList();
    }

    /** Creates a class section. */
    @PostMapping("/classes")
    public ResponseEntity<Map<String, Object>> createClass(@RequestBody Map<String, String> body) {
        long subjectId = parseLong(body.get("subjectId"), "subjectId");
        String section = body.getOrDefault("section", "").trim();
        String room = body.getOrDefault("room", "").trim();
        if (section.isBlank() || room.isBlank()) {
            throw ApiException.badRequest("MISSING_FIELDS", "Section and room are required.");
        }
        if (academic.listAllSubjects().stream().noneMatch(subject -> subject.id() == subjectId)) {
            throw ApiException.badRequest("UNKNOWN_SUBJECT", "There is no subject with that identifier.");
        }
        long id = academic.createClass(subjectId, section, room);
        return ResponseEntity.status(201).body(Map.of("id", id, "section", section, "room", room));
    }

    /** Removes a class section, with the lectures and the enrolments that belong to it. */
    @DeleteMapping("/classes/{id}")
    public ResponseEntity<Map<String, String>> deleteClass(@PathVariable long id) {
        if (attendance.sessionsForClass(id).size() > 0) {
            throw ApiException.conflict("CLASS_HAS_SESSIONS",
                    "The class section has attendance sessions. Those are a record of what happened "
                            + "and are not removed with the class.");
        }
        academic.deleteClass(id);
        return ResponseEntity.ok(Map.of("status", "removed"));
    }

    /** Removes a subject that has no class sections. */
    @DeleteMapping("/subjects/{id}")
    public ResponseEntity<Map<String, String>> deleteSubject(@PathVariable long id) {
        if (academic.listAllClasses().stream().anyMatch(view -> view.subjectId() == id)) {
            throw ApiException.conflict("SUBJECT_IN_USE",
                    "The subject still has class sections. Remove those first.");
        }
        academic.deleteSubject(id);
        return ResponseEntity.ok(Map.of("status", "removed"));
    }

    /** Enrols a student in a class. */
    @PostMapping("/enrollments")
    public Map<String, String> enrol(@RequestParam long studentId, @RequestParam long classId) {
        if (users.findById(studentId).isEmpty()) {
            throw ApiException.badRequest("UNKNOWN_STUDENT", "There is no account with that identifier.");
        }
        if (academic.findClass(classId).isEmpty()) {
            throw ApiException.badRequest("UNKNOWN_CLASS", "There is no class section with that identifier.");
        }
        if (!academic.isEnrolled(studentId, classId)) {
            academic.enrol(studentId, classId);
        }
        return Map.of("status", "enrolled");
    }

    /** Removes an enrolment. */
    @DeleteMapping("/enrollments")
    public Map<String, String> unenrol(@RequestParam long studentId, @RequestParam long classId) {
        academic.unenrol(studentId, classId);
        return Map.of("status", "removed");
    }

    /** The enrolments of a class. */
    @GetMapping("/enrollments")
    public List<AcademicRepository.Enrolment> enrolments(@RequestParam long classId) {
        return academic.enrolmentsForClass(classId);
    }

    /** The audit trail. */
    @GetMapping("/audit-logs")
    public List<AuditRepository.AuditEntry> auditLogs(@RequestParam(defaultValue = "100") int limit) {
        return audit.recent(Math.min(limit, 500));
    }

    /** The most recent verification attempts. */
    @GetMapping("/verification-attempts")
    public List<AttendanceRepository.AttemptRow> verificationAttempts(@RequestParam(defaultValue = "50") int limit) {
        return attendance.recentAttempts(Math.min(limit, 500));
    }

    /** The institution settings. */
    @GetMapping("/settings")
    public Map<String, String> settings() {
        return audit.settings();
    }

    /** Updates one institution setting. */
    @PutMapping("/settings")
    public Map<String, String> updateSetting(@RequestParam String key, @RequestParam String value) {
        audit.putSetting(key.trim(), value.trim());
        return audit.settings();
    }

    /** The storage provider in use and the number of enrolled templates. */
    @GetMapping("/storage")
    public Map<String, String> storageProvider() {
        return Map.of("provider", storage.providerName(),
                "templates", Long.toString(biometrics.templateCount()));
    }

    private static long parseLong(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("INVALID_ID", field + " must be a number.");
        }
    }

    private static Long parseOptionalLong(String value, String field) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        return parseLong(value, field);
    }
}
