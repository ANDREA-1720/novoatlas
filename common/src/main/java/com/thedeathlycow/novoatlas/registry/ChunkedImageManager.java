package com.thedeathlycow.novoatlas.registry;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static com.thedeathlycow.novoatlas.NovoAtlasPlatform.getConfigPath;

public class ChunkedImageManager {
    private static final int DEFAULT_CACHE_SIZE = 100;
    private static final int CHUNK_SIZE = 512;

    private static final Path CONFIG_PATH = getConfigPath();
    public static final int width;
    public static final int height;

    static{
        try (Stream<Path> xDirsStream = Files.list(CONFIG_PATH)) {
            List<Path> xDirs = xDirsStream
                    .filter(Files::isDirectory)
                    .filter(path -> {
                        try {
                            return path.getFileName().toString().matches("\\d+");
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .toList();

            int xCount = xDirs.size();

            // Find "0" directory from xDirs
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
                                try {
                                    return path.getFileName().toString().matches("\\d+");
                                } catch (Exception e) {
                                    return false;
                                }
                            })
                            .toList();

                    yCount = yDirs.size();
                }
            }

            width = xCount * CHUNK_SIZE;
            height = yCount * CHUNK_SIZE;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param pixels 1D array for better memory efficiency
     */
    // Wrapper class for pixel data with dimensions
    private record PixelData(int[] pixels, int width, int height) {
        // Fast inline method for 2D to 1D index conversion
        int getPixel(int x, int y) {
            return pixels[y * width + x];
        }
    }

    // High-performance concurrent LRU cache for pixel data using Caffeine
    private static final AsyncLoadingCache<String, PixelData> pixelCache;

    // Virtual thread executor for async operations
    private static final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    static {
        // Allow configuration of pixel cache size via system property
        String pixelCacheSizeProperty = System.getProperty("novoatlas.pixelCache.size");
        int maxPixelCacheSize =
                pixelCacheSizeProperty != null ? Integer.parseInt(pixelCacheSizeProperty) : DEFAULT_CACHE_SIZE;

        // Configure main pixel cache with async loading.
        pixelCache =
                Caffeine.newBuilder()
                        .maximumSize(maxPixelCacheSize)
                        .buildAsync(
                                (key, executor) ->
                                        CompletableFuture.supplyAsync(
                                                () -> loadPixelData(key), virtualExecutor
                                        )
                        ); // Explicitly use our virtualExecutor for loading

    }

    /** Main method to get pixel value at world coordinates */
    public static int getPixel(int x, int y, String type) {
        int imageX = x / CHUNK_SIZE;
        int imageY = y / CHUNK_SIZE;

        String imageKey = buildImageKey(imageX, imageY, type);

        // Get pixel data from cache (thread-safe and efficient)
        PixelData pixelData = getPixelDataFromCache(imageKey);

        if (pixelData != null) {
            int actualX = x - (imageX * CHUNK_SIZE);
            int actualY = y - (imageY * CHUNK_SIZE);

            return pixelData.getPixel(actualX, actualY);
        }

        return Integer.MIN_VALUE;
    }

    private static String buildImageKey(int imageX, int imageY, String type) {
        return imageX + "/" + imageY + "/" + type + ".png";
    }

    private static PixelData getPixelDataFromCache(String imageKey) {
        try {
            // Caffeine loading cache handles all the concurrent access and loading logic.
            // We've configured it to use `loadPixelData` asynchronously.
            CompletableFuture<PixelData> future = pixelCache.get(imageKey);

            return future.join();
        } catch (Exception e) {
            NovoAtlas.LOGGER.error("Error loading pixel data for: {}", imageKey, e);
            return null;
        }
    }

    /**
     * Asynchronous loading method for Caffeine cache.
     * This method performs the actual blocking file I/O and image decoding.
     * It is designed to be called within a CompletableFuture.supplyAsync,
     * ensuring that the blocking work is done on the designated executor.
     */
    private static PixelData loadPixelData(String imageKey) {
        Path imagePath = CONFIG_PATH.resolve(imageKey);

        try (InputStream is = Files.newInputStream(imagePath)) {
            BufferedImage image = ImageIO.read(is); // This is the blocking call
            if (image != null) {
                return extractPixelData1D(image, imageKey);
            } else {
                NovoAtlas.LOGGER.warn("Failed to decode image {}", imagePath);
            }
        } catch (IOException e) {
            NovoAtlas.LOGGER.debug("Error reading image {}: {}", imagePath, e.getMessage());
            // Wrap IOException in an unchecked exception for CompletableFuture to handle
            throw new RuntimeException("Failed to load image: " + imageKey, e);
        }

        return null;
    }

    private static PixelData extractPixelData1D(BufferedImage image, String imageKey) {
        int width = image.getWidth();
        int height = image.getHeight();

        // Determine if this is a heightmap based on the key
        // This still works because the 'type' (e.g., "heightmap") is part of the imageKey.
        boolean isHeightmap = imageKey.contains("heightmap");

        int[] pixelData = new int[width * height];
        if (isHeightmap) {
            // For heightmaps, extract grayscale values from raster
            WritableRaster raster = image.getRaster();

            // Use bulk data extraction if possible
            if (raster.getNumBands() == 1) {
                // Single band - use bulk extraction
                int[] samples = raster.getSamples(0, 0, width, height, 0, (int[]) null);
                System.arraycopy(samples, 0, pixelData, 0, samples.length);
            } else {
                // Fallback for multi-band rasters
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        pixelData[y * width + x] = raster.getSample(x, y, 0);
                    }
                }
            }

        } else {
            // For color images, use bulk RGB extraction

            image.getRGB(0, 0, width, height, pixelData, 0, width);

            // Apply the RGB mask to remove alpha channel if needed
            for (int i = 0; i < pixelData.length; i++) {
                pixelData[i] = pixelData[i] & 0xffffff;
            }
        }
        return new PixelData(pixelData, width, height);
    }

    public static String getTypeString(MapImage.Type type){
        return type == MapImage.Type.HEIGHTMAP ? "heightmap" : "biome_map";
    }
}