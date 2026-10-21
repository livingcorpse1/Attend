package com.veriqra.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The teaching diary of a faculty member.
 *
 * <p>The rules that matter are ownership and the absence of clashes: a teacher may only
 * schedule their own subjects, and a teacher or a class may never be in two places at the
 * same time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TimetableIntegrationTest {

    @Autowired
    MockMvc mvc;

    private MockHttpSession faculty;
    private MockHttpSession otherFaculty;
    private MockHttpSession admin;

    /** The class the first teacher owns. */
    private long ownClass;
    /** The class the second teacher owns. */
    private long otherClass;
    /** A lecture in the first teacher's diary. */
    private long ownSlot;
    /** A lecture in the second teacher's diary. */
    private long otherSlot;

    @BeforeEach
    void setUp() throws Exception {
        faculty = login("faculty@veriqra.local", "Demo!2026");
        otherFaculty = login("faculty2@veriqra.local", "Demo!2026");
        admin = login("admin@veriqra.local", "AdminDemo!26");

        long ownSubject = TestFixtures.createSubject(mvc, admin, "TS101", "Own Subject",
                TestFixtures.userId(mvc, admin, "faculty@veriqra.local"));
        long otherSubject = TestFixtures.createSubject(mvc, admin, "TS102", "Other Subject",
                TestFixtures.userId(mvc, admin, "faculty2@veriqra.local"));

        ownClass = TestFixtures.createClass(mvc, admin, ownSubject, "A", "Room 1");
        otherClass = TestFixtures.createClass(mvc, admin, otherSubject, "A", "Room 2");

        // One lecture in each diary.
        ownSlot = addLecture(faculty, ownClass, 1, "09:00", "10:00");
        otherSlot = addLecture(otherFaculty, otherClass, 4, "10:00", "11:00");
    }

    private long addLecture(MockHttpSession session, long classId, int dayOfWeek, String starts, String ends)
            throws Exception {
        MvcResult created = mvc.perform(post("/api/faculty/timetable").with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":" + classId + ",\"dayOfWeek\":" + dayOfWeek
                                + ",\"starts\":\"" + starts + "\",\"ends\":\"" + ends + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    void aTeacherSeesOnlyTheirOwnDiary() throws Exception {
        mvc.perform(get("/api/faculty/timetable").session(faculty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].facultyName").value("Dr. Maya Rao"));

        mvc.perform(get("/api/faculty/timetable").session(otherFaculty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].facultyName").value("Prof. Anand Iyer"));
    }

    @Test
    void aTeacherCanAddALectureToTheirOwnSubject() throws Exception {
        MvcResult created = mvc.perform(post("/api/faculty/timetable").with(csrf())
                        .session(faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":" + ownClass + ",\"dayOfWeek\":2,\"starts\":\"14:00\",\"ends\":\"15:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dayOfWeek").value(2))
                .andExpect(jsonPath("$.starts").value("14:00"))
                .andExpect(jsonPath("$.classId").value(ownClass))
                .andReturn();

        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();

        mvc.perform(get("/api/faculty/timetable").session(faculty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.id == " + id + ")]", hasSize(1)));
    }

    @Test
    void aTeacherCannotScheduleASubjectTheyDoNotTeach() throws Exception {
        mvc.perform(post("/api/faculty/timetable").with(csrf())
                        .session(faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":" + otherClass + ",\"dayOfWeek\":6,\"starts\":\"10:00\",\"ends\":\"11:00\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ASSIGNED"));
    }

    @Test
    void aLectureCannotOverlapAnotherOneOfTheSameTeacher() throws Exception {
        // The teacher already teaches the own class on Monday from 09:00 to 10:00.
        long second = TestFixtures.createClass(mvc, admin, TestFixtures.subjectOf(mvc, admin, ownClass),
                "B", "Room 3");

        mvc.perform(post("/api/faculty/timetable").with(csrf())
                        .session(faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":" + second + ",\"dayOfWeek\":1,\"starts\":\"09:30\",\"ends\":\"10:30\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEACHER_BUSY"));
    }

    @Test
    void theSameClassCannotBeScheduledTwiceAtTheSameTime() throws Exception {
        mvc.perform(post("/api/faculty/timetable").with(csrf())
                        .session(faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":" + ownClass + ",\"dayOfWeek\":1,\"starts\":\"09:00\",\"ends\":\"10:00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLASS_BUSY"));
    }

    @Test
    void aLectureMustEndAfterItStarts() throws Exception {
        mvc.perform(post("/api/faculty/timetable").with(csrf())
                        .session(faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":" + ownClass + ",\"dayOfWeek\":2,\"starts\":\"15:00\",\"ends\":\"14:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }

    @Test
    void aLectureMustSitInsideTheTeachingDay() throws Exception {
        mvc.perform(post("/api/faculty/timetable").with(csrf())
                        .session(faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":" + ownClass + ",\"dayOfWeek\":2,\"starts\":\"03:00\",\"ends\":\"04:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OUTSIDE_TEACHING_HOURS"));
    }

    @Test
    void theWeekdayMustBeValid() throws Exception {
        mvc.perform(post("/api/faculty/timetable").with(csrf())
                        .session(faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":" + ownClass + ",\"dayOfWeek\":9,\"starts\":\"15:00\",\"ends\":\"16:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DAY"));
    }

    @Test
    void aTeacherCannotChangeTheLectureOfAnotherTeacher() throws Exception {
        mvc.perform(patch("/api/faculty/timetable/" + otherSlot).with(csrf())
                        .session(faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":" + ownClass + ",\"dayOfWeek\":2,\"starts\":\"16:00\",\"ends\":\"17:00\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_OWNER"));
    }

    @Test
    void aTeacherCannotRemoveTheLectureOfAnotherTeacher() throws Exception {
        mvc.perform(delete("/api/faculty/timetable/" + otherSlot).with(csrf()).session(faculty))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_OWNER"));
    }

    @Test
    void aTeacherCanMoveTheirOwnLecture() throws Exception {
        mvc.perform(patch("/api/faculty/timetable/" + ownSlot).with(csrf())
                        .session(faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":" + ownClass + ",\"dayOfWeek\":4,\"starts\":\"16:00\",\"ends\":\"17:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayOfWeek").value(4))
                .andExpect(jsonPath("$.starts").value("16:00"));
    }

    @Test
    void aTeacherCanRemoveTheirOwnLecture() throws Exception {
        mvc.perform(delete("/api/faculty/timetable/" + ownSlot).with(csrf()).session(faculty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("removed"));
    }

    @Test
    void aStudentCannotReachTheTeachingDiary() throws Exception {
        MockHttpSession student = login("student@veriqra.local", "Demo!2026");

        mvc.perform(get("/api/faculty/timetable").session(student))
                .andExpect(status().isForbidden());
    }

    /* ------------------------------------------------------------------ helpers */

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private long firstSlotId(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/faculty/timetable").session(session))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$[0].id")).longValue();
    }
}
