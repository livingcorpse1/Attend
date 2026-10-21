package com.veriqra.app.web;

import com.veriqra.app.repository.BiometricRepository;
import com.veriqra.app.repository.UserRepository;
import com.veriqra.app.security.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Blocks the student endpoints that need a verified face.
 *
 * <p>Sign up is the first thing a student does, and the platform requires a verified face
 * before the student can take part in attendance. Rather than scattering that check over
 * every controller, it is enforced once here for the whole student API. The biometric
 * endpoints are excluded so the student can actually complete the check.
 *
 * <p>The screens a student only reads are not blocked. A student who has not run the
 * identity check yet can still see their timetable, their attendance history and the
 * classes that are taking attendance. Only the two calls that mark an attendance are
 * closed, because those are the ones that need the face. Locking a student out of the
 * whole application would leave them with nothing to do but the check itself.
 */
@Component
public class FaceVerificationInterceptor implements HandlerInterceptor {

    /** The student endpoints that only read and stay open before the check. */
    private static final java.util.List<String> READ_ONLY_PATHS = java.util.List.of(
            "/api/student/dashboard",
            "/api/student/attendance",
            "/api/student/timetable",
            "/api/student/sessions/active");

    private final UserRepository users;
    private final BiometricRepository biometrics;
    private final ObjectMapper objectMapper;

    public FaceVerificationInterceptor(UserRepository users,
                                        BiometricRepository biometrics,
                                        ObjectMapper objectMapper) {
        this.users = users;
        this.biometrics = biometrics;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        if ("GET".equals(request.getMethod()) && READ_ONLY_PATHS.contains(request.getRequestURI())) {
            return true;
        }

        var authentication = request.getUserPrincipal();
        if (authentication == null || authentication.getName() == null) {
            return true;
        }
        var user = users.findByEmail(authentication.getName());
        if (user.isEmpty() || user.get().role() != Role.STUDENT) {
            return true;
        }
        long studentId = user.get().id();
        if (biometrics.findTemplate(studentId).isPresent() && user.get().faceVerified()) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(java.util.Map.of(
                "code", "FACE_VERIFICATION_REQUIRED",
                "message", "Complete the face verification before using this part of the application.",
                "status", HttpServletResponse.SC_FORBIDDEN)));
        return false;
    }
}
