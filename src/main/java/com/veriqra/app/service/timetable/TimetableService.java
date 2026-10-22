package com.veriqra.app.service.timetable;

import com.veriqra.app.config.AppProperties;
import com.veriqra.app.domain.dto.AttendanceDtos.TimetableView;
import com.veriqra.app.repository.AcademicRepository;
import com.veriqra.app.repository.AuditRepository;
import com.veriqra.app.security.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * The teaching diary of a faculty member.
 *
 * <p>A teacher may only see and change their own slots, and only for the subjects they
 * are assigned to. Every change is checked against the teacher's own diary and against
 * the diary of the class, so a room or a class can never be double booked.
 */
@Service
public class TimetableService {

    private static final Logger log = LoggerFactory.getLogger(TimetableService.class);

    private final AcademicRepository academic;
    private final AuditRepository audit;
    private final AppProperties properties;
    private final com.veriqra.app.repository.AttendanceRepository attendance;

    public TimetableService(AcademicRepository academic, AuditRepository audit, AppProperties properties,
                             com.veriqra.app.repository.AttendanceRepository attendance) {
        this.academic = academic;
        this.audit = audit;
        this.properties = properties;
        this.attendance = attendance;
    }

    /** The first hour of the teaching day. */
    private LocalTime dayStarts() {
        return parseTime(properties.getTeachingDay().getStarts(), "teaching day start");
    }

    /** The last hour of the teaching day. */
    private LocalTime dayEnds() {
        return parseTime(properties.getTeachingDay().getEnds(), "teaching day end");
    }

    /**
     * The lectures of the teacher on a weekday, with a note of whether attendance may
     * be taken for them right now.
     */
    public List<ScheduleEntry> schedule(long facultyId, int dayOfWeek) {
        return academic.timetableForFacultyOnDay(facultyId, dayOfWeek).stream()
                .map(slot -> new ScheduleEntry(
                        slot.id(),
                        slot.classId(),
                        slot.subjectCode(),
                        slot.subjectName(),
                        slot.section(),
                        slot.room(),
                        slot.dayOfWeek(),
                        slot.starts().toString(),
                        slot.ends().toString(),
                        canTakeAttendanceNow(slot),
                        reasonIfNot(slot)))
                .toList();
    }

    /**
     * The lectures of the teacher today.
     */
    public List<ScheduleEntry> scheduleToday(long facultyId) {
        int today = java.time.LocalDate.now().getDayOfWeek().getValue();
        return schedule(facultyId, today);
    }

    /**
     * A lecture of the teacher, when attendance may be taken for it right now.
     *
     * @throws ApiException when the lecture does not exist, is not the teacher's, or is
     *                      outside the window in which attendance may be taken
     */
    public AcademicRepository.TimetableSlot requireOpenSlot(long facultyId, long slotId) {
        AcademicRepository.TimetableSlot slot = academic.findTimetableSlot(slotId)
                .orElseThrow(() -> ApiException.notFound("SLOT_NOT_FOUND", "That lecture is not on your timetable."));
        if (slot.facultyId() == null || slot.facultyId() != facultyId) {
            throw ApiException.forbidden("NOT_OWNER", "That lecture is on the timetable of another teacher.");
        }
        if (!canTakeAttendanceNow(slot)) {
            throw ApiException.conflict("SLOT_NOT_OPEN", reasonIfNot(slot));
        }
        return slot;
    }

    /**
     * True when attendance may be taken for the lecture.
     */
    private boolean canTakeAttendanceNow(AcademicRepository.TimetableSlot slot) {
        return reasonIfNot(slot) == null;
    }

