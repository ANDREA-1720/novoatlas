package com.thedeathlycow.novoatlas.world.gen;

import com.thedeathlycow.novoatlas.registry.ChunkedImageManager;
import net.minecraft.util.Mth;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;

import static com.thedeathlycow.novoatlas.registry.ChunkedImageManager.getPixel;

public record MapImage(
        Type type
) {
    public enum Type {
        BIOME_MAP,
        HEIGHTMAP
    }

    public static MapImage fromBufferedImage(BufferedImage image, Type type) {
        return new MapImage(type);
    }

    private static int[][] getGrayScalePixels(BufferedImage image, int width, int height) {
        int[][] pixels = new int[width][height];
        Raster raster = image.getRaster();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                pixels[x][y] = raster.getSample(x, y, 0);
            }
        }

        return pixels;
    }

    private static int[][] getColorPixels(BufferedImage image, int width, int height) {
        int[] data = new int[width * height];
        image.getRGB(0, 0, width, height, data, 0, width);

        int x = 0;
        int y = 0;
        int[][] pixels = new int[width][height];

        for (int datum : data) {
            if (x >= width) {
                x = 0;
                y++;
            }
            pixels[x++][y] = datum & 0xffffff;
        }

        return pixels;
    }

    public int sample(int x, int z, MapInfo info) {
        return this.sample(x, z, info, Integer.MIN_VALUE);
    }

    public int sample(int x, int z, MapInfo info, int fallback) {
        float horizontalScale = info.horizontalScale().value();

        int width = ChunkedImageManager.width;
        int height = ChunkedImageManager.height;

        double xR = (x / horizontalScale) + width / 2.0; // these will always be even numbers
        double zR = (z / horizontalScale) + height / 2.0;

        if (xR < 0 || zR < 0 || xR >= width || zR >= height) {
            return fallback;
        }

        return this.sampleDirect(xR, zR, info);
    }

    private int sampleDirect(double x, double z, MapInfo info) {
        if (this.type == Type.HEIGHTMAP) {
            double height = info.horizontalScale().interpolate(x, z, this);
            return Mth.floor(info.verticalScale() * height + info.startingY());
        } else {
            return this.getTruncated(x, z, (byte) 1);
        }
    }

    int getTruncated(double x, double z, Byte type) {
        int truncatedX = Mth.floor(x);
        int truncatedZ = Mth.floor(z);
        return getPixel(truncatedX, truncatedZ, type);
    }
}