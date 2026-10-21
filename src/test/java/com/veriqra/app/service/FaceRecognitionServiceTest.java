package com.veriqra.app.service;

import com.veriqra.app.config.AppProperties;
import com.veriqra.app.service.biometric.BiometricProfile;
import com.veriqra.app.service.biometric.FaceAlignment;
import com.veriqra.app.service.biometric.FaceDetector;
import com.veriqra.app.service.biometric.FaceEmbedder;
import com.veriqra.app.service.biometric.FaceImage;
import com.veriqra.app.service.biometric.FaceRecognitionService;
import com.veriqra.app.service.biometric.HeadPose;
import com.veriqra.app.service.biometric.ScrfdDetector;
import com.veriqra.app.service.biometric.YuNetDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the server side computer vision pipeline against known portraits.
 *
 * <p>These tests exercise the real ONNX models, so they are skipped when the models have
 * not been installed. Run {@code scripts/fetch-models.ps1} first to enable them.
 *
 * <p>Every pair of models that is installed is put through the same checks, so the
 * stronger pair of the InsightFace family is held to the same standard as the permissive
 * pair of the OpenCV model zoo.
 *
 * <p>The two portraits in {@code src/test/resources/fixtures} are the official White
 * House portraits of two former presidents. They are works of the United States federal
 * government and therefore in the public domain. They were taken from the MIT licensed
 * {@code face_recognition} project.
 */
class FaceRecognitionServiceTest {

    private static Path modelsDirectory() {
        AppProperties properties = new AppProperties();
        return Path.of(properties.getBiometric().getModelsDirectory()).toAbsolutePath().normalize();
    }

    private static boolean modelsPresent() {
        Path directory = modelsDirectory();
        return Files.isRegularFile(directory.resolve(FaceRecognitionService.DETECTOR_MODEL))
                && Files.isRegularFile(directory.resolve(FaceRecognitionService.EMBEDDER_MODEL));
    }

    /** The pairs of models that are installed on this machine. */
    private static List<BiometricProfile> installedProfiles() {
        Path directory = modelsDirectory();
        return java.util.Arrays.stream(BiometricProfile.values())
                .filter(profile -> Files.isRegularFile(directory.resolve(profile.detectorFile()))
                        && Files.isRegularFile(directory.resolve(profile.embedderFile())))
                .toList();
    }

    private static FaceDetector detector(BiometricProfile profile) throws Exception {
        Path directory = modelsDirectory();
        return profile.detector() == BiometricProfile.Detector.SCRFD
                ? new ScrfdDetector(directory.resolve(profile.detectorFile()), 0.5f)
                : new YuNetDetector(directory.resolve(profile.detectorFile()), 0.5f);
    }