    /**
     * Why attendance may not be taken for the lecture, or null when it may.
     */
    private String reasonIfNot(AcademicRepository.TimetableSlot slot) {
        java.time.LocalDate today = java.time.LocalDate.now();
        if (slot.dayOfWeek() != today.getDayOfWeek().getValue()) {
            return "That lecture is on another day. Attendance is only taken on the day of the lecture.";
        }
        // The window is worked out on the full date, so a lecture that runs across
        // midnight is still measured correctly.
        java.time.LocalDateTime starts = today.atTime(slot.starts());
        java.time.LocalDateTime ends = today.atTime(slot.ends());
        if (ends.isBefore(starts)) {
            ends = ends.plusDays(1);
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime opens = starts.minusMinutes(properties.getAttendance().getOpenBeforeMinutes());
        java.time.LocalDateTime closes = ends.plusMinutes(properties.getAttendance().getOpenAfterMinutes());
        if (now.isBefore(opens)) {
            return "That lecture starts at " + slot.starts() + ". Attendance opens "
                    + properties.getAttendance().getOpenBeforeMinutes() + " minutes before it.";
        }
        if (now.isAfter(closes)) {
            return "That lecture ended at " + slot.ends() + ". The window for taking its attendance closed "
                    + properties.getAttendance().getOpenAfterMinutes() + " minutes later.";
        }
        return null;
    }

    /** One lecture of the teaching diary. */
    public record ScheduleEntry(
            long id,
            long classId,
            String subjectCode,
            String subjectName,
            String section,
            String room,
            int dayOfWeek,
            String starts,
            String ends,
            boolean open,
            String message) {
    }

    /** The slots of one teacher, ordered by weekday and start time. */
    public List<TimetableView> list(long facultyId) {
        return academic.timetableForFaculty(facultyId).stream()
                .map(slot -> new TimetableView(slot.id(), slot.classId(), slot.subjectCode(), slot.subjectName(), slot.section(),
                        slot.room(), slot.dayOfWeek(), slot.starts().toString(), slot.ends().toString(),
                        slot.facultyName()))
                .toList();
    }

    /** The slots of one teacher for the whole calendar, used by the dashboard. */
    public List<TimetableView> listFor(long facultyId) {
        return list(facultyId);
    }

    /**
     * Adds a lecture to the diary.
     *
     * @throws ApiException when the slot breaks a rule
     */
    @Transactional
    public TimetableView create(long facultyId, long classId, int dayOfWeek, String starts, String ends) {
        LocalTime start = parseTime(starts, "starts");
        LocalTime end = parseTime(ends, "ends");
        validate(facultyId, classId, dayOfWeek, start, end, null);

        long id = academic.createTimetableSlot(classId, facultyId, dayOfWeek, start, end);
        audit.record(facultyId, "TIMETABLE_SLOT_CREATED", "slot=" + id, "TEACHER_ACTION",
                "Lecture added to the teaching diary", null, describe(classId, dayOfWeek, start, end));
        log.info("Faculty {} added a timetable slot {}", facultyId, id);
        return requireSlot(facultyId, id);
    }
    /**
     * Moves or reschedules a lecture.
     */
    @Transactional
    public TimetableView update(long facultyId, long slotId, long classId, int dayOfWeek,
                                 String starts, String ends) {
        requireSlot(facultyId, slotId);
        LocalTime start = parseTime(starts, "starts");
        LocalTime end = parseTime(ends, "ends");
        validate(facultyId, classId, dayOfWeek, start, end, slotId);

        academic.updateTimetableSlot(slotId, classId, dayOfWeek, start, end);
        audit.record(facultyId, "TIMETABLE_SLOT_UPDATED", "slot=" + slotId, "TEACHER_ACTION",
                "Lecture rescheduled", null, describe(classId, dayOfWeek, start, end));
        return requireSlot(facultyId, slotId);
    }

    /**
     * Removes a lecture from the diary.
     */
    @Transactional
    public void delete(long facultyId, long slotId) {
        TimetableView slot = requireSlot(facultyId, slotId);
        // A lecture that was used for an attendance session is a record of what
        // happened and is not removed with a click.
        if (!attendance.sessionsForSlot(slotId).isEmpty()) {
            throw ApiException.conflict("SLOT_HAS_SESSIONS",
                    "This lecture has attendance sessions. Those are a record of what happened "
                            + "and are not removed with the lecture.");
        }
        academic.deleteTimetableSlot(slotId);
        audit.record(facultyId, "TIMETABLE_SLOT_DELETED", "slot=" + slotId, "TEACHER_ACTION",
                "Lecture removed from the teaching diary", slot.subjectCode(), null);
    }

    // ------------------------------------------------------------------ internals

    private TimetableView requireSlot(long facultyId, long slotId) {
        AcademicRepository.TimetableSlot slot = academic.findTimetableSlot(slotId)
                .orElseThrow(() -> ApiException.notFound("SLOT_NOT_FOUND", "That lecture does not exist."));
        if (slot.facultyId() == null || slot.facultyId() != facultyId) {
            throw ApiException.forbidden("NOT_OWNER",
                    "That lecture belongs to another teacher.");
        }
        return new TimetableView(slot.id(), slot.classId(), slot.subjectCode(), slot.subjectName(), slot.section(),
                slot.room(), slot.dayOfWeek(), slot.starts().toString(), slot.ends().toString(),
                slot.facultyName());
    }

    private void validate(long facultyId, long classId, int dayOfWeek, LocalTime start, LocalTime end,
                           Long excludeId) {
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw ApiException.badRequest("INVALID_DAY", "The weekday must be between 1 (Monday) and 7 (Sunday).");
        }
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("INVALID_TIME_RANGE", "The lecture has to end after it starts.");
        }
        if (start.isBefore(dayStarts()) || end.isAfter(dayEnds())) {
            throw ApiException.badRequest("OUTSIDE_TEACHING_HOURS",
                    "Lectures must sit inside the teaching day, between "
                            + dayStarts() + " and " + dayEnds() + ".");
        }
        if (!academic.facultyTeaches(facultyId, classId)) {
            throw ApiException.forbidden("NOT_ASSIGNED",
                    "You can only schedule lectures for the subjects you are assigned to.");
        }

        List<AcademicRepository.TimetableSlot> clashes = academic.findOverlappingSlots(
                facultyId, classId, dayOfWeek, start, end, excludeId);
        for (AcademicRepository.TimetableSlot clash : clashes) {
            boolean sameClass = clash.classId() == classId;
            String subject = clash.subjectCode() + " (" + clash.section() + ")";
            if (sameClass) {
                throw ApiException.conflict("CLASS_BUSY",
                        subject + " is already scheduled from " + clash.starts() + " to " + clash.ends()
                                + " on that day.");
            }
            throw ApiException.conflict("TEACHER_BUSY",
                    "You already teach " + subject + " from " + clash.starts() + " to " + clash.ends()
                            + " on that day.");
        }
    }

    private static LocalTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_TIME", "The " + field + " time is required.");
        }
        try {
            return LocalTime.parse(value.trim().toUpperCase(Locale.ROOT));
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest("INVALID_TIME",
                    "The " + field + " time must look like 09:00.");
        }
    }

    private static String describe(long classId, int dayOfWeek, LocalTime start, LocalTime end) {
        return "class=" + classId + ",day=" + dayOfWeek + ",from=" + start + ",to=" + end;
    }
}
