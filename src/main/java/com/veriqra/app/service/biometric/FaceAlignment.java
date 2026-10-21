package com.veriqra.app.service.biometric;

/**
 * Geometric alignment of a detected face onto the canonical crop the recognition model expects.
 *
 * <p>SFace was trained on 112x112 crops produced by an Umeyama similarity transform from
 * the five YuNet landmarks onto a fixed reference template. Reproducing that transform
 * exactly is what keeps the embedding comparable with the reference implementation.
 */
public final class FaceAlignment {

    /** The reference landmark template used by SFace, in 112x112 pixel coordinates. */
    private static final double[] TEMPLATE = {
            38.2946, 51.6963,   // right eye
            73.5318, 51.5014,   // left eye
            56.0252, 71.7366,   // nose tip
            41.5493, 92.3655,   // right mouth corner
            70.7299, 92.2041    // left mouth corner
    };

    /** Output size of the aligned crop. */
    public static final int CROP_SIZE = 112;

    private FaceAlignment() {
    }

    /**
     * Aligns the face onto the canonical template.
     *
     * @param source      the full frame the face was detected in
     * @param landmarks   ten coordinates, ordered as right eye, left eye, nose, right mouth, left mouth
     * @return a 112x112 RGB crop
     */
    public static FaceImage align(FaceImage source, float[] landmarks) {
        double[] srcX = new double[5];
        double[] srcY = new double[5];
        for (int i = 0; i < 5; i++) {
            srcX[i] = landmarks[i * 2];
            srcY[i] = landmarks[i * 2 + 1];
        }
        double[] dstX = new double[5];
        double[] dstY = new double[5];
        for (int i = 0; i < 5; i++) {
            dstX[i] = TEMPLATE[i * 2];
            dstY[i] = TEMPLATE[i * 2 + 1];
        }

        double[] matrix = similarityTransform(srcX, srcY, dstX, dstY);
        return warp(source, matrix);
    }

    /**
     * Computes the Umeyama similarity transform that maps the source landmarks onto the target.
     *
     * <p>The closed form below is the least squares optimum for a scale, rotation and
     * translation, and matches the reference implementation shipped with OpenCV: the
     * rotation is the one that maximises {@code trace(R * B)} and the scale follows from
     * {@code trace(R * B) / variance(src)}.
     *
     * @return a 2x3 affine matrix {@code [a, b, c, d, e, f]} where
     *         {@code x' = a * x + b * y + c} and {@code y' = d * x + e * y + f}
     */
    public static double[] similarityTransform(double[] srcX, double[] srcY, double[] dstX, double[] dstY) {
        int count = srcX.length;
        double srcMeanX = mean(srcX);
        double srcMeanY = mean(srcY);
        double dstMeanX = mean(dstX);
        double dstMeanY = mean(dstY);

        // Cross covariance between the destination and the source landmarks.
        double a00 = 0d;
        double a01 = 0d;
        double a10 = 0d;
        double a11 = 0d;
        double sourceVariance = 0d;

        for (int i = 0; i < count; i++) {
            double sx = srcX[i] - srcMeanX;
            double sy = srcY[i] - srcMeanY;
            double dx = dstX[i] - dstMeanX;
            double dy = dstY[i] - dstMeanY;
            a00 += dx * sx;
            a01 += dx * sy;
            a10 += dy * sx;
            a11 += dy * sy;
            sourceVariance += sx * sx + sy * sy;
        }
        a00 /= count;
        a01 /= count;
        a10 /= count;
        a11 /= count;
        sourceVariance /= count;

        if (sourceVariance < 1e-12) {
            return new double[]{1d, 0d, dstMeanX - srcMeanX, 0d, 1d, dstMeanY - srcMeanY};
        }

        // A negative determinant means the landmarks are mirrored relative to the
        // template. Mirroring the source restores a well posed fit.
        boolean mirrored = (a00 * a11 - a01 * a10) < 0d;
        if (mirrored) {
            double[] mirroredX = new double[count];
            for (int i = 0; i < count; i++) {
                mirroredX[i] = 2d * srcMeanX - srcX[i];
            }
            double[] transform = solve(mirroredX, srcY, dstX, dstY, srcMeanX, srcMeanY,
                    dstMeanX, dstMeanY, sourceVariance);
            // Compose the fitted transform with the mirror that produced it.
            return new double[]{-transform[0], transform[1], transform[2], -transform[3], transform[4], transform[5]};
        }
        return solve(srcX, srcY, dstX, dstY, srcMeanX, srcMeanY, dstMeanX, dstMeanY, sourceVariance);
    }

