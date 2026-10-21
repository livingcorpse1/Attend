package com.veriqra.app.domain.dto;

import com.veriqra.app.domain.AppUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The JSON shapes exchanged with the account endpoints.
 */
public final class AccountDtos {

    private AccountDtos() {
    }

    /** Sign in request. */
    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    /**
     * Self registration request for a student.
     *
     * <p>Consent is mandatory and versioned: the platform records which version of the
     * biometric notice the student agreed to at the moment they enrolled.
     */
    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 140) String fullName,
            @Email @NotBlank @Size(max = 190) String email,
            @NotBlank @Size(min = 10, max = 72) String password,
            @Pattern(regexp = "^[A-Za-z0-9-]{4,40}$", message = "must contain 4 to 40 letters, digits or dashes")
            String studentNumber,
            @NotBlank String consent,
            boolean consentAccepted) {
    }

    /** The signed in account, as exposed to the client. */
    public record AccountProfile(
            long id,
            String email,
            String fullName,
            String role,
            boolean active,
            String studentNumber,
            String faceState,
            Instant faceVerifiedAt,
            Instant createdAt) {

        public static AccountProfile from(AppUser user) {
            return new AccountProfile(user.id(), user.email(), user.fullName(), user.role().name(),
                    user.active(), user.studentNumber(), user.faceState().name(), user.faceVerifiedAt(),
                    user.createdAt());
        }
    }

    /**
     * The bootstrap payload returned before the client knows whether anyone is signed in.
     *
     * @param csrfToken the token the client has to echo on every state changing request
     * @param account the signed in account, or null
     * @param institution the name and the location of the institution
     * @param consentVersion the version of the biometric notice the client has to accept
     * @param demoMode whether the demonstration accounts exist
     * @param demoAccounts the demonstration accounts, empty when the demonstration is off
     */
    public record SessionBootstrap(
            String csrfToken,
            AccountProfile account,
            Map<String, String> institution,
            String consentVersion,
            boolean demoMode,
            List<String> demoAccounts) {
    }

    /** The outcome of a sign in attempt. */
    public record AuthenticationResult(boolean authenticated, String message) {
    }
}
