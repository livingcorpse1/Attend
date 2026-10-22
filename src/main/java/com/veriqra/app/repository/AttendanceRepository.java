package com.veriqra.app.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Data access for attendance sessions, attendance records and verification attempts.
 */
@Repository
public class AttendanceRepository {

    /** Lifecycle of an attendance session. */
    public enum SessionStatus {
        ACTIVE,
        CLOSED,
        EXPIRED;

        public static SessionStatus fromDatabase(String value) {
            if (value == null) {
                return CLOSED;
            }
            try {
                return valueOf(value);
            } catch (IllegalArgumentException ex) {
                return CLOSED;
            }
        }
    }

    /** Attendance outcome for one student in one session. */
    public enum AttendanceStatus {
        PRESENT,
        ABSENT,
        EXCUSED;

        public static AttendanceStatus fromDatabase(String value) {
            if (value == null) {
                return ABSENT;
            }
            try {
                return valueOf(value);
            } catch (IllegalArgumentException ex) {
                return ABSENT;
            }
        }
    }

    /** How an attendance record was produced. */
    public enum RecordMethod {
        /** QR plus a successful server side face verification. */
        FACE_VERIFIED,
        /** A teacher marked the student present by hand. */
        MANUAL,
        /** A teacher marked the student excused. */
        EXCUSED
    }

    /** An attendance session row. */
    public record SessionRow(
            long id,
            long classId,
            long facultyId,
            Instant startedAt,
            Instant endsAt,
            Instant closedAt,
            SessionStatus status,
            String sessionNonce,
            String title) {
    }

    /** A recorded attendance outcome. */
    public record RecordRow(
            long id,
            long sessionId,
            long studentId,
            AttendanceStatus status,
            Instant recordedAt,
            Double matchScore,
            Double livenessScore,
            RecordMethod method,
            String source,
            String note) {
    }

    /** A row of the live roster. */
    public record RosterRow(
            long studentId,
            String studentName,
            String studentNumber,
            String status,
            Instant recordedAt,
            Double matchScore,
            String method,
            boolean faceVerified) {
    }

    /** An audit-friendly record of a rejected verification. */
    public record AttemptRow(long id, long sessionId, long studentId, String outcome, String detail, Instant attemptedAt) {
    }

    private final JdbcTemplate db;

    public AttendanceRepository(JdbcTemplate db) {
        this.db = db;
    }

    private static final RowMapper<SessionRow> SESSION_MAPPER = (rs, rowNum) -> new SessionRow(
            rs.getLong("id"),
            rs.getLong("class_id"),
            rs.getLong("faculty_id"),
            rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("ends_at").toInstant(),
            rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(),
            SessionStatus.fromDatabase(rs.getString("status")),
            rs.getString("session_nonce"),
            rs.getString("title"));

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> new RecordRow(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getLong("student_id"),
            AttendanceStatus.fromDatabase(rs.getString("status")),
            rs.getTimestamp("recorded_at").toInstant(),
            (Double) rs.getObject("match_score"),
            (Double) rs.getObject("liveness_score"),
            parseMethod(rs.getString("method")),
            rs.getString("source"),
            rs.getString("note"));

    private static RecordMethod parseMethod(String value) {
        if (value == null || value.isBlank()) {
            return RecordMethod.MANUAL;
        }
        try {
            return RecordMethod.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return RecordMethod.MANUAL;
        }
    }

    public long insertSession(long classId, long facultyId, Instant startedAt, Instant endsAt,
                              String sessionNonce, String title) {
        return insertSession(classId, facultyId, startedAt, endsAt, sessionNonce, title, null);
    }

