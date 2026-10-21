import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Turns a portrait into a face that looks to the side.
 *
 * <p>The face is warped with a perspective, so the far cheek is narrower than the near
 * one and the features move towards the middle. The liveness check measures where the
 * nose sits between the eyes, so a warped picture stands in for a head that turns. It
 * is used to test the check with a fake camera.
 *
 * <p>The picture can be cropped first, so the face fills the frame of the fake camera
 * and the landmarks are found as precisely as they would be in a real classroom.
 *
 * <p>Usage: java TurnFace.java &lt;input.jpg&gt; &lt;output.jpg&gt; &lt;turn&gt; &lt;faceCentreX&gt;
 *       &lt;faceWidth&gt; [cropX,cropY,cropWidth,cropHeight]
 */
public class TurnFace {

    public static void main(String[] args) throws IOException {
        BufferedImage source = ImageIO.read(new File(args[0]));
        if (source == null) {
            throw new IOException("The picture could not be read: " + args[0]);
        }
        double turn = Double.parseDouble(args[2]);
        int centre = Integer.parseInt(args[3]);
        int faceWidth = Integer.parseInt(args[4]);

        if (args.length > 5) {
            String[] crop = args[5].split(",");
            int x = Integer.parseInt(crop[0]);
            int y = Integer.parseInt(crop[1]);
            int width = Integer.parseInt(crop[2]);
            int height = Integer.parseInt(crop[3]);
            source = source.getSubimage(x, y, Math.min(width, source.getWidth() - x),
                    Math.min(height, source.getHeight() - y));
            centre -= x;
            System.out.println("The picture was cropped to " + source.getWidth() + "x" + source.getHeight());
        }

        double half = Math.max(1, faceWidth / 2d);
        boolean mirror = turn < 0;
        double strength = Math.abs(turn);
        int eyeLine = centre;
        if (mirror) {
            // The picture is turned the other way by turning the mirror image, so both
            // directions are the same warp and the face stays recognisable.
            source = mirror(source);
            centre = source.getWidth() - centre;
        }

        BufferedImage turned = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            // The part of the face below the eyes slides to one side, so the nose moves
            // away from the middle of the eyes while the eyes themselves stay in place.
            double shift = strength * Math.max(0, y - eyeLine) / 100d;
            for (int x = 0; x < source.getWidth(); x++) {
                int sampleX = (int) Math.round(x + shift);
                sampleX = Math.max(0, Math.min(source.getWidth() - 1, sampleX));
                turned.setRGB(x, y, source.getRGB(sampleX, y));
            }
        }
        if (mirror) {
            turned = mirror(turned);
        }
        ImageIO.write(turned, "jpg", new File(args[1]));
        System.out.println("Wrote " + args[1] + " with a turn of " + turn);
    }

    /**
     * Flips a picture from left to right.
     */
    private static BufferedImage mirror(BufferedImage source) {
        BufferedImage flipped = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                flipped.setRGB(source.getWidth() - 1 - x, y, source.getRGB(x, y));
            }
        }
        return flipped;
    }
}
