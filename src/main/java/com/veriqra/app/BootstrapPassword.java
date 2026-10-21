package com.veriqra.app;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates the password of the first administrator account.
 *
 * <p>When a password is configured it is used as it is. Otherwise a strong password is
 * generated and written to the log, so a deployment never starts with a credential that
 * is written down in the source.
 */
final class BootstrapPassword {

    private BootstrapPassword() {
    }

    /**
     * A new random password.
     */
    static String generate(SecureRandom random) {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return "Vq!" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
