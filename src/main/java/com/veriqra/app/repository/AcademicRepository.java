package com.veriqra.app.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Time;
import java.time.LocalTime;
import java.util.List;

/**
 * Data access for the academic structure: subjects, class sections, enrolments and timetable.
 */
@Repository
public class AcademicRepository {

    /** A subject that a teacher owns. */
    public record SubjectView(long id, String code, String name, Long facultyId, String facultyName) {
    }

    /** A class section that attendance is taken for. */
    public record ClassView(long id, long subjectId, String subjectCode, String subjectName,
                            String section, String room, Long facultyId, String facultyName, long enrolled) {
    }

    /** A timetable slot. */
    public record TimetableSlot(long id, long classId, String section, String subjectCode, String subjectName,
                                 String room, int dayOfWeek, LocalTime starts, LocalTime ends,
                                 Long facultyId, String facultyName) {
    }

    /** A student enrolled in a class section. */
    public record Enrolment(long studentId, String studentName, String studentNumber,
                             long classId, String section, String subjectCode, String subjectName) {
    }

    private final JdbcTemplate db;

    public AcademicRepository(JdbcTemplate db) {
        this.db = db;
    }

    private static final RowMapper<ClassView> CLASS_MAPPER = (rs, rowNum) -> new ClassView(
            rs.getLong("id"),
            rs.getLong("subject_id"),
            rs.getString("subject_code"),
            rs.getString("subject_name"),
            rs.getString("section"),
            rs.getString("room"),
            rs.getObject("faculty_id", Long.class),
            rs.getString("faculty_name"),
            rs.getLong("enrolled"));

    private static final RowMapper<TimetableSlot> TIMETABLE_MAPPER = (rs, rowNum) -> new TimetableSlot(
            rs.getLong("id"),
            rs.getLong("class_id"),
            rs.getString("section"),
            rs.getString("subject_code"),
            rs.getString("subject_name"),
            rs.getString("room"),
            rs.getInt("day_of_week"),
            rs.getTime("starts") == null ? LocalTime.MIDNIGHT : rs.getTime("starts").toLocalTime(),
            rs.getTime("ends") == null ? LocalTime.MIDNIGHT : rs.getTime("ends").toLocalTime(),
            rs.getObject("faculty_id", Long.class),
            rs.getString("faculty_name"));

    public List<SubjectView> listAllSubjects() {
        return db.query("""
                select s.id, s.code, s.name, s.faculty_id, u.full_name as faculty_name
                  from subject s
                  left join app_user u on u.id = s.faculty_id
                 order by s.code
                """, (rs, rowNum) -> new SubjectView(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getObject("faculty_id", Long.class), rs.getString("faculty_name")));
    }

    /** Lists class sections together with enrolment counts. */
    public List<ClassView> listAllClasses() {
        return db.query("""
                select c.id, c.subject_id, s.code as subject_code, s.name as subject_name,
                       c.name as section, c.room, s.faculty_id, u.full_name as faculty_name,
                       (select count(*) from enrollment e where e.class_id = c.id) as enrolled
                  from class_section c
                  join subject s on s.id = c.subject_id
                  left join app_user u on u.id = s.faculty_id
                 order by s.code, c.name
                """, CLASS_MAPPER);
    }

    /** Lists the class sections a faculty member is assigned to teach. */
    public List<ClassView> listClassesForFaculty(long facultyId) {
        return db.query("""
                select c.id, c.subject_id, s.code as subject_code, s.name as subject_name,
                       c.name as section, c.room, s.faculty_id, u.full_name as faculty_name,
                       (select count(*) from enrollment e where e.class_id = c.id) as enrolled
                  from class_section c
                  join subject s on s.id = c.subject_id
                  left join app_user u on u.id = s.faculty_id
                 where s.faculty_id = ?
                 order by s.code, c.name
                """, CLASS_MAPPER, facultyId);
    }

    public boolean facultyTeaches(long facultyId, long classId) {
        Integer count = db.queryForObject("""
                select count(*) from class_section c
                  join subject s on s.id = c.subject_id
                 where c.id = ? and s.faculty_id = ?
                """, Integer.class, classId, facultyId);
        return count != null && count > 0;
    }

    /** Reads a single class section. */
    public java.util.Optional<ClassView> findClass(long classId) {
        return db.query("""
                select c.id, c.subject_id, s.code as subject_code, s.name as subject_name,
                       c.name as section, c.room, s.faculty_id, u.full_name as faculty_name,
                       (select count(*) from enrollment e where e.class_id = c.id) as enrolled
                  from class_section c
                  join subject s on s.id = c.subject_id
                  left join app_user u on u.id = s.faculty_id
                 where c.id = ?
                """, CLASS_MAPPER, classId).stream().findFirst();
    }

    public long createClass(long subjectId, String section, String room) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement(
                    "insert into class_section(subject_id, name, room) values(?,?,?)", new String[]{"id"});
            statement.setLong(1, subjectId);
            statement.setString(2, section);
            statement.setString(3, room);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public long createSubject(String code, String name, Long facultyId) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement(
                    "insert into subject(code, name, faculty_id) values(?,?,?)", new String[]{"id"});
            statement.setString(1, code);
            statement.setString(2, name);
            if (facultyId == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, facultyId);
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    /** Timetable slots for a student, ordered by weekday then start time. */
    public List<TimetableSlot> timetableForStudent(long studentId) {
        return db.query("""
                select t.id, t.class_id, c.name as section, s.code as subject_code, s.name as subject_name,
                       c.room, t.day_of_week, t.starts, t.ends, t.faculty_id, u.full_name as faculty_name
                  from timetable t
                  join class_section c on c.id = t.class_id
                  join subject s on s.id = c.subject_id
                  join enrollment e on e.class_id = c.id and e.student_id = ?
                  left join app_user u on u.id = t.faculty_id
                 order by t.day_of_week, t.starts
                """, TIMETABLE_MAPPER, studentId);
    }

