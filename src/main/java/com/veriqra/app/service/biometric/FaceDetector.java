package com.veriqra.app.service.biometric;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.TensorInfo;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Face detection built on the YuNet ONNX model.
 *
 * <p>YuNet is a single stage anchor free detector that returns a bounding box and five
 * landmarks (right eye, left eye, nose tip, right mouth corner, left mouth corner). The
 * decoding below mirrors the reference implementation shipped with OpenCV so the numbers
 * are directly comparable with the official tooling.
 *
 * <p>The detector runs entirely on the server. The browser only ever ships pixels.
 */
public final class FaceDetector implements AutoCloseable {

    private static final int[] STRIDES = {8, 16, 32};
    private static final String[] CLASS_OUTPUTS = {"cls_8", "cls_16", "cls_32"};
    private static final String[] OBJECTNESS_OUTPUTS = {"obj_8", "obj_16", "obj_32"};
    private static final String[] BOX_OUTPUTS = {"bbox_8", "bbox_16", "bbox_32"};
    private static final String[] LANDMARK_OUTPUTS = {"kps_8", "kps_16", "kps_32"};

    /** One detected face, expressed in the coordinate system of the source image. */
    public record DetectedFace(float x, float y, float width, float height, float[] landmarks, float score) {

        /** Landmark accessors, in the order YuNet produces them. */
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

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final int inputSize;
    private final float scoreThreshold;
    private final float nmsThreshold;

    public FaceDetector(Path modelPath, float scoreThreshold) throws OrtException, IOException {
        if (!Files.isRegularFile(modelPath)) {
            throw new IOException("Face detection model is missing at " + modelPath.toAbsolutePath()
                    + ". Run scripts/fetch-models.ps1 to download it.");
        }
        this.environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        options.setInterOpNumThreads(1);
        this.session = environment.createSession(modelPath.toString(), options);
        this.inputSize = resolveInputSize(session);
        this.scoreThreshold = scoreThreshold;
        this.nmsThreshold = 0.3f;
    }

    /**
     * Reads the spatial size the model expects.
     *
     * <p>The published YuNet export has a fixed 640x640 input. A different export with a
     * different size is picked up automatically instead of failing at inference time.
     */
    private static int resolveInputSize(OrtSession session) throws OrtException {
        for (NodeInfo nodeInfo : session.getInputInfo().values()) {
            if (nodeInfo.getInfo() instanceof TensorInfo tensorInfo) {
                long[] shape = tensorInfo.getShape();
                if (shape.length == 4 && shape[2] > 0 && shape[3] > 0) {
                    return (int) shape[2];
                }
            }
        }
        return 640;
    }

