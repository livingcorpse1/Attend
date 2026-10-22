package com.veriqra.app.web;

import com.veriqra.app.repository.AcademicRepository;
import com.veriqra.app.repository.AttendanceRepository;
import com.veriqra.app.repository.AuditRepository;
import com.veriqra.app.repository.UserRepository;
import com.veriqra.app.security.ApiException;
import com.veriqra.app.security.CurrentAccount;
import com.veriqra.app.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The academic structure a teacher owns.
 *
 * <p>There is no administrator. A teacher defines their own subjects, the class sections
 * that run them, and the students who attend, and nobody can touch anybody else's.
 */
@RestController
@RequestMapping("/api/faculty")
public class FacultyStructureController {

    private final AcademicRepository academic;
    private final AttendanceRepository attendance;
    private final UserRepository users;
    private final AuditRepository audit;
    private final CurrentAccount currentAccount;
    private final PasswordEncoder passwordEncoder;

    public FacultyStructureController(AcademicRepository academic,
                                      AttendanceRepository attendance,
                                      UserRepository users,
                                      AuditRepository audit,
                                      CurrentAccount currentAccount,
                                      PasswordEncoder passwordEncoder) {
        this.academic = academic;
        this.attendance = attendance;
        this.users = users;
        this.audit = audit;
        this.currentAccount = currentAccount;
        this.passwordEncoder = passwordEncoder;
    }

    /** Removes a subject of the teacher that has no class sections. */
    @DeleteMapping("/subjects/{id}")
    public ResponseEntity<Map<String, String>> deleteSubject(@PathVariable long id,
                                                            Authentication authentication) {
        long facultyId = currentAccount.id(authentication);
        if (!academic.ownsSubject(facultyId, id)) {
            throw ApiException.forbidden("NOT_OWNER", "That subject belongs to another teacher.");
        }
        boolean inUse = academic.listAllClasses().stream().anyMatch(view -> view.subjectId() == id);
        if (inUse) {
            throw ApiException.conflict("SUBJECT_IN_USE",
                    "The subject still has class sections. Remove those first.");
        }
        academic.deleteSubject(id);
        audit.record(facultyId, "SUBJECT_REMOVED", "subject=" + id, "TEACHER_ACTION",
                "Subject removed", null, null);
        return ResponseEntity.ok(Map.of("status", "removed"));
    }

    /**
     * Adds a colleague to the teaching staff.
     *
     * <p>There is no administrator, so a teacher provisions the other teachers. The
     * password is returned once, in the answer, and never again.
     */
    @PostMapping("/teachers")
    public ResponseEntity<Map<String, Object>> createTeacher(@RequestBody Map<String, String> body,
                                                              Authentication authentication) {
        long facultyId = currentAccount.id(authentication);
        String email = body.getOrDefault("email", "").trim().toLowerCase(Locale.ROOT);
        String fullName = body.getOrDefault("fullName", "").trim();
        String password = body.getOrDefault("password", "").trim();

        if (email.isBlank() || fullName.isBlank()) {
            throw ApiException.badRequest("MISSING_FIELDS", "The name and the email are required.");
        }
        if (!email.contains("@")) {
            throw ApiException.badRequest("INVALID_EMAIL", "That is not an email address.");
        }
        if (password.isBlank()) {
            password = generatePassword();
        }
        if (password.length() < 10) {
            throw ApiException.badRequest("PASSWORD_TOO_SHORT",
                    "The password has to be at least 10 characters.");
        }
        if (users.findByEmail(email).isPresent()) {
            throw ApiException.conflict("EMAIL_TAKEN", "There is already an account with that email address.");
        }

        long id = users.create(email, passwordEncoder.encode(password), fullName, Role.FACULTY, null);
        audit.record(facultyId, "TEACHER_ADDED", "user=" + id, "TEACHER_ACTION",
                "Colleague added to the teaching staff", null, email);
        return ResponseEntity.status(201)
                .body(Map.of("id", id, "email", email, "fullName", fullName, "password", password));
    }

