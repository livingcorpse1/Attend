package com.veriqra.app.security;

/**
 * Authorisation roles recognised by the platform.
 *
 * <p>There are only two. A teacher teaches and takes attendance, a student is marked
 * present. There is no administrator role: the college is run by the people who use it.
 */
public enum Role {
    /** Teaching staff: runs attendance sessions, schedules their own lectures, corrects records. */
    FACULTY,
    /** Student: marks their own presence and sees their own attendance. */
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
