package com.veriqra.app.domain;

import com.veriqra.app.security.Role;

import java.time.Instant;

/**
 * An account row from {@code app_user}, decoupled from password material.
 *
 * @param id             primary key
 * @param email          sign in identifier, always stored lowercase
 * @param fullName       display name
 * @param role           authorisation role
 * @param active         whether the account may authenticate at all
 * @param studentNumber  institute roll number, students only
 * @param faceState      current face verification state
 * @param faceVerifiedAt when the biometric template was enrolled, {@code null} while pending
 * @param createdAt      account creation timestamp
 */
public record AppUser(
        long id,
        String email,
        String fullName,
        Role role,
        boolean active,
        String studentNumber,
        FaceState faceState,
        Instant faceVerifiedAt,
        Instant createdAt) {

    /** Lifecycle of the mandatory face verification for a student account. */
    public enum FaceState {
        /** No template enrolled yet. The account may sign in but not record attendance. */
        PENDING,
        /** A template is enrolled and verified. */
        VERIFIED,
        /** The student asked for their biometric data to be removed. */
        REVOKED
    }

    /** True when this account is allowed to take part in attendance marking. */
    public boolean faceVerified() {
        return faceState == FaceState.VERIFIED;
    }
}
