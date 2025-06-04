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
import java.util.regex.Pattern;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = GrapesEatingAnimation.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class EatingItemModelOverride {

    private static final int MAX_CACHE_SIZE = 100;
    private static final long CACHE_CLEANUP_INTERVAL = 30_000L; // 30 seconds
    private static final long CACHE_ENTRY_TTL = 300_000L; // 5 minutes

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH_PATTERN = Pattern.compile("[a-z0-9_./-]+");

    private static final ConcurrentHashMap<String, CachedModel> frameModelCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> accessTimes = new ConcurrentHashMap<>();

    private static volatile long lastCacheCleanup = 0;
    private static volatile boolean modelsRegistered = false;

    private static class CachedModel {
        final BakedModel model;
        final long cacheTime;
        volatile long lastAccessed;

        CachedModel(BakedModel model) {
            this.model = model;
            this.cacheTime = System.currentTimeMillis();
            this.lastAccessed = this.cacheTime;
        }

        boolean isExpired(long currentTime) {
            return currentTime - cacheTime > CACHE_ENTRY_TTL;
        }

        void updateAccess(long currentTime) {
            this.lastAccessed = currentTime;
        }
    }

    @SubscribeEvent
    public static void onModelRegister(ModelEvent.RegisterAdditional event) {
        GrapesEatingAnimation.LOGGER.info("GEA: Registering additional models");
        clearCache();

        try {
            Map<String, List<String>> animations = EatingAnimationConfig.getAllAnimations();
            if (animations.isEmpty()) {
                GrapesEatingAnimation.LOGGER.warn("GEA: No animations configured");
                return;
            }

            Set<String> registeredFrames = new HashSet<>();
            int totalFrames = 0;

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

                    if (registeredFrames.add(frameName)) {
                        if (registerFrameModel(event, frameName)) {
                            totalFrames++;
                        }
                    }
                }
            }

            GrapesEatingAnimation.LOGGER.info("GEA: Successfully registered {} unique frame models for {} items",
                    totalFrames, animations.size());

        } catch (Exception e) {
            GrapesEatingAnimation.LOGGER.error("GEA: Critical error during model registration", e);
        }
    }

    private static boolean registerFrameModel(ModelEvent.RegisterAdditional event, String frameName) {
        try {
            if (!isValidResourceLocation(frameName)) {
                GrapesEatingAnimation.LOGGER.warn("GEA: Invalid ResourceLocation format: {}", frameName);
                return false;
            }

            ResourceLocation frameLocation = new ResourceLocation(frameName);
            ModelResourceLocation frameModelLocation = new ModelResourceLocation(
                    frameLocation.getNamespace(),
                    frameLocation.getPath(),
                    "inventory"
            );

            event.register(frameModelLocation);
            GrapesEatingAnimation.LOGGER.debug("GEA: Registered frame model: {}", frameModelLocation);
            return true;

        } catch (Exception e) {
            GrapesEatingAnimation.LOGGER.warn("GEA: Failed to register frame model '{}': {}", frameName, e.getMessage());
            return false;
        }
    }

    @SubscribeEvent
    public static void onModelBake(ModelEvent.ModifyBakingResult event) {
        GrapesEatingAnimation.LOGGER.info("GEA: Model baking event triggered");

        try {
            Map<ResourceLocation, BakedModel> modelRegistry = event.getModels();
            clearCache();
            modelsRegistered = true;

            Map<String, List<String>> animations = EatingAnimationConfig.getAllAnimations();
            GrapesEatingAnimation.LOGGER.info("GEA: Processing {} animated items", animations.size());

            int successCount = 0;
            for (String itemKey : animations.keySet()) {
                if (wrapItemModel(modelRegistry, itemKey)) {
                    successCount++;
                }
            }

            GrapesEatingAnimation.LOGGER.info("GEA: Successfully wrapped {} item models", successCount);

        } catch (Exception e) {
            GrapesEatingAnimation.LOGGER.error("GEA: Critical error during model baking", e);
        }
    }

    private static boolean wrapItemModel(Map<ResourceLocation, BakedModel> modelRegistry, String itemKey) {
        try {
            if (!isValidResourceLocation(itemKey)) {
                GrapesEatingAnimation.LOGGER.warn("GEA: Invalid item ResourceLocation: {}", itemKey);
                return false;
            }

            ResourceLocation itemId = new ResourceLocation(itemKey);
            ModelResourceLocation modelLocation = new ModelResourceLocation(itemId, "inventory");

            BakedModel originalModel = modelRegistry.get(modelLocation);
            if (originalModel != null) {
                BakedModel wrappedModel = new EatingAnimatedBakedModel(originalModel, itemId);
                modelRegistry.put(modelLocation, wrappedModel);
                GrapesEatingAnimation.LOGGER.debug("GEA: Wrapped model for item: {}", itemId);
                return true;
            } else {
                GrapesEatingAnimation.LOGGER.warn("GEA: Could not find original model for item: {}", itemId);
                return false;
            }
        } catch (Exception e) {
            GrapesEatingAnimation.LOGGER.warn("GEA: Failed to wrap model for item '{}': {}", itemKey, e.getMessage());
            return false;
        }
    }

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

        int expiredCount = removeExpiredEntries(currentTime);

        int removedLRU = removeLRUEntries();

        if (expiredCount > 0 || removedLRU > 0) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Cache cleanup - removed {} expired, {} LRU entries. Cache size: {}",
                    expiredCount, removedLRU, frameModelCache.size());
        }
    }

    private static int removeExpiredEntries(long currentTime) {
        int expiredCount = 0;
        Iterator<Map.Entry<String, CachedModel>> iterator = frameModelCache.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, CachedModel> entry = iterator.next();
            if (entry.getValue().isExpired(currentTime)) {
                iterator.remove();
                accessTimes.remove(entry.getKey());
                expiredCount++;
            }
        }

        return expiredCount;
    }

    private static int removeLRUEntries() {
        if (frameModelCache.size() <= MAX_CACHE_SIZE) {
            return 0;
        }

        List<Map.Entry<String, Long>> sortedByAccess = new ArrayList<>(accessTimes.entrySet());
        sortedByAccess.sort(Map.Entry.comparingByValue());

        int toRemove = frameModelCache.size() - MAX_CACHE_SIZE;
        int removedCount = 0;

        for (int i = 0; i < toRemove && i < sortedByAccess.size(); i++) {
            String key = sortedByAccess.get(i).getKey();
            if (frameModelCache.remove(key) != null) {
                accessTimes.remove(key);
                removedCount++;
            }
        }

        return removedCount;
    }

    private static boolean isValidResourceLocation(String location) {
        if (location == null || location.trim().isEmpty()) {
            return false;
        }

        int colonIndex = location.indexOf(':');
        if (colonIndex <= 0 || colonIndex >= location.length() - 1) {
            return false;
        }

        String namespace = location.substring(0, colonIndex);
        String path = location.substring(colonIndex + 1);

        return NAMESPACE_PATTERN.matcher(namespace).matches() &&
                PATH_PATTERN.matcher(path).matches();
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
        public final double loadFactor;

        CacheStats(int currentSize, int maxSize, long timeSinceLastCleanup) {
            this.currentSize = currentSize;
            this.maxSize = maxSize;
            this.timeSinceLastCleanup = timeSinceLastCleanup;
            this.loadFactor = maxSize > 0 ? (double) currentSize / maxSize : 0.0;
        }

        @Override
        public String toString() {
            return String.format("CacheStats{size=%d/%d (%.1f%%), lastCleanup=%dms ago}",
                    currentSize, maxSize, loadFactor * 100, timeSinceLastCleanup);
        }
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
            if (!modelsRegistered || !(entity instanceof Player)) {
                return originalOverrides.resolve(model, stack, world, entity, seed);
            }

            performCacheCleanup();

            Player player = (Player) entity;

            if (!itemMatches(stack, itemId)) {
                return originalOverrides.resolve(model, stack, world, entity, seed);
            }

            try {
                BakedModel animatedModel = resolveAnimatedModel(player, stack);
                return animatedModel != null ? animatedModel : originalOverrides.resolve(model, stack, world, entity, seed);
            } catch (Exception e) {
                GrapesEatingAnimation.LOGGER.warn("GEA: Error during model resolution for player {}: {}",
                        player.getName().getString(), e.getMessage());
                return originalOverrides.resolve(model, stack, world, entity, seed);
            }
        }

        @Nullable
        private BakedModel resolveAnimatedModel(Player player, ItemStack stack) {
            if (!player.isUsingItem()) {
                return null;
            }

            ItemStack currentlyUsing = player.getUseItem();
            if (currentlyUsing.isEmpty() || !itemMatches(currentlyUsing, itemId)) {
                return null;
            }

            if (!isTheSameItemStack(stack, currentlyUsing, player)) {
                return null;
            }

            EatingAnimationHandler.EatingAnimationState state = EatingAnimationHandler.getAnimationState(player);
            if (state == null) {
                return null;
            }

            String currentFrame = state.getCurrentFrame(player);
            if (currentFrame == null) {
                return null;
            }

            BakedModel frameModel = resolveFrameModelCached(currentFrame);
            if (frameModel != null) {
                GrapesEatingAnimation.LOGGER.debug("GEA: Using frame model: {} for player: {} eating {}",
                        currentFrame, player.getName().getString(), itemId);
            }

            return frameModel;
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

        private boolean isTheSameItemStack(ItemStack stackBeingRendered, ItemStack stackBeingConsumed, Player player) {
            return stackBeingRendered == player.getMainHandItem() && stackBeingConsumed == player.getMainHandItem() ||
                    stackBeingRendered == player.getOffhandItem() && stackBeingConsumed == player.getOffhandItem();
        }

        @Nullable
        private BakedModel resolveFrameModelCached(String frameName) {
            long currentTime = System.currentTimeMillis();

            CachedModel cached = frameModelCache.get(frameName);
            if (cached != null && !cached.isExpired(currentTime)) {
                cached.updateAccess(currentTime);
                accessTimes.put(frameName, cached.lastAccessed);
                return cached.model;
            }

            BakedModel frameModel = loadFrameModel(frameName);
            if (frameModel != null) {
                cacheFrameModel(frameName, frameModel, currentTime);
            }

            return frameModel;
        }

        @Nullable
        private BakedModel loadFrameModel(String frameName) {
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

                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.getModelManager() == null) {
                    GrapesEatingAnimation.LOGGER.debug("GEA: Model manager not available");
                    return null;
                }

                BakedModel frameModel = minecraft.getModelManager().getModel(frameModelLocation);
                BakedModel missingModel = minecraft.getModelManager().getMissingModel();

                if (frameModel != null && frameModel != missingModel) {
                    GrapesEatingAnimation.LOGGER.debug("GEA: Successfully loaded frame model: {}", frameModelLocation);
                    return frameModel;
                } else {
                    GrapesEatingAnimation.LOGGER.debug("GEA: Frame model not found: {}", frameModelLocation);
                    return null;
                }
            } catch (Exception e) {
                GrapesEatingAnimation.LOGGER.warn("GEA: Error loading frame model for {}: {}", frameName, e.getMessage());
                return null;
            }
        }

        private void cacheFrameModel(String frameName, BakedModel frameModel, long currentTime) {
            if (frameModelCache.size() >= MAX_CACHE_SIZE) {
                performCacheCleanup();
            }

            if (frameModelCache.size() < MAX_CACHE_SIZE) {
                CachedModel cachedModel = new CachedModel(frameModel);
                frameModelCache.put(frameName, cachedModel);
                accessTimes.put(frameName, currentTime);
                GrapesEatingAnimation.LOGGER.debug("GEA: Cached frame model: {}", frameName);
            }
        }
    }
}