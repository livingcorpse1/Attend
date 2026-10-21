package com.veriqra.app.service.biometric;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Face embedding built on the SFace ONNX model.
 *
 * <p>SFace maps an aligned 112x112 crop onto a 128 dimensional descriptor. The graph
 * performs its own input normalisation, so the tensor is fed with raw RGB pixels.
 */
public final class FaceEmbedder implements AutoCloseable {

    /** Dimensionality of the produced descriptor. */
    public static final int EMBEDDING_SIZE = 128;

    private final OrtEnvironment environment;
    private final OrtSession session;

    public FaceEmbedder(Path modelPath) throws OrtException, IOException {
        if (!Files.isRegularFile(modelPath)) {
            throw new IOException("Face recognition model is missing at " + modelPath.toAbsolutePath()
                    + ". Run scripts/fetch-models.ps1 to download it.");
        }
        this.environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        options.setInterOpNumThreads(1);
        this.session = environment.createSession(modelPath.toString(), options);
    }

    /**
     * Computes the L2 normalised descriptor of an aligned crop.
     *
     * @param aligned a 112x112 RGB crop
     * @return the normalised descriptor
     */
    public float[] embed(FaceImage aligned) throws OrtException {
        if (aligned.width() != FaceAlignment.CROP_SIZE || aligned.height() != FaceAlignment.CROP_SIZE) {
            throw new IllegalArgumentException("The recogniser expects a "
                    + FaceAlignment.CROP_SIZE + "x" + FaceAlignment.CROP_SIZE + " crop");
        }
        float[] blob = toBlob(aligned);
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(blob),
                new long[]{1, 3, FaceAlignment.CROP_SIZE, FaceAlignment.CROP_SIZE});
             OrtSession.Result result = session.run(Map.of("data", tensor))) {

            OnnxTensor output = (OnnxTensor) result.get("fc1").orElseThrow(
                    () -> new OrtException("The face recognition model did not produce the output 'fc1'"));
            try (OnnxTensor owned = output) {
                FloatBuffer buffer = owned.getFloatBuffer();
                float[] descriptor = new float[buffer.remaining()];
                buffer.get(descriptor);
                return normalise(descriptor);
            }
        }
    }

    private static float[] toBlob(FaceImage image) {
        int plane = image.width() * image.height();
        float[] blob = new float[3 * plane];
        byte[] rgb = image.rgb();
        for (int i = 0, p = 0; i < plane; i++, p += 3) {
            blob[i] = rgb[p] & 0xFF;
            blob[plane + i] = rgb[p + 1] & 0xFF;
            blob[2 * plane + i] = rgb[p + 2] & 0xFF;
        }
        return blob;
    }

    /** Scales a descriptor to unit length. */
    public static float[] normalise(float[] descriptor) {
        double sumOfSquares = 0d;
        for (float value : descriptor) {
            sumOfSquares += value * value;
        }
        double norm = Math.sqrt(sumOfSquares);
        if (norm < 1e-9) {
            return descriptor.clone();
        }
        float[] normalised = new float[descriptor.length];
        for (int i = 0; i < descriptor.length; i++) {
            normalised[i] = (float) (descriptor[i] / norm);
        }
        return normalised;
    }

    /** Cosine similarity of two descriptors, in the range -1..1. */
    public static double cosineSimilarity(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("Descriptors have different dimensions");
        }
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        double denominator = Math.sqrt(leftNorm * rightNorm);
        return denominator < 1e-9 ? 0d : dot / denominator;
    }

    /** Average of several descriptors, re-normalised afterwards. */
    public static float[] average(List<float[]> descriptors) {
        if (descriptors.isEmpty()) {
            throw new IllegalArgumentException("At least one descriptor is required");
        }
        float[] mean = new float[descriptors.get(0).length];
        for (float[] descriptor : descriptors) {
            for (int i = 0; i < mean.length; i++) {
                mean[i] += descriptor[i];
            }
        }
        for (int i = 0; i < mean.length; i++) {
            mean[i] /= descriptors.size();
        }
        return normalise(mean);
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
