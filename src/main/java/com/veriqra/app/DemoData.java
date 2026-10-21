package com.veriqra.app;

import com.veriqra.app.repository.AcademicRepository;
import com.veriqra.app.repository.AttendanceRepository;
import com.veriqra.app.repository.UserRepository;
import com.veriqra.app.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalTime;
import java.util.List;

/**
 * Creates the demonstration accounts, the subjects and the classes they teach.
 *
 * <p>There is no administrator. The demonstration is a staff of five teachers, each of
 * whom owns one subject with its class section, the students who attend it and the
 * weekly lecture, so the application can be tried out on a fresh installation.
 * Everything can be removed or changed from the teacher's own screens.
 *
 * <p>The accounts are the only seeded data and they can be switched off with
 * {@code DEMO_DATA_ENABLED=false}. They are never created in the {@code prod} profile.
 */
@Configuration
@Profile("!prod")
@ConditionalOnProperty(name = "veriqra.demo-data.enabled", havingValue = "true", matchIfMissing = true)
public class DemoData {

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    /** The accounts that are created, in the order they are listed. */
    private record DemoAccount(String email, String fullName, Role role, String studentNumber) {
    }

    /** A teacher of the demonstration staff. */
    private record DemoTeacher(String email, String fullName) {
    }

    /**
     * A subject of the demonstration, the teacher who owns it, the class section that
     * runs it, the students who attend it and the weekly lecture.
     */
    private record DemoSubject(
            String code,
            String name,
            String teacherEmail,
            String section,
            String room,
            int day,
            String starts,
            String ends,
            List<String> students) {
    }

    private static final String DEMO_PASSWORD = "Demo!2026";

    /** The subject the earlier version of the demonstration used. */
    private static final String LEGACY_SUBJECT_CODE = "ML";

    private static final DemoAccount[] DEMO_ACCOUNTS = {
            new DemoAccount("faculty@veriqra.local", "Prof. Anita Desai", Role.FACULTY, null),
            new DemoAccount("faculty2@veriqra.local", "Prof. Anand Iyer", Role.FACULTY, null),
            new DemoAccount("faculty3@veriqra.local", "Prof. Priya Nair", Role.FACULTY, null),
            new DemoAccount("faculty4@veriqra.local", "Prof. Rahul Menon", Role.FACULTY, null),
            new DemoAccount("faculty5@veriqra.local", "Prof. Kavita Rao", Role.FACULTY, null),
            new DemoAccount("student@veriqra.local", "Aarav Sharma", Role.STUDENT, "VQ24001"),
            new DemoAccount("student2@veriqra.local", "Diya Patil", Role.STUDENT, "VQ24002"),
            new DemoAccount("student3@veriqra.local", "Kabir Mehta", Role.STUDENT, "VQ24003"),
            new DemoAccount("student4@veriqra.local", "Ananya Rao", Role.STUDENT, "VQ24004")
    };

    /** The staff of the demonstration. */
    private static final DemoTeacher[] DEMO_TEACHERS = {
            new DemoTeacher("faculty@veriqra.local", "Prof. Anita Desai"),
            new DemoTeacher("faculty2@veriqra.local", "Prof. Anand Iyer"),
            new DemoTeacher("faculty3@veriqra.local", "Prof. Priya Nair"),
            new DemoTeacher("faculty4@veriqra.local", "Prof. Rahul Menon"),
            new DemoTeacher("faculty5@veriqra.local", "Prof. Kavita Rao")
    };

    /** The subjects of the demonstration. Each of them has a teacher of its own. */
    private static final DemoSubject[] DEMO_SUBJECTS = {
            new DemoSubject("AIML", "Artificial Intelligence & Machine Learning",
                    "faculty@veriqra.local", "A", "Innovation Lab 204", 1, "10:00", "11:00",
                    List.of("student@veriqra.local", "student2@veriqra.local")),
            new DemoSubject("AIDS", "Artificial Intelligence & Data Structures",
                    "faculty2@veriqra.local", "A", "Lecture Hall 2", 2, "11:00", "12:00",
                    List.of("student@veriqra.local", "student3@veriqra.local")),
            new DemoSubject("DBMS", "Database Management Systems",
                    "faculty3@veriqra.local", "A", "Systems Lab 3", 3, "10:00", "11:00",
                    List.of("student2@veriqra.local", "student3@veriqra.local", "student4@veriqra.local")),
            new DemoSubject("CN", "Computer Networks",
                    "faculty4@veriqra.local", "A", "Lecture Hall 1", 4, "12:00", "13:00",
                    List.of("student@veriqra.local", "student4@veriqra.local")),
            new DemoSubject("DSA", "Data Structures & Algorithms",
                    "faculty5@veriqra.local", "A", "Algorithms Lab 1", 5, "09:00", "10:00",
                    List.of("student3@veriqra.local", "student4@veriqra.local"))
    };

    private final UserRepository users;
    private final AcademicRepository academic;
    private final AttendanceRepository attendance;
    private final PasswordEncoder passwordEncoder;

