package com.veriqra.app.web;

import com.veriqra.app.service.biometric.FaceRecognitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The full attendance journey, from a self registered student to a verified attendance
 * record.
 *
 * <p>The computer vision is mocked here on purpose. The real pipeline is verified
 * separately in {@code FaceRecognitionServiceTest}; this test is about the orchestration
 * and the rules around it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttendanceFlowIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    com.veriqra.app.service.report.ReportService reports;

    @Autowired
    com.veriqra.app.repository.UserRepository users;

    @MockitoBean
    FaceRecognitionService faceRecognition;

    private MockHttpSession facultySession;
    private MockHttpSession studentSession;
    private long classId;
    private long slotId;

    @BeforeEach
    void setUp() throws Exception {
        // The face pipeline is mocked, so the version of the models is stubbed as well.
        when(faceRecognition.modelVersion()).thenReturn("test-model");
        when(faceRecognition.isAvailable()).thenReturn(true);

        facultySession = login("faculty@veriqra.local", "Demo!2026");
        studentSession = login("student@veriqra.local", "Demo!2026");

        // There is no administrator. The teacher builds the class they teach.
        long subject = TestFixtures.createSubject(mvc, facultySession, "AT101", "Attendance Subject");
        classId = TestFixtures.createClass(mvc, facultySession, subject, "A", "Room 1");
        TestFixtures.enrol(mvc, facultySession, "student@veriqra.local", classId);
        TestFixtures.enrol(mvc, facultySession, "student2@veriqra.local", classId);

        // Attendance is only taken for a lecture that is on the teaching diary.
        slotId = TestFixtures.addLectureToday(mvc, facultySession, classId);
    }

    @Test
    void aLectureThatHasAttendanceSessionsIsNotRemoved() throws Exception {
        // The lecture of the setup has a session, so it stays: it is a record of what
        // happened and not a row of the diary any more.
        startSession();
        mvc.perform(delete("/api/faculty/timetable/" + slotId).with(csrf()).session(facultySession))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_HAS_SESSIONS"));
    }

    @Test
    void aStudentCanReadTheirOwnScreensBeforeTheIdentityCheck() throws Exception {
        // The screens a student only reads stay open, so the check is not a dead end.
        mvc.perform(get("/api/student/dashboard").session(studentSession))
                .andExpect(status().isOk());
        mvc.perform(get("/api/student/timetable").session(studentSession))
                .andExpect(status().isOk());
        mvc.perform(get("/api/student/attendance").session(studentSession))
                .andExpect(status().isOk());
    }

    @Test
    void aStudentWithoutAVerifiedFaceCannotScanTheClassroomCode() throws Exception {
        long session = startSession();

        mvc.perform(post("/api/student/attendance/scan").with(csrf())
                        .session(studentSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + qrToken(session) + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FACE_VERIFICATION_REQUIRED"));
    }
    @Test
    void theCompleteJourneyEndsInAVerifiedAttendanceRecord() throws Exception {
        long session = startSession();
        stubSuccessfulFaceCheck();

        enrollFace(studentSession);

        mvc.perform(get("/api/student/dashboard").session(studentSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.biometricState").value("VERIFIED"));

        String ticketId = scanForTicket(session, studentSession);

        mvc.perform(multipartBuilder("/api/student/attendance/submit", "ticketId", ticketId, frame("f0.jpg")).with(csrf()).session(studentSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RECORDED"))
                .andExpect(jsonPath("$.matchScore").exists());

        mvc.perform(get("/api/faculty/sessions/" + session + "/roster").session(facultySession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("PRESENT"))
                .andExpect(jsonPath("$[0].method").value("FACE_VERIFIED"))
                .andExpect(jsonPath("$[1].status").value("PENDING"));

        mvc.perform(get("/api/student/attendance").session(studentSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void theAttendanceRateIsAShareOfWhatCouldHaveBeenRecorded() throws Exception {
        long session = startSession();
        stubSuccessfulFaceCheck();
        enrollFace(studentSession);
        String ticketId = scanForTicket(session, studentSession);
        mvc.perform(multipartBuilder("/api/student/attendance/submit", "ticketId",
                        ticketId, frame("f0.jpg"))
                        .with(csrf()).session(studentSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RECORDED"));

        // The class holds two students and one session was held, so two attendances could
        // have been recorded and one of them was. The teacher sees fifty per cent, and
        // the student who was there sees a hundred.
        mvc.perform(get("/api/faculty/reports/subjects").session(facultySession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessions").value(1))
                .andExpect(jsonPath("$[0].attended").value(1))
                .andExpect(jsonPath("$[0].enrolled").value(2))
                .andExpect(jsonPath("$[0].percentage").value(50.0));

        // The rate of the student is read from the repository, because the dashboard
        // reads its numbers on other connections, which do not see the rows of a test
        // that is rolled back.
        var forStudent = reports.subjectAttendanceForStudent(studentId());
        assertThat(forStudent.stream().mapToDouble(view -> view.percentage()).sum(), is(100.0d));
    }

    /** The identifier of the student of the test. */
    private long studentId() {
        return users.findByEmail("student@veriqra.local").orElseThrow().id();
    }

    @Test
    void aTicketCanOnlyBeUsedOnce() throws Exception {
        long session = startSession();
        stubSuccessfulFaceCheck();
        enrollFace(studentSession);
        String ticketId = scanForTicket(session, studentSession);

        mvc.perform(multipartBuilder("/api/student/attendance/submit", "ticketId", ticketId, frame("f0.jpg")).with(csrf()).session(studentSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RECORDED"));

        mvc.perform(multipartBuilder("/api/student/attendance/submit", "ticketId", ticketId, frame("f0.jpg")).with(csrf()).session(studentSession))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_USED"));
    }

    @Test
    void aFailedFaceCheckDoesNotCreateAnAttendanceRecord() throws Exception {
        long session = startSession();
        stubSuccessfulFaceCheck();

        enrollFace(studentSession);

        // The live check now fails.
        when(faceRecognition.analyse(anyList(), any())).thenReturn(
                new FaceRecognitionService.CaptureAnalysis(false, "LIVENESS_FAILED",
                        "The requested head movement was not detected.", null, 0, 0, 0, 0, null, null));

        String ticketId = scanForTicket(session, studentSession);

        mvc.perform(multipartBuilder("/api/student/attendance/submit", "ticketId", ticketId, frame("f0.jpg")).with(csrf()).session(studentSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"));

        mvc.perform(get("/api/faculty/sessions/" + session + "/roster").session(facultySession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void aTamperedCodeIsRejected() throws Exception {
        long session = startSession();
        stubSuccessfulFaceCheck();
        enrollFace(studentSession);

        mvc.perform(post("/api/student/attendance/scan").with(csrf())
                        .session(studentSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + qrToken(session) + ".tampered\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QR_INVALID"));
    }

    @Test
    void aTeacherCorrectionIsRecordedInTheAuditTrail() throws Exception {
        long session = startSession();
        long studentId = firstEnrolledStudent(session);

        mvc.perform(patch("/api/faculty/sessions/" + session + "/roster/" + studentId).with(csrf())
                        .session(facultySession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PRESENT\",\"reasonCode\":\"VERIFIED_ROLL_CALL\","
                                + "\"explanation\":\"Confirmed during roll call\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PRESENT"));

        mvc.perform(get("/api/audit-logs").session(facultySession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("ATTENDANCE_OVERRIDE"))
                .andExpect(jsonPath("$[0].reasonCode").value("VERIFIED_ROLL_CALL"));
    }

    @Test
    void aTeacherCannotOpenTheSessionOfAnotherTeacher() throws Exception {
        String colleaguePassword = TestFixtures.addTeacher(mvc, facultySession, "colleague@veriqra.local", "Prof. Anand Iyer");
        MockHttpSession other = login("colleague@veriqra.local", colleaguePassword);
        long session = startSession();

        mvc.perform(get("/api/faculty/sessions/" + session + "/roster").session(other))
                .andExpect(status().isForbidden());
    }

    @Test
    void aStudentCannotStartASession() throws Exception {
        mvc.perform(post("/api/faculty/sessions").with(csrf())
                        .session(studentSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":" + classId + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void registrationCreatesAPendingAccountThatStillCannotTakeAttendance() throws Exception {
        MockHttpSession fresh = new MockHttpSession();

        mvc.perform(post("/api/auth/register").with(csrf())
                        .session(fresh)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Test Student\",\"email\":\"test.student@example.edu\","
                                + "\"password\":\"TestPass!2026\",\"studentNumber\":\"VQ99001\","
                                + "\"consent\":\"2026-01\",\"consentAccepted\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.faceState").value("PENDING"));

        // The screens that only read stay open, so the student is not locked out of the
        // application while the check is open.
        mvc.perform(get("/api/student/dashboard").session(fresh))
                .andExpect(status().isOk());

        // The attendance cannot be marked yet.
        mvc.perform(post("/api/student/attendance/scan").with(csrf())
                        .session(fresh)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"anything\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FACE_VERIFICATION_REQUIRED"));
    }

    @Test
    void registrationWithoutConsentIsRejected() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Test Student\",\"email\":\"no.consent@example.edu\","
                                + "\"password\":\"TestPass!2026\",\"studentNumber\":\"VQ99002\","
                                + "\"consent\":\"2026-01\",\"consentAccepted\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
    }

    @Test
    void aWeakPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Test Student\",\"email\":\"weak@example.edu\","
                                + "\"password\":\"short\",\"studentNumber\":\"VQ99003\","
                                + "\"consent\":\"2026-01\",\"consentAccepted\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("password")));
    }

    @Test
    void aStateChangingRequestWithoutTheCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"student@veriqra.local\",\"password\":\"Demo!2026\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anInvalidPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"student@veriqra.local\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void aStudentCannotReachTheTeachingWorkspace() throws Exception {
        mvc.perform(get("/api/faculty/structure").session(studentSession))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTeacherCannotTakeTheAttendanceOfALectureThatIsNotOnTheirDiary() throws Exception {
        // A colleague owns the other lecture.
        String colleaguePassword = TestFixtures.addTeacher(mvc, facultySession, "colleague@veriqra.local",
                "Prof. Anand Iyer");
        MockHttpSession colleague = login("colleague@veriqra.local", colleaguePassword);
        long colleagueSubject = TestFixtures.createSubject(mvc, colleague, "AT102", "Colleague Subject");
        long colleagueClass = TestFixtures.createClass(mvc, colleague, colleagueSubject, "A", "Room 2");
        long colleagueSlot = TestFixtures.addLectureToday(mvc, colleague, colleagueClass);

        mvc.perform(post("/api/faculty/sessions").with(csrf())
                        .session(facultySession)
                        .param("slotId", Long.toString(colleagueSlot)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_OWNER"));
    }

    @Test
    void aTeacherCannotTakeTheAttendanceOfALectureOnAnotherDay() throws Exception {
        int otherDay = java.time.LocalDate.now().getDayOfWeek().getValue() % 7 + 1;
        long otherSlot = TestFixtures.addLecture(mvc, facultySession, classId, otherDay, "07:00", "08:00");

        mvc.perform(post("/api/faculty/sessions").with(csrf())
                        .session(facultySession)
                        .param("slotId", Long.toString(otherSlot)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_NOT_OPEN"));
    }

    @Test
    void aTeacherSeesTheLecturesOfTheDayInTheirDiary() throws Exception {
        MvcResult result = mvc.perform(get("/api/faculty/schedule").session(facultySession))
                .andExpect(status().isOk())
                .andReturn();
        List<Object> lectures = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$");
        boolean found = false;
        for (Object entry : lectures) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> lecture = (java.util.Map<String, Object>) entry;
            if (((Number) lecture.get("id")).longValue() == slotId) {
                found = true;
                assertThat((Boolean) lecture.get("open"), is(true));
            }
        }
        assertThat(found, is(true));
    }

    /* ------------------------------------------------------------------ helpers */

    @Test
    void aTeacherCanEnrolAndRemoveAStudentOfTheirOwnClass() throws Exception {
        // The enrolment of the setup has to be there, and the student has to be able to
        // leave the class again.
        MvcResult roster = mvc.perform(get("/api/faculty/structure").session(facultySession))
                .andExpect(status().isOk())
                .andReturn();
        List<Object> classes = com.jayway.jsonpath.JsonPath.read(
                roster.getResponse().getContentAsString(), "$.classes");
        long enrolled = 0;
        for (Object entry : classes) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> row = (java.util.Map<String, Object>) entry;
            if (((Number) row.get("id")).longValue() == classId) {
                enrolled = ((Number) row.get("enrolled")).longValue();
            }
        }
        assertThat(enrolled, is(2L));
    }

    @Test
    void aTeacherCanRemoveAClassSectionThatHasNoSessions() throws Exception {
        long subject = TestFixtures.createSubject(mvc, facultySession, "AT102", "Subject To Remove");
        long removable = TestFixtures.createClass(mvc, facultySession, subject, "A", "Room 9");

        mvc.perform(delete("/api/faculty/classes/" + removable).with(csrf()).session(facultySession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("removed"));

        mvc.perform(delete("/api/faculty/subjects/" + subject).with(csrf()).session(facultySession))
                .andExpect(status().isOk());
    }

    @Test
    void aTeacherCannotRemoveAClassSectionOfAnotherTeacher() throws Exception {
        String colleaguePassword = TestFixtures.addTeacher(mvc, facultySession, "colleague@veriqra.local",
                "Prof. Anand Iyer");
        MockHttpSession colleague = login("colleague@veriqra.local", colleaguePassword);
        long subject = TestFixtures.createSubject(mvc, colleague, "AT103", "Subject Of The Colleague");
        long otherClass = TestFixtures.createClass(mvc, colleague, subject, "A", "Room 10");

        mvc.perform(delete("/api/faculty/classes/" + otherClass).with(csrf()).session(facultySession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_OWNER"));
    }

    private void stubSuccessfulFaceCheck() {
        when(faceRecognition.isAvailable()).thenReturn(true);
        when(faceRecognition.matchThreshold()).thenReturn(0.55f);
        when(faceRecognition.similarity(any(float[].class), any(float[].class))).thenReturn(0.88d);
        when(faceRecognition.analyse(anyList(), any())).thenReturn(acceptedAnalysis());
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private long startSession() throws Exception {
        MvcResult result = mvc.perform(post("/api/faculty/sessions").with(csrf())
                        .session(facultySession)
                        .param("slotId", Long.toString(slotId)))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id"))
                .longValue();
    }

    private String qrToken(long session) throws Exception {
        MvcResult result = mvc.perform(get("/api/faculty/sessions/" + session + "/qr").session(facultySession))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private String scanForTicket(long session, MockHttpSession target) throws Exception {
        MvcResult result = mvc.perform(post("/api/student/attendance/scan").with(csrf())
                        .session(target)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + qrToken(session) + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.ticketId");
    }

    /**
     * The identifier of the first enrolled student of a session. The identifiers are read
     * from the roster instead of being hard coded, because rolled back tests make the
     * identity columns move.
     */
    private long firstEnrolledStudent(long session) throws Exception {
        MvcResult roster = mvc.perform(get("/api/faculty/sessions/" + session + "/roster").session(facultySession))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                roster.getResponse().getContentAsString(), "$[0].studentId")).longValue();
    }

    private String enrollFace(MockHttpSession session) throws Exception {
        when(faceRecognition.isAvailable()).thenReturn(true);
        when(faceRecognition.analyse(anyList(), any())).thenReturn(acceptedAnalysis());
        MvcResult challenge = mvc.perform(post("/api/student/biometric/challenge").with(csrf()).session(session))
                .andExpect(status().isOk())
                .andReturn();
        String challengeId = com.jayway.jsonpath.JsonPath.read(
                challenge.getResponse().getContentAsString(), "$.challengeId");

        mvc.perform(multipartBuilder("/api/student/biometric/enroll", "challengeId", challengeId, frame("f0.jpg"), frame("f1.jpg"), frame("f2.jpg"), frame("f3.jpg"), frame("f4.jpg")).with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(true));
        return challengeId;
    }

    private FaceRecognitionService.CaptureAnalysis acceptedAnalysis() {
        float[] descriptor = new float[128];
        for (int i = 0; i < descriptor.length; i++) {
            descriptor[i] = (float) Math.sin(i);
        }
        return new FaceRecognitionService.CaptureAnalysis(true, "OK", "Face verified", descriptor,
                0.9d, 0.8d, 0.95d, 5, null, null);
    }

    private MockMultipartFile frame(String name) {
        return new MockMultipartFile("frames", name, "image/jpeg", portraitBytes());
    }

    /**
     * A real JPEG, so the upload is decoded the same way a browser frame would be.
     */
    private byte[] portraitBytes() {
        try (java.io.InputStream input = getClass().getResourceAsStream("/fixtures/portrait-a.jpg")) {
            if (input == null) {
                throw new IllegalStateException("The portrait fixture is missing from the test resources");
            }
            return input.readAllBytes();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("The portrait fixture could not be read", ex);
        }
    }

    /**
     * Builds a multipart request. The builder is kept in its own type so the frame
     * methods stay available after the security post processor is applied.
     */
    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder multipartBuilder(
            String path, String parameterName, String parameterValue, MockMultipartFile... files) {
        var builder = multipart(path);
        for (MockMultipartFile file : files) {
            builder.file(file);
        }
        builder.param(parameterName, parameterValue);
        return builder;
    }
}