    /** Timetable slots for a faculty member. */
    public List<TimetableSlot> timetableForFaculty(long facultyId) {
        return db.query("""
                select t.id, t.class_id, c.name as section, s.code as subject_code, s.name as subject_name,
                       c.room, t.day_of_week, t.starts, t.ends, t.faculty_id, u.full_name as faculty_name
                  from timetable t
                  join class_section c on c.id = t.class_id
                  join subject s on s.id = c.subject_id
                  left join app_user u on u.id = t.faculty_id
                 where t.faculty_id = ?
                 order by t.day_of_week, t.starts
                """, TIMETABLE_MAPPER, facultyId);
    }

    public long createTimetableSlot(long classId, long facultyId, int dayOfWeek, LocalTime starts, LocalTime ends) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement(
                    "insert into timetable(class_id, faculty_id, day_of_week, starts, ends) values(?,?,?,?,?)",
                    new String[]{"id"});
            statement.setLong(1, classId);
            statement.setLong(2, facultyId);
            statement.setInt(3, dayOfWeek);
            statement.setTime(4, Time.valueOf(starts));
            statement.setTime(5, Time.valueOf(ends));
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    /** Reads a single timetable slot. */
    public java.util.Optional<TimetableSlot> findTimetableSlot(long id) {
        return db.query("""
                select t.id, t.class_id, c.name as section, s.code as subject_code, s.name as subject_name,
                       c.room, t.day_of_week, t.starts, t.ends, t.faculty_id, u.full_name as faculty_name
                  from timetable t
                  join class_section c on c.id = t.class_id
                  join subject s on s.id = c.subject_id
                  left join app_user u on u.id = t.faculty_id
                 where t.id = ?
                """, TIMETABLE_MAPPER, id).stream().findFirst();
    }

    /** Moves or reschedules an existing slot. */
    public void updateTimetableSlot(long id, long classId, int dayOfWeek, LocalTime starts, LocalTime ends) {
        db.update("update timetable set class_id = ?, day_of_week = ?, starts = ?, ends = ? where id = ?",
                classId, dayOfWeek, Time.valueOf(starts), Time.valueOf(ends), id);
    }

    /** Removes a slot. */
    public void deleteTimetableSlot(long id) {
        db.update("delete from timetable where id = ?", id);
    }

    /**
     * Finds the slots that overlap the given window.
     *
     * <p>Two windows overlap when they start before the other one ends. The comparison
     * runs in the database so the check sees exactly what the constraint would see.
     *
     * @param facultyId the teacher whose diary is checked
     * @param classId   the class whose diary is checked
     * @param excludeId a slot that is being moved and must not collide with itself
     */
    public List<TimetableSlot> findOverlappingSlots(long facultyId, long classId, int dayOfWeek,
                                                    LocalTime starts, LocalTime ends, Long excludeId) {
        return db.query("""
                select t.id, t.class_id, c.name as section, s.code as subject_code, s.name as subject_name,
                       c.room, t.day_of_week, t.starts, t.ends, t.faculty_id, u.full_name as faculty_name
                  from timetable t
                  join class_section c on c.id = t.class_id
                  join subject s on s.id = c.subject_id
                  left join app_user u on u.id = t.faculty_id
                 where t.day_of_week = ?
                   and (t.faculty_id = ? or t.class_id = ?)
                   and t.starts < ? and t.ends > ?
                   and (? is null or t.id <> ?)
                """, TIMETABLE_MAPPER, dayOfWeek, facultyId, classId,
                Time.valueOf(ends), Time.valueOf(starts), excludeId, excludeId);
    }

    /** Enrolled students for a class section. */
    public List<Enrolment> enrolmentsForClass(long classId) {
        return db.query("""
                select e.student_id, u.full_name as student_name, u.student_number,
                       e.class_id, c.name as section, s.code as subject_code, s.name as subject_name
                  from enrollment e
                  join app_user u on u.id = e.student_id
                  join class_section c on c.id = e.class_id
                  join subject s on s.id = c.subject_id
                 where e.class_id = ?
                 order by u.full_name
                """, (rs, rowNum) -> new Enrolment(rs.getLong("student_id"), rs.getString("student_name"),
                rs.getString("student_number"), rs.getLong("class_id"), rs.getString("section"),
                rs.getString("subject_code"), rs.getString("subject_name")), classId);
    }




    public boolean isEnrolled(long studentId, long classId) {        Integer count = db.queryForObject("select count(*) from enrollment where student_id = ? and class_id = ?",
                Integer.class, studentId, classId);
        return count != null && count > 0;
    }

    /** Removes a class section with the lectures and the enrolments that belong to it. */
    public void deleteClass(long classId) {
        db.update("delete from timetable where class_id = ?", classId);
        db.update("delete from enrollment where class_id = ?", classId);
        db.update("delete from class_section where id = ?", classId);
    }

    /** Removes a subject. */
    public void deleteSubject(long subjectId) {
        db.update("delete from subject where id = ?", subjectId);
    }

    /** Enrols a student in a class section. */
    public void enrol(long studentId, long classId) {
        db.update("insert into enrollment(student_id, class_id) values(?,?)", studentId, classId);
    }

    public void unenrol(long studentId, long classId) {
        db.update("delete from enrollment where student_id = ? and class_id = ?", studentId, classId);
    }

    /** Number of students enrolled in a class section. */
    public long enrolmentCount(long classId) {
        Long count = db.queryForObject("select count(*) from enrollment where class_id = ?", Long.class, classId);
        return count == null ? 0L : count;
    }



}
