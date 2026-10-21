package com.veriqra.app.service.attendance;

import com.veriqra.app.config.AppProperties;
import com.veriqra.app.domain.dto.AttendanceDtos.QrPayloadView;
import com.veriqra.app.domain.dto.AttendanceDtos.RosterEntry;
import com.veriqra.app.domain.dto.AttendanceDtos.RosterUpdateRequest;
import com.veriqra.app.domain.dto.AttendanceDtos.SessionView;
import com.veriqra.app.repository.AcademicRepository;
import com.veriqra.app.repository.AttendanceRepository;
import com.veriqra.app.repository.AuditRepository;
import com.veriqra.app.security.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The teaching side of attendance: running a session, projecting the rotating code,
 * reading the live roster and correcting records with an audit trail.
 */
@Service
public class AttendanceService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceService.class);

    private final AttendanceRepository attendance;
    private final AcademicRepository academic;
    private final AuditRepository audit;
    private final QrTokenService qrTokens;
    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();

    public AttendanceService(AttendanceRepository attendance,
                             AcademicRepository academic,
                             AuditRepository audit,
                             QrTokenService qrTokens,
                             AppProperties properties) {
        this.attendance = attendance;
        this.academic = academic;
        this.audit = audit;
        this.qrTokens = qrTokens;
        this.properties = properties;
    }

    /**
     * Opens an attendance session for a class.
     *
     * @throws ApiException when the teacher is not assigned to the class
     */
    @Transactional
    public SessionView startSession(long facultyId, long classId, String title) {
        if (!academic.facultyTeaches(facultyId, classId)) {
            throw ApiException.forbidden("NOT_ASSIGNED",
                    "You are not assigned to teach this class.");
        }
        Instant now = Instant.now();
        Instant endsAt = now.plusSeconds(properties.getAttendance().getSessionDurationMinutes() * 60L);
        String nonce = newNonce();
        long sessionId = attendance.insertSession(classId, facultyId, now, endsAt, nonce, title);
        audit.record(facultyId, "SESSION_STARTED", "session=" + sessionId, "TEACHER_ACTION",
                "Attendance session opened", null, title);
        log.info("Faculty {} opened attendance session {}", facultyId, sessionId);
        return buildViews(List.of(attendance.findSession(sessionId).orElseThrow())).get(0);
    }

    /** Closes a session. */
    @Transactional
    public SessionView closeSession(long facultyId, long sessionId) {
        AttendanceRepository.SessionRow before = requireOwnership(facultyId, sessionId);
        Instant now = Instant.now();
        attendance.closeSession(sessionId, now);
        audit.record(facultyId, "SESSION_CLOSED", "session=" + sessionId, "TEACHER_ACTION",
                "Attendance session closed", null, "CLOSED");
        return buildViews(List.of(attendance.findSession(sessionId).orElse(before))).get(0);
    }

    /** Issues the rotating QR payload for a session. */
    public QrPayloadView qrPayload(long facultyId, long sessionId) {
        AttendanceRepository.SessionRow session = requireOwnership(facultyId, sessionId);
        if (session.status() != AttendanceRepository.SessionStatus.ACTIVE) {
            throw ApiException.conflict("SESSION_NOT_ACTIVE",
                    "This session is closed. Start a new session to take attendance.");
        }
        if (session.endsAt().isBefore(Instant.now())) {
            throw ApiException.conflict("SESSION_EXPIRED",
                    "This session has timed out. Start a new session to take attendance.");
        }
        QrTokenService.IssuedQr issued = qrTokens.issue(sessionId, session.sessionNonce());
        return new QrPayloadView(issued.payload(), issued.signature(), issued.token(),
                issued.issuedAt(), issued.expiresAt(), issued.expiresInSeconds(), qrTokens.refreshSeconds());
    }
    /** The live roster of a session. */
    public List<RosterEntry> roster(long facultyId, long sessionId) {
        AttendanceRepository.SessionRow session = requireOwnership(facultyId, sessionId);
        return attendance.rosterForSession(sessionId, session.classId()).stream()
                .map(row -> new RosterEntry(row.studentId(), row.studentName(), row.studentNumber(),
                        row.status(), row.recordedAt(), row.matchScore(), row.method(), row.faceVerified()))
                .toList();
    }

    /** A single session as the teacher sees it. */
    public SessionView session(long facultyId, long sessionId) {
        AttendanceRepository.SessionRow row = requireOwnership(facultyId, sessionId);
        return buildViews(List.of(row)).get(0);
    }

    /** The sessions of a teacher, most recent first. */
    public List<SessionView> sessions(long facultyId, AttendanceRepository.SessionStatus status, int limit) {
        return buildViews(attendance.sessionsForFaculty(facultyId, status, limit));
    }

    /**
     * Applies a teacher correction to one attendance record.
     *
     * <p>Every correction writes an audit entry that names the actor, the reason and both
     * the previous and the new value.
     */
    @Transactional
    public RosterEntry mark(long facultyId, long sessionId, long studentId, RosterUpdateRequest request) {
        AttendanceRepository.SessionRow session = requireOwnership(facultyId, sessionId);
        AttendanceRepository.AttendanceStatus status;
        try {
            status = AttendanceRepository.AttendanceStatus.valueOf(request.status().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_STATUS",
                    "Status must be PRESENT, ABSENT or EXCUSED.");
        }

        AttendanceRepository.RecordRow existing = attendance.findRecord(sessionId, studentId).orElse(null);
        String previous = existing == null ? "ABSENT" : existing.status().name();
        Instant now = Instant.now();
        AttendanceRepository.RecordMethod method = existing != null && existing.method() != null
                ? existing.method()
                : AttendanceRepository.RecordMethod.MANUAL;

        attendance.upsertRecord(sessionId, studentId, status, now,
                existing == null ? null : existing.matchScore(),
                existing == null ? null : existing.livenessScore(),
                status == AttendanceRepository.AttendanceStatus.EXCUSED
                        ? AttendanceRepository.RecordMethod.EXCUSED : method,
                "TEACHER", request.explanation());

        audit.record(facultyId, "ATTENDANCE_OVERRIDE",
                "session=" + sessionId + ",student=" + studentId,
                request.reasonCode().trim().toUpperCase(), request.explanation().trim(),
                previous, status.name());
        log.info("Faculty {} changed attendance of student {} in session {} from {} to {}",
                facultyId, studentId, sessionId, previous, status.name());

        return roster(facultyId, sessionId).stream()
                .filter(entry -> entry.studentId() == studentId)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("ROSTER_ENTRY_MISSING",
                        "That student is not enrolled in this class."));
    }

    /** Closes sessions whose window has elapsed. */
    public int expireStaleSessions() {
        int closed = attendance.expireStaleSessions(Instant.now());
        if (closed > 0) {
            log.info("Expired {} attendance sessions that ran past their window", closed);
        }
        return closed;
    }

    /**
     * Removes a session that holds no attendance records.
     *
     * <p>Used when a session was opened for the wrong class by mistake.
     */
    @Transactional
    public void deleteSession(long sessionId) {
        AttendanceRepository.SessionRow session = attendance.findSession(sessionId)
                .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "That session does not exist."));
        if (attendance.presentCount(sessionId) > 0) {
            throw ApiException.conflict("SESSION_HAS_RECORDS",
                    "The session already holds attendance records and is not removed.");
        }
        attendance.deleteSession(sessionId);        audit.record(session.facultyId(), "SESSION_REMOVED", "session=" + sessionId, "ADMIN_ACTION",
                "Session removed because it was opened by mistake", null, null);
        log.info("Removed the empty attendance session {}", sessionId);
    }

    // ------------------------------------------------------------------ internals

    private AttendanceRepository.SessionRow requireOwnership(long facultyId, long sessionId) {
        AttendanceRepository.SessionRow session = attendance.findSession(sessionId)
                .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "That session does not exist."));
        if (session.facultyId() != facultyId) {
            throw ApiException.forbidden("NOT_OWNER",
                    "You are not allowed to manage this session.");
        }
        return session;
    }

    /**
     * Builds the view of a set of sessions.
     *
     * <p>The class of every session and the number of students present are read once for
     * the whole list. Asking for them one session at a time costs a round trip per
     * session, which on a distant database is what makes a list feel slow.
     */
    private List<SessionView> buildViews(List<AttendanceRepository.SessionRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, AcademicRepository.ClassView> classes = academic.listAllClasses().stream()
                .collect(Collectors.toMap(AcademicRepository.ClassView::id, view -> view));
        Map<Long, Long> present = attendance.presentCounts(
                rows.stream().map(AttendanceRepository.SessionRow::id).toList());

        return rows.stream()
                .map(row -> {
                    AcademicRepository.ClassView classView = classes.get(row.classId());
                    if (classView == null) {
                        throw ApiException.notFound("CLASS_NOT_FOUND",
                                "The class of this session is missing.");
                    }
                    return new SessionView(row.id(), row.classId(), classView.subjectCode(),
                            classView.subjectName(), classView.section(), classView.room(),
                            classView.facultyName(), row.title(), row.status().name(), row.startedAt(),
                            row.endsAt(), row.closedAt(), present.getOrDefault(row.id(), 0L),
                            classView.enrolled());
                })
                .toList();
    }

    private String newNonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
