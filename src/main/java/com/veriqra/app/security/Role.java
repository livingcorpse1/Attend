package com.veriqra.app.security;

/**
 * Authorisation roles recognised by the platform.
 *
 * <p>The value stored in {@code app_user.role} is exactly the enum name, so the
 * database and the Java code stay in sync without a translation table.
 */
public enum Role {
    /** Institution administrator: manages users, classes and reads audit trails. */
    ADMIN,
    /** Teaching staff: runs attendance sessions and corrects records with an audit trail. */
    FACULTY,
    /** Student: scans rotating QR codes and verifies presence with a face check. */
    STUDENT;

    public static Role fromDatabase(String value) {
        if (value == null) {
            return STUDENT;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unknown role stored in the database: " + value, ex);
        }
    }
}