    private FaceImage fixture(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (input == null) {
                throw new IOException("Missing test fixture: " + name);
            }
            return FaceImage.decode(input.readAllBytes());
        }
    }

    @Test
    @EnabledIf("modelsPresent")
    void detectsExactlyOneFaceInAKnownPortrait() throws Exception {
        for (BiometricProfile profile : installedProfiles()) {
            try (FaceDetector detector = detector(profile)) {
                List<FaceDetector.DetectedFace> faces = detector.detect(fixture("portrait-a.jpg"));
                assertEquals(1, faces.size(),
                        "Exactly one face is expected in the portrait fixture for " + profile.id());
                FaceDetector.DetectedFace face = faces.get(0);
                assertTrue(face.score() > 0.8f,
                        "Detection score should be high for " + profile.id() + ", was " + face.score());
                assertTrue(face.interocularDistance() > 20d,
                        "Landmarks should describe a face of a sensible size for " + profile.id()
                                + ", interocular was " + face.interocularDistance());
            }
        }
    }

    @Test
    @EnabledIf("modelsPresent")
    void embeddingOfTheSameFaceIsStableAndMatchesItself() throws Exception {
        for (BiometricProfile profile : installedProfiles()) {
            Path directory = modelsDirectory();
            try (FaceDetector detector = detector(profile);
                 FaceEmbedder embedder = new FaceEmbedder(directory.resolve(profile.embedderFile()),
                         profile.embedder())) {

                FaceImage portrait = fixture("portrait-a.jpg");
                FaceDetector.DetectedFace face = detector.detect(portrait).get(0);

                FaceImage aligned = FaceAlignment.align(portrait, face.landmarks());
                assertEquals(FaceAlignment.CROP_SIZE, aligned.width());
                assertEquals(FaceAlignment.CROP_SIZE, aligned.height());

                float[] first = embedder.embed(aligned);
                float[] second = embedder.embed(aligned);
                assertEquals(profile.embedder().embeddingSize(), first.length);
                assertTrue(FaceEmbedder.cosineSimilarity(first, second) > 0.999d,
                        "The same face must produce an identical descriptor for " + profile.id());
            }
        }
    }

    @Test
    @EnabledIf("modelsPresent")
    void alignedCropIsSharpEnoughToPassTheQualityGate() throws Exception {
        AppProperties properties = new AppProperties();
        for (BiometricProfile profile : installedProfiles()) {
            try (FaceDetector detector = detector(profile)) {
                FaceImage portrait = fixture("portrait-a.jpg");
                FaceDetector.DetectedFace face = detector.detect(portrait).get(0);
                FaceImage aligned = FaceAlignment.align(portrait, face.landmarks());
                assertTrue(aligned.laplacianVariance() > properties.getBiometric().getMinimumSharpness(),
                        "The aligned crop should pass the sharpness gate for " + profile.id()
                                + ", variance was " + aligned.laplacianVariance());
            }
        }
    }

    @Test
    @EnabledIf("modelsPresent")
    void headPoseIsNearFrontalForAPortraitFacingTheCamera() throws Exception {
        for (BiometricProfile profile : installedProfiles()) {
            try (FaceDetector detector = detector(profile)) {
                FaceImage portrait = fixture("portrait-a.jpg");
                FaceDetector.DetectedFace face = detector.detect(portrait).get(0);
                double yaw = HeadPose.yawIndicator(face);
                assertTrue(Math.abs(yaw) < 0.5d,
                        "A frontal portrait should have a small yaw indicator for " + profile.id()
                                + ", was " + yaw);
            }
        }
    }

    @Test
    @EnabledIf("modelsPresent")
    void twoFacesInOneFrameAreDetectedSoProxyingIsRejected() throws Exception {
        for (BiometricProfile profile : installedProfiles()) {
            try (FaceDetector detector = detector(profile)) {
                FaceImage portrait = fixture("portrait-a.jpg");
                FaceImage duo = compose(portrait, portrait);
                List<FaceDetector.DetectedFace> faces = detector.detect(duo);
                assertEquals(2, faces.size(),
                        "Both pasted portraits should be detected for " + profile.id()
                                + ", found " + faces.size());
            }
        }
    }

    @Test
    @EnabledIf("modelsPresent")
    void descriptorsOfDifferentFacesAreClearlySeparated() throws Exception {
        AppProperties properties = new AppProperties();
        for (BiometricProfile profile : installedProfiles()) {
            Path directory = modelsDirectory();
            try (FaceDetector detector = detector(profile);
                 FaceEmbedder embedder = new FaceEmbedder(directory.resolve(profile.embedderFile()),
                         profile.embedder())) {

                FaceImage portrait = fixture("portrait-a.jpg");
                FaceImage second = fixture("portrait-b.jpg");

                float[] portraitDescriptor = embedder.embed(
                        FaceAlignment.align(portrait, detector.detect(portrait).get(0).landmarks()));

                List<FaceDetector.DetectedFace> secondFaces = detector.detect(second);
                assumeTrue(!secondFaces.isEmpty(), "The second fixture needs a detectable face");

                float[] secondDescriptor = embedder.embed(
                        FaceAlignment.align(second, secondFaces.get(0).landmarks()));

                double similarity = FaceEmbedder.cosineSimilarity(portraitDescriptor, secondDescriptor);
                assertTrue(similarity < properties.getBiometric().getMatchThreshold(),
                        "Different people must score below the match threshold for " + profile.id()
                                + ", similarity was " + similarity);
            }
        }
    }

    @Test
    @EnabledIf("modelsPresent")
    void theTwoDetectorsAgreeOnWhereTheFaceIs() throws Exception {
        List<BiometricProfile> installed = installedProfiles();
        assumeTrue(installed.size() > 1, "Two pairs of models have to be installed for this check");
        BiometricProfile yunet = null;
        BiometricProfile scrfd = null;
        for (BiometricProfile profile : installed) {
            if (profile.detector() == BiometricProfile.Detector.YUNET) {
                yunet = profile;
            } else {
                scrfd = profile;
            }
        }
        assumeTrue(yunet != null && scrfd != null, "One pair of each detector has to be installed");

        try (FaceDetector first = detector(yunet);
             FaceDetector second = detector(scrfd)) {
            FaceImage portrait = fixture("portrait-a.jpg");
            FaceDetector.DetectedFace one = first.detect(portrait).get(0);
            FaceDetector.DetectedFace two = second.detect(portrait).get(0);

            // The landmarks of the two detectors are reported in the same order, so the
            // eyes of one have to sit on the eyes of the other.
            double eyeDistance = Math.hypot(one.rightEyeX() - two.rightEyeX(),
                    one.rightEyeY() - two.rightEyeY());
            assertTrue(eyeDistance < 12d,
                    "The detectors should place the eyes of the face at the same spot, they differ by "
                            + eyeDistance + " pixels");
        }
    }

    /** Places two copies of the source side by side on a single canvas. */
    private static FaceImage compose(FaceImage left, FaceImage right) {
        int width = left.width() + right.width();
        int height = Math.max(left.height(), right.height());
        byte[] rgb = new byte[width * height * 3];
        for (int row = 0; row < left.height(); row++) {
            System.arraycopy(left.rgb(), row * left.width() * 3,
                    rgb, row * width * 3, left.width() * 3);
        }
        for (int row = 0; row < right.height(); row++) {
            System.arraycopy(right.rgb(), row * right.width() * 3,
                    rgb, row * width * 3 + left.width() * 3, right.width() * 3);
        }
        return new FaceImage(width, height, rgb);
    }

    @Test
    void missingModelsDegradeGracefullyInsteadOfCrashing(@TempDir Path tempDirectory) {
        AppProperties properties = new AppProperties();
        properties.getBiometric().setModelsDirectory(tempDirectory.toString());
        FaceRecognitionService service = new FaceRecognitionService(properties);

        assertFalse(service.isAvailable());
        assertTrue(service.unavailableReason().contains(tempDirectory.toString()));

        var analysis = service.analyse(List.of(), null);
        assertFalse(analysis.accepted());
        assertEquals("BIOMETRIC_UNAVAILABLE", analysis.reasonCode());
    }

    @Test
    void anUnknownProfileIsRejectedWithAClearMessage() {
        AppProperties properties = new AppProperties();
        properties.getBiometric().setProfile("no-such-profile");
        FaceRecognitionService service = new FaceRecognitionService(properties);

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, service::profile);
        assertTrue(failure.getMessage().contains("no-such-profile"));
    }

    @Test
    void theVersionOfTheModelsFollowsTheProfile() {
        AppProperties properties = new AppProperties();
        properties.getBiometric().setProfile("scrfd-arcface");
        FaceRecognitionService service = new FaceRecognitionService(properties);
        assertEquals("scrfd-10g+arcface-w600k-r50", service.modelVersion());

        properties.getBiometric().setProfile("yunet-sface");
        assertEquals("yunet-2023mar+sface-2021dec", service.modelVersion());

        properties.getBiometric().setModelVersion("custom-version");
        assertEquals("custom-version", service.modelVersion());
    }

    @Test
    void frameImageOperationsBehaveAsExpected() throws IOException {
        FaceImage portrait = fixture("portrait-a.jpg");
        assertTrue(portrait.width() > 100 && portrait.height() > 100);
        assertEquals(portrait.width() * portrait.height(), portrait.toGrayscale().length);

        FaceImage resized = portrait.resize(320, 240);
        assertEquals(320, resized.width());
        assertEquals(240, resized.height());

        FaceImage crop = portrait.crop(10, 10, 50, 50);
        assertEquals(50, crop.width());
        assertEquals(50, crop.height());

        assumeTrue(portrait.laplacianVariance() > 0d);
    }
}
