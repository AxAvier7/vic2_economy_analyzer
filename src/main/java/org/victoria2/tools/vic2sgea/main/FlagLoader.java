package org.victoria2.tools.vic2sgea.main;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads country flags from the game/mod folders. Victoria II ships flags as TGA,
 * which JavaFX cannot decode, so those are converted on the fly. Lookup order is
 * mod folder, game folder, then the png set bundled into the jar.
 */
public final class FlagLoader {

    private static final String[] DISK_EXTENSIONS = {".tga", ".png", ".bmp"};
    private static final Map<String, Image> CACHE = new HashMap<>();

    private FlagLoader() {
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    public static synchronized Image getFlag(String tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        return CACHE.computeIfAbsent(tag, FlagLoader::load);
    }

    private static Image load(String tag) {
        for (Path root : roots()) {
            Path flagsDir = root.resolve("gfx").resolve("flags");
            for (String ext : DISK_EXTENSIONS) {
                Path candidate = flagsDir.resolve(tag + ext);
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                try {
                    return read(candidate);
                } catch (IOException e) {
                    System.out.println("Could not read flag " + candidate + ": " + e);
                }
            }
        }

        URL bundled = FlagLoader.class.getResource("/flags/" + tag + ".png");
        if (bundled != null) {
            return new Image(bundled.toString());
        }
        return null;
    }

    private static List<Path> roots() {
        List<Path> roots = new ArrayList<>(2);
        PathKeeper.getModPath().ifPresent(roots::add);
        PathKeeper.getLocalisationPath().ifPresent(roots::add);
        return roots;
    }

    private static Image read(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".tga")) {
            return readTga(Files.readAllBytes(file));
        }
        try (InputStream in = Files.newInputStream(file)) {
            return new Image(in);
        }
    }

    private static Image readTga(byte[] b) throws IOException {
        if (b.length < 18) {
            throw new IOException("truncated tga header");
        }

        int idLength = b[0] & 0xFF;
        int colorMapType = b[1] & 0xFF;
        int imageType = b[2] & 0xFF;
        int width = (b[12] & 0xFF) | ((b[13] & 0xFF) << 8);
        int height = (b[14] & 0xFF) | ((b[15] & 0xFF) << 8);
        int bitsPerPixel = b[16] & 0xFF;
        int descriptor = b[17] & 0xFF;

        boolean indexMapped = colorMapType == 1;
        if (colorMapType != 0 && !indexMapped) {
            throw new IOException("unsupported tga colour map type " + colorMapType);
        }
        if (imageType != 1 && imageType != 2 && imageType != 3
                && imageType != 9 && imageType != 10 && imageType != 11) {
            throw new IOException("unsupported tga image type " + imageType);
        }
        if (indexMapped && bitsPerPixel != 8) {
            throw new IOException("unsupported indexed tga depth " + bitsPerPixel + "bpp");
        }
        if (!indexMapped && bitsPerPixel != 8 && bitsPerPixel != 24 && bitsPerPixel != 32) {
            throw new IOException("unsupported tga depth " + bitsPerPixel + "bpp");
        }
        if (width <= 0 || height <= 0) {
            throw new IOException("invalid tga size " + width + "x" + height);
        }

        boolean rle = imageType == 9 || imageType == 10 || imageType == 11;
        int bytesPerPixel = bitsPerPixel / 8;
        int offset = 18 + idLength;

        int[] colorMap = null;
        if (indexMapped) {
            int mapFirst = (b[3] & 0xFF) | ((b[4] & 0xFF) << 8);
            int mapLength = (b[5] & 0xFF) | ((b[6] & 0xFF) << 8);
            int mapDepth = b[7] & 0xFF;
            if (mapDepth != 16 && mapDepth != 24 && mapDepth != 32) {
                throw new IOException("unsupported tga colour map depth " + mapDepth);
            }
            colorMap = new int[mapFirst + mapLength];
            int mapBytes = mapDepth / 8;
            for (int i = 0; i < mapLength; i++) {
                colorMap[mapFirst + i] = readPixel(b, offset + i * mapBytes, mapDepth, null);
            }
            offset += mapLength * mapBytes;
        }

        int[] argb = new int[width * height];
        int pixelCount = width * height;
        int src = offset;
        int dst = 0;
        int maxAlpha = 0;

        while (dst < pixelCount) {
            if (rle) {
                int packet = b[src++] & 0xFF;
                int runLength = (packet & 0x7F) + 1;
                if ((packet & 0x80) != 0) {
                    int pixel = readPixel(b, src, bitsPerPixel, colorMap);
                    maxAlpha = Math.max(maxAlpha, pixel >>> 24);
                    src += bytesPerPixel;
                    for (int i = 0; i < runLength && dst < pixelCount; i++) {
                        argb[dst++] = pixel;
                    }
                } else {
                    for (int i = 0; i < runLength && dst < pixelCount; i++) {
                        int pixel = readPixel(b, src, bitsPerPixel, colorMap);
                        maxAlpha = Math.max(maxAlpha, pixel >>> 24);
                        src += bytesPerPixel;
                        argb[dst++] = pixel;
                    }
                }
            } else {
                int pixel = readPixel(b, src, bitsPerPixel, colorMap);
                maxAlpha = Math.max(maxAlpha, pixel >>> 24);
                src += bytesPerPixel;
                argb[dst++] = pixel;
            }
        }

        // some flags are stored fully transparent despite the alpha channel being declared
        if (maxAlpha == 0) {
            for (int i = 0; i < argb.length; i++) {
                argb[i] |= 0xFF000000;
            }
        }

        // bit 5 of the descriptor marks a top left origin, default is bottom left
        if ((descriptor & 0x20) == 0) {
            flipVertically(argb, width, height);
        }

        WritableImage image = new WritableImage(width, height);
        image.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), argb, 0, width);
        return image;
    }

    private static int readPixel(byte[] b, int offset, int bitsPerPixel, int[] colorMap) {
        if (colorMap != null) {
            return colorMap[b[offset] & 0xFF];
        }
        if (bitsPerPixel == 8) {
            int gray = b[offset] & 0xFF;
            return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }
        if (bitsPerPixel == 16) {
            int value = (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
            int alpha = (value & 0x8000) != 0 ? 0xFF : 0x00;
            int red = (value >> 10) & 0x1F;
            int green = (value >> 5) & 0x1F;
            int blue = value & 0x1F;
            return (alpha << 24)
                    | ((red << 3 | red >> 2) << 16)
                    | ((green << 3 | green >> 2) << 8)
                    | (blue << 3 | blue >> 2);
        }

        int blue = b[offset] & 0xFF;
        int green = b[offset + 1] & 0xFF;
        int red = b[offset + 2] & 0xFF;
        int alpha = bitsPerPixel == 32 ? b[offset + 3] & 0xFF : 0xFF;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static void flipVertically(int[] pixels, int width, int height) {
        int[] row = new int[width];
        for (int y = 0; y < height / 2; y++) {
            int top = y * width;
            int bottom = (height - 1 - y) * width;
            System.arraycopy(pixels, top, row, 0, width);
            System.arraycopy(pixels, bottom, pixels, top, width);
            System.arraycopy(row, 0, pixels, bottom, width);
        }
    }
}