    public DemoData(UserRepository users,
                    AcademicRepository academic,
                    AttendanceRepository attendance,
                    PasswordEncoder passwordEncoder) {
        this.users = users;
        this.academic = academic;
        this.attendance = attendance;
        this.passwordEncoder = passwordEncoder;
    }

    @Bean
    public ApplicationRunner seedDemoAccounts() {
        return (ApplicationArguments args) -> {
            // The subject of the earlier demonstration is cleared away first, so the
            // lectures of the new demonstration do not sit on top of the old ones.
            removeTheLegacySubject();
            for (DemoAccount account : DEMO_ACCOUNTS) {
                var existing = users.findByEmail(account.email());
                if (existing.isPresent()) {
                    // The demonstration password is published on the sign in screen, so it
                    // is kept in step with the account.
                    users.updatePassword(existing.get().id(), passwordEncoder.encode(DEMO_PASSWORD));
                    users.updateProfile(existing.get().id(), account.fullName(), account.studentNumber());
                    continue;
                }
                users.create(account.email(), passwordEncoder.encode(DEMO_PASSWORD),
                        account.fullName(), account.role(), account.studentNumber());
            }
            for (DemoSubject subject : DEMO_SUBJECTS) {
                seedTheClass(subject);
            }
        };
    }

    /**
     * Removes the subject of the first demonstration, which the earlier version of the
     * demonstration used.
     *
     * <p>The subject is only removed while nothing depends on it, so attendance that was
     * already taken with it is never lost.
     */
    private void removeTheLegacySubject() {
        var teacher = users.findByEmail("faculty@veriqra.local");
        if (teacher.isEmpty()) {
            return;
        }
        long facultyId = teacher.get().id();
        long subjectId = academic.listSubjectsForFaculty(facultyId).stream()
                .filter(view -> LEGACY_SUBJECT_CODE.equals(view.code()))
                .findFirst()
                .map(AcademicRepository.SubjectView::id)
                .orElse(-1L);
        if (subjectId < 0) {
            return;
        }
        for (AcademicRepository.ClassView classView : academic.listAllClasses()) {
            if (classView.subjectId() != subjectId) {
                continue;
            }
            if (attendance.recordsForClass(classView.id()) > 0) {
                // The subject has been used, so it stays and the record remains.
                return;
            }
            for (AcademicRepository.Enrolment enrolment : academic.enrolmentsForClass(classView.id())) {
                academic.unenrol(enrolment.studentId(), classView.id());
            }
            academic.timetableForFaculty(facultyId).stream()
                    .filter(slot -> slot.classId() == classView.id())
                    .forEach(slot -> academic.deleteTimetableSlot(slot.id()));
            attendance.sessionsForClass(classView.id())
                    .forEach(session -> attendance.deleteSession(session.id()));
            academic.deleteClass(classView.id());
        }
        academic.deleteSubject(subjectId);
        log.info("The subject {} of the earlier demonstration has been removed.", LEGACY_SUBJECT_CODE);
    }

    /**
     * Creates the subject, the class section, the enrolments and the weekly lecture of
     * one subject of the demonstration.
     */
    private void seedTheClass(DemoSubject subject) {
        var teacher = users.findByEmail(subject.teacherEmail());
        if (teacher.isEmpty()) {
            return;
        }
        long facultyId = teacher.get().id();

        long subjectId = academic.listSubjectsForFaculty(facultyId).stream()
                .filter(view -> subject.code().equals(view.code()))
                .findFirst()
                .map(AcademicRepository.SubjectView::id)
                .orElseGet(() -> academic.createSubject(subject.code(), subject.name(), facultyId));

        long classId = academic.listAllClasses().stream()
                .filter(view -> view.subjectId() == subjectId && subject.section().equals(view.section()))
                .findFirst()
                .map(AcademicRepository.ClassView::id)
                .orElseGet(() -> academic.createClass(subjectId, subject.section(), subject.room()));

        for (String email : subject.students()) {
            users.findByEmail(email).ifPresent(student -> {
                if (!academic.isEnrolled(student.id(), classId)) {
                    academic.enrol(student.id(), classId);
                }
            });
        }

        LocalTime starts = LocalTime.parse(subject.starts());
        boolean hasLecture = academic.timetableForFaculty(facultyId).stream()
                .anyMatch(slot -> slot.classId() == classId
                        && slot.dayOfWeek() == subject.day()
                        && slot.starts().equals(starts));
        if (!hasLecture) {
            academic.createTimetableSlot(classId, facultyId, subject.day(),
                    starts, LocalTime.parse(subject.ends()));
        }

        log.info("The demonstration class {} {} of {} is ready.",
                subject.code(), subject.section(), subject.teacherEmail());
    }

    /**
     * The demonstration accounts, for the sign in screen to show.
     *
     * @return one line per account
     */
    public java.util.List<String> accountHints() {
        java.util.List<String> hints = new java.util.ArrayList<>();
        for (DemoAccount account : DEMO_ACCOUNTS) {
            hints.add(account.email() + " / " + DEMO_PASSWORD);
        }
        return hints;
    }
}