    /**
     * Detects every face in the image.
     *
     * @return the detections ordered by descending confidence
     */
    public List<DetectedFace> detect(FaceImage image) throws OrtException {
        Letterbox letterbox = Letterbox.apply(image, inputSize);
        float[] input = letterbox.toBlob();

        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input),
                new long[]{1, 3, inputSize, inputSize});
             OrtSession.Result result = session.run(Map.of("input", tensor))) {

            float[] cls8 = read(result, CLASS_OUTPUTS[0]);
            float[] cls16 = read(result, CLASS_OUTPUTS[1]);
            float[] cls32 = read(result, CLASS_OUTPUTS[2]);
            float[] obj8 = read(result, OBJECTNESS_OUTPUTS[0]);
            float[] obj16 = read(result, OBJECTNESS_OUTPUTS[1]);
            float[] obj32 = read(result, OBJECTNESS_OUTPUTS[2]);
            float[] bbox8 = read(result, BOX_OUTPUTS[0]);
            float[] bbox16 = read(result, BOX_OUTPUTS[1]);
            float[] bbox32 = read(result, BOX_OUTPUTS[2]);
            float[] kps8 = read(result, LANDMARK_OUTPUTS[0]);
            float[] kps16 = read(result, LANDMARK_OUTPUTS[1]);
            float[] kps32 = read(result, LANDMARK_OUTPUTS[2]);

            List<DetectedFace> faces = new ArrayList<>();
            decodeStride(8, cls8, obj8, bbox8, kps8, faces);
            decodeStride(16, cls16, obj16, bbox16, kps16, faces);
            decodeStride(32, cls32, obj32, bbox32, kps32, faces);

            List<DetectedFace> suppressed = nonMaximumSuppression(faces);
            List<DetectedFace> mapped = new ArrayList<>(suppressed.size());
            for (DetectedFace face : suppressed) {
                mapped.add(letterbox.toSource(face));
            }
            mapped.sort(Comparator.comparingDouble(DetectedFace::score).reversed());
            return mapped;
        }
    }

    private void decodeStride(int stride, float[] cls, float[] obj, float[] bbox, float[] kps,
                              List<DetectedFace> sink) {
        int cols = inputSize / stride;
        int rows = inputSize / stride;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int index = r * cols + c;
                float clsScore = clamp01(cls[index]);
                float objectness = clamp01(obj[index]);
                float score = (float) Math.sqrt(clsScore * objectness);
                if (score < scoreThreshold) {
                    continue;
                }
                float centreX = (c + bbox[index * 4]) * stride;
                float centreY = (r + bbox[index * 4 + 1]) * stride;
                float width = (float) Math.exp(bbox[index * 4 + 2]) * stride;
                float height = (float) Math.exp(bbox[index * 4 + 3]) * stride;
                float x = centreX - width / 2f;
                float y = centreY - height / 2f;

                float[] landmarks = new float[10];
                for (int n = 0; n < 5; n++) {
                    landmarks[n * 2] = (kps[index * 10 + n * 2] + c) * stride;
                    landmarks[n * 2 + 1] = (kps[index * 10 + n * 2 + 1] + r) * stride;
                }
                sink.add(new DetectedFace(x, y, width, height, landmarks, score));
            }
        }
    }

    private List<DetectedFace> nonMaximumSuppression(List<DetectedFace> faces) {
        List<DetectedFace> ordered = new ArrayList<>(faces);
        ordered.sort(Comparator.comparingDouble(DetectedFace::score).reversed());
        List<DetectedFace> kept = new ArrayList<>();
        for (DetectedFace candidate : ordered) {
            boolean overlaps = false;
            for (DetectedFace existing : kept) {
                if (iou(candidate, existing) > nmsThreshold) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    private static double iou(DetectedFace left, DetectedFace right) {
        float leftX2 = left.x() + left.width();
        float leftY2 = left.y() + left.height();
        float rightX2 = right.x() + right.width();
        float rightY2 = right.y() + right.height();
        float overlapX = Math.max(0f, Math.min(leftX2, rightX2) - Math.max(left.x(), right.x()));
        float overlapY = Math.max(0f, Math.min(leftY2, rightY2) - Math.max(left.y(), right.y()));
        float intersection = overlapX * overlapY;
        float union = left.width() * left.height() + right.width() * right.height() - intersection;
        return union <= 0f ? 0d : intersection / union;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float[] read(OrtSession.Result result, String name) throws OrtException {
        OnnxTensor tensor = (OnnxTensor) result.get(name).orElseThrow(
                () -> new OrtException("The face detection model did not produce the output '" + name + "'"));
        try (OnnxTensor owned = tensor) {
            FloatBuffer buffer = owned.getFloatBuffer();
            float[] values = new float[buffer.remaining()];
            buffer.get(values);
            return values;
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }

    /**
     * Aspect preserving letterbox transform.
     *
     * <p>The frame is scaled so its longest side matches the model input and the remainder
     * is padded. Coordinates are mapped back to the source image afterwards so downstream
     * code always works in original pixels.
     */
    static final class Letterbox {

        private final FaceImage padded;
        private final float scale;
        private final float offsetX;
        private final float offsetY;

        private Letterbox(FaceImage padded, float scale, float offsetX, float offsetY) {
            this.padded = padded;
            this.scale = scale;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        static Letterbox apply(FaceImage image, int size) {
            float scale = (float) size / Math.max(image.width(), image.height());
            int targetWidth = Math.max(1, Math.round(image.width() * scale));
            int targetHeight = Math.max(1, Math.round(image.height() * scale));
            FaceImage resized = image.resize(targetWidth, targetHeight);
            int offsetX = (size - targetWidth) / 2;
            int offsetY = (size - targetHeight) / 2;

            byte[] buffer = new byte[size * size * 3];
            for (int row = 0; row < targetHeight; row++) {
                System.arraycopy(resized.rgb(), row * targetWidth * 3,
                        buffer, ((row + offsetY) * size + offsetX) * 3, targetWidth * 3);
            }
            FaceImage padded = new FaceImage(size, size, buffer);
            return new Letterbox(padded, scale, offsetX, offsetY);
        }

        /** Builds the NCHW blob. YuNet was trained on raw BGR pixels without rescaling. */
        float[] toBlob() {
            float[] blob = new float[3 * padded.width() * padded.height()];
            int plane = padded.width() * padded.height();
            byte[] rgb = padded.rgb();
            for (int i = 0, p = 0; i < plane; i++, p += 3) {
                blob[i] = rgb[p + 2] & 0xFF;
                blob[plane + i] = rgb[p + 1] & 0xFF;
                blob[2 * plane + i] = rgb[p] & 0xFF;
            }
            return blob;
        }

        DetectedFace toSource(DetectedFace face) {
            float x = (face.x() - offsetX) / scale;
            float y = (face.y() - offsetY) / scale;
            float width = face.width() / scale;
            float height = face.height() / scale;
            float[] landmarks = new float[face.landmarks().length];
            for (int i = 0; i < landmarks.length; i += 2) {
                landmarks[i] = (face.landmarks()[i] - offsetX) / scale;
                landmarks[i + 1] = (face.landmarks()[i + 1] - offsetY) / scale;
            }
            return new DetectedFace(x, y, width, height, landmarks, face.score());
        }
    }
}
