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
 * Face embedding.
 *
 * <p>Two recognition models are supported and both take the same aligned 112x112 crop:
 * <ul>
 *   <li><b>SFace</b> - a small MobileFaceNet trained with the SFace loss. The graph
 *       normalises its input itself, so the tensor is fed with raw pixels. The
 *       descriptor has 128 values.</li>
 *   <li><b>ArcFace</b> - a ResNet50 trained with the ArcFace loss on 600 thousand
 *       identities. It is the strongest of the two by a wide margin. The normalisation
 *       has to be done here, and the descriptor has 512 values.</li>
 * </ul>
 *
 * <p>The two models describe the same face in different ways, so a template that was
 * enrolled with one of them cannot be compared with a descriptor of the other. The
 * version of the model is stored with every template for that reason.
 */
public final class FaceEmbedder implements AutoCloseable {

    /** The recognition models the pipeline knows. */
    public enum Profile {

        /** SFace, 128 dimensional, published under the Apache licence. */
        SFACE("face_recognition_sface_2021dec.onnx", 128, false),

        /** ArcFace, 512 dimensional, the strongest model of the family. */
        ARCFACE("arcface_w600k_r50.onnx", 512, true);

        private final String fileName;
        private final int embeddingSize;
        private final boolean normalisesOutside;

        Profile(String fileName, int embeddingSize, boolean normalisesOutside) {
            this.fileName = fileName;
            this.embeddingSize = embeddingSize;
            this.normalisesOutside = normalisesOutside;
        }

        /** The name of the file the model is stored in. */
        public String fileName() {
            return fileName;
        }

        /** The number of values the descriptor has. */
        public int embeddingSize() {
            return embeddingSize;
        }

        /**
         * True when the model expects the pixels to be centred and scaled before they are
         * handed over, instead of doing that itself.
         */
        public boolean normalisesOutside() {
            return normalisesOutside;
        }
    }

    /** Dimensionality of the descriptor of the SFace model. */
    public static final int EMBEDDING_SIZE = Profile.SFACE.embeddingSize();

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final Profile profile;
    private final String inputName;
    private final String outputName;

    public FaceEmbedder(Path modelPath) throws OrtException, IOException {
        this(modelPath, detectProfile(modelPath));
    }

    public FaceEmbedder(Path modelPath, Profile profile) throws OrtException, IOException {
        if (!Files.isRegularFile(modelPath)) {
            throw new IOException("Face recognition model is missing at " + modelPath.toAbsolutePath()
                    + ". Run scripts/fetch-models.ps1 to download it.");
        }
        this.profile = profile;
        this.environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        options.setInterOpNumThreads(1);
        this.session = environment.createSession(modelPath.toString(), options);
        this.inputName = session.getInputInfo().keySet().iterator().next();
        this.outputName = session.getOutputInfo().keySet().iterator().next();
    }

    /**
     * The profile a model file belongs to.
     *
     * <p>The models are recognised by the number of values their descriptor has, so the
     * pipeline works with a model whose file name was changed.
     */
    private static Profile detectProfile(Path modelPath) {
        String name = modelPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("arcface") || name.contains("w600k") || name.contains("adaface")) {
            return Profile.ARCFACE;
        }
        return Profile.SFACE;
    }

    /** The model behind this embedder. */
    public Profile profile() {
        return profile;
    }

    /** The number of values the descriptor has. */
    public int embeddingSize() {
        return profile.embeddingSize();
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
             OrtSession.Result result = session.run(Map.of(inputName, tensor))) {

            OnnxTensor output = (OnnxTensor) result.get(outputName).orElseThrow(
                    () -> new OrtException("The face recognition model did not produce the output '"
                            + outputName + "'"));
            try (OnnxTensor owned = output) {
                FloatBuffer buffer = owned.getFloatBuffer();
                float[] descriptor = new float[buffer.remaining()];
                buffer.get(descriptor);
                return normalise(descriptor);
            }
        }
    }

    /**
     * Builds the NCHW blob of the crop.
     *
     * <p>The SFace model centres and scales the pixels itself, so it is fed with the raw
     * values. ArcFace expects them to be centred on 127.5 and divided by 127.5.
     */
    private float[] toBlob(FaceImage image) {
        int plane = image.width() * image.height();
        float[] blob = new float[3 * plane];
        byte[] rgb = image.rgb();
        if (profile.normalisesOutside()) {
            for (int i = 0, p = 0; i < plane; i++, p += 3) {
                blob[i] = ((rgb[p] & 0xFF) - 127.5f) / 127.5f;
                blob[plane + i] = ((rgb[p + 1] & 0xFF) - 127.5f) / 127.5f;
                blob[2 * plane + i] = ((rgb[p + 2] & 0xFF) - 127.5f) / 127.5f;
            }
            return blob;
        }
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
