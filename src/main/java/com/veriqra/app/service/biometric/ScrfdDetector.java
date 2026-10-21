package com.veriqra.app.service.biometric;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Face detection built on the SCRFD ONNX model.
 *
 * <p>SCRFD is the detector of the InsightFace model family. It is a single stage
 * anchor free detector that predicts the distance from an anchor to the four sides of a
 * face and to the five landmarks, and it is markedly better than YuNet on small faces,
 * on faces that are turned away and on crowded scenes.
 *
 * <p>The decoding below follows the reference implementation of the InsightFace project
 * exactly: the frame is scaled into a square canvas, the predicted distances are turned
 * into boxes around the anchors, and the landmarks are placed relative to the anchor
 * centre.
 *
 * <p>The landmarks are reported in the same order the other detector uses, so the
 * alignment step is shared.
 */
public final class ScrfdDetector implements FaceDetector {

    /** The size the frame is shown at. */
    private static final int INPUT_SIZE = 640;

    /** The strides the three levels of the feature pyramid use. */
    private static final int[] STRIDES = {8, 16, 32};

    /** The number of anchors per cell of the feature map. */
    private static final int ANCHORS_PER_CELL = 2;

    /** The mean and the standard deviation the model was trained with. */
    private static final float MEAN = 127.5f;
    private static final float STD = 128.0f;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    /** The score, box and landmark outputs of the three levels, ordered by stride. */
    private final String[] scoreOutputs = new String[3];
    private final String[] boxOutputs = new String[3];
    private final String[] landmarkOutputs = new String[3];
    private final float scoreThreshold;
    private final float nmsThreshold;

    public ScrfdDetector(Path modelPath, float scoreThreshold) throws OrtException, IOException {
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
        this.scoreThreshold = scoreThreshold;
        this.nmsThreshold = 0.4f;
        readOutputs();
    }

    /**
     * Sorts the outputs of the model into the three levels of the feature pyramid.
     *
     * <p>The outputs are recognised by their shape and not by their name: a score output
     * has one column, a box output has four and a landmark output has ten. Within a
     * group the level with the most candidates is the finest one, so the levels end up in
     * the order of the strides 8, 16 and 32.
     */
    private void readOutputs() throws OrtException, IOException {
        List<String> scoreNames = new ArrayList<>();
        List<String> boxNames = new ArrayList<>();
        List<String> landmarkNames = new ArrayList<>();
        List<Integer> scoreRows = new ArrayList<>();
        List<Integer> boxRows = new ArrayList<>();
        List<Integer> landmarkRows = new ArrayList<>();

        for (Map.Entry<String, ai.onnxruntime.NodeInfo> entry : session.getOutputInfo().entrySet()) {
            if (!(entry.getValue().getInfo() instanceof ai.onnxruntime.TensorInfo tensor)) {
                continue;
            }
            long[] shape = tensor.getShape();
            if (shape.length != 2) {
                continue;
            }
            int columns = (int) shape[1];
            int rows = (int) shape[0];
            if (columns == 1) {
                scoreNames.add(entry.getKey());
                scoreRows.add(rows);
            } else if (columns == 4) {
                boxNames.add(entry.getKey());
                boxRows.add(rows);
            } else if (columns == 10) {
                landmarkNames.add(entry.getKey());
                landmarkRows.add(rows);
            }
        }
        if (scoreNames.size() != 3 || boxNames.size() != 3 || landmarkNames.size() != 3) {
            throw new IOException("The SCRFD model is expected to have three score, three box "
                    + "and three landmark outputs");
        }
        scoreOutputs[0] = scoreNames.get(indexOfLargest(scoreRows));
        scoreOutputs[1] = scoreNames.get(indexOfLargest(scoreRows, scoreNames.indexOf(scoreOutputs[0])));
        scoreOutputs[2] = scoreNames.get(indexOfSmallest(scoreRows));

        boxOutputs[0] = boxNames.get(indexOfLargest(boxRows));
        boxOutputs[1] = boxNames.get(indexOfLargest(boxRows, boxNames.indexOf(boxOutputs[0])));
        boxOutputs[2] = boxNames.get(indexOfSmallest(boxRows));

        landmarkOutputs[0] = landmarkNames.get(indexOfLargest(landmarkRows));
        landmarkOutputs[1] = landmarkNames.get(indexOfLargest(landmarkRows, landmarkNames.indexOf(landmarkOutputs[0])));
        landmarkOutputs[2] = landmarkNames.get(indexOfSmallest(landmarkRows));
    }

