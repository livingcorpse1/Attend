package com.veriqra.app.service;

import com.veriqra.app.config.AppProperties;
import com.veriqra.app.service.attendance.QrTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The QR token is the token that carries the authority to take attendance, so its
 * properties are tested directly.
 */
class QrTokenServiceTest {

    private AppProperties properties;
    private QrTokenService service;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.setQrSecret("a-test-secret-that-is-long-enough-for-hmac-sha-256");
        properties.getQr().setTtlSeconds(8);
        properties.getQr().setClockSkewSeconds(5);
        service = new QrTokenService(properties);
    }

    @Test
    void issuesATokenThatValidates() {
        QrTokenService.IssuedQr issued = service.issue(42L, "session-nonce");

        QrTokenService.QrValidation validation = service.validate(issued.token());

        assertTrue(validation.valid(), validation.reason());
        assertEquals(42L, validation.sessionId());
        assertEquals("session-nonce", validation.sessionNonce());
    }

    @Test
    void everyIssuedTokenIsUnique() {
        String first = service.issue(42L, "session-nonce").token();
        String second = service.issue(42L, "session-nonce").token();

        assertNotEquals(first, second, "The random component must make every token unique");
    }

    @Test
    void rejectsATamperedSignature() {
        QrTokenService.IssuedQr issued = service.issue(42L, "session-nonce");
        String tampered = issued.payload() + ".not-the-signature";

        QrTokenService.QrValidation validation = service.validate(tampered);

        assertFalse(validation.valid());
        assertTrue(validation.reason().toLowerCase().contains("signature"));
    }

    @Test
    void rejectsATamperedPayload() {
        QrTokenService.IssuedQr issued = service.issue(42L, "session-nonce");
        String[] parts = issued.payload().split("\\.");
        parts[1] = "9999";
        String tampered = String.join(".", parts) + "." + issued.signature();

        QrTokenService.QrValidation validation = service.validate(tampered);

        assertFalse(validation.valid());
    }

    @Test
    void rejectsAMalformedToken() {
        assertFalse(service.validate("").valid());
        assertFalse(service.validate("nonsense").valid());
        assertFalse(service.validate("a.b.c").valid());
    }

    @Test
    void rejectsAnExpiredToken() {
        properties.getQr().setTtlSeconds(0);
        QrTokenService.IssuedQr issued = service.issue(42L, "session-nonce");

        QrTokenService.QrValidation validation = service.validate(issued.token());

        assertFalse(validation.valid());
        assertTrue(validation.reason().toLowerCase().contains("expired"));
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() {
        AppProperties other = new AppProperties();
        other.setQrSecret("a-completely-different-secret-value-for-testing");
        QrTokenService.IssuedQr issued = new QrTokenService(other).issue(42L, "session-nonce");

        assertFalse(service.validate(issued.token()).valid(),
                "A token signed with a different key must never be accepted");
    }

    @Test
    void nonceComparisonIsExact() {
        assertTrue(service.nonceMatches("abc", "abc"));
        assertFalse(service.nonceMatches("abc", "abd"));
        assertFalse(service.nonceMatches("abc", null));
    }
}
