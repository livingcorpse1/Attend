package com.veriqra.app.web;

import com.veriqra.app.domain.dto.AttendanceDtos.ClassView;
import com.veriqra.app.domain.dto.AttendanceDtos.LowAttendanceView;
import com.veriqra.app.domain.dto.AttendanceDtos.QrPayloadView;
import com.veriqra.app.domain.dto.AttendanceDtos.RosterEntry;
import com.veriqra.app.domain.dto.AttendanceDtos.RosterUpdateRequest;
import com.veriqra.app.domain.dto.AttendanceDtos.SessionView;
import com.veriqra.app.domain.dto.AttendanceDtos.StartSessionRequest;
import com.veriqra.app.domain.dto.AttendanceDtos.SubjectAttendanceView;
import com.veriqra.app.domain.dto.AttendanceDtos.TimetableView;
import com.veriqra.app.domain.dto.AttendanceDtos.TimetableSlotRequest;
import com.veriqra.app.config.ParallelQueries;
import com.veriqra.app.repository.AcademicRepository;
import com.veriqra.app.repository.AttendanceRepository;
import com.veriqra.app.security.CurrentAccount;
import com.veriqra.app.service.attendance.AttendanceService;
import com.veriqra.app.service.report.ReportService;
import com.veriqra.app.service.timetable.TimetableService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a teacher can do: the dashboard, the live attendance session and the
 * timetable.
 */
@RestController
@RequestMapping("/api/faculty")
public class FacultyController {

    private final AttendanceService attendance;
    private final ReportService reports;
    private final AcademicRepository academic;
    private final TimetableService timetableService;
    private final ParallelQueries parallel;
    private final CurrentAccount currentAccount;

    public FacultyController(AttendanceService attendance,
                             ReportService reports,
                             AcademicRepository academic,
                             TimetableService timetableService,
                             ParallelQueries parallel,
                             CurrentAccount currentAccount) {
        this.attendance = attendance;
        this.reports = reports;
        this.academic = academic;
        this.timetableService = timetableService;
        this.parallel = parallel;
        this.currentAccount = currentAccount;
    }

    /** The numbers shown on the teacher dashboard. */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(Authentication authentication) {
        long facultyId = currentAccount.id(authentication);

        // The dashboard is assembled from four independent reads. They are sent together
        // so the screen waits for the slowest one instead of for all four in a row.
        List<Object> results = parallel.runAll(
                () -> reports.subjectAttendanceForFaculty(facultyId),
                () -> attendance.sessions(facultyId, null, 8),
                () -> academic.listClassesForFaculty(facultyId),
                () -> timetableService.list(facultyId));

        @SuppressWarnings("unchecked")
        List<SubjectAttendanceView> subjects = (List<SubjectAttendanceView>) results.get(0);
        @SuppressWarnings("unchecked")
        List<SessionView> recent = (List<SessionView>) results.get(1);
        @SuppressWarnings("unchecked")
        List<ClassView> classes = (List<ClassView>) results.get(2);
        @SuppressWarnings("unchecked")
        List<TimetableView> diary = (List<TimetableView>) results.get(3);

        long sessions = subjects.stream().mapToLong(SubjectAttendanceView::sessions).sum();
        long present = subjects.stream().mapToLong(SubjectAttendanceView::attended).sum();
        double percentage = sessions == 0 ? 0d : (present * 100d) / sessions;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("overallPercentage", Math.round(percentage * 10d) / 10d);
        body.put("sessionsHeld", sessions);
        body.put("recordsMarked", present);
        body.put("classesTaught", classes.size());
        body.put("activeSession", recent.stream()
                .filter(session -> "ACTIVE".equals(session.status()))
                .findFirst().orElse(null));
        body.put("recentSessions", recent);
        body.put("subjects", subjects);
        body.put("timetable", diary);
        return body;
    }

    /** The class sections the teacher is assigned to. */
    @GetMapping("/classes")
    public List<ClassView> classes(Authentication authentication) {
        return academic.listClassesForFaculty(currentAccount.id(authentication)).stream()
                .map(view -> new ClassView(view.id(), view.subjectCode(), view.subjectName(), view.section(),
                        view.room(), view.enrolled()))
                .toList();
    }

    /** The timetable of the teacher. */
    @GetMapping("/timetable")
    public List<TimetableView> timetable(Authentication authentication) {
        return timetableService.list(currentAccount.id(authentication));
    }

    /** Adds a lecture to the teaching diary. */
    @PostMapping("/timetable")
    public ResponseEntity<TimetableView> createSlot(@Valid @RequestBody TimetableSlotRequest request,
                                                     Authentication authentication) {
        TimetableView created = timetableService.create(currentAccount.id(authentication),
                request.classId(), request.dayOfWeek(), request.starts(), request.ends());
        return ResponseEntity.status(201).body(created);
    }

    /** Moves or reschedules a lecture. */
    @PatchMapping("/timetable/{slotId}")
    public TimetableView updateSlot(@PathVariable long slotId,
                                     @Valid @RequestBody TimetableSlotRequest request,
                                     Authentication authentication) {
        return timetableService.update(currentAccount.id(authentication), slotId,
                request.classId(), request.dayOfWeek(), request.starts(), request.ends());
    }

