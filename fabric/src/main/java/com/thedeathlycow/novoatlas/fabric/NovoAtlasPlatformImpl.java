package com.thedeathlycow.novoatlas.fabric;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class NovoAtlasPlatformImpl {
    public static boolean isModLoaded(String modid) {
        return FabricLoader.getInstance().isModLoaded(modid);
    }

    public static Path getConfigPath() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("novoatlas");
        try {
            Files.createDirectories(configPath);
        } catch (IOException e){
            throw new RuntimeException(e.getMessage());
        }
        return configPath;
    }
}