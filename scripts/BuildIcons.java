import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Builds the application icons and the favicon from the brand picture.
 *
 * <p>The picture is trimmed to the mark, the mark is placed on the dark background of
 * the application and the result is written as the icons of the manifest. The favicon
 * is an SVG that carries the small picture, so the browser has one file to fetch.
 *
 * <p>Usage: java BuildIcons.java &lt;source.jpg&gt; &lt;icons-directory&gt;
 */
public class BuildIcons {

    /** The dark background of the brand. */
    private static final Color BACKGROUND = new Color(0x0b, 0x0b, 0x0d);

    public static void main(String[] args) throws IOException {
        Path source = Path.of(args[0]);
        Path directory = Path.of(args[1]);
        Files.createDirectories(directory);

        BufferedImage picture = ImageIO.read(source.toFile());
        if (picture == null) {
            throw new IOException("The picture could not be read: " + source);
        }
        System.out.println("The picture is " + picture.getWidth() + "x" + picture.getHeight());

        // The background of the picture is used, so the edge of the mark does not show.
        Color background = new Color(picture.getRGB(4, 4));

        Rectangle mark = findMark(picture);
        System.out.println("The mark sits at " + mark);

        write(directory.resolve("icon-192.png"), square(picture, mark, 192, 0.80d, background));
        write(directory.resolve("icon-512.png"), square(picture, mark, 512, 0.86d, background));
        write(directory.resolve("icon-maskable-512.png"), square(picture, mark, 512, 0.62d, background));
        write(directory.resolve("icon-180.png"), square(picture, mark, 180, 0.86d, background));
        write(directory.resolve("logo.png"), square(picture, mark, 256, 0.92d, background));

        // The favicon carries a small picture inside an SVG, so the browser needs one
        // file and the mark stays sharp on every screen.
        BufferedImage small = square(picture, mark, 64, 0.92d, background);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(small, "png", buffer);
        String encoded = Base64.getEncoder().encodeToString(buffer.toByteArray());
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\" role=\"img\""
                + " aria-label=\"VeriQra\">\n"
                + "  <rect width=\"64\" height=\"64\" rx=\"12\" fill=\"#0b0b0d\"/>\n"
                + "  <image href=\"data:image/png;base64," + encoded + "\" width=\"64\" height=\"64\"/>\n"
                + "</svg>\n";
        Files.writeString(directory.resolve("favicon.svg"), svg);
        System.out.println("Wrote the icons and the favicon in " + directory);
    }

    /** A rectangle of the picture. */
    private record Rectangle(int x, int y, int width, int height) {
    }

    /**
     * Finds the mark, which is everything that is not the dark background.
     */
    private static Rectangle findMark(BufferedImage picture) {
        int left = picture.getWidth();
        int top = picture.getHeight();
        int right = 0;
        int bottom = 0;
        for (int y = 0; y < picture.getHeight(); y++) {
            for (int x = 0; x < picture.getWidth(); x++) {
                int argb = picture.getRGB(x, y);
                int luma = (((argb >> 16) & 0xFF) * 77 + ((argb >> 8) & 0xFF) * 150 + (argb & 0xFF) * 29) >> 8;
                if (luma > 60) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right <= left || bottom <= top) {
            return new Rectangle(0, 0, picture.getWidth(), picture.getHeight());
        }
        return new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }

    /**
     * Puts the mark on the dark background, scaled to the given share of the picture.
     */
    private static BufferedImage square(BufferedImage picture, Rectangle mark, int size, double share,
                                        Color background) {
        BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setColor(background);
        graphics.fillRect(0, 0, size, size);

        double target = size * share;
        double scale = Math.min(target / mark.width(), target / mark.height());
        int width = (int) Math.round(mark.width() * scale);
        int height = (int) Math.round(mark.height() * scale);
        int x = (size - width) / 2;
        int y = (size - height) / 2;
        // The destination comes first in this form of drawImage.
        graphics.drawImage(picture, x, y, x + width, y + height,
                mark.x(), mark.y(), mark.x() + mark.width(), mark.y() + mark.height(), null);
        graphics.dispose();
        return canvas;
    }

    private static void write(Path path, BufferedImage image) throws IOException {
        ImageIO.write(image, "png", path.toFile());
        System.out.println("  wrote " + path.getFileName() + " (" + image.getWidth() + "x" + image.getHeight() + ")");
    }
}