    /** Removes a lecture from the teaching diary. */
    @DeleteMapping("/timetable/{slotId}")
    public ResponseEntity<Map<String, String>> deleteSlot(@PathVariable long slotId,
                                                          Authentication authentication) {
        timetableService.delete(currentAccount.id(authentication), slotId);
        return ResponseEntity.ok(Map.of("status", "removed"));
    }

    /** The attendance sessions of the teacher. */
    @GetMapping("/sessions")
    public List<SessionView> sessions(@RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "25") int limit,
                                      Authentication authentication) {
        AttendanceRepository.SessionStatus parsed = null;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            try {
                parsed = AttendanceRepository.SessionStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                parsed = null;
            }
        }
        return attendance.sessions(currentAccount.id(authentication), parsed, Math.min(limit, 100));
    }

    /** Opens a new attendance session. */
    @PostMapping("/sessions")
    public SessionView start(@Valid @RequestBody StartSessionRequest request, Authentication authentication) {
        return attendance.startSession(currentAccount.id(authentication), request.classId(), request.title());
    }

    /** A single session. */
    @GetMapping("/sessions/{sessionId}")
    public SessionView session(@PathVariable long sessionId, Authentication authentication) {
        return attendance.session(currentAccount.id(authentication), sessionId);
    }

    /** The rotating code of a session. */
    @GetMapping("/sessions/{sessionId}/qr")
    public QrPayloadView qr(@PathVariable long sessionId, Authentication authentication) {
        return attendance.qrPayload(currentAccount.id(authentication), sessionId);
    }

    /** Closes a session. */
    @PostMapping("/sessions/{sessionId}/close")
    public SessionView close(@PathVariable long sessionId, Authentication authentication) {
        return attendance.closeSession(currentAccount.id(authentication), sessionId);
    }

    /**
     * Removes a session that was opened by mistake.
     *
     * <p>Only the teacher who owns the session may do this, and only while it holds no
     * attendance records. Once a student has been marked present, the session is a
     * record of what happened and it stays.
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, String>> remove(@PathVariable long sessionId,
                                                     Authentication authentication) {
        attendance.deleteSession(sessionId, currentAccount.id(authentication));
        return ResponseEntity.ok(Map.of("status", "removed"));
    }

    /** The live roster of a session. */
    @GetMapping("/sessions/{sessionId}/roster")
    public List<RosterEntry> roster(@PathVariable long sessionId, Authentication authentication) {
        return attendance.roster(currentAccount.id(authentication), sessionId);
    }

    /** Applies a teacher correction to one record. */
    @PatchMapping("/sessions/{sessionId}/roster/{studentId}")
    public RosterEntry mark(@PathVariable long sessionId,
                             @PathVariable long studentId,
                             @Valid @RequestBody RosterUpdateRequest request,
                             Authentication authentication) {
        return attendance.mark(currentAccount.id(authentication), sessionId, studentId, request);
    }

    /** Per subject attendance totals. */
    @GetMapping("/reports/subjects")
    public List<SubjectAttendanceView> subjectReport(Authentication authentication) {
        return reports.subjectAttendanceForFaculty(currentAccount.id(authentication));
    }

    /** Students below the institutional threshold. */
    @GetMapping("/reports/low-attendance")
    public List<LowAttendanceView> lowAttendance(Authentication authentication) {
        return reports.lowAttendanceForFaculty(currentAccount.id(authentication));
    }

    /** Exports the low attendance list as CSV. */
    @GetMapping("/reports/low-attendance.csv")
    public ResponseEntity<byte[]> lowAttendanceCsv(Authentication authentication) throws IOException {
        List<LowAttendanceView> rows = reports.lowAttendanceForFaculty(currentAccount.id(authentication));
        List<List<String>> body = new ArrayList<>();
        for (LowAttendanceView row : rows) {
            body.add(List.of(row.studentNumber() == null ? "" : row.studentNumber(), row.studentName(),
                    row.subjectCode(), row.subjectName(), Long.toString(row.sessions()),
                    Long.toString(row.attended()), String.format("%.1f", row.percentage())));
        }
        byte[] csv = reports.toCsv(
                List.of("Roll number", "Student", "Subject code", "Subject", "Sessions", "Attended", "Percentage"),
                body);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=veriqra-low-attendance.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    /** Exports the low attendance list as PDF. */
    @GetMapping("/reports/low-attendance.pdf")
    public ResponseEntity<byte[]> lowAttendancePdf(Authentication authentication) throws IOException {
        List<LowAttendanceView> rows = reports.lowAttendanceForFaculty(currentAccount.id(authentication));
        List<List<String>> body = new ArrayList<>();
        for (LowAttendanceView row : rows) {
            body.add(List.of(row.studentNumber() == null ? "" : row.studentNumber(), row.studentName(),
                    row.subjectCode(), row.subjectName(), Long.toString(row.sessions()),
                    Long.toString(row.attended()), String.format("%.1f", row.percentage())));
        }
        byte[] pdf = reports.toPdf("Low attendance report",
                List.of("Roll number", "Student", "Subject code", "Subject", "Sessions", "Attended", "Percentage"),
                body);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=veriqra-low-attendance.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

}
