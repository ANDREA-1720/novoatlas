package com.thedeathlycow.novoatlas.neoforge;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class NovoAtlasPlatformImpl {
    public static boolean isModLoaded(String modid) {
        return LoadingModList.get().getModFileById(modid) != null;
    }

    public static Path getConfigPath() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("novoatlas");
        try {
            Files.createDirectories(configPath);
        } catch (IOException e){
            throw new RuntimeException(e.getMessage());
        }
        return configPath;
    }
}