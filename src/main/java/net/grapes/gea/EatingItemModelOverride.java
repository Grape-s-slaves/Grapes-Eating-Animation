package net.grapes.gea;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = GrapesEatingAnimation.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class EatingItemModelOverride {

    // Cache for resolved frame models to avoid repeated lookups
    private static final ConcurrentHashMap<String, BakedModel> frameModelCache = new ConcurrentHashMap<>();
    private static volatile boolean modelsRegistered = false;

    @SubscribeEvent
    public static void onModelRegister(ModelEvent.RegisterAdditional event) {
        GrapesEatingAnimation.LOGGER.info("GEA: Registering additional models");

        try {
            // Register all animation frame models so they get baked
            Map<String, List<String>> animations = EatingAnimationConfig.getAllAnimations();
            Set<String> registeredFrames = new HashSet<>();

            for (Map.Entry<String, List<String>> entry : animations.entrySet()) {
                String itemId = entry.getKey();
                List<String> frames = entry.getValue();

                if (frames == null || frames.isEmpty()) {
                    GrapesEatingAnimation.LOGGER.warn("GEA: No frames found for item: {}", itemId);
                    continue;
                }

                for (String frameName : frames) {
                    if (frameName == null || frameName.trim().isEmpty()) {
                        GrapesEatingAnimation.LOGGER.warn("GEA: Invalid frame name for item {}: '{}'", itemId, frameName);
                        continue;
                    }

                    if (!registeredFrames.contains(frameName)) {
                        try {
                            // Validate ResourceLocation format before creating
                            if (!isValidResourceLocation(frameName)) {
                                GrapesEatingAnimation.LOGGER.warn("GEA: Invalid ResourceLocation format: {}", frameName);
                                continue;
                            }

                            ResourceLocation frameLocation = new ResourceLocation(frameName);
                            ModelResourceLocation frameModelLocation = new ModelResourceLocation(
                                    frameLocation.getNamespace(),
                                    frameLocation.getPath(),
                                    "inventory"
                            );

                            event.register(frameModelLocation);
                            registeredFrames.add(frameName);
                            GrapesEatingAnimation.LOGGER.debug("GEA: Registered frame model: {}", frameModelLocation);

                        } catch (Exception e) {
                            GrapesEatingAnimation.LOGGER.warn("GEA: Failed to register frame model '{}' for item '{}': {}",
                                    frameName, itemId, e.getMessage());
                        }
                    }
                }
            }

            GrapesEatingAnimation.LOGGER.info("GEA: Successfully registered {} unique frame models", registeredFrames.size());

        } catch (Exception e) {
            GrapesEatingAnimation.LOGGER.error("GEA: Critical error during model registration", e);
        }
    }

    @SubscribeEvent
    public static void onModelBake(ModelEvent.ModifyBakingResult event) {
        GrapesEatingAnimation.LOGGER.info("GEA: Model baking event triggered");

        try {
            Map<ResourceLocation, BakedModel> modelRegistry = event.getModels();

            // Clear frame model cache when models are rebaked (resource pack reload)
            frameModelCache.clear();
            modelsRegistered = true;

            // Override models for items that have eating animations
            Map<String, List<String>> animations = EatingAnimationConfig.getAllAnimations();
            GrapesEatingAnimation.LOGGER.info("GEA: Processing {} animated items", animations.size());

            int successCount = 0;
            for (String itemKey : animations.keySet()) {
                try {
                    if (!isValidResourceLocation(itemKey)) {
                        GrapesEatingAnimation.LOGGER.warn("GEA: Invalid item ResourceLocation: {}", itemKey);
                        continue;
                    }

                    ResourceLocation itemId = new ResourceLocation(itemKey);
                    ModelResourceLocation modelLocation = new ModelResourceLocation(itemId, "inventory");

                    BakedModel originalModel = modelRegistry.get(modelLocation);
                    if (originalModel != null) {
                        BakedModel wrappedModel = new EatingAnimatedBakedModel(originalModel, itemId);
                        modelRegistry.put(modelLocation, wrappedModel);
                        successCount++;
                        GrapesEatingAnimation.LOGGER.debug("GEA: Wrapped model for item: {}", itemId);
                    } else {
                        GrapesEatingAnimation.LOGGER.warn("GEA: Could not find original model for item: {}", itemId);
                    }
                } catch (Exception e) {
                    GrapesEatingAnimation.LOGGER.warn("GEA: Failed to wrap model for item '{}': {}", itemKey, e.getMessage());
                }
            }

            GrapesEatingAnimation.LOGGER.info("GEA: Successfully wrapped {} item models", successCount);

        } catch (Exception e) {
            GrapesEatingAnimation.LOGGER.error("GEA: Critical error during model baking", e);
        }
    }

    /**
     * Validates if a string is a valid ResourceLocation format
     */
    private static boolean isValidResourceLocation(String location) {
        if (location == null || location.trim().isEmpty()) {
            return false;
        }

        // Basic validation for ResourceLocation format (namespace:path)
        String[] parts = location.split(":", 2);
        if (parts.length != 2) {
            return false;
        }

        String namespace = parts[0];
        String path = parts[1];

        // Check for valid characters (simplified check)
        return namespace.matches("[a-z0-9_.-]+") && path.matches("[a-z0-9_.//-]+");
    }

    private static class EatingAnimatedBakedModel implements BakedModel {
        private final BakedModel originalModel;
        private final ResourceLocation itemId;
        private final ItemOverrides itemOverrides;

        public EatingAnimatedBakedModel(BakedModel originalModel, ResourceLocation itemId) {
            this.originalModel = originalModel;
            this.itemId = itemId;
            this.itemOverrides = new EatingItemOverrides(originalModel.getOverrides(), itemId);
        }

        @Override
        public ItemOverrides getOverrides() {
            return itemOverrides;
        }

        // Delegate all other methods to the original model
        @Override
        public List<net.minecraft.client.renderer.block.model.BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
            return originalModel.getQuads(state, side, rand);
        }

        @Override
        public boolean useAmbientOcclusion() {
            return originalModel.useAmbientOcclusion();
        }

        @Override
        public boolean isGui3d() {
            return originalModel.isGui3d();
        }

        @Override
        public boolean usesBlockLight() {
            return originalModel.usesBlockLight();
        }

        @Override
        public boolean isCustomRenderer() {
            return originalModel.isCustomRenderer();
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return originalModel.getParticleIcon();
        }

        @Override
        public net.minecraft.client.renderer.block.model.ItemTransforms getTransforms() {
            return originalModel.getTransforms();
        }
    }

    private static class EatingItemOverrides extends ItemOverrides {
        private final ItemOverrides originalOverrides;
        private final ResourceLocation itemId;

        public EatingItemOverrides(ItemOverrides originalOverrides, ResourceLocation itemId) {
            this.originalOverrides = originalOverrides;
            this.itemId = itemId;
        }

        @Override
        public BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel world, @Nullable LivingEntity entity, int seed) {
            // Early return if models haven't been registered yet
            if (!modelsRegistered) {
                return originalOverrides.resolve(model, stack, world, entity, seed);
            }

            // Check if this is a player eating this specific item
            if (entity instanceof Player) {
                Player player = (Player) entity;

                if (player.isUsingItem() && stack.isEdible() && itemMatches(stack, itemId)) {
                    try {
                        // Update animation state
                        EatingAnimationHandler.updateAnimationState(player);
                        EatingAnimationHandler.EatingAnimationState state = EatingAnimationHandler.getAnimationState(player);

                        if (state != null) {
                            String currentFrame = state.getCurrentFrame();
                            if (currentFrame != null) {
                                // Try to resolve the frame model with caching
                                BakedModel frameModel = resolveFrameModelCached(currentFrame);
                                if (frameModel != null) {
                                    GrapesEatingAnimation.LOGGER.debug("GEA: Using frame model: {}", currentFrame);
                                    return frameModel;
                                } else {
                                    GrapesEatingAnimation.LOGGER.debug("GEA: Frame model not available: {}", currentFrame);
                                }
                            }
                        }
                    } catch (Exception e) {
                        GrapesEatingAnimation.LOGGER.warn("GEA: Error during model resolution: {}", e.getMessage());
                    }
                } else {
                    // Clear animation state if not eating
                    EatingAnimationHandler.clearAnimationState(player);
                }
            }

            // Fall back to original override resolution
            return originalOverrides.resolve(model, stack, world, entity, seed);
        }

        private boolean itemMatches(ItemStack stack, ResourceLocation expectedId) {
            try {
                ResourceLocation actualId = ForgeRegistries.ITEMS.getKey(stack.getItem());
                return expectedId.equals(actualId);
            } catch (Exception e) {
                GrapesEatingAnimation.LOGGER.debug("GEA: Error checking item match: {}", e.getMessage());
                return false;
            }
        }

        private BakedModel resolveFrameModelCached(String frameName) {
            // Check cache first
            BakedModel cached = frameModelCache.get(frameName);
            if (cached != null) {
                return cached;
            }

            try {
                if (!isValidResourceLocation(frameName)) {
                    GrapesEatingAnimation.LOGGER.debug("GEA: Invalid frame ResourceLocation: {}", frameName);
                    return null;
                }

                ResourceLocation frameLocation = new ResourceLocation(frameName);
                ModelResourceLocation frameModelLocation = new ModelResourceLocation(
                        frameLocation.getNamespace(),
                        frameLocation.getPath(),
                        "inventory"
                );

                GrapesEatingAnimation.LOGGER.debug("GEA: Looking for frame model at: {}", frameModelLocation);

                // Get the model from the model manager
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.getModelManager() == null) {
                    GrapesEatingAnimation.LOGGER.debug("GEA: Model manager not available");
                    return null;
                }

                BakedModel frameModel = minecraft.getModelManager().getModel(frameModelLocation);
                BakedModel missingModel = minecraft.getModelManager().getMissingModel();

                if (frameModel != null && frameModel != missingModel) {
                    // Cache the successful result
                    frameModelCache.put(frameName, frameModel);
                    GrapesEatingAnimation.LOGGER.debug("GEA: Successfully found and cached frame model");
                    return frameModel;
                } else {
                    GrapesEatingAnimation.LOGGER.debug("GEA: Frame model not found or is missing model: {}", frameModelLocation);
                    return null;
                }
            } catch (Exception e) {
                GrapesEatingAnimation.LOGGER.warn("GEA: Error resolving frame model for {}: {}", frameName, e.getMessage());
                return null;
            }
        }
    }
}