    private static String generatePassword() {
        byte[] bytes = new byte[9];
        new java.security.SecureRandom().nextBytes(bytes);
        return "Vq!" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The subjects and the class sections of the teacher, in one read. */
    @GetMapping("/structure")    public Map<String, Object> structure(Authentication authentication) {
        long facultyId = currentAccount.id(authentication);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subjects", academic.listSubjectsForFaculty(facultyId).stream()
                .map(view -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", view.id());
                    row.put("code", view.code());
                    row.put("name", view.name());
                    return row;
                })
                .toList());
        body.put("classes", academic.listClassesForFaculty(facultyId).stream()
                .map(view -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", view.id());
                    row.put("subjectId", view.subjectId());
                    row.put("subjectCode", view.subjectCode());
                    row.put("subjectName", view.subjectName());
                    row.put("section", view.section());
                    row.put("room", view.room());
                    row.put("enrolled", view.enrolled());
                    return row;
                })
                .toList());
        return body;
    }

    /** The subjects the signed in teacher owns. */
    @GetMapping("/subjects")
    public List<Map<String, Object>> subjects(Authentication authentication) {
        long facultyId = currentAccount.id(authentication);
        return academic.listSubjectsForFaculty(facultyId).stream()
                .map(view -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", view.id());
                    row.put("code", view.code());
                    row.put("name", view.name());
                    return row;
                })
                .toList();
    }

    /** Creates a subject that belongs to the signed in teacher. */
    @PostMapping("/subjects")
    public ResponseEntity<Map<String, Object>> createSubject(@RequestBody Map<String, String> body,
                                                              Authentication authentication) {
        long facultyId = currentAccount.id(authentication);
        String code = body.getOrDefault("code", "").trim().toUpperCase(Locale.ROOT);
        String name = body.getOrDefault("name", "").trim();
        if (code.isBlank() || name.isBlank()) {
            throw ApiException.badRequest("MISSING_FIELDS", "The subject code and name are required.");
        }
        boolean taken = academic.listSubjectsForFaculty(facultyId).stream()
                .anyMatch(subject -> subject.code().equalsIgnoreCase(code));
        if (taken) {
            throw ApiException.conflict("SUBJECT_CODE_TAKEN", "You already have a subject with that code.");
        }
        long id = academic.createSubject(code, name, facultyId);
        audit.record(facultyId, "SUBJECT_CREATED", "subject=" + id, "TEACHER_ACTION",
                "Subject created", null, code);
        return ResponseEntity.status(201).body(Map.of("id", id, "code", code, "name", name));
    }

    /** Creates a class section for one of the teacher's own subjects. */
    @PostMapping("/classes")
    public ResponseEntity<Map<String, Object>> createClass(@RequestBody Map<String, String> body,
                                                            Authentication authentication) {
        long facultyId = currentAccount.id(authentication);
        long subjectId = parseLong(body.get("subjectId"), "subjectId");
        String section = body.getOrDefault("section", "").trim();
        String room = body.getOrDefault("room", "").trim();
        if (section.isBlank() || room.isBlank()) {
            throw ApiException.badRequest("MISSING_FIELDS", "Section and room are required.");
        }
        if (!academic.ownsSubject(facultyId, subjectId)) {
            throw ApiException.forbidden("NOT_ASSIGNED",
                    "You can only create class sections for your own subjects.");
        }
        boolean exists = academic.listClassesForFaculty(facultyId).stream()
                .anyMatch(view -> view.subjectId() == subjectId && section.equalsIgnoreCase(view.section()));
        if (exists) {
            throw ApiException.conflict("CLASS_EXISTS",
                    "There is already a class section with that name for this subject.");
        }
        long id = academic.createClass(subjectId, section, room);
        audit.record(facultyId, "CLASS_CREATED", "class=" + id, "TEACHER_ACTION",
                "Class section created", null, section);
        return ResponseEntity.status(201).body(Map.of("id", id, "section", section, "room", room));
    }

    /** Removes a class section of one of the teacher's own subjects. */
    @DeleteMapping("/classes/{id}")
    public ResponseEntity<Map<String, String>> deleteClass(@PathVariable long id, Authentication authentication) {
        long facultyId = currentAccount.id(authentication);
        AcademicRepository.ClassView classView = academic.findClass(id)
                .orElseThrow(() -> ApiException.notFound("CLASS_NOT_FOUND", "That class section does not exist."));
        if (!academic.ownsSubject(facultyId, classView.subjectId())) {
            throw ApiException.forbidden("NOT_OWNER", "That class section belongs to another teacher.");
        }
        if (!attendance.sessionsForClass(id).isEmpty()) {
            throw ApiException.conflict("CLASS_HAS_SESSIONS",
                    "The class section has attendance sessions. Those are a record of what happened "
                            + "and are not removed with the class.");
        }
        // The students of a class section go with it.
        academic.unenrolAll(id);
        academic.deleteClass(id);
        audit.record(facultyId, "CLASS_REMOVED", "class=" + id, "TEACHER_ACTION",
                "Class section removed", classView.section(), null);
        return ResponseEntity.ok(Map.of("status", "removed"));
    }

    /** Enrols a student into one of the teacher's own class sections. */
    @PostMapping("/enrollments")
    public Map<String, String> enrol(@RequestParam String email, @RequestParam long classId,
                                     Authentication authentication) {
        long facultyId = currentAccount.id(authentication);
        AcademicRepository.ClassView classView = academic.findClass(classId)
                .orElseThrow(() -> ApiException.notFound("CLASS_NOT_FOUND", "That class section does not exist."));
        if (!academic.ownsSubject(facultyId, classView.subjectId())) {
            throw ApiException.forbidden("NOT_OWNER", "That class section belongs to another teacher.");
        }
        var student = users.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.notFound("STUDENT_NOT_FOUND",
                        "There is no account with that email address. The student has to register first."));
        if (student.role() != Role.STUDENT) {
            throw ApiException.badRequest("NOT_A_STUDENT", "That account is not a student.");
        }
        if (!academic.isEnrolled(student.id(), classId)) {
            academic.enrol(student.id(), classId);
            audit.record(facultyId, "ENROLMENT_ADDED", "class=" + classId + ",student=" + student.id(),
                    "TEACHER_ACTION", "Student enrolled", null, student.email());
        }
        return Map.of("status", "enrolled");
    }

    /** Removes a student from one of the teacher's own class sections. */
    @DeleteMapping("/enrollments")
    public Map<String, String> unenrol(@RequestParam long studentId, @RequestParam long classId,
                                       Authentication authentication) {
        long facultyId = currentAccount.id(authentication);
        AcademicRepository.ClassView classView = academic.findClass(classId)
                .orElseThrow(() -> ApiException.notFound("CLASS_NOT_FOUND", "That class section does not exist."));
        if (!academic.ownsSubject(facultyId, classView.subjectId())) {
            throw ApiException.forbidden("NOT_OWNER", "That class section belongs to another teacher.");
        }
        academic.unenrol(studentId, classId);
        return Map.of("status", "removed");
    }

    private static long parseLong(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("INVALID_ID", field + " must be a number.");
        }
    }
}
