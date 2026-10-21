package com.veriqra.app.service.biometric;

import com.veriqra.app.config.AppProperties;
import com.veriqra.app.domain.dto.AttendanceDtos.BiometricStatusView;
import com.veriqra.app.domain.dto.AttendanceDtos.ChallengeView;
import com.veriqra.app.domain.dto.AttendanceDtos.EnrolmentResponse;
import com.veriqra.app.repository.AuditRepository;
import com.veriqra.app.repository.BiometricRepository;
import com.veriqra.app.repository.UserRepository;
import com.veriqra.app.security.ApiException;
import com.veriqra.app.service.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Owns the biometric enrolment and verification flow.
 *
 * <p>Everything that decides whether a student is the enrolled person happens here and
 * inside {@link FaceRecognitionService}. The client is only ever allowed to say "here
 * are some pixels".
 */
@Service
public class BiometricService {

    private static final Logger log = LoggerFactory.getLogger(BiometricService.class);

    /** Milliseconds the client should wait between two captured frames. */
    private static final int FRAME_INTERVAL_MILLIS = 320;

    /** The result of comparing a live capture with an enrolled template. */
    public record MatchOutcome(
            boolean matched,
            String reasonCode,
            String message,
            float[] descriptor,
            double similarity,
            double livenessScore,
            double qualityScore) {

        static MatchOutcome failed(String reasonCode, String message) {
            return new MatchOutcome(false, reasonCode, message, null, 0d, 0d, 0d);
        }
    }

    private final BiometricRepository repository;
    private final UserRepository users;
    private final AuditRepository audit;
    private final FaceRecognitionService face;
    private final StorageService storage;
    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();

    public BiometricService(BiometricRepository repository,
                            UserRepository users,
                            AuditRepository audit,
                            FaceRecognitionService face,
                            StorageService storage,
                            AppProperties properties) {
        this.repository = repository;
        this.users = users;
        this.audit = audit;
        this.face = face;
        this.storage = storage;
        this.properties = properties;
    }

    /** Issues a random liveness challenge for a student. */
    @Transactional
    public ChallengeView issueChallenge(long studentId, String context) {
        LivenessChallenge challenge = LivenessChallenge.random(random);
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(properties.getBiometric().getChallengeTtlSeconds());
        BiometricRepository.Challenge stored = repository.createChallenge(
                studentId, challenge.code(), issuedAt, expiresAt, context);

        return new ChallengeView(
                stored.id(),
                challenge.instruction(),
                challenge.guidance(),
                expiresAt,
                properties.getBiometric().getRequiredFrames(),
                FRAME_INTERVAL_MILLIS);
    }

    /**
     * Validates a challenge and returns the instruction it carries.
     *
     * @throws ApiException when the challenge is unknown, expired, already used or issued
     *                      to a different student
     */
    public LivenessChallenge resolveChallenge(long studentId, String challengeId) {
        BiometricRepository.Challenge challenge = repository.findChallenge(challengeId)
                .orElseThrow(() -> ApiException.badRequest("CHALLENGE_UNKNOWN",
                        "The verification request has expired. Please start again."));
        if (challenge.studentId() != studentId) {
            throw ApiException.forbidden("CHALLENGE_MISMATCH",
                    "That verification request belongs to another account.");
        }
        if (challenge.consumedAt() != null) {
            throw ApiException.conflict("CHALLENGE_USED",
                    "That verification request has already been used. Please start again.");
        }
        if (challenge.expiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("CHALLENGE_EXPIRED",
                    "The verification request has expired. Please start again.");
        }
        return LivenessChallenge.fromCode(challenge.kind());
    }

    /** Marks a challenge as used so it cannot be replayed. */
    public void consumeChallenge(String challengeId) {
        repository.consumeChallenge(challengeId);
    }

    /**
     * Enrols a face template for a student.
     *
     * @param frames     the captured frames
     * @param challengeId the challenge that was issued for this capture
     */
    @Transactional
    public EnrolmentResponse enrol(long studentId, List<FaceImage> frames, String challengeId) {
        LivenessChallenge challenge = resolveChallenge(studentId, challengeId);
        FaceRecognitionService.CaptureAnalysis analysis = face.analyse(frames, challenge);
        if (!analysis.accepted()) {
            audit.record(studentId, "BIOMETRIC_ENROLMENT_REJECTED", "student=" + studentId,
                    analysis.reasonCode(), analysis.message(), null, null);
            return new EnrolmentResponse(false, analysis.message(), "PENDING",
                    null, null, null);
        }

        byte[] embedding = toBytes(analysis.descriptor());
        Instant enrolledAt = Instant.now();
        String storagePath = storeEnrolmentImage(studentId, analysis.primaryFrame(), enrolledAt);

        repository.saveTemplate(studentId, face.modelVersion(), embedding, enrolledAt,
                analysis.usableFrames(), analysis.qualityScore(), analysis.minPairwiseSimilarity(), storagePath);
        users.markFaceVerified(studentId, enrolledAt);
        consumeChallenge(challengeId);
        audit.record(studentId, "BIOMETRIC_ENROLLED", "student=" + studentId,
                "SELF_SERVICE_ENROLMENT", "Face template enrolled and liveness verified",
                null, face.modelVersion());

        return new EnrolmentResponse(true, "Your face is verified. You can now mark attendance.",
                "VERIFIED", analysis.qualityScore(), analysis.minPairwiseSimilarity(), enrolledAt);
    }

