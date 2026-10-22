package com.veriqra.app.service.attendance;

import com.veriqra.app.config.AppProperties;
import com.veriqra.app.domain.dto.AttendanceDtos.ScanResponse;
import com.veriqra.app.domain.dto.AttendanceDtos.SubmissionResponse;
import com.veriqra.app.repository.AcademicRepository;
import com.veriqra.app.repository.AttendanceRepository;
import com.veriqra.app.repository.BiometricRepository;
import com.veriqra.app.security.ApiException;
import com.veriqra.app.service.LiveUpdates;
import com.veriqra.app.service.biometric.BiometricService;
import com.veriqra.app.service.biometric.FaceImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The student side of attendance.
 *
 * <p>The flow has three steps and each one is checked on the server:
 * <ol>
 *   <li>the student scans the rotating code, which proves the teacher is running a session;</li>
 *   <li>the server issues a single use ticket and a random liveness challenge;</li>
 *   <li>the student performs the challenge, and the server compares the live face with
 *       the enrolled template before writing the record.</li>
 * </ol>
 *
 * <p>A student cannot skip a step. There is no code path that writes an attendance record
 * from a browser supplied verdict.
 */
@Service
public class StudentAttendanceService {

    private static final Logger log = LoggerFactory.getLogger(StudentAttendanceService.class);

    private final AttendanceRepository attendance;
    private final AcademicRepository academic;
    private final BiometricRepository biometrics;
    private final QrTokenService qrTokens;
    private final BiometricService biometricService;
    private final AppProperties properties;
    private final LiveUpdates liveUpdates;

    public StudentAttendanceService(AttendanceRepository attendance,
                                    AcademicRepository academic,
                                    BiometricRepository biometrics,
                                    QrTokenService qrTokens,
                                    BiometricService biometricService,
                                    AppProperties properties,
                                    LiveUpdates liveUpdates) {
        this.attendance = attendance;
        this.academic = academic;
        this.biometrics = biometrics;
        this.qrTokens = qrTokens;
        this.biometricService = biometricService;
        this.properties = properties;
        this.liveUpdates = liveUpdates;
    }

