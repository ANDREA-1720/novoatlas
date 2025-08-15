package com.thedeathlycow.novoatlas.registry;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.thedeathlycow.novoatlas.NovoAtlas;
import com.thedeathlycow.novoatlas.world.gen.MapImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.thedeathlycow.novoatlas.NovoAtlasPlatform.getConfigPath;

public final class ChunkedImageManager {
    private ChunkedImageManager() {}
    private static final int DEFAULT_CACHE_SIZE = 100;

    /** Size in pixels of each image chunk. Must remain a power of two. */
    private static final int CHUNK_SIZE = 512;
    private static final int CHUNK_BITS = 9;            // 2^9 = 512
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;

    private static final Path CONFIG_PATH = getConfigPath();

    public static final int width;
    public static final int height;

    private static final byte TYPE_HEIGHTMAP = 0;
    private static final byte TYPE_BIOME_MAP = 1;

    // Local cache for avoiding ConcurrentHashMap overhead
    private static final ThreadLocal<LocalCache> TL_LOCAL_CACHE =
            ThreadLocal.withInitial(LocalCache::new);

    private static final class LocalCache {
        ImageKey lastKey;
        PixelData lastData;
    }

    static {
        ImageIO.setUseCache(false);

        int derivedWidth;
        int derivedHeight;
        try (Stream<Path> xDirsStream = Files.list(CONFIG_PATH)) {
            List<Path> xDirs = xDirsStream
                    .filter(Files::isDirectory)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        for (int i = 0, n = name.length(); i < n; i++) {
                            char c = name.charAt(i);
                            if (c < '0' || c > '9') return false;
                        }
                        return !name.isEmpty();
                    })
                    .toList();

            int xCount = xDirs.size();

            Optional<Path> zeroDirOpt = xDirs.stream()
                    .filter(path -> path.getFileName().toString().equals("0"))
                    .findFirst();

            int yCount = 0;
            if (zeroDirOpt.isPresent()) {
                Path zeroDir = zeroDirOpt.get();
                try (Stream<Path> yDirsStream = Files.list(zeroDir)) {
                    List<Path> yDirs = yDirsStream
                            .filter(Files::isDirectory)
                            .filter(path -> {
                                String name = path.getFileName().toString();
                                for (int i = 0, n = name.length(); i < n; i++) {
                                    char c = name.charAt(i);
                                    if (c < '0' || c > '9') return false;
                                }
                                return !name.isEmpty();
                            })
                            .toList();
                    yCount = yDirs.size();
                }
            }

            derivedWidth = xCount * CHUNK_SIZE;
            derivedHeight = yCount * CHUNK_SIZE;
        } catch (IOException e) {
            throw new RuntimeException("Failed computing atlas dimensions under " + CONFIG_PATH, e);
        }
        width = derivedWidth;
        height = derivedHeight;
    }

    /**
     * Compact wrapper for pixel array and its dimensions.
     * Pixels are stored row-major: idx = y * width + x.
     */
    private record PixelData(int[] pixels, int width, int height) {
        int getPixel(int x, int y) {
            return pixels[y * width + x];
        }
    }

    /**
     * Immutable cache key. Avoid strings in hot path.
     */
    private record ImageKey(int imageX, int imageY, byte type) {}

    private static final LoadingCache<ImageKey, PixelData> pixelCache;

    static {
        String pixelCacheSizeProperty = System.getProperty("novoatlas.pixelCache.size");
        int maxPixelCacheSize = (pixelCacheSizeProperty != null)
                ? Integer.parseInt(pixelCacheSizeProperty)
                : DEFAULT_CACHE_SIZE;

        pixelCache = Caffeine.newBuilder()
                .maximumSize(maxPixelCacheSize)
                .build(ChunkedImageManager::loadPixelData);
    }

    /**
     * Hot-path pixel fetch.
     * @param x world X (pixel) coordinate
     * @param y world Y (pixel) coordinate
     * @param typeId 0 = heightmap, 1 = biome_map
     * @return pixel value, or Integer.MIN_VALUE on failure
     */
    public static int getPixel(int x, int y, byte typeId) {
        final int imageX = x >>> CHUNK_BITS;
        final int imageY = y >>> CHUNK_BITS;
        final int actualX = x & CHUNK_MASK;
        final int actualY = y & CHUNK_MASK;

        LocalCache local = TL_LOCAL_CACHE.get();
        ImageKey key = local.lastKey;

        if (key != null &&
                key.imageX == imageX &&
                key.imageY == imageY &&
                key.type == typeId) {
            return local.lastData.getPixel(actualX, actualY);
        }

        ImageKey newKey = new ImageKey(imageX, imageY, typeId);
        PixelData pd = pixelCache.get(newKey);

        local.lastKey = newKey;
        local.lastData = pd;

        assert pd != null;
        return pd.getPixel(actualX, actualY);
    }


    public static String getTypeString(MapImage.Type type) {
        return (type == MapImage.Type.HEIGHTMAP) ? "heightmap" : "biome_map";
    }

    /**
     * Cache loader. Performs blocking file I/O and decoding.
     */
    private static PixelData loadPixelData(ImageKey key) {
        final String typeStr = (key.type == TYPE_HEIGHTMAP) ? "heightmap" : "biome_map";
        // Build path with Path.resolve, avoid string "/" concatenations
        Path imagePath = CONFIG_PATH
                .resolve(Integer.toString(key.imageX))
                .resolve(Integer.toString(key.imageY))
                .resolve(typeStr + ".png");

        try (InputStream is = Files.newInputStream(imagePath)) {
            BufferedImage image = ImageIO.read(is);
            if (image == null) {
                NovoAtlas.LOGGER.warn("Failed to decode image: {}", imagePath);
                return null;
            }
            return extractPixelData1D(image, key.type);
        } catch (IOException e) {
            NovoAtlas.LOGGER.debug("I/O error reading {}: {}", imagePath, e.toString());
            return null;
        } catch (RuntimeException e) {
            NovoAtlas.LOGGER.debug("Unexpected error loading {}: {}", imagePath, e.toString());
            return null;
        }
    }

    /**
     * Convert BufferedImage into a packed int[] (row-major).
     * For heightmaps: grayscale int per pixel (band 0).
     * For color maps: 24-bit RGB (alpha stripped).
     */
    private static PixelData extractPixelData1D(BufferedImage image, byte typeId) {
        final int w = image.getWidth();
        final int h = image.getHeight();
        final int[] pixelData = new int[w * h];

        if (typeId == TYPE_HEIGHTMAP) {
            WritableRaster raster = image.getRaster();
            if (raster.getNumBands() == 1) {
                // Bulk fetch of single band
                int[] samples = raster.getSamples(0, 0, w, h, 0, (int[]) null);
                System.arraycopy(samples, 0, pixelData, 0, samples.length);
            } else {
                // Fallback: take band 0 (still fast, but not bulk)
                for (int yy = 0, idx = 0; yy < h; yy++) {
                    for (int xx = 0; xx < w; xx++, idx++) {
                        pixelData[idx] = raster.getSample(xx, yy, 0);
                    }
                }
            }
        } else {
            // Color: fetch ARGB, strip alpha to 24-bit RGB
            image.getRGB(0, 0, w, h, pixelData, 0, w);
            for (int i = 0; i < pixelData.length; i++) {
                pixelData[i] &= 0x00FF_FFFF;
            }
        }

        return new PixelData(pixelData, w, h);
    }
}