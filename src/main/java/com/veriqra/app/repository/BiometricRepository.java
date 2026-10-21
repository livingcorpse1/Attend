package com.veriqra.app.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for biometric enrolments, liveness challenges and verification tickets.
 */
@Repository
public class BiometricRepository {

    /** The stored face template of one student. */
    public record FaceTemplate(long studentId, String modelVersion, byte[] embedding, Instant enrolledAt,
                               int sampleCount, double qualityScore, double meanSimilarity, String storagePath) {
    }

    /** A liveness challenge handed to the client. */
    public record Challenge(String id, long studentId, String kind, Instant issuedAt, Instant expiresAt,
                            String context, Instant consumedAt) {
    }

    /** A ticket that authorises exactly one attendance submission. */
    public record Ticket(String id, long studentId, long sessionId, String challengeId, Instant issuedAt,
                         Instant expiresAt, Instant consumedAt, String state) {
    }

    private final JdbcTemplate db;

    public BiometricRepository(JdbcTemplate db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- templates

    public Optional<FaceTemplate> findTemplate(long studentId) {
        return db.query("select * from face_template where student_id = ?", (rs, rowNum) -> new FaceTemplate(
                rs.getLong("student_id"),
                rs.getString("model_version"),
                rs.getBytes("embedding"),
                rs.getTimestamp("enrolled_at").toInstant(),
                rs.getInt("sample_count"),
                rs.getDouble("quality_score"),
                rs.getDouble("mean_similarity"),
                rs.getString("storage_path")), studentId).stream().findFirst();
    }

    public void saveTemplate(long studentId, String modelVersion, byte[] embedding, Instant enrolledAt,
                             int sampleCount, double qualityScore, double meanSimilarity, String storagePath) {
        db.update("delete from face_template where student_id = ?", studentId);
        db.update("""
                insert into face_template(student_id, model_version, embedding, enrolled_at, sample_count,
                                          quality_score, mean_similarity, storage_path)
                values(?,?,?,?,?,?,?,?)
                """, studentId, modelVersion, embedding, Timestamp.from(enrolledAt), sampleCount,
                qualityScore, meanSimilarity, storagePath);
    }

    public void deleteTemplate(long studentId) {
        db.update("delete from face_template where student_id = ?", studentId);
    }

    public long templateCount() {
        Long count = db.queryForObject("select count(*) from face_template", Long.class);
        return count == null ? 0L : count;
    }

    // -------------------------------------------------------------- challenges

    public Challenge createChallenge(long studentId, String kind, Instant issuedAt, Instant expiresAt, String context) {
        String id = UUID.randomUUID().toString();
        db.update("insert into liveness_challenge(id, student_id, kind, issued_at, expires_at, context) "
                + "values(?,?,?,?,?,?)", id, studentId, kind, Timestamp.from(issuedAt), Timestamp.from(expiresAt), context);
        return new Challenge(id, studentId, kind, issuedAt, expiresAt, context, null);
    }

    public Optional<Challenge> findChallenge(String id) {
        return db.query("select * from liveness_challenge where id = ?", (rs, rowNum) -> new Challenge(
                rs.getString("id"),
                rs.getLong("student_id"),
                rs.getString("kind"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getString("context"),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant()),
                id).stream().findFirst();
    }

    /** Marks a challenge as used. Returns false when it had already been consumed. */
    public boolean consumeChallenge(String id) {
        return db.update("update liveness_challenge set consumed_at = current_timestamp "
                + "where id = ? and consumed_at is null", id) == 1;
    }

    public int deleteExpiredChallenges(Instant now) {
        return db.update("delete from liveness_challenge where expires_at < ?", Timestamp.from(now));
    }

    // ----------------------------------------------------------------- tickets

    public Ticket createTicket(long studentId, long sessionId, String challengeId, Instant issuedAt, Instant expiresAt) {
        String id = UUID.randomUUID().toString();
        db.update("""
                insert into verification_ticket(id, student_id, session_id, challenge_id, issued_at, expires_at, state)
                values(?,?,?,?,?,?, 'ISSUED')
                """, id, studentId, sessionId, challengeId, Timestamp.from(issuedAt), Timestamp.from(expiresAt));
        return new Ticket(id, studentId, sessionId, challengeId, issuedAt, expiresAt, null, "ISSUED");
    }

    public Optional<Ticket> findTicket(String id) {
        return db.query("select * from verification_ticket where id = ?", (rs, rowNum) -> new Ticket(
                rs.getString("id"),
                rs.getLong("student_id"),
                rs.getLong("session_id"),
                rs.getString("challenge_id"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                rs.getString("state")), id).stream().findFirst();
    }

    /** Consumes a ticket exactly once. Returns false when it was already used or expired. */
    public boolean consumeTicket(String id) {
        return db.update("update verification_ticket set consumed_at = current_timestamp, state = 'CONSUMED' "
                + "where id = ? and consumed_at is null and expires_at > current_timestamp", id) == 1;
    }

    public int deleteExpiredTickets(Instant now) {
        return db.update("delete from verification_ticket where expires_at < ?", Timestamp.from(now));
    }

    /** Timestamp of the last successful attendance submission of a student in a session. */
    public Optional<Instant> lastSubmissionAt(long sessionId, long studentId) {
        List<Instant> rows = db.query("select max(recorded_at) from attendance_record "
                        + "where session_id = ? and student_id = ?",
                (rs, rowNum) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(),
                sessionId, studentId);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }
}
