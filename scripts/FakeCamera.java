import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Turns pictures into a Y4M file, so they can be used as a fake camera.
 *
 * <p>Every entry of the list is a picture and the number of frames it is shown for.
 * The file is played in a loop by the browser, so a list of two pictures gives a
 * camera that shows the first one and then the second. Two pictures joined with a
 * plus are put side by side, so a frame can carry a face and a code at the same time.
 *
 * <p>Usage: java FakeCamera.java &lt;output.y4m&gt; &lt;width&gt; &lt;height&gt;
 *       &lt;picture&gt;:&lt;frames&gt; [&lt;picture&gt;:&lt;frames&gt; …]
 */
public class FakeCamera {

    /**
     * Reads a picture, or fails with a message that says which one.
     */
    private static BufferedImage read(String path) throws IOException {
        BufferedImage image = ImageIO.read(new File(path));
        if (image == null) {
            throw new IOException("The picture could not be read: [" + path + "]");
        }
        return image;
    }

    public static void main(String[] args) throws IOException {
        String output = args[0];
        int width = Integer.parseInt(args[1]);
        int height = Integer.parseInt(args[2]);

        try (OutputStream out = new FileOutputStream(output)) {
            out.write(("YUV4MPEG2 W" + width + " H" + height + " F30:1 Ip A1:1 C420\n").getBytes("US-ASCII"));
            for (int entry = 3; entry < args.length; entry++) {
                // The path may carry a drive letter, so the frame count is only read
                // when the last part of the argument is a number.
                String argument = args[entry];
                int frames = 30;
                int colon = argument.lastIndexOf(':');
                if (colon > 1 && argument.substring(colon + 1).matches("\\d+")) {
                    frames = Integer.parseInt(argument.substring(colon + 1));
                    argument = argument.substring(0, colon);
                }

                // Two pictures joined with a plus are put side by side, so a frame can
                // carry a face and a code at the same time.
                BufferedImage left;
                BufferedImage right = null;
                int plus = argument.indexOf('+');
                if (plus > 0) {
                    left = read(argument.substring(0, plus));
                    right = read(argument.substring(plus + 1));
                } else {
                    left = read(argument);
                }
                BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = scaled.createGraphics();
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                if (right != null) {
                    int half = width / 2;
                    graphics.drawImage(left, 0, 0, half, height, null);
                    graphics.drawImage(right, half, 0, width - half, height, null);
                } else {
                    graphics.drawImage(left, 0, 0, width, height, null);
                }
                graphics.dispose();

                byte[] luma = new byte[width * height];
                byte[] chromaU = new byte[width * height / 4];
                byte[] chromaV = new byte[width * height / 4];
                float[] u = new float[width * height];
                float[] v = new float[width * height];
                int[] pixels = scaled.getRGB(0, 0, width, height, null, 0, width);
                for (int i = 0; i < pixels.length; i++) {
                    int argb = pixels[i];
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    luma[i] = (byte) Math.max(0, Math.min(255, Math.round(0.299f * r + 0.587f * g + 0.114f * b)));
                    u[i] = -0.169f * r - 0.331f * g + 0.5f * b + 128f;
                    v[i] = 0.5f * r - 0.419f * g - 0.081f * b + 128f;
                }
                int index = 0;
                for (int row = 0; row < height / 2; row++) {
                    for (int column = 0; column < width / 2; column++) {
                        float sumU = 0f;
                        float sumV = 0f;
                        for (int dy = 0; dy < 2; dy++) {
                            for (int dx = 0; dx < 2; dx++) {
                                int pixel = (row * 2 + dy) * width + (column * 2 + dx);
                                sumU += u[pixel];
                                sumV += v[pixel];
                            }
                        }
                        chromaU[index] = (byte) Math.max(0, Math.min(255, Math.round(sumU / 4f)));
                        chromaV[index] = (byte) Math.max(0, Math.min(255, Math.round(sumV / 4f)));
                        index++;
                    }
                }

                for (int frame = 0; frame < frames; frame++) {
                    out.write("FRAME\n".getBytes("US-ASCII"));
                    out.write(luma);
                    out.write(chromaU);
                    out.write(chromaV);
                }
                System.out.println("  " + argument + " for " + frames + " frames");
            }
        }
        System.out.println("Wrote " + output + " (" + width + "x" + height + ")");
    }
}