    /** Inserts a new attendance session for a lecture of the teaching diary. */
    public long insertSession(long classId, long facultyId, Instant startedAt, Instant endsAt,
                              String sessionNonce, String title, Long timetableSlotId) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into attendance_session(class_id, faculty_id, started_at, ends_at, status,
                                                   session_nonce, title, timetable_slot_id)
                    values(?,?,?,?,'ACTIVE',?,?,?)
                    """, new String[]{"id"});
            statement.setLong(1, classId);
            statement.setLong(2, facultyId);
            statement.setTimestamp(3, Timestamp.from(startedAt));
            statement.setTimestamp(4, Timestamp.from(endsAt));
            statement.setString(5, sessionNonce);
            statement.setString(6, title);
            if (timetableSlotId == null) {
                statement.setNull(7, java.sql.Types.BIGINT);
            } else {
                statement.setLong(7, timetableSlotId);
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<SessionRow> findSession(long id) {
        List<SessionRow> rows = db.query("select * from attendance_session where id = ?", SESSION_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean sessionBelongsToFaculty(long sessionId, long facultyId) {
        Integer count = db.queryForObject(
                "select count(*) from attendance_session where id = ? and faculty_id = ?",
                Integer.class, sessionId, facultyId);
        return count != null && count > 0;
    }

    public void closeSession(long sessionId, Instant closedAt) {
        db.update("update attendance_session set status = 'CLOSED', closed_at = ? where id = ? and status = 'ACTIVE'",
                Timestamp.from(closedAt), sessionId);
    }

    /** Removes a session that holds no attendance records. */
    public void deleteSession(long sessionId) {
        db.update("delete from attendance_session where id = ?", sessionId);
    }

    /** Closes sessions whose window has elapsed. Returns the number of rows closed. */
    public int expireStaleSessions(Instant now) {
        return db.update("update attendance_session set status = 'EXPIRED', closed_at = ? "
                + "where status = 'ACTIVE' and ends_at <= ?", Timestamp.from(now), Timestamp.from(now));
    }

    public List<SessionRow> sessionsForFaculty(long facultyId, SessionStatus status, int limit) {
        // A session whose time is up is never shown as running.
        expireStaleSessions(Instant.now());
        if (status == null) {
            return db.query("select * from attendance_session where faculty_id = ? order by started_at desc limit ?",
                    SESSION_MAPPER, facultyId, limit);
        }
        return db.query("select * from attendance_session where faculty_id = ? and status = ? order by started_at desc limit ?",
                SESSION_MAPPER, facultyId, status.name(), limit);
    }

    public List<SessionRow> activeSessionsForClass(long classId, Instant now) {
        return db.query("""
                select * from attendance_session
                 where class_id = ? and status = 'ACTIVE' and ends_at > ?
                 order by started_at desc
                """, SESSION_MAPPER, classId, Timestamp.from(now));
    }

    /** Every session of a class section, whatever its state. */
    public List<SessionRow> sessionsForClass(long classId) {
        return db.query("select * from attendance_session where class_id = ? order by started_at desc",
                SESSION_MAPPER, classId);
    }

    /** Every session that was taken for one lecture of the teaching diary. */
    public List<SessionRow> sessionsForSlot(long slotId) {
        return db.query("select * from attendance_session where timetable_slot_id = ? order by started_at desc",
                SESSION_MAPPER, slotId);
    }

    /** How many attendance records were written in a class section. */
    public int recordsForClass(long classId) {
        return db.queryForObject("""
                select count(*) from attendance_record r
                  join attendance_session s on s.id = r.session_id
                 where s.class_id = ?
                """, Integer.class, classId);
    }

    /** Active sessions for the classes a student is enrolled in. */
    public List<SessionRow> activeSessionsForStudent(long studentId, Instant now) {
        return db.query("""
                select x.* from attendance_session x
                  join enrollment e on e.class_id = x.class_id and e.student_id = ?
                 where x.status = 'ACTIVE' and x.ends_at > ?
                 order by x.started_at desc
                """, SESSION_MAPPER, studentId, Timestamp.from(now));
    }

    public List<SessionRow> recentSessionsForFaculty(long facultyId, int limit) {
        return db.query("select * from attendance_session where faculty_id = ? order by started_at desc limit ?",
                SESSION_MAPPER, facultyId, limit);
    }

    /** Inserts or updates the attendance record for a student in a session. */
    public void upsertRecord(long sessionId, long studentId, AttendanceStatus status, Instant recordedAt,
                             Double matchScore, Double livenessScore, RecordMethod method, String source, String note) {
        int updated = db.update("""
                update attendance_record
                   set status = ?, recorded_at = ?, match_score = ?, liveness_score = ?,
                       method = ?, source = ?, note = ?
                 where session_id = ? and student_id = ?
                """, status.name(), Timestamp.from(recordedAt), matchScore, livenessScore,
                method.name(), source, note, sessionId, studentId);
        if (updated == 0) {
            db.update("""
                    insert into attendance_record(session_id, student_id, status, recorded_at,
                                                  match_score, liveness_score, method, source, note)
                    values(?,?,?,?,?,?,?,?,?)
                    """, sessionId, studentId, status.name(), Timestamp.from(recordedAt),
                    matchScore, livenessScore, method.name(), source, note);
        }
    }

    public Optional<RecordRow> findRecord(long sessionId, long studentId) {
        List<RecordRow> rows = db.query("select * from attendance_record where session_id = ? and student_id = ?",
                RECORD_MAPPER, sessionId, studentId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean recordExists(long sessionId, long studentId) {
        Integer count = db.queryForObject(
                "select count(*) from attendance_record where session_id = ? and student_id = ?",
                Integer.class, sessionId, studentId);
        return count != null && count > 0;
    }

    /** Live roster for a session, including students who have not checked in yet. */
    public List<RosterRow> rosterForSession(long sessionId, long classId) {
        return db.query("""
                select u.id as student_id, u.full_name as student_name, u.student_number,
                       coalesce(r.status, 'PENDING') as status, r.recorded_at,
                       r.match_score, r.method,
                       (select count(*) from face_template f where f.student_id = u.id) > 0 as face_verified
                  from enrollment e
                  join app_user u on u.id = e.student_id
                  left join attendance_record r on r.student_id = u.id and r.session_id = ?
                 where e.class_id = ?
                 order by u.full_name
                """, (rs, rowNum) -> new RosterRow(
                rs.getLong("student_id"),
                rs.getString("student_name"),
                rs.getString("student_number"),
                rs.getString("status"),
                rs.getTimestamp("recorded_at") == null ? null : rs.getTimestamp("recorded_at").toInstant(),
                (Double) rs.getObject("match_score"),
                rs.getString("method"),
                rs.getBoolean("face_verified")), sessionId, classId);
    }

    public long presentCount(long sessionId) {
        Long count = db.queryForObject(
                "select count(*) from attendance_record where session_id = ? and status = 'PRESENT'",
                Long.class, sessionId);
        return count == null ? 0L : count;
    }

    /**
     * The number of present students for several sessions at once.
     *
     * <p>The list of sessions is shown on a dashboard, and asking the database for every
     * session on its own costs a round trip each time. One grouped query answers all of
     * them at once.
     */
    public java.util.Map<Long, Long> presentCounts(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return java.util.Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(sessionIds.size(), "?"));
        java.util.Map<Long, Long> counts = new java.util.LinkedHashMap<>();
        db.query("select session_id, count(*) from attendance_record "
                        + "where session_id in (" + placeholders + ") and status = 'PRESENT' group by session_id",
                rs -> {
                    counts.put(rs.getLong(1), rs.getLong(2));
                },
                sessionIds.toArray());
        return counts;
    }

    public void insertAttempt(long sessionId, long studentId, String outcome, String detail) {
        db.update("insert into verification_attempt(session_id, student_id, outcome, detail, attempted_at) "
                + "values(?,?,?,?,current_timestamp)", sessionId, studentId, outcome, detail);
    }

    public List<AttemptRow> recentAttempts(int limit) {
        return db.query("select * from verification_attempt order by attempted_at desc limit ?",
                (rs, rowNum) -> new AttemptRow(rs.getLong("id"), rs.getLong("session_id"), rs.getLong("student_id"),
                        rs.getString("outcome"), rs.getString("detail"), rs.getTimestamp("attempted_at").toInstant()),
                limit);
    }

    /** Attendance history for one student, most recent first. */
    public List<RecordRow> historyForStudent(long studentId, int limit) {
        return db.query("""
                select r.* from attendance_record r
                  join attendance_session x on x.id = r.session_id
                 where r.student_id = ?
                 order by r.recorded_at desc
                 limit ?
                """, RECORD_MAPPER, studentId, limit);
    }

    /** Attendance history for every student of a faculty member, most recent first. */
    public List<RecordRow> historyForFaculty(long facultyId, int limit) {
        return db.query("""
                select r.* from attendance_record r
                  join attendance_session x on x.id = r.session_id
                 where x.faculty_id = ?
                 order by r.recorded_at desc
                 limit ?
                """, RECORD_MAPPER, facultyId, limit);
    }

    /** Counts sessions and present records for one student in one subject. */
    public SubjectAttendance subjectAttendanceForStudent(long studentId) {
        return db.queryForObject("""
                select count(distinct x.id) as sessions, count(r.id) as attended
                  from enrollment e
                  join class_section c on c.id = e.class_id
                  join subject s on s.id = c.subject_id
                  left join attendance_session x on x.class_id = c.id
                  left join attendance_record r on r.session_id = x.id and r.student_id = e.student_id
                 where e.student_id = ?
                 group by s.id, s.code, s.name
                """, (rs, rowNum) -> new SubjectAttendance(rs.getString("code"), rs.getString("name"),
                rs.getLong("sessions"), rs.getLong("attended")), studentId);
    }

    /** Aggregate attendance figures for one subject of one student. */
    public record SubjectAttendance(String subjectCode, String subjectName, long sessions, long attended) {
        public double percentage() {
            return sessions == 0 ? 0d : (attended * 100d) / sessions;
        }
    }

    /** Per subject attendance totals for a student. */
    public List<SubjectAttendance> subjectAttendanceForStudent(long studentId, int limit) {
        return db.query("""
                select s.code as subject_code, s.name as subject_name,
                       count(distinct x.id) as sessions, count(r.id) as attended
                  from enrollment e
                  join class_section c on c.id = e.class_id
                  join subject s on s.id = c.subject_id
                  left join attendance_session x on x.class_id = c.id
                  left join attendance_record r on r.session_id = x.id and r.student_id = e.student_id
                 where e.student_id = ?
                 group by s.id, s.code, s.name
                 order by s.code
                 limit ?
                """, (rs, rowNum) -> new SubjectAttendance(rs.getString("subject_code"), rs.getString("subject_name"),
                rs.getLong("sessions"), rs.getLong("attended")), studentId, limit);
    }

    /** Per subject attendance totals for a faculty member. */
    public List<SubjectAttendance> subjectAttendanceForFaculty(long facultyId, int limit) {
        return db.query("""
                select s.code as subject_code, s.name as subject_name,
                       count(distinct x.id) as sessions, count(r.id) as attended
                  from attendance_session x
                  join class_section c on c.id = x.class_id
                  join subject s on s.id = c.subject_id
                  left join attendance_record r on r.session_id = x.id
                 where x.faculty_id = ?
                 group by s.id, s.code, s.name
                 order by s.code
                 limit ?
                """, (rs, rowNum) -> new SubjectAttendance(rs.getString("subject_code"), rs.getString("subject_name"),
                rs.getLong("sessions"), rs.getLong("attended")), facultyId, limit);
    }

    /** Students whose attendance in a subject falls below the threshold. */
    public List<LowAttendance> lowAttendanceForFaculty(long facultyId, double thresholdPercent) {
        return db.query("""
                select u.id as student_id, u.full_name as student_name, u.student_number,
                       s.code as subject_code, s.name as subject_name,
                       count(distinct x.id) as sessions, count(r.id) as attended,
                       case when count(distinct x.id) = 0 then 0
                            else 100.0 * count(r.id) / count(distinct x.id) end as percentage
                  from enrollment e
                  join app_user u on u.id = e.student_id
                  join class_section c on c.id = e.class_id
                  join subject s on s.id = c.subject_id
                  left join attendance_session x on x.class_id = c.id
                  left join attendance_record r on r.session_id = x.id and r.student_id = u.id
                 where s.faculty_id = ?
                 group by u.id, u.full_name, u.student_number, s.id, s.code, s.name
                having count(distinct x.id) > 0
                   and 100.0 * count(r.id) / count(distinct x.id) < ?
                 order by percentage asc, u.full_name
                """, (rs, rowNum) -> new LowAttendance(rs.getLong("student_id"), rs.getString("student_name"),
                rs.getString("student_number"), rs.getString("subject_code"), rs.getString("subject_name"),
                rs.getLong("sessions"), rs.getLong("attended"), rs.getDouble("percentage")),
                facultyId, thresholdPercent);
    }

    /** A low attendance finding for one student in one subject. */
    public record LowAttendance(long studentId, String studentName, String studentNumber, String subjectCode,
                                 String subjectName, long sessions, long attended, double percentage) {
    }
}
