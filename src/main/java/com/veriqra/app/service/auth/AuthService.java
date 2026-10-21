package com.veriqra.app.service.auth;

import com.veriqra.app.config.AppProperties;
import com.veriqra.app.domain.AppUser;
import com.veriqra.app.domain.dto.AccountDtos.AccountProfile;
import com.veriqra.app.domain.dto.AccountDtos.RegisterRequest;
import com.veriqra.app.repository.AuditRepository;
import com.veriqra.app.repository.UserRepository;
import com.veriqra.app.security.ApiException;
import com.veriqra.app.security.Role;
import com.veriqra.app.service.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Account creation, sign in and the login throttle.
 *
 * <p>Student accounts are created by the students themselves. The account is created in a
 * {@code PENDING} biometric state: it can sign in, but it cannot take part in attendance
 * until the face template has been enrolled and verified. Administrators and faculty are
 * provisioned by an administrator, never through this service.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** A password must be long enough and must not be a single repeated character. */
    private static final Pattern WEAK_PASSWORD = Pattern.compile("^(.)\\1+$");

    private final UserRepository users;
    private final AuditRepository audit;
    private final SettingsService settings;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final AppProperties properties;

    public AuthService(UserRepository users,
                       AuditRepository audit,
                       SettingsService settings,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       AppProperties properties) {
        this.users = users;
        this.audit = audit;
        this.settings = settings;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.properties = properties;
    }

    /**
     * Creates a student account.
     *
     * @throws ApiException when the request breaks a registration rule
     */
    @Transactional
    public AccountProfile register(RegisterRequest request) {
        String email = normaliseEmail(request.email());
        String fullName = request.fullName().trim();
        String studentNumber = request.studentNumber() == null ? null : request.studentNumber().trim().toUpperCase(Locale.ROOT);
        String consentVersion = settings.get(SettingsService.CONSENT_VERSION, "");

        if (!request.consentAccepted()) {
            throw ApiException.badRequest("CONSENT_REQUIRED",
                    "You must accept the biometric data notice before creating an account.");
        }
        if (consentVersion.isBlank() || !consentVersion.equals(request.consent())) {
            throw ApiException.badRequest("CONSENT_VERSION_UNKNOWN",
                    "The biometric data notice has changed. Reload the page and accept the current version.");
        }
        validatePassword(request.password());
        if (fullName.length() < 3) {
            throw ApiException.badRequest("NAME_TOO_SHORT", "Please enter your full name.");
        }
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN",
                    "An account already exists for that email address. Try signing in instead.");
        }
        if (studentNumber != null && !studentNumber.isBlank() && users.existsByStudentNumber(studentNumber)) {
            throw ApiException.conflict("STUDENT_NUMBER_TAKEN",
                    "That roll number is already registered. Check the number and try again.");
        }

        long userId;
        try {
            userId = users.create(email, passwordEncoder.encode(request.password()), fullName,
                    Role.STUDENT, studentNumber == null || studentNumber.isBlank() ? null : studentNumber);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("EMAIL_TAKEN",
                    "An account already exists for that email address. Try signing in instead.");
        }

        audit.record(userId, "ACCOUNT_REGISTERED", "student=" + userId, "SELF_SERVICE",
                "Student account created through self registration", null, email);
        log.info("Registered a new student account for {}", email);

        return users.findById(userId)
                .map(AccountProfile::from)
                .orElseThrow(() -> ApiException.unavailable("ACCOUNT_UNAVAILABLE",
                        "The account was created but could not be read back."));
    }

    /**
     * Authenticates an account, applying the login throttle.
     *
     * @param ipAddress the address the request came from, used for the address throttle
     */
    public Authentication authenticate(String email, String password, String ipAddress) {
        String identifier = normaliseEmail(email);
        enforceThrottle(identifier, ipAddress);

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(identifier, password));
            users.findByEmail(identifier).ifPresent(user -> users.recordSuccessfulLogin(user.id()));
            audit.recordLoginAttempt(identifier, ipAddress, true);
            return authentication;
        } catch (AuthenticationException ex) {
            audit.recordLoginAttempt(identifier, ipAddress, false);
            log.info("Rejected a sign in attempt for {}", identifier);
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Email or password is incorrect.");
        }
    }

    /** The account behind an authentication. */
    public AccountProfile profileOf(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Sign in to continue.");
        }
        return users.findByEmail(authentication.getName())
                .map(AccountProfile::from)
                .orElseThrow(() -> ApiException.unauthorized("ACCOUNT_MISSING",
                        "The signed in account no longer exists."));
    }

    /** The account id behind an authentication. */
    public long userIdOf(Authentication authentication) {
        return profileOf(authentication).id();
    }

    private long userIdOf(String email) {
        Optional<AppUser> user = users.findByEmail(email);
        return user.map(AppUser::id).orElse(0L);
    }

    private void enforceThrottle(String identifier, String ipAddress) {
        Instant since = Instant.now().minus(properties.getAttendance().getLoginFailureWindowMinutes(), ChronoUnit.MINUTES);
        long identifierFailures = audit.countRecentFailures(identifier, since);
        long addressFailures = audit.countRecentFailuresByIp(ipAddress, since);
        int limit = properties.getAttendance().getMaxLoginFailures();

        if (identifierFailures >= limit) {
            throw ApiException.tooManyRequests("LOGIN_THROTTLED",
                    "Too many failed sign in attempts. Try again in "
                            + properties.getAttendance().getLoginFailureWindowMinutes() + " minutes.");
        }
        if (addressFailures >= limit * 5L) {
            throw ApiException.tooManyRequests("LOGIN_THROTTLED",
                    "Too many failed sign in attempts from this network. Try again later.");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 10) {
            throw ApiException.badRequest("PASSWORD_TOO_SHORT",
                    "Choose a password of at least 10 characters.");
        }
        if (password.length() > 72) {
            throw ApiException.badRequest("PASSWORD_TOO_LONG",
                    "Passwords may not exceed 72 characters.");
        }
        if (WEAK_PASSWORD.matcher(password).matches()) {
            throw ApiException.badRequest("PASSWORD_TOO_WEAK",
                    "Choose a password that is not a single repeated character.");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char character : password.toCharArray()) {
            hasLetter |= Character.isLetter(character);
            hasDigit |= Character.isDigit(character);
        }
        if (!hasLetter || !hasDigit) {
            throw ApiException.badRequest("PASSWORD_TOO_WEAK",
                    "Choose a password that mixes letters and digits.");
        }
    }

    private static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