    /**
     * Validates a scanned QR code and issues a ticket for the face check.
     *
     * <p>The session is derived from the signed payload, so the student only has to scan
     * the code. Everything else is checked against the database.
     */
    @Transactional
    public ScanResponse scan(long studentId, String token) {
        QrTokenService.QrValidation validation = qrTokens.validate(token);
        if (!validation.valid()) {
            throw ApiException.badRequest("QR_INVALID", validation.reason());
        }

        long sessionId = validation.sessionId();
        AttendanceRepository.SessionRow session = attendance.findSession(sessionId)
                .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "That class session does not exist."));

        if (session.status() != AttendanceRepository.SessionStatus.ACTIVE) {
            throw ApiException.conflict("SESSION_NOT_ACTIVE", "This class session is closed.");
        }
        if (session.endsAt().isBefore(Instant.now())) {
            throw ApiException.conflict("SESSION_EXPIRED", "This class session has ended.");
        }
        if (!qrTokens.nonceMatches(session.sessionNonce(), validation.sessionNonce())) {
            throw ApiException.badRequest("QR_STALE",
                    "That QR code was issued for an earlier session. Scan the current code.");
        }
        if (!academic.isEnrolled(studentId, session.classId())) {
            throw ApiException.forbidden("NOT_ENROLLED", "You are not enrolled in this class.");
        }
        if (biometrics.findTemplate(studentId).isEmpty()) {
            throw ApiException.forbidden("FACE_NOT_VERIFIED",
                    "Your face has not been verified yet. Complete the identity check first.");
        }
        if (attendance.recordExists(sessionId, studentId)) {
            throw ApiException.conflict("ALREADY_RECORDED",
                    "Your attendance for this class has already been recorded.");
        }

        enforceSubmissionInterval(studentId, sessionId);

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(properties.getAttendance().getVerificationTicketTtlSeconds());

        var challenge = biometricService.issueChallenge(studentId, "ATTENDANCE");
        BiometricRepository.Ticket ticket = biometrics.createTicket(studentId, sessionId,
                challenge.challengeId(), issuedAt, expiresAt);

        AcademicRepository.ClassView classView = academic.findClass(session.classId())
                .orElseThrow(() -> ApiException.notFound("CLASS_NOT_FOUND", "The class of this session is missing."));

        return new ScanResponse(ticket.id(), challenge.challengeId(), challenge.instruction(),
                challenge.guidance(), sessionId, classView.subjectCode(), classView.subjectName(),
                classView.section(), classView.room(), classView.facultyName(), expiresAt,
                properties.getBiometric().getRequiredFrames());
    }

    /**
     * Completes an attendance submission.
     *
     * @param ticketId the ticket issued by the scan
     * @param frames   the frames captured while performing the liveness challenge
     */
    @Transactional
    public SubmissionResponse submit(long studentId, String ticketId, List<FaceImage> frames) {
        BiometricRepository.Ticket ticket = biometrics.findTicket(ticketId)
                .orElseThrow(() -> ApiException.badRequest("TICKET_UNKNOWN",
                        "That attendance attempt has expired. Please scan the code again."));
        if (ticket.studentId() != studentId) {
            throw ApiException.forbidden("TICKET_MISMATCH", "That attendance attempt belongs to another account.");
        }
        if (ticket.expiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("TICKET_EXPIRED",
                    "That attendance attempt has expired. Please scan the code again.");
        }
        if (!biometrics.consumeTicket(ticketId)) {
            throw ApiException.conflict("TICKET_USED",
                    "That attendance attempt has already been submitted.");
        }

        AttendanceRepository.SessionRow session = attendance.findSession(ticket.sessionId())
                .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "That class session does not exist."));
        if (session.status() != AttendanceRepository.SessionStatus.ACTIVE) {
            throw ApiException.conflict("SESSION_NOT_ACTIVE", "This class session is closed.");
        }
        if (attendance.recordExists(session.id(), studentId)) {
            return new SubmissionResponse("ALREADY_RECORDED",
                    "Your attendance for this class has already been recorded.",
                    null, null, null, null);
        }

        BiometricService.MatchOutcome outcome = biometricService.verify(studentId, frames, ticket.challengeId());
        if (!outcome.matched()) {
            attendance.insertAttempt(session.id(), studentId, "REJECTED", outcome.reasonCode());
            log.info("Rejected attendance of student {} for session {}: {}",
                    studentId, session.id(), outcome.reasonCode());
            return new SubmissionResponse("REJECTED", outcome.message(), null,
                    outcome.similarity() > 0 ? outcome.similarity() : null, null, null);
        }

        Instant now = Instant.now();
        attendance.upsertRecord(session.id(), studentId, AttendanceRepository.AttendanceStatus.PRESENT, now,
                outcome.similarity(), outcome.livenessScore(),
                AttendanceRepository.RecordMethod.FACE_VERIFIED, "QR_FACE", null);
        biometricService.consumeChallenge(ticket.challengeId());

        // The teacher watches the room of the session, so the roster is up to date at
        // once instead of at the next poll.
        liveUpdates.publish(String.valueOf(session.facultyId()), "roster");
        liveUpdates.publish(String.valueOf(studentId), "attendance");

        Optional<AttendanceRepository.RecordRow> record = attendance.findRecord(session.id(), studentId);
        log.info("Recorded attendance of student {} for session {} with match score {}",
                studentId, session.id(), outcome.similarity());

        return new SubmissionResponse("RECORDED", "Your attendance has been recorded.",
                record.map(AttendanceRepository.RecordRow::id).orElse(null),
                outcome.similarity(), outcome.livenessScore(), now);
    }

    /** The active sessions a student may mark attendance for. */
    public List<AcademicRepository.ClassView> activeClasses(long studentId) {
        return attendance.activeSessionsForStudent(studentId, Instant.now()).stream()
                .map(session -> academic.findClass(session.classId()))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    private void enforceSubmissionInterval(long studentId, long sessionId) {
        Optional<Instant> last = biometrics.lastSubmissionAt(sessionId, studentId);
        if (last.isEmpty()) {
            return;
        }
        long elapsed = Instant.now().getEpochSecond() - last.get().getEpochSecond();
        int minimum = properties.getAttendance().getSubmissionIntervalSeconds();
        if (elapsed < minimum) {
            throw ApiException.tooManyRequests("SUBMISSION_TOO_FAST",
                    "Please wait a moment before trying again.");
        }
    }
}
