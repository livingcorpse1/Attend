package com.veriqra.app.service.biometric;

import java.util.List;

/**
 * A face detector.
 *
 * <p>Two detectors are supported and both return the same description of a face, so the
 * rest of the pipeline does not care which one is in use:
 * <ul>
 *   <li>{@link YuNetDetector} - the YuNet model of the OpenCV model zoo. Small, fast and
 *       published under a permissive licence.</li>
 *   <li>{@link ScrfdDetector} - the SCRFD model of InsightFace. It is the most accurate
 *       of the two, in particular on small and badly lit faces.</li>
 * </ul>
 *
 * <p>The landmarks are always reported in the same order: right eye, left eye, nose tip,
 * right mouth corner and left mouth corner. That is the order the recognition models
 * expect, and the order the alignment step uses.
 */
public interface FaceDetector extends AutoCloseable {

    /** One detected face, expressed in the coordinate system of the source image. */
    record DetectedFace(float x, float y, float width, float height, float[] landmarks, float score) {

        /** Landmark accessors, in the order the detectors produce them. */
        public float rightEyeX() {
            return landmarks[0];
        }

        public float rightEyeY() {
            return landmarks[1];
        }

        public float leftEyeX() {
            return landmarks[2];
        }

        public float leftEyeY() {
            return landmarks[3];
        }

        public float noseX() {
            return landmarks[4];
        }

        public float noseY() {
            return landmarks[5];
        }

        public float rightMouthX() {
            return landmarks[8];
        }

        public float rightMouthY() {
            return landmarks[9];
        }

        public float leftMouthX() {
            return landmarks[10];
        }

        public float leftMouthY() {
            return landmarks[11];
        }

        /** Distance between the two eye centres in pixels. */
        public double interocularDistance() {
            double dx = leftEyeX() - rightEyeX();
            double dy = leftEyeY() - rightEyeY();
            return Math.hypot(dx, dy);
        }
    }

    /**
     * Detects every face in the image.
     *
     * @return the detections ordered by descending confidence
     */
    List<DetectedFace> detect(FaceImage image) throws ai.onnxruntime.OrtException;

    /** The name of the model behind this detector. */
    String modelName();

    @Override
    void close() throws ai.onnxruntime.OrtException;
}
