package com.veriqra.app.web;

import com.veriqra.app.config.ParallelQueries;
import com.veriqra.app.domain.dto.AttendanceDtos.BiometricStatusView;
import com.veriqra.app.domain.dto.AttendanceDtos.ChallengeView;
import com.veriqra.app.domain.dto.AttendanceDtos.EnrolmentResponse;
import com.veriqra.app.domain.dto.AttendanceDtos.ScanRequest;
import com.veriqra.app.domain.dto.AttendanceDtos.ScanResponse;
import com.veriqra.app.domain.dto.AttendanceDtos.SubmissionResponse;
import com.veriqra.app.domain.dto.AttendanceDtos.SubjectAttendanceView;
import com.veriqra.app.domain.dto.AttendanceDtos.TimetableView;
import com.veriqra.app.repository.AcademicRepository;
import com.veriqra.app.security.ApiException;
import com.veriqra.app.security.CurrentAccount;
import com.veriqra.app.service.report.ReportService;
import com.veriqra.app.service.attendance.StudentAttendanceService;
import com.veriqra.app.service.biometric.BiometricService;
import com.veriqra.app.service.biometric.CaptureReader;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a student can do.
 *
 * <p>The identity check gate is enforced by an interceptor, so a student who has not
 * enrolled a face template can still reach the biometric endpoints but nothing else.
 */
@RestController
@RequestMapping("/api/student")
public class StudentController {

    private final StudentAttendanceService attendance;
    private final BiometricService biometrics;
    private final CaptureReader captureReader;
    private final AcademicRepository academic;
    private final ReportService reports;
    private final ParallelQueries parallel;
    private final CurrentAccount currentAccount;

    public StudentController(StudentAttendanceService attendance,
                             BiometricService biometrics,
                             CaptureReader captureReader,
                             AcademicRepository academic,
                             ReportService reports,
                             ParallelQueries parallel,
                             CurrentAccount currentAccount) {
        this.attendance = attendance;
        this.biometrics = biometrics;
        this.captureReader = captureReader;
        this.academic = academic;
        this.reports = reports;
        this.parallel = parallel;
        this.currentAccount = currentAccount;
    }

    /** The numbers shown on the student dashboard. */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(Authentication authentication) {
        long studentId = currentAccount.id(authentication);

        // Three independent reads, sent together.
        List<Object> results = parallel.runAll(
                () -> reports.subjectAttendanceForStudent(studentId),
                () -> attendance.activeClasses(studentId).size(),
                () -> academic.timetableForStudent(studentId));

        @SuppressWarnings("unchecked")
        List<SubjectAttendanceView> subjects = (List<SubjectAttendanceView>) results.get(0);
        int activeSessions = (Integer) results.get(1);
        @SuppressWarnings("unchecked")
        List<AcademicRepository.TimetableSlot> slots = (List<AcademicRepository.TimetableSlot>) results.get(2);

        long sessions = subjects.stream().mapToLong(SubjectAttendanceView::sessions).sum();
        long attended = subjects.stream().mapToLong(SubjectAttendanceView::attended).sum();
        double percentage = sessions == 0 ? 0d : (attended * 100d) / sessions;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("overallPercentage", Math.round(percentage * 10d) / 10d);
        body.put("subjectsAttended", attended);
        body.put("totalSessions", sessions);
        body.put("activeSessions", activeSessions);
        body.put("biometricState", biometrics.status(studentId).state());
        body.put("subjects", subjects);
        body.put("recent", reports.recentHistory(studentId, 6));
        body.put("timetable", slots.stream()
                .map(slot -> new TimetableView(slot.id(), slot.classId(), slot.subjectCode(), slot.subjectName(),
                        slot.section(), slot.room(), slot.dayOfWeek(), slot.starts().toString(),
                        slot.ends().toString(), slot.facultyName()))
                .toList());
        return body;
    }

    /** The attendance history of the signed in student. */
    @GetMapping("/attendance")
    public List<Map<String, Object>> attendanceHistory(Authentication authentication) {
        return reports.historyForStudent(currentAccount.id(authentication));
    }

    /** The timetable of the signed in student. */
    @GetMapping("/timetable")
    public List<TimetableView> timetable(Authentication authentication) {
        long studentId = currentAccount.id(authentication);
        return academic.timetableForStudent(studentId).stream()
                .map(slot -> new TimetableView(slot.id(), slot.classId(), slot.subjectCode(), slot.subjectName(), slot.section(),
                        slot.room(), slot.dayOfWeek(), slot.starts().toString(), slot.ends().toString(),
                        slot.facultyName()))
                .toList();
    }

    /** The classes that are currently taking attendance. */
    @GetMapping("/sessions/active")
    public List<Map<String, Object>> activeSessions(Authentication authentication) {
        long studentId = currentAccount.id(authentication);
        return attendance.activeClasses(studentId).stream()
                .map(view -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("classId", view.id());
                    row.put("subjectCode", view.subjectCode());
                    row.put("subjectName", view.subjectName());
                    row.put("section", view.section());
                    row.put("room", view.room());
                    row.put("facultyName", view.facultyName());
                    return row;
                })
                .toList();
    }

    // ------------------------------------------------------------------ biometrics

    /** The biometric state of the signed in student. */
    @GetMapping("/biometric/status")
    public BiometricStatusView biometricStatus(Authentication authentication) {
        return biometrics.status(currentAccount.id(authentication));
    }

    /** Issues a liveness challenge for the identity check. */
    @PostMapping("/biometric/challenge")
    public ChallengeView challenge(Authentication authentication) {
        return biometrics.issueChallenge(currentAccount.id(authentication), "ENROLMENT");
    }

    /** Completes the identity check and enrols the face template. */
    @PostMapping("/biometric/enroll")
    public EnrolmentResponse enrol(@RequestParam("frames") List<MultipartFile> frames,
                                    @RequestParam("challengeId") String challengeId,
                                    Authentication authentication) {
        long studentId = currentAccount.id(authentication);
        return biometrics.enrol(studentId, captureReader.read(frames), challengeId);
    }

    /** Removes the biometric data of the signed in student. */
    @DeleteMapping("/biometric/template")
    public ResponseEntity<Map<String, String>> revokeTemplate(Authentication authentication) {
        biometrics.revoke(currentAccount.id(authentication));
        return ResponseEntity.ok(Map.of("status", "removed", "state", "REVOKED"));
    }

    // ------------------------------------------------------------------ attendance

    /** Validates a scanned QR code and issues a ticket for the face check. */
    @PostMapping("/attendance/scan")
    public ScanResponse scan(@Valid @RequestBody ScanRequest request, Authentication authentication) {
        return attendance.scan(currentAccount.id(authentication), request.token());
    }

    /** Completes an attendance submission. */
    @PostMapping("/attendance/submit")
    public SubmissionResponse submit(@RequestParam("ticketId") String ticketId,
                                     @RequestParam("frames") List<MultipartFile> frames,
                                     Authentication authentication) {
        if (ticketId == null || ticketId.isBlank()) {
            throw ApiException.badRequest("TICKET_MISSING", "The attendance attempt is missing. Scan the code again.");
        }
        return attendance.submit(currentAccount.id(authentication), ticketId, captureReader.read(frames));
    }

}