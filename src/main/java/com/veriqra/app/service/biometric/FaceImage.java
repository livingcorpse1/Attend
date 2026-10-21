package com.veriqra.app.service.biometric;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * A plain RGB raster used by the biometric pipeline.
 *
 * <p>The pipeline deliberately avoids a full imaging framework: it only needs decoding,
 * bilinear resampling, cropping, luminance statistics and re-encoding. Keeping this class
 * small makes the inference code easy to audit, which matters because it decides whether
 * a student is marked present.
 */
public final class FaceImage {

    private final int width;
    private final int height;
    private final byte[] rgb;

    public FaceImage(int width, int height, byte[] rgb) {
        if (rgb.length != width * height * 3) {
            throw new IllegalArgumentException("Pixel buffer does not match the declared dimensions");
        }
        this.width = width;
        this.height = height;
        this.rgb = rgb;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public byte[] rgb() {
        return rgb;
    }

    /**
     * Decodes an encoded image (JPEG, PNG, BMP, GIF) into an RGB raster.
     *
     * @throws IOException when the bytes cannot be decoded
     */
    public static FaceImage decode(byte[] encoded) throws IOException {
        try (InputStream input = new ByteArrayInputStream(encoded)) {
            BufferedImage source = ImageIO.read(input);
            if (source == null) {
                throw new IOException("Unsupported or corrupt image payload");
            }
            return fromBuffered(source);
        }
    }

    public static FaceImage fromBuffered(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        byte[] rgb = new byte[width * height * 3];
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            int offset = y * width * 3;
            for (int x = 0; x < width; x++) {
                int argb = row[x];
                rgb[offset + x * 3] = (byte) ((argb >> 16) & 0xFF);
                rgb[offset + x * 3 + 1] = (byte) ((argb >> 8) & 0xFF);
                rgb[offset + x * 3 + 2] = (byte) (argb & 0xFF);
            }
        }
        return new FaceImage(width, height, rgb);
    }

    public int redAt(int x, int y) {
        return rgb[(y * width + x) * 3] & 0xFF;
    }

    public int greenAt(int x, int y) {
        return rgb[(y * width + x) * 3 + 1] & 0xFF;
    }

    public int blueAt(int x, int y) {
        return rgb[(y * width + x) * 3 + 2] & 0xFF;
    }

    /** Bilinear resize to the requested size. */
    public FaceImage resize(int targetWidth, int targetHeight) {
        byte[] out = new byte[targetWidth * targetHeight * 3];
        double scaleX = (double) width / targetWidth;
        double scaleY = (double) height / targetHeight;
        for (int y = 0; y < targetHeight; y++) {
            double sourceY = (y + 0.5) * scaleY - 0.5;
            int y0 = (int) Math.floor(sourceY);
            double fy = sourceY - y0;
            int y1 = clamp(y0 + 1, 0, height - 1);
            y0 = clamp(y0, 0, height - 1);
            for (int x = 0; x < targetWidth; x++) {
                double sourceX = (x + 0.5) * scaleX - 0.5;
                int x0 = (int) Math.floor(sourceX);
                double fx = sourceX - x0;
                int x1 = clamp(x0 + 1, 0, width - 1);
                x0 = clamp(x0, 0, width - 1);
                for (int channel = 0; channel < 3; channel++) {
                    double top = mix(channelAt(x0, y0, channel), channelAt(x1, y0, channel), fx);
                    double bottom = mix(channelAt(x0, y1, channel), channelAt(x1, y1, channel), fx);
                    out[(y * targetWidth + x) * 3 + channel] = (byte) Math.round(mix(top, bottom, fy));
                }
            }
        }
        return new FaceImage(targetWidth, targetHeight, out);
    }

    /** Extracts a rectangular crop, clamped to the image bounds. */
    public FaceImage crop(int x, int y, int cropWidth, int cropHeight) {
        int x0 = clamp(x, 0, width - 1);
        int y0 = clamp(y, 0, height - 1);
        int w = clamp(cropWidth, 1, width - x0);
        int h = clamp(cropHeight, 1, height - y0);
        byte[] out = new byte[w * h * 3];
        for (int row = 0; row < h; row++) {
            System.arraycopy(rgb, ((y0 + row) * width + x0) * 3, out, row * w * 3, w * 3);
        }
        return new FaceImage(w, h, out);
    }

