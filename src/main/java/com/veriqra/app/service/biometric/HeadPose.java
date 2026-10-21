package com.veriqra.app.service.biometric;

import com.veriqra.app.service.biometric.FaceDetector.DetectedFace;

/**
 * Head pose estimation from the five YuNet landmarks.
 *
 * <p>Only the horizontal rotation (yaw) is estimated. The nose tip offset from the
 * midpoint of the eyes, measured in inter-ocular distance units, is a stable and cheap
 * proxy: it is close to zero for a frontal face and grows in magnitude as the head
 * rotates. The sign is well defined because YuNet reports the person's right eye first,
 * which appears on the left of an unmirrored camera image.
 */
public final class HeadPose {

    private HeadPose() {
    }

    /**
     * Signed yaw indicator.
     *
     * @return a negative value when the face is turned towards the person's right, a
     *         positive value when it is turned towards the person's left
     */
    public static double yawIndicator(DetectedFace face) {
        double distance = face.interocularDistance();
        if (distance < 1e-6) {
            return 0d;
        }
        double eyeMidpoint = (face.rightEyeX() + face.leftEyeX()) / 2d;
        return (face.noseX() - eyeMidpoint) / distance;
    }

    /** Width of the mouth relative to the distance between the eyes. */
    public static double mouthRatio(DetectedFace face) {
        double distance = face.interocularDistance();
        if (distance < 1e-6) {
            return 0d;
        }
        double mouthWidth = Math.hypot(face.leftMouthX() - face.rightMouthX(),
                face.leftMouthY() - face.rightMouthY());
        return mouthWidth / distance;
    }

    /** Vertical offset of the nose below the eye line, in inter-ocular distance units. */
    public static double pitchIndicator(DetectedFace face) {
        double distance = face.interocularDistance();
        if (distance < 1e-6) {
            return 0d;
        }
        double eyeLine = (face.rightEyeY() + face.leftEyeY()) / 2d;
        return (face.noseY() - eyeLine) / distance;
    }
}
