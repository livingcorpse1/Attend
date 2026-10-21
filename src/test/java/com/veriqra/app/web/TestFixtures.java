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
 * <p>There is no administrator. A teacher creates their own subjects, the class sections
 * that run them and the students who attend, so the tests do the same.
 */
final class TestFixtures {

    private TestFixtures() {
    }

    /**
     * Adds a colleague to the teaching staff.
     *
     * <p>There is no administrator, so a teacher provisions the other teachers.
     *
     * @return the password of the new account
     */
    static String addTeacher(MockMvc mvc, MockHttpSession teacher, String email, String fullName)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/faculty/teachers").with(csrf())
                        .session(teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"fullName\":\"" + fullName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return (String) JsonPath.read(result.getResponse().getContentAsString(), "$.password");
    }

    /** Creates a subject that belongs to the teacher. */
    static long createSubject(MockMvc mvc, MockHttpSession teacher, String code, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/faculty/subjects").with(csrf())
                        .session(teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** Creates a class section for a subject of the teacher. */
    static long createClass(MockMvc mvc, MockHttpSession teacher, long subjectId, String section, String room)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/faculty/classes").with(csrf())
                        .session(teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":" + subjectId + ",\"section\":\"" + section
                                + "\",\"room\":\"" + room + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** Enrols a student in a class section. */
    static void enrol(MockMvc mvc, MockHttpSession teacher, String studentEmail, long classId) throws Exception {
        mvc.perform(post("/api/faculty/enrollments").with(csrf())
                        .session(teacher)
                        .param("email", studentEmail)
                        .param("classId", Long.toString(classId)))
                .andExpect(status().isOk());
    }

    /** The subject a class section belongs to. */
    static long subjectOf(MockMvc mvc, MockHttpSession teacher, long classId) throws Exception {
        MvcResult result = mvc.perform(get("/api/faculty/structure").session(teacher))
                .andExpect(status().isOk())
                .andReturn();
        List<Object> classes = JsonPath.read(result.getResponse().getContentAsString(), "$.classes");
        for (Object entry : classes) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> row = (java.util.Map<String, Object>) entry;
            if (((Number) row.get("id")).longValue() == classId) {
                Object subjectId = row.get("subjectId");
                if (subjectId instanceof Number number) {
                    return number.longValue();
                }
            }
        }
        throw new IllegalStateException("No class section with the identifier " + classId);
    }
}