    /**
     * Compares a live capture with the enrolled template of a student.
     *
     * @param frames      the captured frames
     * @param challengeId the challenge that was issued for this capture
     */
    public MatchOutcome verify(long studentId, List<FaceImage> frames, String challengeId) {
        Optional<BiometricRepository.FaceTemplate> template = repository.findTemplate(studentId);
        if (template.isEmpty()) {
            return MatchOutcome.failed("NO_TEMPLATE",
                    "Your face has not been verified yet. Complete the identity check first.");
        }
        LivenessChallenge challenge = resolveChallenge(studentId, challengeId);
        FaceRecognitionService.CaptureAnalysis analysis = face.analyse(frames, challenge);
        if (!analysis.accepted()) {
            return MatchOutcome.failed(analysis.reasonCode(), analysis.message());
        }

        float[] enrolled = toFloats(template.get().embedding());
        if (!face.modelVersion().equals(template.get().modelVersion())) {
            // The template was enrolled with another pair of models, so the two
            // descriptors cannot be compared. The student has to run the check again.
            return MatchOutcome.failed("MODEL_CHANGED",
                    "Your face was enrolled with another version of the face models. "
                            + "Please run the identity check again to mark your attendance.");
        }
        double similarity = face.similarity(analysis.descriptor(), enrolled);
        double threshold = face.matchThreshold();
        if (similarity < threshold) {
            return new MatchOutcome(false, "FACE_MISMATCH",
                    "The face did not match your enrolment. Please try again, or ask your teacher for a correction.",
                    analysis.descriptor(), similarity, analysis.livenessScore(), analysis.qualityScore());
        }

        return new MatchOutcome(true, "OK", "Face verified", analysis.descriptor(), similarity,
                analysis.livenessScore(), analysis.qualityScore());
    }

    /** The biometric state of a student. */
    public BiometricStatusView status(long studentId) {
        Optional<BiometricRepository.FaceTemplate> template = repository.findTemplate(studentId);
        return new BiometricStatusView(
                template.isPresent() ? "VERIFIED" : "PENDING",
                template.map(BiometricRepository.FaceTemplate::enrolledAt).orElse(null),
                template.map(BiometricRepository.FaceTemplate::modelVersion).orElse(null),
                template.map(BiometricRepository.FaceTemplate::sampleCount).orElse(0),
                face.isAvailable(),
                face.isAvailable() ? null : face.unavailableReason(),
                face.modelVersion());
    }

    /** Removes the biometric data of a student at their request. */
    @Transactional
    public void revoke(long studentId) {
        Optional<BiometricRepository.FaceTemplate> template = repository.findTemplate(studentId);
        repository.deleteTemplate(studentId);
        users.markFaceRevoked(studentId);
        template.map(BiometricRepository.FaceTemplate::storagePath)
                .ifPresent(path -> storage.delete(path));
        audit.record(studentId, "BIOMETRIC_REVOKED", "student=" + studentId,
                "STUDENT_REQUEST", "Biometric template removed at the request of the student", null, null);
    }

    /** The enrolled descriptor of a student, if any. */
    public Optional<BiometricRepository.FaceTemplate> template(long studentId) {
        return repository.findTemplate(studentId);
    }

    private String storeEnrolmentImage(long studentId, FaceImage frame, Instant enrolledAt) {
        if (frame == null) {
            return null;
        }
        try {
            byte[] jpeg = frame.toJpegBytes(0.85f);
            String path = "biometrics/" + studentId + "/" + enrolledAt.toEpochMilli() + ".jpg";
            return storage.store(path, jpeg, "image/jpeg");
        } catch (IOException ex) {
            // The template is the security artefact. A missing reference image must not
            // block the enrolment, so the failure is logged and the path stays empty.
            log.warn("Unable to store the enrolment image for student {}", studentId, ex);
            return null;
        }
    }

    /** Packs a descriptor for storage. */
    public static byte[] toBytes(float[] descriptor) {
        byte[] bytes = new byte[descriptor.length * 4];
        for (int i = 0; i < descriptor.length; i++) {
            bytes[i * 4] = (byte) (Float.floatToIntBits(descriptor[i]) >>> 24);
            bytes[i * 4 + 1] = (byte) (Float.floatToIntBits(descriptor[i]) >>> 16);
            bytes[i * 4 + 2] = (byte) (Float.floatToIntBits(descriptor[i]) >>> 8);
            bytes[i * 4 + 3] = (byte) Float.floatToIntBits(descriptor[i]);
        }
        return bytes;
    }

    /** Unpacks a descriptor read from storage. */
    public static float[] toFloats(byte[] bytes) {
        int count = bytes.length / 4;
        float[] descriptor = new float[count];
        for (int i = 0; i < count; i++) {
            int value = ((bytes[i * 4] & 0xFF) << 24)
                    | ((bytes[i * 4 + 1] & 0xFF) << 16)
                    | ((bytes[i * 4 + 2] & 0xFF) << 8)
                    | (bytes[i * 4 + 3] & 0xFF);
            descriptor[i] = Float.intBitsToFloat(value);
        }
        return descriptor;
    }

    /** Deletes expired challenges and tickets. */
    public int purgeExpired() {
        Instant now = Instant.now().minus(1, ChronoUnit.DAYS);
        int challenges = repository.deleteExpiredChallenges(now);
        int tickets = repository.deleteExpiredTickets(now);
        return challenges + tickets;
    }
}