    /** The index of the entry with the most rows. */
    private static int indexOfLargest(List<Integer> rows) {
        int best = 0;
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i) > rows.get(best)) {
                best = i;
            }
        }
        return best;
    }

    /** The index of the entry with the most rows, ignoring one entry. */
    private static int indexOfLargest(List<Integer> rows, int ignore) {
        int best = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (i == ignore) {
                continue;
            }
            if (best < 0 || rows.get(i) > rows.get(best)) {
                best = i;
            }
        }
        return best;
    }

    /** The index of the entry with the fewest rows. */
    private static int indexOfSmallest(List<Integer> rows) {
        int best = 0;
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i) < rows.get(best)) {
                best = i;
            }
        }
        return best;
    }

    @Override
    public String modelName() {
        return "scrfd";
    }

    @Override
    public List<DetectedFace> detect(FaceImage image) throws OrtException {
        // The frame is scaled into a square canvas, keeping the proportions and padding
        // the remainder, exactly as the reference implementation does.
        float imageRatio = (float) image.height() / image.width();
        float modelRatio = (float) INPUT_SIZE / INPUT_SIZE;
        int newWidth;
        int newHeight;
        if (imageRatio > modelRatio) {
            newHeight = INPUT_SIZE;
            newWidth = Math.max(1, (int) (newHeight / imageRatio));
        } else {
            newWidth = INPUT_SIZE;
            newHeight = Math.max(1, (int) (newWidth * imageRatio));
        }
        float detScale = (float) newHeight / image.height();
        FaceImage resized = image.resize(newWidth, newHeight);

        float[] blob = toBlob(resized, INPUT_SIZE, INPUT_SIZE);
        float[][] outputs = new float[9][];
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(blob),
                new long[]{1, 3, INPUT_SIZE, INPUT_SIZE});
             OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
            for (int level = 0; level < 3; level++) {
                outputs[level] = read(result, scoreOutputs[level]);
                outputs[level + 3] = read(result, boxOutputs[level]);
                outputs[level + 6] = read(result, landmarkOutputs[level]);
            }
        }

        List<DetectedFace> faces = new ArrayList<>();
        for (int level = 0; level < STRIDES.length; level++) {
            int stride = STRIDES[level];
            float[] scores = outputs[level];
            float[] boxes = outputs[level + 3];
            float[] kps = outputs[level + 6];

            int height = INPUT_SIZE / stride;
            int width = INPUT_SIZE / stride;
            for (int index = 0; index < scores.length; index++) {
                float score = scores[index];
                if (score < scoreThreshold) {
                    continue;
                }
                int cell = index / ANCHORS_PER_CELL;
                int row = cell / width;
                int column = cell % width;
                float anchorX = column * stride;
                float anchorY = row * stride;

                float left = anchorX - boxes[index * 4] * stride;
                float top = anchorY - boxes[index * 4 + 1] * stride;
                float right = anchorX + boxes[index * 4 + 2] * stride;
                float bottom = anchorY + boxes[index * 4 + 3] * stride;

                float[] landmarks = new float[10];
                for (int point = 0; point < 5; point++) {
                    float offsetX = kps[index * 10 + point * 2] * stride;
                    float offsetY = kps[index * 10 + point * 2 + 1] * stride;
                    landmarks[point * 2] = anchorX + offsetX;
                    landmarks[point * 2 + 1] = anchorY + offsetY;
                }

                faces.add(new DetectedFace(left, top, right - left, bottom - top, landmarks, score));
            }
        }

        // The coordinates are in the canvas, so they are divided by the scale of the
        // resize to get back to the source.
        List<DetectedFace> mapped = new ArrayList<>(faces.size());
        for (DetectedFace face : faces) {
            float[] landmarks = new float[10];
            for (int i = 0; i < landmarks.length; i += 2) {
                landmarks[i] = face.landmarks()[i] / detScale;
                landmarks[i + 1] = face.landmarks()[i + 1] / detScale;
            }
            mapped.add(new DetectedFace(face.x() / detScale, face.y() / detScale,
                    face.width() / detScale, face.height() / detScale, landmarks, face.score()));
        }

        List<DetectedFace> suppressed = nonMaximumSuppression(mapped);
        suppressed.sort(Comparator.comparingDouble(DetectedFace::score).reversed());
        return suppressed;
    }

    /**
     * Builds the NCHW blob of the canvas.
     *
     * <p>The model was trained on colour channels that are centred on 127.5 and scaled by
     * 128, and the channels are in red, green, blue order.
     */
    private static float[] toBlob(FaceImage image, int width, int height) {
        float[] blob = new float[3 * width * height];
        int plane = width * height;
        byte[] rgb = image.rgb();
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int target = row * width + column;
                int source = (row * image.width() + column) * 3;
                if (row >= image.height() || column >= image.width()) {
                    // The padded part of the canvas is black.
                    blob[target] = (0f - MEAN) / STD;
                    blob[plane + target] = (0f - MEAN) / STD;
                    blob[2 * plane + target] = (0f - MEAN) / STD;
                    continue;
                }
                blob[target] = ((rgb[source] & 0xFF) - MEAN) / STD;
                blob[plane + target] = ((rgb[source + 1] & 0xFF) - MEAN) / STD;
                blob[2 * plane + target] = ((rgb[source + 2] & 0xFF) - MEAN) / STD;
            }
        }
        return blob;
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
}
