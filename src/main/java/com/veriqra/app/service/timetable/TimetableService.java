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

    public TimetableService(AcademicRepository academic, AuditRepository audit, AppProperties properties) {
        this.academic = academic;
        this.audit = audit;
        this.properties = properties;
    }

    /** The first hour of the teaching day. */
    private LocalTime dayStarts() {
        return parseTime(properties.getTeachingDay().getStarts(), "teaching day start");
    }

    /** The last hour of the teaching day. */
    private LocalTime dayEnds() {
        return parseTime(properties.getTeachingDay().getEnds(), "teaching day end");
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