    /** Converts to an 8 bit luminance plane using the ITU-R BT.601 weights. */
    public byte[] toGrayscale() {
        byte[] gray = new byte[width * height];
        for (int i = 0, p = 0; i < gray.length; i++, p += 3) {
            int value = (rgb[p] * 77 + rgb[p + 1] * 150 + rgb[p + 2] * 29) >> 8;
            gray[i] = (byte) Math.max(0, Math.min(255, value));
        }
        return gray;
    }

    /**
     * Variance of the Laplacian of the luminance plane.
     *
     * <p>Used as a focus measure: a blurry frame (camera out of focus, or a low resolution
     * print scanned at an angle) has a low value and is rejected before inference.
     */
    public double laplacianVariance() {
        if (width < 3 || height < 3) {
            return 0d;
        }
        byte[] gray = toGrayscale();
        double sum = 0d;
        double sumSquares = 0d;
        int count = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                double laplacian = 4 * (gray[index] & 0xFF)
                        - (gray[index - 1] & 0xFF) - (gray[index + 1] & 0xFF)
                        - (gray[index - width] & 0xFF) - (gray[index + width] & 0xFF);
                sum += laplacian;
                sumSquares += laplacian * laplacian;
                count++;
            }
        }
        if (count == 0) {
            return 0d;
        }
        double mean = sum / count;
        return sumSquares / count - mean * mean;
    }

    /** Mean luminance of the raster, in the 0..255 range. */
    public double meanLuma() {
        byte[] gray = toGrayscale();
        long sum = 0;
        for (byte value : gray) {
            sum += value & 0xFF;
        }
        return gray.length == 0 ? 0d : (double) sum / gray.length;
    }

    /**
     * A percentile of the luminance of the raster, in the 0..255 range.
     *
     * <p>The mean is a poor measure of how well a face is exposed. A portrait with a
     * dark background has a low mean even when the face itself is well lit, and the
     * other way round. The percentiles describe the bright and the dark ends of the
     * picture, which is what says whether the face is usable.
     *
     * @param fraction a number between 0 and 1
     */
    public int percentileLuma(double fraction) {
        byte[] gray = toGrayscale();
        if (gray.length == 0) {
            return 0;
        }
        int[] histogram = new int[256];
        for (byte value : gray) {
            histogram[value & 0xFF]++;
        }
        int target = (int) Math.round(gray.length * Math.max(0d, Math.min(1d, fraction)));
        int seen = 0;
        for (int i = 0; i < histogram.length; i++) {
            seen += histogram[i];
            if (seen >= target) {
                return i;
            }
        }
        return 255;
    }

    /** Mean absolute difference between two equally sized images, in 0..255 luminance units. */
    public static double meanAbsoluteDifference(FaceImage left, FaceImage right) {
        if (left.width != right.width || left.height != right.height) {
            return Double.MAX_VALUE;
        }
        byte[] a = left.toGrayscale();
        byte[] b = right.toGrayscale();
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs((a[i] & 0xFF) - (b[i] & 0xFF));
        }
        return a.length == 0 ? 0d : (double) sum / a.length;
    }

    /** Encodes the raster as JPEG so it can be stored as an enrolment artefact. */
    public byte[] toJpegBytes(float quality) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = (y * width + x) * 3;
                row[x] = 0xFF00_0000 | ((rgb[index] & 0xFF) << 16)
                        | ((rgb[index + 1] & 0xFF) << 8) | (rgb[index + 2] & 0xFF);
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return encodeJpeg(image, quality);
    }

    /** Encodes a buffered image as JPEG with the requested quality. */
    public static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG encoder is available in this runtime");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(Math.max(0.1f, Math.min(1f, quality)));
            }
            writer.write(null, new IIOImage(image, null, null), params);
            output.flush();
            return buffer.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private int channelAt(int x, int y, int channel) {
        return rgb[(y * width + x) * 3 + channel] & 0xFF;
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
