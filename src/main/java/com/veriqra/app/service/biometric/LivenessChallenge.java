package com.veriqra.app.service.biometric;

import java.security.SecureRandom;

/**
 * The active liveness challenges the server can ask a student to perform.
 *
 * <p>A printed photograph or a screen replay cannot satisfy any of these, which is the
 * whole point: the challenge is what separates a live person from a static image. The
 * challenge is chosen server side and is bound to the student and the transaction, so
 * the client cannot pick an easy one.
 */
public enum LivenessChallenge {

    /** The student must rotate their head towards their own left shoulder. */
    TURN_LEFT(
            "Turn your head to your LEFT",
            "Look straight at the camera first, then slowly turn your head to your left until the "
                    + "indicator on screen fills up.",
            "TURN_LEFT"),

    /** The student must rotate their head towards their own right shoulder. */
    TURN_RIGHT(
            "Turn your head to your RIGHT",
            "Look straight at the camera first, then slowly turn your head to your right until the "
                    + "indicator on screen fills up.",
            "TURN_RIGHT");

    private final String instruction;
    private final String guidance;
    private final String code;

    LivenessChallenge(String instruction, String guidance, String code) {
        this.instruction = instruction;
        this.guidance = guidance;
        this.code = code;
    }

    public String instruction() {
        return instruction;
    }

    public String guidance() {
        return guidance;
    }

    public String code() {
        return code;
    }

    /** Picks a challenge uniformly at random using a cryptographically strong source. */
    public static LivenessChallenge random(SecureRandom random) {
        LivenessChallenge[] values = values();
        return values[random.nextInt(values.length)];
    }

    /** Resolves a stored challenge code back to the enum. */
    public static LivenessChallenge fromCode(String code) {
        for (LivenessChallenge challenge : values()) {
            if (challenge.code.equals(code)) {
                return challenge;
            }
        }
        throw new IllegalArgumentException("Unknown liveness challenge: " + code);
    }
}
