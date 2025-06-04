package net.grapes.gea;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public class EatingAnimationHandler {
    // Enhanced animation state management
    private static final int MAX_ANIMATION_STATES = 50; // Limit concurrent animations
    private static final ConcurrentHashMap<Player, EatingAnimationState> activeAnimations = new ConcurrentHashMap<>();

    // Enhanced cleanup timing
    private static long lastCleanupTime = 0;
    private static final long CLEANUP_INTERVAL = 2000; // 2 seconds (more frequent)
    private static final long STALE_THRESHOLD = 10000; // 10 seconds for stale detection

    // Performance monitoring
    private static int cleanupCount = 0;

    public EatingAnimationHandler() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        updateAnimationState(player);
        performEnhancedCleanup();
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            clearAnimationState(player);
            GrapesEatingAnimation.LOGGER.debug("GEA: Cleaned up animation state for disconnected player");
        }
    }

    public static void updateAnimationState(Player player) {
        ItemStack activeItem = player.getUseItem();

        if (activeItem.isEmpty()) {
            if (activeAnimations.containsKey(player)) {
                GrapesEatingAnimation.LOGGER.debug("GEA: Removing animation for player - no active item");
                activeAnimations.remove(player);
            }
            return;
        }

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(activeItem.getItem());
        if (itemId == null) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Item ID is null for active item");
            return;
        }

        if (!EatingAnimationConfig.hasAnimation(itemId)) {
            if (activeAnimations.containsKey(player)) {
                GrapesEatingAnimation.LOGGER.debug("GEA: Removing animation - item has no animation config");
                activeAnimations.remove(player);
            }
            return;
        }

        if (!player.isUsingItem() || !activeItem.isEdible()) {
            if (activeAnimations.containsKey(player)) {
                activeAnimations.remove(player);
            }
            return;
        }

        EatingAnimationState state = activeAnimations.computeIfAbsent(player,
                k -> {
                    // Check if we're at capacity
                    if (activeAnimations.size() >= MAX_ANIMATION_STATES) {
                        performEnhancedCleanup(); // Force cleanup

                        // If still at capacity, remove oldest entry
                        if (activeAnimations.size() >= MAX_ANIMATION_STATES) {
                            removeOldestAnimation();
                        }
                    }

                    GrapesEatingAnimation.LOGGER.info("GEA: Starting new eating animation for {} (duration: {})",
                            itemId, activeItem.getUseDuration());
                    return new EatingAnimationState(itemId, activeItem.getUseDuration(), player.tickCount);
                });

        state.update(player);
    }

    private static void removeOldestAnimation() {
        if (activeAnimations.isEmpty()) return;

        Player oldestPlayer = null;
        long oldestTime = Long.MAX_VALUE;

        for (Map.Entry<Player, EatingAnimationState> entry : activeAnimations.entrySet()) {
            long startTime = entry.getValue().startTickCount;
            if (startTime < oldestTime) {
                oldestTime = startTime;
                oldestPlayer = entry.getKey();
            }
        }

        if (oldestPlayer != null) {
            activeAnimations.remove(oldestPlayer);
            GrapesEatingAnimation.LOGGER.debug("GEA: Removed oldest animation to make room");
        }
    }

    private static void performEnhancedCleanup() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastCleanupTime < CLEANUP_INTERVAL) {
            return;
        }

        lastCleanupTime = currentTime;
        cleanupCount++;

        int removedCount = 0;
        Iterator<Map.Entry<Player, EatingAnimationState>> iterator = activeAnimations.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Player, EatingAnimationState> entry = iterator.next();
            Player player = entry.getKey();
            EatingAnimationState state = entry.getValue();

            boolean shouldRemove = false;

            // Check various cleanup conditions
            if (player == null) {
                shouldRemove = true;
            } else if (!player.isUsingItem()) {
                shouldRemove = true;
            } else if (state.isExpired(player)) {
                shouldRemove = true;
            } else if (state.isStale(player, STALE_THRESHOLD)) {
                shouldRemove = true;
            } else {
                // Check if item still has animation config
                ItemStack currentItem = player.getUseItem();
                if (!currentItem.isEmpty()) {
                    ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(currentItem.getItem());
                    if (itemId == null || !EatingAnimationConfig.hasAnimation(itemId)) {
                        shouldRemove = true;
                    }
                }
            }

            if (shouldRemove) {
                iterator.remove();
                removedCount++;
            }
        }

        if (removedCount > 0 || cleanupCount % 10 == 0) { // Log periodically
            GrapesEatingAnimation.LOGGER.debug("GEA: Enhanced cleanup #{} - removed {} stale entries. Active: {}/{}",
                    cleanupCount, removedCount, activeAnimations.size(), MAX_ANIMATION_STATES);
        }
    }

    public static EatingAnimationState getAnimationState(Player player) {
        return activeAnimations.get(player);
    }

    public static void setAnimationState(Player player, EatingAnimationState state) {
        if (activeAnimations.size() >= MAX_ANIMATION_STATES) {
            performEnhancedCleanup();
        }
        activeAnimations.put(player, state);
    }

    public static void clearAnimationState(Player player) {
        activeAnimations.remove(player);
    }

    // Enhanced stats method
    public static AnimationStats getAnimationStats() {
        return new AnimationStats(
                activeAnimations.size(),
                MAX_ANIMATION_STATES,
                cleanupCount,
                System.currentTimeMillis() - lastCleanupTime
        );
    }

    public static class AnimationStats {
        public final int activeCount;
        public final int maxCapacity;
        public final int totalCleanups;
        public final long timeSinceLastCleanup;

        AnimationStats(int activeCount, int maxCapacity, int totalCleanups, long timeSinceLastCleanup) {
            this.activeCount = activeCount;
            this.maxCapacity = maxCapacity;
            this.totalCleanups = totalCleanups;
            this.timeSinceLastCleanup = timeSinceLastCleanup;
        }
    }

    public static class EatingAnimationState {
        private final List<String> frames;
        private final int totalDurationTicks;
        private final int startTickCount;
        private final long creationTime; // Add creation timestamp
        private String lastFrame = null;

        public EatingAnimationState(ResourceLocation itemId, int useDuration, int startTick) {
            this.frames = EatingAnimationConfig.getAnimationFrames(itemId);
            this.totalDurationTicks = useDuration;
            this.startTickCount = startTick;
            this.creationTime = System.currentTimeMillis();

            GrapesEatingAnimation.LOGGER.info("GEA: Animation state created - {} frames, {} total duration",
                    frames != null ? frames.size() : 0, totalDurationTicks);
        }

        public EatingAnimationState(ResourceLocation itemId, int useDuration) {
            this(itemId, useDuration, Minecraft.getInstance().player != null ?
                    Minecraft.getInstance().player.tickCount : 0);
        }

        public void update(Player player) {
            // Update method for any per-tick updates
        }

        public String getCurrentFrame(Player player) {
            if (frames == null || frames.isEmpty()) {
                return null;
            }

            if (player == null) {
                return frames.get(0);
            }

            int currentTick = player.tickCount;
            int elapsedTicks = currentTick - startTickCount;
            elapsedTicks = Math.min(elapsedTicks, totalDurationTicks);

            int currentFrameIndex = calculateFrameIndex(elapsedTicks);
            String frame = frames.get(currentFrameIndex);

            if (!frame.equals(lastFrame)) {
                GrapesEatingAnimation.LOGGER.debug("GEA: Animation frame changed to: {} (index: {}, elapsed ticks: {})",
                        frame, currentFrameIndex, elapsedTicks);
                lastFrame = frame;
            }

            return frame;
        }

        private int calculateFrameIndex(int elapsedTicks) {
            if (frames.size() <= 1) {
                return 0;
            }

            elapsedTicks = Math.max(0, Math.min(elapsedTicks, totalDurationTicks - 1));
            int frameIndex = (elapsedTicks * frames.size()) / totalDurationTicks;
            return Math.min(frameIndex, frames.size() - 1);
        }

        public String getCurrentFrame() {
            return getCurrentFrame(Minecraft.getInstance().player);
        }

        public boolean isExpired(Player player) {
            if (player == null) {
                return true;
            }

            int elapsedTicks = player.tickCount - startTickCount;
            return elapsedTicks >= totalDurationTicks;
        }

        public boolean isExpired() {
            return isExpired(Minecraft.getInstance().player);
        }

        // New method to detect stale animations
        public boolean isStale(Player player, long staleThresholdMs) {
            if (player == null) {
                return true;
            }

            long age = System.currentTimeMillis() - creationTime;
            return age > staleThresholdMs;
        }

        public List<String> getFrames() {
            return frames;
        }

        public int getTotalDurationTicks() {
            return totalDurationTicks;
        }

        public int getElapsedTicks(Player player) {
            if (player == null) {
                return 0;
            }
            return Math.max(0, player.tickCount - startTickCount);
        }

        public int getElapsedTicks() {
            return getElapsedTicks(Minecraft.getInstance().player);
        }

        public long getCreationTime() {
            return creationTime;
        }
    }
}