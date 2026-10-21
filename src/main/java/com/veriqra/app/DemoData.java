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

/**
 * Creates the demonstration accounts and the class they teach.
 *
 * <p>There is no administrator. The demonstration is one teacher, one subject, one class
 * section and the student who attends it, so the application can be tried out on a fresh
 * installation. Everything can be removed or changed from the teacher's own screens.
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

    private static final String DEMO_PASSWORD = "Demo!2026";

    private static final DemoAccount[] DEMO_ACCOUNTS = {
            new DemoAccount("faculty@veriqra.local", "Prof. Anita Desai", Role.FACULTY, null),
            new DemoAccount("student@veriqra.local", "Aarav Sharma", Role.STUDENT, "VQ24001"),
            new DemoAccount("student2@veriqra.local", "Diya Patil", Role.STUDENT, "VQ24002")
    };

    /** The subject the demonstration teacher owns. */
    private static final String DEMO_SUBJECT_CODE = "ML";
    private static final String DEMO_SUBJECT_NAME = "Machine Learning";
    private static final String DEMO_SECTION = "A";
    private static final String DEMO_ROOM = "Innovation Lab 204";
    private static final int DEMO_DAY = 1;
    private static final String DEMO_STARTS = "10:00";
    private static final String DEMO_ENDS = "11:00";

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
            seedTheClass();
        };
    }

    /**
     * Creates the subject, the class section, the enrolments and the weekly lecture.
     */
    private void seedTheClass() {
        var teacher = users.findByEmail("faculty@veriqra.local");
        if (teacher.isEmpty()) {
            return;
        }
        long facultyId = teacher.get().id();

        long subjectId = academic.listSubjectsForFaculty(facultyId).stream()
                .filter(subject -> DEMO_SUBJECT_CODE.equals(subject.code()))
                .findFirst()
                .map(AcademicRepository.SubjectView::id)
                .orElseGet(() -> academic.createSubject(DEMO_SUBJECT_CODE, DEMO_SUBJECT_NAME, facultyId));

        long classId = academic.listAllClasses().stream()
                .filter(view -> view.subjectId() == subjectId && DEMO_SECTION.equals(view.section()))
                .findFirst()
                .map(AcademicRepository.ClassView::id)
                .orElseGet(() -> academic.createClass(subjectId, DEMO_SECTION, DEMO_ROOM));

        for (String email : new String[]{"student@veriqra.local", "student2@veriqra.local"}) {
            users.findByEmail(email).ifPresent(student -> {
                if (!academic.isEnrolled(student.id(), classId)) {
                    academic.enrol(student.id(), classId);
                }
            });
        }

        boolean hasLecture = academic.timetableForFaculty(facultyId).stream()
                .anyMatch(slot -> slot.classId() == classId
                        && slot.dayOfWeek() == DEMO_DAY
                        && slot.starts().equals(LocalTime.parse(DEMO_STARTS)));
        if (!hasLecture) {
            academic.createTimetableSlot(classId, facultyId, DEMO_DAY,
                    LocalTime.parse(DEMO_STARTS), LocalTime.parse(DEMO_ENDS));
        }

        log.info("The demonstration class {} {} is ready.", DEMO_SUBJECT_CODE, DEMO_SECTION);
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
