package com.veriqra.app.web;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Builds the academic structure the tests need.
 *
 * <p>Nothing about the college is seeded, so every test that needs a subject, a class
 * section or an enrolment creates exactly what it uses.
 */
final class TestFixtures {

    private TestFixtures() {
    }

    /** The identifier of an account, by its email address. */
    static long userId(MockMvc mvc, MockHttpSession admin, String email) throws Exception {
        MvcResult result = mvc.perform(get("/api/admin/users").session(admin))
                .andExpect(status().isOk())
                .andReturn();
        List<Object> users = JsonPath.read(result.getResponse().getContentAsString(), "$");
        for (Object entry : users) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> user = (java.util.Map<String, Object>) entry;
            if (email.equals(user.get("email"))) {
                return ((Number) user.get("id")).longValue();
            }
        }
        throw new IllegalStateException("No account for " + email);
    }

    /** Creates a subject that belongs to a teacher. */
    static long createSubject(MockMvc mvc, MockHttpSession admin, String code, String name, long facultyId)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/subjects").with(csrf())
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name
                                + "\",\"facultyId\":" + facultyId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** Creates a class section for a subject. */
    static long createClass(MockMvc mvc, MockHttpSession admin, long subjectId, String section, String room)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/classes").with(csrf())
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":" + subjectId + ",\"section\":\"" + section
                                + "\",\"room\":\"" + room + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** Enrols a student in a class section. */
    static void enrol(MockMvc mvc, MockHttpSession admin, long studentId, long classId) throws Exception {
        mvc.perform(post("/api/admin/enrollments").with(csrf())
                        .session(admin)
                        .param("studentId", Long.toString(studentId))
                        .param("classId", Long.toString(classId)))
                .andExpect(status().isOk());
    }

    /** The subject a class section belongs to. */
    static long subjectOf(MockMvc mvc, MockHttpSession admin, long classId) throws Exception {
        MvcResult result = mvc.perform(get("/api/admin/classes").session(admin))
                .andExpect(status().isOk())
                .andReturn();
        List<Object> classes = JsonPath.read(result.getResponse().getContentAsString(), "$");
        for (Object entry : classes) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> row = (java.util.Map<String, Object>) entry;
            if (((Number) row.get("id")).longValue() == classId) {
                return ((Number) row.get("subjectId")).longValue();
            }
        }
        throw new IllegalStateException("No class section with the identifier " + classId);
    }
}
