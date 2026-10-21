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
 * <p>Two exports of the model exist. The older one has a fixed 640x640 input, so a frame
 * is scaled and padded into a square before it is shown to the model. The newer export
 * takes any size, which lets the frame be shown at its own resolution: a face that fills
 * a small part of the picture stays large in the input, and the padding is gone.
 *
 * <p>The detector runs entirely on the server. The browser only ever ships pixels.
 */
public final class YuNetDetector implements FaceDetector {

    private static final int[] STRIDES = {8, 16, 32};
    private static final String[] CLASS_OUTPUTS = {"cls_8", "cls_16", "cls_32"};
    private static final String[] OBJECTNESS_OUTPUTS = {"obj_8", "obj_16", "obj_32"};
    private static final String[] BOX_OUTPUTS = {"bbox_8", "bbox_16", "bbox_32"};
    private static final String[] LANDMARK_OUTPUTS = {"kps_8", "kps_16", "kps_32"};

    /** The longest side the frame is shown at, so the detection stays quick. */
    private static final int MAX_INPUT = 960;

    /** The input has to be a whole number of strides. */
    private static final int STRIDE_MULTIPLE = 32;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    /** True when the model takes any size and the frame is shown at its own resolution. */
    private final boolean dynamicInput;
    private final int fixedInputSize;
    private final float scoreThreshold;
    private final float nmsThreshold;

    public YuNetDetector(Path modelPath, float scoreThreshold) throws OrtException, IOException {
        if (!Files.isRegularFile(modelPath)) {
            throw new IOException("Face detection model is missing at " + modelPath.toAbsolutePath()
                    + ". Run scripts/fetch-models.ps1 to download it.");
        }
        this.environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        options.setInterOpNumThreads(1);
        this.session = environment.createSession(modelPath.toString(), options);
        this.inputName = session.getInputInfo().keySet().iterator().next();
        this.fixedInputSize = resolveInputSize(session);
        this.dynamicInput = fixedInputSize <= 0;
        this.scoreThreshold = scoreThreshold;
        this.nmsThreshold = 0.3f;
    }

    @Override
    public String modelName() {
        return "yunet";
    }

