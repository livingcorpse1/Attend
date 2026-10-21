package com.veriqra.app.service.biometric;

import java.util.Locale;

/**
 * The pairs of models the biometric pipeline can run with.
 *
 * <p>Two pairs are supported:
 * <ul>
 *   <li><b>yunet-sface</b> - YuNet and SFace from the OpenCV model zoo. They are small,
 *       quick and published under a permissive licence, which makes them the safe
 *       default for a deployment that may grow into a commercial one.</li>
 *   <li><b>scrfd-arcface</b> - SCRFD and ArcFace from the InsightFace model family.
 *       SCRFD finds far more faces in hard pictures and ArcFace separates identities
 *       much better, so this is the strongest pair. The weights of the InsightFace
 *       family are published for non commercial research, which has to be settled
 *       before a deployment that is not a college project uses them.</li>
 * </ul>
 *
 * <p>The pair is part of the version of a face template: a template enrolled with one
 * pair cannot be compared with a descriptor of the other.
 */
public enum BiometricProfile {

    /**
     * The permissive pair of the OpenCV model zoo.
     *
     * <p>The version names the weights, and not the export. The detection model is the
     * export of May 2026, which carries the weights of March 2023 and takes a frame of
     * any size, so the templates that were enrolled with the older export stay valid.
     */
    YUNET_SFACE("yunet-sface",
            "face_detection_yunet_2026may.onnx",
            "face_recognition_sface_2021dec.onnx",
            "yunet-2023mar+sface-2021dec",
            Detector.YUNET,
            FaceEmbedder.Profile.SFACE,
            0.80f),

    /** The strongest pair of the InsightFace model family. */
    SCRFD_ARCFACE("scrfd-arcface",
            "scrfd_10g.onnx",
            "arcface_w600k_r50.onnx",
            "scrfd-10g+arcface-w600k-r50",
            Detector.SCRFD,
            FaceEmbedder.Profile.ARCFACE,
            0.50f);

    /** The detector of a pair. */
    public enum Detector {
        YUNET, SCRFD
    }

    private final String id;
    private final String detectorFile;
    private final String embedderFile;
    private final String version;
    private final Detector detector;
    private final FaceEmbedder.Profile embedder;
    private final float detectionThreshold;

    BiometricProfile(String id, String detectorFile, String embedderFile, String version,
                      Detector detector, FaceEmbedder.Profile embedder, float detectionThreshold) {
        this.id = id;
        this.detectorFile = detectorFile;
        this.embedderFile = embedderFile;
        this.version = version;
        this.detector = detector;
        this.embedder = embedder;
        this.detectionThreshold = detectionThreshold;
    }

    /** The name the configuration uses. */
    public String id() {
        return id;
    }

    /** The file name of the detection model. */
    public String detectorFile() {
        return detectorFile;
    }

    /** The file name of the recognition model. */
    public String embedderFile() {
        return embedderFile;
    }

    /** The version string that is stored with every face template. */
    public String version() {
        return version;
    }

    /** The detector of the pair. */
    public Detector detector() {
        return detector;
    }

    /** The recognition model of the pair. */
    public FaceEmbedder.Profile embedder() {
        return embedder;
    }

    /**
     * The confidence a detection has to reach before it is looked at.
     *
     * <p>The two detectors report their confidence differently, so the threshold of a
     * pair belongs to the pair.
     */
    public float detectionThreshold() {
        return detectionThreshold;
    }

    /**
     * The pair a configuration names.
     *
     * @throws IllegalArgumentException when the name is not known
     */
    public static BiometricProfile fromId(String id) {
        if (id != null) {
            String wanted = id.trim().toLowerCase(Locale.ROOT);
            for (BiometricProfile profile : values()) {
                if (profile.id.equals(wanted)) {
                    return profile;
                }
            }
        }
        throw new IllegalArgumentException("Unknown biometric model profile '" + id
                + "'. Known profiles are yunet-sface and scrfd-arcface.");
    }
}