    private static double[] solve(double[] srcX, double[] srcY, double[] dstX, double[] dstY,
                                  double srcMeanX, double srcMeanY,
                                  double dstMeanX, double dstMeanY,
                                  double sourceVariance) {
        int count = srcX.length;
        double a00 = 0d;
        double a01 = 0d;
        double a10 = 0d;
        double a11 = 0d;
        for (int i = 0; i < count; i++) {
            double sx = srcX[i] - srcMeanX;
            double sy = srcY[i] - srcMeanY;
            double dx = dstX[i] - dstMeanX;
            double dy = dstY[i] - dstMeanY;
            a00 += dx * sx;
            a01 += dx * sy;
            a10 += dy * sx;
            a11 += dy * sy;
        }
        a00 /= count;
        a01 /= count;
        a10 /= count;
        a11 /= count;

        double x = a00 + a11;
        double y = a10 - a01;
        double magnitude = Math.hypot(x, y);
        double theta = Math.atan2(y, x);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double scale = magnitude / sourceVariance;

        double tx = dstMeanX - scale * (cos * srcMeanX - sin * srcMeanY);
        double ty = dstMeanY - scale * (sin * srcMeanX + cos * srcMeanY);

        return new double[]{scale * cos, -scale * sin, tx, scale * sin, scale * cos, ty};
    }

    /**
     * Warps the source image with the given affine matrix using inverse mapping and bilinear sampling.
     *
     * @param matrix a 2x3 affine matrix
     */
    public static FaceImage warp(FaceImage source, double[] matrix) {
        double determinant = matrix[0] * matrix[4] - matrix[1] * matrix[3];
        if (Math.abs(determinant) < 1e-12) {
            return source.resize(CROP_SIZE, CROP_SIZE);
        }
        double inverseA = matrix[4] / determinant;
        double inverseB = -matrix[1] / determinant;
        double inverseC = -matrix[3] / determinant;
        double inverseD = matrix[0] / determinant;
        double inverseE = (matrix[1] * matrix[5] - matrix[4] * matrix[2]) / determinant;
        double inverseF = (matrix[3] * matrix[2] - matrix[0] * matrix[5]) / determinant;

        byte[] out = new byte[CROP_SIZE * CROP_SIZE * 3];
        for (int y = 0; y < CROP_SIZE; y++) {
            for (int x = 0; x < CROP_SIZE; x++) {
                double sourceX = inverseA * x + inverseB * y + inverseE;
                double sourceY = inverseC * x + inverseD * y + inverseF;
                int x0 = (int) Math.floor(sourceX);
                int y0 = (int) Math.floor(sourceY);
                double fx = sourceX - x0;
                double fy = sourceY - y0;
                int index = (y * CROP_SIZE + x) * 3;
                for (int channel = 0; channel < 3; channel++) {
                    double top = mix(sample(source, x0, y0, channel), sample(source, x0 + 1, y0, channel), fx);
                    double bottom = mix(sample(source, x0, y0 + 1, channel), sample(source, x0 + 1, y0 + 1, channel), fx);
                    out[index + channel] = (byte) Math.round(mix(top, bottom, fy));
                }
            }
        }
        return new FaceImage(CROP_SIZE, CROP_SIZE, out);
    }

    private static double sample(FaceImage image, int x, int y, int channel) {
        int clampedX = Math.max(0, Math.min(image.width() - 1, x));
        int clampedY = Math.max(0, Math.min(image.height() - 1, y));
        return image.rgb()[(clampedY * image.width() + clampedX) * 3 + channel] & 0xFF;
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double mean(double[] values) {
        double sum = 0d;
        for (double value : values) {
            sum += value;
        }
        return values.length == 0 ? 0d : sum / values.length;
    }
}
