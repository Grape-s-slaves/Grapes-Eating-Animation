package net.grapes.gea;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EatingAnimationConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final Gson GSON = new Gson();
    private static final Path CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("gea-animations.json");

    private static Map<String, List<String>> animationMap = new HashMap<>();

    static {
        SPEC = BUILDER.build();
        loadConfig();
    }

    public static void loadConfig() {
        GrapesEatingAnimation.LOGGER.info("GEA: Loading configuration from {}", CONFIG_FILE);
        try {
            if (!Files.exists(CONFIG_FILE)) {
                GrapesEatingAnimation.LOGGER.info("GEA: Config file doesn't exist, creating default");
                createDefaultConfig();
            }

            try (FileReader reader = new FileReader(CONFIG_FILE.toFile())) {
                Type type = new TypeToken<Map<String, List<String>>>(){}.getType();
                Map<String, List<String>> loadedMap = GSON.fromJson(reader, type);
                if (loadedMap != null) {
                    animationMap = loadedMap;
                    GrapesEatingAnimation.LOGGER.info("GEA: Successfully loaded {} eating animations", animationMap.size());
                    for (Map.Entry<String, List<String>> entry : animationMap.entrySet()) {
                        GrapesEatingAnimation.LOGGER.debug("GEA: Animation for {}: {} frames", entry.getKey(), entry.getValue().size());
                    }
                } else {
                    GrapesEatingAnimation.LOGGER.warn("GEA: Loaded config is null, using empty map");
                }
            }
        } catch (IOException e) {
            GrapesEatingAnimation.LOGGER.error("GEA: Failed to load eating animation config", e);
        }
    }

    private static void createDefaultConfig() {
        try {
            Map<String, List<String>> defaultConfig = new HashMap<>();
            defaultConfig.put("minecraft:apple", List.of("anim:apple_0", "anim:apple_1", "anim:apple_2"));
            defaultConfig.put("minecraft:bread", List.of("anim:bread_0", "anim:bread_1"));

            try (FileWriter writer = new FileWriter(CONFIG_FILE.toFile())) {
                GSON.toJson(defaultConfig, writer);
            }

            GrapesEatingAnimation.LOGGER.info("Created default eating animation config");
        } catch (IOException e) {
            GrapesEatingAnimation.LOGGER.error("Failed to create default config", e);
        }
    }

    public static List<String> getAnimationFrames(ResourceLocation itemId) {
        List<String> frames = animationMap.get(itemId.toString());
        if (frames != null) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Found {} frames for item {}", frames.size(), itemId);
        } else {
            GrapesEatingAnimation.LOGGER.debug("GEA: No animation found for item {}", itemId);
        }
        return frames;
    }

    public static boolean hasAnimation(ResourceLocation itemId) {
        boolean hasAnim = animationMap.containsKey(itemId.toString());
        GrapesEatingAnimation.LOGGER.debug("GEA: Item {} has animation: {}", itemId, hasAnim);
        return hasAnim;
    }

    public static Map<String, List<String>> getAllAnimations() {
        return new HashMap<>(animationMap);
    }

    public static void reloadConfig() {
        loadConfig();
    }
}