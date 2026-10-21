package com.veriqra.app.service.attendance;

import com.veriqra.app.config.AppProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Issues and validates the rotating QR payloads a teacher projects in class.
 *
 * <p>A payload is {@code VQ1.<sessionId>.<sessionNonce>.<issuedAt>.<expiresAt>.<random>}
 * followed by a dot and a base64url encoded HMAC-SHA-256 signature. Every field that
 * matters is inside the signature, so a student cannot move a code to another session,
 * extend its life, or replay one that was captured earlier.
 *
 * <p>Four properties make the code usable as a live attendance signal:
 * <ul>
 *   <li>the payload expires after a few seconds, so a photograph of the screen is useless;</li>
 *   <li>the payload is bound to the session nonce, so closing and reopening a session
 *       invalidates every code that was issued before;</li>
 *   <li>the signature is compared in constant time, so a timing attack reveals nothing;</li>
 *   <li>the random component makes every payload unique, which keeps the audit trail honest.</li>
 * </ul>
 */
@Service
public class QrTokenService {

    private static final String VERSION = "VQ1";
    private static final String SEPARATOR = ".";
    private static final int PAYLOAD_PARTS = 6;
    private static final int RANDOM_BYTES = 12;

    /** A freshly issued QR payload. */
    public record IssuedQr(String payload, String signature, String token, long issuedAt, long expiresAt,
                           int expiresInSeconds) {
    }

    /** The outcome of validating a payload. */
    public record QrValidation(boolean valid, String reason, long sessionId, String sessionNonce,
                                long issuedAt, long expiresAt) {

        static QrValidation invalid(String reason) {
            return new QrValidation(false, reason, 0L, null, 0L, 0L);
        }
    }

    private final SecretKeySpec signingKey;
    private final SecureRandom random = new SecureRandom();
    private final AppProperties properties;

    public QrTokenService(AppProperties properties) {
        this.properties = properties;
        this.signingKey = new SecretKeySpec(
                properties.getQrSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** Issues a payload for a session. */
    public IssuedQr issue(long sessionId, String sessionNonce) {
        long now = Instant.now().getEpochSecond();
        long expiresAt = now + properties.getQr().getTtlSeconds();
        String randomPart = toBase64Url(randomBytes(RANDOM_BYTES));
        String payload = String.join(SEPARATOR,
                VERSION,
                Long.toString(sessionId),
                sessionNonce,
                Long.toString(now),
                Long.toString(expiresAt),
                randomPart);
        String signature = sign(payload);
        return new IssuedQr(payload, signature, payload + SEPARATOR + signature,
                now, expiresAt, properties.getQr().getTtlSeconds());
    }

    /**
     * Validates a payload.
     *
     * <p>The session is derived from the payload itself, so a student only has to scan
     * the code. The caller then compares the session identifier and nonce with the
     * session stored in the database.
     *
     * @param token the full token, as scanned by the client
     */
    public QrValidation validate(String token) {
        if (token == null || token.isBlank()) {
            return QrValidation.invalid("No QR payload was supplied.");
        }
        String trimmed = token.trim();
        int separator = trimmed.lastIndexOf(SEPARATOR);
        if (separator <= 0 || separator == trimmed.length() - 1) {
            return QrValidation.invalid("The QR payload is malformed.");
        }
        String payload = trimmed.substring(0, separator);
        String signature = trimmed.substring(separator + 1);

        String[] parts = payload.split("\\.");
        if (parts.length != PAYLOAD_PARTS) {
            return QrValidation.invalid("The QR payload is malformed.");
        }
        if (!VERSION.equals(parts[0])) {
            return QrValidation.invalid("The QR payload version is not supported.");
        }

        String expectedSignature = sign(payload);
        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return QrValidation.invalid("The QR code signature does not match. Scan the current code.");
        }

        long sessionId;
        long issuedAt;
        long expiresAt;
        try {
            sessionId = Long.parseLong(parts[1]);
            issuedAt = Long.parseLong(parts[3]);
            expiresAt = Long.parseLong(parts[4]);
        } catch (NumberFormatException ex) {
            return QrValidation.invalid("The QR payload is malformed.");
        }
        String sessionNonce = parts[2];
        if (parts[5].length() < 8) {
            return QrValidation.invalid("The QR payload is malformed.");
        }

        long now = Instant.now().getEpochSecond();
        int skew = properties.getQr().getClockSkewSeconds();
        if (issuedAt > now + skew) {
            return QrValidation.invalid("The QR code was issued in the future. Check your device clock.");
        }
        if (expiresAt <= now) {
            return QrValidation.invalid("The QR code has expired. Scan the current code and try again.");
        }
        if (expiresAt - issuedAt > properties.getQr().getTtlSeconds()) {
            return QrValidation.invalid("The QR code lifetime is not valid.");
        }

        return new QrValidation(true, "OK", sessionId, sessionNonce, issuedAt, expiresAt);
    }

    /** The number of seconds a payload stays valid. */
    public int ttlSeconds() {
        return properties.getQr().getTtlSeconds();
    }

    /** How often the teacher client should refresh the projected code. */
    public int refreshSeconds() {
        return properties.getQr().getRefreshSeconds();
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            return toBase64Url(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA-256 is not available in this runtime", ex);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    /** True when two session nonces are equal, compared in constant time. */
    public boolean nonceMatches(String left, String right) {
        return constantTimeEquals(left, right);
    }

    private byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String toBase64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
