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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = GrapesEatingAnimation.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class EatingItemModelOverride {

    // Enhanced cache with size limits and access tracking
    private static final int MAX_CACHE_SIZE = 100; // Configurable limit
    private static final ConcurrentHashMap<String, CachedModel> frameModelCache = new ConcurrentHashMap<>();

    // Track access times for LRU eviction
    private static final Map<String, Long> accessTimes = new ConcurrentHashMap<>();

    // Cleanup timing
    private static long lastCacheCleanup = 0;
    private static final long CACHE_CLEANUP_INTERVAL = 30000; // 30 seconds
    private static final long CACHE_ENTRY_TTL = 300000; // 5 minutes

    private static volatile boolean modelsRegistered = false;

    // Wrapper class for cached models with metadata
    private static class CachedModel {
        final BakedModel model;
        final long cacheTime;
        volatile long lastAccessed;

        CachedModel(BakedModel model) {
            this.model = model;
            this.cacheTime = System.currentTimeMillis();
            this.lastAccessed = this.cacheTime;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - cacheTime > CACHE_ENTRY_TTL;
        }

        void updateAccess() {
            this.lastAccessed = System.currentTimeMillis();
        }
    }

    @SubscribeEvent
    public static void onModelRegister(ModelEvent.RegisterAdditional event) {
        GrapesEatingAnimation.LOGGER.info("GEA: Registering additional models");

        // Clear cache when models are being re-registered
        clearCache();

        try {
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

            // Clear cache when models are rebaked (resource pack reload)
            clearCache();
            modelsRegistered = true;

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

    // Enhanced cache management methods
    public static void clearCache() {
        frameModelCache.clear();
        accessTimes.clear();
        GrapesEatingAnimation.LOGGER.debug("GEA: Frame model cache cleared");
    }

    private static void performCacheCleanup() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastCacheCleanup < CACHE_CLEANUP_INTERVAL) {
            return;
        }

        lastCacheCleanup = currentTime;

        // Remove expired entries
        int expiredCount = 0;
        Iterator<Map.Entry<String, CachedModel>> iterator = frameModelCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedModel> entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
                accessTimes.remove(entry.getKey());
                expiredCount++;
            }
        }

        // If cache is still too large, remove LRU entries
        int removedLRU = 0;
        if (frameModelCache.size() > MAX_CACHE_SIZE) {
            List<Map.Entry<String, Long>> sortedByAccess = new ArrayList<>(accessTimes.entrySet());
            sortedByAccess.sort(Map.Entry.comparingByValue());

            int toRemove = frameModelCache.size() - MAX_CACHE_SIZE;
            for (int i = 0; i < toRemove && i < sortedByAccess.size(); i++) {
                String key = sortedByAccess.get(i).getKey();
                frameModelCache.remove(key);
                accessTimes.remove(key);
                removedLRU++;
            }
        }

        if (expiredCount > 0 || removedLRU > 0) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Cache cleanup - removed {} expired, {} LRU entries. Cache size: {}",
                    expiredCount, removedLRU, frameModelCache.size());
        }
    }

    public static CacheStats getCacheStats() {
        return new CacheStats(
                frameModelCache.size(),
                MAX_CACHE_SIZE,
                System.currentTimeMillis() - lastCacheCleanup
        );
    }

    public static class CacheStats {
        public final int currentSize;
        public final int maxSize;
        public final long timeSinceLastCleanup;

        CacheStats(int currentSize, int maxSize, long timeSinceLastCleanup) {
            this.currentSize = currentSize;
            this.maxSize = maxSize;
            this.timeSinceLastCleanup = timeSinceLastCleanup;
        }
    }

    private static boolean isValidResourceLocation(String location) {
        if (location == null || location.trim().isEmpty()) {
            return false;
        }

        String[] parts = location.split(":", 2);
        if (parts.length != 2) {
            return false;
        }

        String namespace = parts[0];
        String path = parts[1];

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
            // Perform periodic cache cleanup
            performCacheCleanup();

            if (!modelsRegistered) {
                return originalOverrides.resolve(model, stack, world, entity, seed);
            }

            // FIXED: Only apply animation if this specific item stack is the one being eaten
            if (entity instanceof Player) {
                Player player = (Player) entity;

                // Check if this specific item matches the one we're handling animations for
                if (itemMatches(stack, itemId)) {
                    try {
                        EatingAnimationHandler.EatingAnimationState state = EatingAnimationHandler.getAnimationState(player);

                        // CRITICAL FIX: Only apply animation if:
                        // 1. Player has an active animation state
                        // 2. Player is currently using an item
                        // 3. The item being used matches this specific item type
                        // 4. The ItemStack being rendered is actually the one being consumed
                        if (state != null && player.isUsingItem()) {
                            ItemStack currentlyUsing = player.getUseItem();

                            // KEY FIX: Check if this specific ItemStack is the one being consumed
                            // This prevents multiple items of the same type from showing animation
                            if (!currentlyUsing.isEmpty() &&
                                    itemMatches(currentlyUsing, itemId) &&
                                    isTheSameItemStack(stack, currentlyUsing, player)) {

                                String currentFrame = state.getCurrentFrame(player);
                                if (currentFrame != null) {
                                    BakedModel frameModel = resolveFrameModelCached(currentFrame);
                                    if (frameModel != null) {
                                        GrapesEatingAnimation.LOGGER.debug("GEA: Using frame model: {} for player: {} eating {}",
                                                currentFrame, player.getName().getString(), itemId);
                                        return frameModel;
                                    } else {
                                        GrapesEatingAnimation.LOGGER.debug("GEA: Frame model not available: {}", currentFrame);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        GrapesEatingAnimation.LOGGER.warn("GEA: Error during model resolution for player {}: {}",
                                player.getName().getString(), e.getMessage());
                    }
                }
            }

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

        /**
         * Determines if the ItemStack being rendered is the same one being consumed.
         * This is the key fix - we need to identify which specific ItemStack is being eaten.
         */
        private boolean isTheSameItemStack(ItemStack stackBeingRendered, ItemStack stackBeingConsumed, Player player) {
            // Method 1: Check if it's in the main hand (most common case)
            if (player.getMainHandItem() == stackBeingConsumed) {
                // If the stack being rendered is also the main hand item, it's the same
                return stackBeingRendered == player.getMainHandItem();
            }

            // Method 2: Check if it's in the off hand
            if (player.getOffhandItem() == stackBeingConsumed) {
                return stackBeingRendered == player.getOffhandItem();
            }

            // Method 3: For other cases (like rendering in inventory), we can use additional checks
            // This is a fallback - in most cases, the above checks should work

            // If we can't determine definitively, we'll be conservative and not show animation
            // for items that aren't in the active hand positions
            return false;
        }

        private BakedModel resolveFrameModelCached(String frameName) {
            // Check cache first and update access time
            CachedModel cached = frameModelCache.get(frameName);
            if (cached != null && !cached.isExpired()) {
                cached.updateAccess();
                accessTimes.put(frameName, cached.lastAccessed);
                return cached.model;
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

                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.getModelManager() == null) {
                    GrapesEatingAnimation.LOGGER.debug("GEA: Model manager not available");
                    return null;
                }

                BakedModel frameModel = minecraft.getModelManager().getModel(frameModelLocation);
                BakedModel missingModel = minecraft.getModelManager().getMissingModel();

                if (frameModel != null && frameModel != missingModel) {
                    // Cache the successful result with size limit check
                    if (frameModelCache.size() >= MAX_CACHE_SIZE) {
                        performCacheCleanup(); // Force cleanup if at limit
                    }

                    if (frameModelCache.size() < MAX_CACHE_SIZE) {
                        CachedModel cachedModel = new CachedModel(frameModel);
                        frameModelCache.put(frameName, cachedModel);
                        accessTimes.put(frameName, cachedModel.lastAccessed);
                        GrapesEatingAnimation.LOGGER.debug("GEA: Successfully found and cached frame model");
                    }

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