    /**
     * Reads the spatial size the model expects.
     *
     * <p>A model with a fixed input reports it. A model that takes any size reports a
     * negative number, and the frame is then shown at its own resolution.
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
        return -1;
    }

    @Override
    public List<DetectedFace> detect(FaceImage image) throws OrtException {
        int width = image.width();
        int height = image.height();
        if (dynamicInput) {
            // The model takes any size, so the frame is scaled to a size it can show
            // without padding, and the coordinates are mapped back afterwards.
            float scale = Math.min(1f, (float) MAX_INPUT / Math.max(width, height));
            width = Math.max(STRIDE_MULTIPLE, (int) (width * scale) / STRIDE_MULTIPLE * STRIDE_MULTIPLE);
            height = Math.max(STRIDE_MULTIPLE, (int) (height * scale) / STRIDE_MULTIPLE * STRIDE_MULTIPLE);
        } else {
            width = fixedInputSize;
            height = fixedInputSize;
        }
        Resize resize = dynamicInput
                ? Resize.scaled(image, width, height)
                : Resize.letterbox(image, width);
        float[] input = resize.toBlob();

        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input),
                new long[]{1, 3, height, width});
             OrtSession.Result result = session.run(Map.of(inputName, tensor))) {

            List<DetectedFace> faces = new ArrayList<>();
            decodeStride(8, read(result, CLASS_OUTPUTS[0]), read(result, OBJECTNESS_OUTPUTS[0]),
                    read(result, BOX_OUTPUTS[0]), read(result, LANDMARK_OUTPUTS[0]), width, height, faces);
            decodeStride(16, read(result, CLASS_OUTPUTS[1]), read(result, OBJECTNESS_OUTPUTS[1]),
                    read(result, BOX_OUTPUTS[1]), read(result, LANDMARK_OUTPUTS[1]), width, height, faces);
            decodeStride(32, read(result, CLASS_OUTPUTS[2]), read(result, OBJECTNESS_OUTPUTS[2]),
                    read(result, BOX_OUTPUTS[2]), read(result, LANDMARK_OUTPUTS[2]), width, height, faces);

            List<DetectedFace> suppressed = nonMaximumSuppression(faces);
            List<DetectedFace> mapped = new ArrayList<>(suppressed.size());
            for (DetectedFace face : suppressed) {
                mapped.add(resize.toSource(face));
            }
            mapped.sort(Comparator.comparingDouble(DetectedFace::score).reversed());
            return mapped;
        }
    }

    private void decodeStride(int stride, float[] cls, float[] obj, float[] bbox, float[] kps,
                              int width, int height, List<DetectedFace> sink) {
        int cols = width / stride;
        int rows = height / stride;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int index = r * cols + c;
                if (index >= cls.length || index >= bbox.length || index * 10 >= kps.length) {
                    continue;
                }
                float clsScore = clamp01(cls[index]);
                float objectness = clamp01(obj[index]);
                float score = (float) Math.sqrt(clsScore * objectness);
                if (score < scoreThreshold) {
                    continue;
                }
                float centreX = (c + bbox[index * 4]) * stride;
                float centreY = (r + bbox[index * 4 + 1]) * stride;
                float boxWidth = (float) Math.exp(bbox[index * 4 + 2]) * stride;
                float boxHeight = (float) Math.exp(bbox[index * 4 + 3]) * stride;
                float x = centreX - boxWidth / 2f;
                float y = centreY - boxHeight / 2f;

                float[] landmarks = new float[10];
                for (int n = 0; n < 5; n++) {
                    landmarks[n * 2] = (kps[index * 10 + n * 2] + c) * stride;
                    landmarks[n * 2 + 1] = (kps[index * 10 + n * 2 + 1] + r) * stride;
                }
                sink.add(new DetectedFace(x, y, boxWidth, boxHeight, landmarks, score));
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
     * The transform between the source frame and the input of the model.
     *
     * <p>When the model takes any size the frame is shown at its own resolution, scaled
     * by a single factor, and the scale is remembered so the coordinates can be mapped
     * back. When the model has a fixed square input the frame is scaled into it and
     * padded, and the offsets are remembered instead.
     */
    static final class Resize {

        private final FaceImage scaled;
        private final float scaleX;
        private final float scaleY;
        private final int offsetX;
        private final int offsetY;

        private Resize(FaceImage scaled, float scaleX, float scaleY, int offsetX, int offsetY) {
            this.scaled = scaled;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        /** Scales the frame onto a canvas of the given size and pads the remainder. */
        static Resize letterbox(FaceImage image, int size) {
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
            return new Resize(new FaceImage(size, size, buffer), 1f / scale, 1f / scale, offsetX, offsetY);
        }

        /** Scales the frame to the given size. */
        static Resize scaled(FaceImage image, int width, int height) {
            if (width == image.width() && height == image.height()) {
                return new Resize(image, 1f, 1f, 0, 0);
            }
            FaceImage resized = image.resize(width, height);
            return new Resize(resized, (float) image.width() / width, (float) image.height() / height, 0, 0);
        }

        /** Builds the NCHW blob. YuNet was trained on raw BGR pixels without rescaling. */
        float[] toBlob() {
            float[] blob = new float[3 * scaled.width() * scaled.height()];
            int plane = scaled.width() * scaled.height();
            byte[] rgb = scaled.rgb();
            for (int i = 0, p = 0; i < plane; i++, p += 3) {
                blob[i] = rgb[p + 2] & 0xFF;
                blob[plane + i] = rgb[p + 1] & 0xFF;
                blob[2 * plane + i] = rgb[p] & 0xFF;
            }
            return blob;
        }

        DetectedFace toSource(DetectedFace face) {
            float x = (face.x() - offsetX) * scaleX;
            float y = (face.y() - offsetY) * scaleY;
            float width = face.width() * scaleX;
            float height = face.height() * scaleY;
            float[] landmarks = new float[face.landmarks().length];
            for (int i = 0; i < landmarks.length; i += 2) {
                landmarks[i] = (face.landmarks()[i] - offsetX) * scaleX;
                landmarks[i + 1] = (face.landmarks()[i + 1] - offsetY) * scaleY;
            }
            return new DetectedFace(x, y, width, height, landmarks, face.score());
        }
    }
}
