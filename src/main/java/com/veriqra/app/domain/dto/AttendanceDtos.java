package com.veriqra.app.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * The JSON shapes exchanged with the attendance and biometric endpoints.
 */
public final class AttendanceDtos {

    private AttendanceDtos() {
    }

    /** The rotating code a teacher projects. */
    public record QrPayloadView(
            String payload,
            String signature,
            String token,
            long issuedAt,
            long expiresAt,
            int expiresInSeconds,
            int refreshSeconds) {
    }

    /** The request that carries a scanned QR token. */
    public record ScanRequest(@NotBlank @Size(max = 512) String token) {
    }

    /**
     * The result of a successful scan.
     *
     * <p>The ticket is the authorisation to submit one attendance record. It is bound to
     * the student and the session, expires quickly and can be used only once.
     */
    public record ScanResponse(
            String ticketId,
            String challengeId,
            String challengeInstruction,
            String challengeGuidance,
            long sessionId,
            String subjectCode,
            String subjectName,
            String section,
            String room,
            String facultyName,
            Instant expiresAt,
            int requiredFrames) {
    }

    /** The outcome of submitting an attendance record. */
    public record SubmissionResponse(
            String outcome,
            String message,
            Long recordId,
            Double matchScore,
            Double livenessScore,
            Instant recordedAt) {
    }

    /** A live attendance session. */
    public record SessionView(
            long id,
            long classId,
            String subjectCode,
            String subjectName,
            String section,
            String room,
            String facultyName,
            String title,
            String status,
            Instant startedAt,
            Instant endsAt,
            Instant closedAt,
            long presentCount,
            long enrolledCount) {
    }

    /** One row of the live roster. */
    public record RosterEntry(
            long studentId,
            String studentName,
            String studentNumber,
            String status,
            Instant recordedAt,
            Double matchScore,
            String method,
            boolean faceVerified) {
    }

    /** The request that opens a new attendance session. */
    public record StartSessionRequest(@NotNull Long classId, @Size(max = 160) String title) {
    }

    /** The request that adds or moves a lecture in the teaching diary. */
    public record TimetableSlotRequest(
            @NotNull Long classId,
            @NotNull Integer dayOfWeek,
            @NotBlank @Size(max = 8) String starts,
            @NotBlank @Size(max = 8) String ends) {
    }

    /** A teacher correction of one attendance record. */
    public record RosterUpdateRequest(
            @NotBlank String status,
            @NotBlank @Size(max = 60) String reasonCode,
            @NotBlank @Size(max = 500) String explanation) {
    }

    /** A liveness challenge handed to the client. */
    public record ChallengeView(
            String challengeId,
            String instruction,
            String guidance,
            Instant expiresAt,
            int requiredFrames,
            int frameIntervalMillis) {
    }

    /** The biometric state of the signed in student. */
    public record BiometricStatusView(
            String state,
            Instant enrolledAt,
            String modelVersion,
            int sampleCount,
            boolean available,
            String unavailableReason,
            String consentVersion) {
    }

    /** The result of an enrolment attempt. */
    public record EnrolmentResponse(
            boolean enrolled,
            String message,
            String state,
            Double qualityScore,
            Double minSimilarity,
            Instant enrolledAt) {
    }

    /** Per subject attendance totals. */
    public record SubjectAttendanceView(
            String subjectCode,
            String subjectName,
            long sessions,
            long attended,
            long enrolled,
            double percentage) {
    }

    /** A student whose attendance is below the institutional threshold. */
    public record LowAttendanceView(
            long studentId,
            String studentName,
            String studentNumber,
            String subjectCode,
            String subjectName,
            long sessions,
            long attended,
            double percentage) {
    }

    /** A timetable slot. */
    public record TimetableView(
            long id,
            long classId,
            String subjectCode,
            String subjectName,
            String section,
            String room,
            int dayOfWeek,
            String starts,
            String ends,
            String facultyName) {
    }

    /** A class section the teacher owns. */
    public record ClassView(
            long id,
            long subjectId,
            String subjectCode,
            String subjectName,
            String section,
            String room,
            long enrolled) {
    }

    /** The numbers shown on the dashboards. */
    public record DashboardStats(
            long total,
            long present,
            long absent,
            long pending,
            double percentage) {
    }
}
