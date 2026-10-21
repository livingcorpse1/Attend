package com.veriqra.app.service.biometric;

import ai.onnxruntime.OrtException;
import com.veriqra.app.config.AppProperties;
import com.veriqra.app.service.biometric.FaceDetector.DetectedFace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The biometric decision point of the platform.
 *
 * <p>Everything in this class runs on the server. The browser supplies pixels and nothing
 * else: it cannot claim that a liveness check passed, that a face was detected, or that a
 * match was good enough. A student becomes present only when this service says so.
 *
 * <p>The analysis of one capture has three independent gates:
 * <ol>
 *   <li><b>Frame quality</b> - exactly one face, large enough, in focus and correctly exposed.</li>
 *   <li><b>Liveness</b> - the head pose must actually change in the direction the server
 *       asked for, and the frames must not be identical stills.</li>
 *   <li><b>Identity</b> - the descriptor must match the enrolled template.</li>
 * </ol>
 *
 * <p>The ONNX models are loaded lazily. When they are missing the service stays
 * available but reports {@code BIOMETRIC_UNAVAILABLE}, so the rest of the platform keeps
 * working and the operator gets an actionable message instead of a crash.
 */
@Service
public class FaceRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(FaceRecognitionService.class);

    /** File names of the two models the pipeline needs. */
    public static final String DETECTOR_MODEL = "face_detection_yunet_2023mar.onnx";
    public static final String EMBEDDER_MODEL = "face_recognition_sface_2021dec.onnx";

    /** Outcome of analysing one capture. */
    public record CaptureAnalysis(
            boolean accepted,
            String reasonCode,
            String message,
            float[] descriptor,
            double livenessScore,
            double qualityScore,
            double minPairwiseSimilarity,
            int usableFrames,
            DetectedFace primaryFace,
            FaceImage primaryFrame) {

        static CaptureAnalysis rejected(String reasonCode, String message) {
            return new CaptureAnalysis(false, reasonCode, message, null, 0d, 0d, 0d, 0, null, null);
        }
    }

    private final AppProperties properties;
    private final Object lock = new Object();

    private volatile FaceDetector detector;
    private volatile FaceEmbedder embedder;
    private volatile String unavailableReason;

    public FaceRecognitionService(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * Analyses a capture against a liveness challenge.
     *
     * @param frames    the frames captured by the client, in capture order
     * @param challenge the challenge the student was asked to perform
     */
    public CaptureAnalysis analyse(List<FaceImage> frames, LivenessChallenge challenge) {
        if (!isAvailable()) {
            return CaptureAnalysis.rejected("BIOMETRIC_UNAVAILABLE", unavailableReason());
        }
        if (frames == null || frames.size() < properties.getBiometric().getRequiredFrames()) {
            return CaptureAnalysis.rejected("TOO_FEW_FRAMES",
                    "Not enough frames were captured. Please hold the camera steady and try again.");
        }

        List<FaceImage> usable = new ArrayList<>(frames.size());
        List<DetectedFace> faces = new ArrayList<>(frames.size());
        List<Float> sharpnessValues = new ArrayList<>(frames.size());

        for (FaceImage frame : frames) {
            List<DetectedFace> detected;
            try {
                detected = detector.detect(frame);
            } catch (OrtException ex) {
                log.warn("Face detection failed", ex);
                return CaptureAnalysis.rejected("DETECTION_FAILED",
                        "The face check could not be completed. Please try again.");
            }
            if (detected.isEmpty()) {
                return CaptureAnalysis.rejected("NO_FACE",
                        "No face was detected. Move into the frame and make sure the lighting is even.");
            }
            if (detected.size() > 1) {
                return CaptureAnalysis.rejected("MULTIPLE_FACES",
                        "More than one face was detected. Only the student taking attendance may be in frame.");
            }
            DetectedFace face = detected.get(0);

            double widthRatio = (double) face.width() / frame.width();
            if (widthRatio < properties.getBiometric().getMinimumFaceWidthRatio()) {
                return CaptureAnalysis.rejected("FACE_TOO_SMALL",
                        "Move closer to the camera so your face fills more of the frame.");
            }

            FaceImage aligned = FaceAlignment.align(frame, face.landmarks());
            double sharpness = aligned.laplacianVariance();
            if (sharpness < properties.getBiometric().getMinimumSharpness()) {
                return CaptureAnalysis.rejected("IMAGE_BLURRY",
                        "The camera image is too blurry. Hold the device steady and focus on your face.");
            }
            double brightness = frame.meanLuma();
            if (brightness < properties.getBiometric().getMinimumBrightness()) {
                return CaptureAnalysis.rejected("IMAGE_TOO_DARK",
                        "The scene is too dark. Face a light source and try again.");
            }
            if (brightness > properties.getBiometric().getMaximumBrightness()) {
                return CaptureAnalysis.rejected("IMAGE_TOO_BRIGHT",
                        "The image is overexposed. Move away from direct light and try again.");
            }

            usable.add(frame);
            faces.add(face);
            sharpnessValues.add((float) sharpness);
        }

        LivenessOutcome liveness = evaluateLiveness(usable, faces, challenge);
        if (!liveness.passed()) {
            return CaptureAnalysis.rejected(liveness.reasonCode(), liveness.message());
        }

        List<float[]> descriptors = new ArrayList<>(usable.size());
        try {
            for (int i = 0; i < usable.size(); i++) {
                FaceImage aligned = FaceAlignment.align(usable.get(i), faces.get(i).landmarks());
                descriptors.add(embedder.embed(aligned));
            }
        } catch (OrtException ex) {
            log.warn("Face embedding failed", ex);
            return CaptureAnalysis.rejected("EMBEDDING_FAILED",
                    "The face check could not be completed. Please try again.");
        }

        double minSimilarity = 1d;
        for (int i = 0; i < descriptors.size(); i++) {
            for (int j = i + 1; j < descriptors.size(); j++) {
                double similarity = FaceEmbedder.cosineSimilarity(descriptors.get(i), descriptors.get(j));
                minSimilarity = Math.min(minSimilarity, similarity);
            }
        }
        if (minSimilarity < properties.getBiometric().getFrameConsistencyThreshold()) {
            return CaptureAnalysis.rejected("FRAMES_INCONSISTENT",
                    "The captured frames did not show the same person consistently. Please try again.");
        }

        float[] descriptor = FaceEmbedder.average(descriptors);
        double qualityScore = average(sharpnessValues)
                / (properties.getBiometric().getMinimumSharpness() * 4d);
        double livenessScore = Math.min(1d, liveness.achievedDelta()
                / (properties.getBiometric().getLivenessYawDelta() * 2d));

        int bestIndex = indexOfBestFrame(faces, liveness.peakIndex());
        return new CaptureAnalysis(true, "OK", "Face verified", descriptor, livenessScore,
                Math.min(1d, qualityScore), minSimilarity, usable.size(), faces.get(bestIndex), usable.get(bestIndex));
    }

    /** Compares a live descriptor with an enrolled template. */
    public double similarity(float[] liveDescriptor, float[] enrolledDescriptor) {
        return FaceEmbedder.cosineSimilarity(liveDescriptor, enrolledDescriptor);
    }

    /** The threshold a live descriptor must reach to be accepted as the same person. */
    public float matchThreshold() {
        return properties.getBiometric().getMatchThreshold();
    }

    /** True when the models are present and the pipeline can run. */
    public boolean isAvailable() {
        return loadEngine() != null;
    }

    /** Human readable explanation of why the pipeline is unavailable. */
    public String unavailableReason() {
        if (unavailableReason != null) {
            return unavailableReason;
        }
        return "The face verification models are not installed on this server.";
    }

    /** The directory the models are loaded from. */
    public Path modelsDirectory() {
        return Path.of(properties.getBiometric().getModelsDirectory()).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ internals

    /** Result of the liveness gate. */
    private record LivenessOutcome(boolean passed, String reasonCode, String message,
                                   double achievedDelta, int peakIndex) {
    }

    private FaceDetector loadEngine() {
        if (detector != null && embedder != null) {
            return detector;
        }
        synchronized (lock) {
            if (detector != null && embedder != null) {
                return detector;
            }
            Path directory = modelsDirectory();
            Path detectorPath = directory.resolve(DETECTOR_MODEL);
            Path embedderPath = directory.resolve(EMBEDDER_MODEL);
            if (!Files.isRegularFile(detectorPath) || !Files.isRegularFile(embedderPath)) {
                unavailableReason = "The face verification models are missing from " + directory
                        + ". Run scripts/fetch-models.ps1 to install them.";
                log.warn("Biometric models are not installed under {}", directory);
                return null;
            }
            try {
                detector = new FaceDetector(detectorPath, properties.getBiometric().getDetectionThreshold());
                embedder = new FaceEmbedder(embedderPath);
                unavailableReason = null;
                log.info("Biometric pipeline ready, models loaded from {}", directory);
                return detector;
            } catch (OrtException | IOException ex) {
                unavailableReason = "The face verification models could not be loaded: " + ex.getMessage();
                log.error("Biometric pipeline failed to start", ex);
                return null;
            }
        }
    }

    private LivenessOutcome evaluateLiveness(List<FaceImage> frames, List<DetectedFace> faces,
                                              LivenessChallenge challenge) {
        int count = frames.size();
        int baselineWindow = Math.max(1, count / 3);

        // The most frontal frame of the opening window is the reference pose.
        int baselineIndex = 0;
        double baselineYaw = Double.MAX_VALUE;
        for (int i = 0; i < baselineWindow; i++) {
            double yaw = HeadPose.yawIndicator(faces.get(i));
            if (Math.abs(yaw) < Math.abs(baselineYaw)) {
                baselineYaw = yaw;
                baselineIndex = i;
            }
        }

        int peakIndex = baselineIndex;
        double extreme = baselineYaw;
        for (int i = 0; i < count; i++) {
            double yaw = HeadPose.yawIndicator(faces.get(i));
            if (challenge == LivenessChallenge.TURN_LEFT ? yaw > extreme : yaw < extreme) {
                extreme = yaw;
                peakIndex = i;
            }
        }

        double delta = challenge == LivenessChallenge.TURN_LEFT ? extreme - baselineYaw : baselineYaw - extreme;
        double required = properties.getBiometric().getLivenessYawDelta();

        if (delta < required) {
            if (peakIndex < baselineWindow) {
                return new LivenessOutcome(false, "LIVENESS_NOT_STARTED",
                        "Please look straight at the camera first, then perform the requested movement.",
                        delta, peakIndex);
            }
            return new LivenessOutcome(false, "LIVENESS_FAILED",
                    "The requested head movement was not detected. " + challenge.guidance(),
                    delta, peakIndex);
        }

        // Consecutive frames must differ, otherwise the capture is a still image.
        double motion = 0d;
        int motionSamples = 0;
        for (int i = 1; i < count; i++) {
            motion += FaceImage.meanAbsoluteDifference(frames.get(i - 1), frames.get(i));
            motionSamples++;
        }
        motion = motionSamples == 0 ? 0d : motion / motionSamples;
        if (motion < 1.2d) {
            return new LivenessOutcome(false, "LIVENESS_STATIC",
                    "The captured frames were identical. A live camera is required for verification.",
                    delta, peakIndex);
        }

        return new LivenessOutcome(true, "OK", "Liveness confirmed", delta, peakIndex);
    }

    private int indexOfBestFrame(List<DetectedFace> faces, int preferredIndex) {
        int index = Math.max(0, Math.min(faces.size() - 1, preferredIndex));
        double bestScore = faces.get(index).score();
        for (int i = 0; i < faces.size(); i++) {
            if (faces.get(i).score() > bestScore) {
                bestScore = faces.get(i).score();
                index = i;
            }
        }
        return index;
    }

    private static double average(List<Float> values) {
        if (values.isEmpty()) {
            return 0d;
        }
        double sum = 0d;
        for (Float value : values) {
            sum += value;
        }
        return sum / values.size();
    }
}
