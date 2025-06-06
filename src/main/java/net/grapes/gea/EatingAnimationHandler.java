// TODO: Replace `Player` key with `UUID` in activeAnimations to prevent identity issues.
// TODO: Implement or remove empty `update(Player)` method in EatingAnimationState.
// TODO: Reduce repetitive clamping logic for ticks by adding a helper method.
// TODO: Consider caching config checks like `EatingAnimationConfig.hasAnimation()`.
// TODO: Avoid over-logging per-frame changes unless debugging.
// TODO: Evaluate whether enhanced cleanup should run more/less frequently.
// TODO: Consider removing redundant `null` checks for `player` and `minecraft.level` in `onRenderHand()`.
// TODO: (Optional) Add unit tests for stale/expired logic for robustness.

package net.grapes.gea;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public class EatingAnimationHandler {
    private static final int MAX_ANIMATION_STATES = 50;
    private static final ConcurrentHashMap<Player, EatingAnimationState> activeAnimations = new ConcurrentHashMap<>();

    private static long lastCleanupTime = 0;
    private static final long CLEANUP_INTERVAL = 2000; // 2 seconds
    private static final long STALE_THRESHOLD = 10000; // 10 seconds

    private static int cleanupCount = 0;

    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        updateAnimationState(minecraft.player);
        performEnhancedCleanup();
    }

    public static void updateAnimationState(Player player) {
        if (player == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

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

        boolean isLocalPlayer = (minecraft.player == player);

        if (isLocalPlayer || minecraft.hasSingleplayerServer()) {
            EatingAnimationState existingState = activeAnimations.get(player);

            if (existingState == null || !existingState.isValidForItem(itemId)) {
                if (activeAnimations.size() >= MAX_ANIMATION_STATES) {
                    performEnhancedCleanup();

                    if (activeAnimations.size() >= MAX_ANIMATION_STATES) {
                        removeOldestAnimation();
                    }
                }

                GrapesEatingAnimation.LOGGER.info("GEA: Starting new eating animation for {} (duration: {})",
                        itemId, activeItem.getUseDuration());

                EatingAnimationState newState = new EatingAnimationState(itemId, activeItem.getUseDuration(), player.tickCount);
                activeAnimations.put(player, newState);
            }

            if (existingState != null) {
                existingState.update(player);
            }
        }
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

            if (player == null) {
                shouldRemove = true;
            } else if (!player.isUsingItem()) {
                shouldRemove = true;
            } else if (state.isExpired(player)) {
                shouldRemove = true;
            } else if (state.isStale(player, STALE_THRESHOLD)) {
                shouldRemove = true;
            } else {
                ItemStack currentItem = player.getUseItem();
                if (!currentItem.isEmpty()) {
                    ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(currentItem.getItem());
                    if (itemId == null || !EatingAnimationConfig.hasAnimation(itemId)) {
                        shouldRemove = true;
                    } else if (!state.isValidForItem(itemId)) {
                        shouldRemove = true;
                    }
                }
            }

            if (shouldRemove) {
                iterator.remove();
                removedCount++;
            }
        }

        if (removedCount > 0 || cleanupCount % 10 == 0) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Enhanced cleanup #{} - removed {} stale entries. Active: {}/{}",
                    cleanupCount, removedCount, activeAnimations.size(), MAX_ANIMATION_STATES);
        }
    }

    public static EatingAnimationState getAnimationState(Player player) {
        return activeAnimations.get(player);
    }

    public static void setAnimationState(Player player, EatingAnimationState state) {
        if (player == null || state == null) {
            return;
        }

        if (activeAnimations.size() >= MAX_ANIMATION_STATES) {
            performEnhancedCleanup();
        }

        activeAnimations.put(player, state);
        GrapesEatingAnimation.LOGGER.debug("GEA: Set animation state for player {} with start tick {} for item {}",
                player.getName().getString(), state.startTickCount, state.itemId);
    }

    public static void clearAnimationState(Player player) {
        EatingAnimationState removed = activeAnimations.remove(player);
        if (removed != null) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Cleared animation state for player {}",
                    player.getName().getString());
        }
    }

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
        private final long creationTime;
        private String lastFrame = null;
        private final boolean useServerTick;
        private final String itemId;

        public EatingAnimationState(ResourceLocation itemId, int useDuration, int serverStartTick, boolean useServerTick) {
            this.frames = EatingAnimationConfig.getAnimationFrames(itemId);
            this.totalDurationTicks = useDuration;
            this.startTickCount = serverStartTick;
            this.creationTime = System.currentTimeMillis();
            this.useServerTick = useServerTick;
            this.itemId = itemId.toString();

            GrapesEatingAnimation.LOGGER.info("GEA: Animation state created - {} frames, {} total duration, server tick: {}, use server tick: {}, item: {}",
                    frames != null ? frames.size() : 0, totalDurationTicks, serverStartTick, useServerTick, this.itemId);
        }

        public EatingAnimationState(ResourceLocation itemId, int useDuration, int startTick) {
            this(itemId, useDuration, startTick, false);
        }

        public EatingAnimationState(ResourceLocation itemId, int useDuration) {
            this(itemId, useDuration, getClientPlayerTickCount(), false);
        }

        private static int getClientPlayerTickCount() {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.player != null ? minecraft.player.tickCount : 0;
        }

        public void update(Player player) {
            // Just reminder
        }

        public boolean isValidForItem(ResourceLocation itemId) {
            return itemId != null && itemId.toString().equals(this.itemId);
        }

        public String getCurrentFrame(Player player) {
            if (frames == null || frames.isEmpty()) {
                return null;
            }

            if (player == null) {
                return frames.get(0);
            }

            int currentTick = player.tickCount;
            int elapsedTicks;

            if (useServerTick) {
                elapsedTicks = Math.max(0, currentTick - startTickCount);

                if (elapsedTicks < 0) {
                    GrapesEatingAnimation.LOGGER.debug("GEA: Negative elapsed ticks detected, using 0. Current: {}, Start: {}",
                            currentTick, startTickCount);
                    elapsedTicks = 0;
                }
            } else {
                elapsedTicks = currentTick - startTickCount;
            }

            elapsedTicks = Math.max(0, Math.min(elapsedTicks, totalDurationTicks - 1));

            int currentFrameIndex = calculateFrameIndex(elapsedTicks);
            String frame = frames.get(currentFrameIndex);

            if (!frame.equals(lastFrame)) {
                GrapesEatingAnimation.LOGGER.debug("GEA: Animation frame changed to: {} (index: {}, elapsed ticks: {}, start: {}, current: {}, server mode: {})",
                        frame, currentFrameIndex, elapsedTicks, startTickCount, currentTick, useServerTick);
                lastFrame = frame;
            }

            return frame;
        }

        private int calculateFrameIndex(int elapsedTicks) {
            if (frames.size() <= 1) {
                return 0;
            }

            if (totalDurationTicks <= 1) {
                return frames.size() - 1;
            }

            elapsedTicks = Math.max(0, Math.min(elapsedTicks, totalDurationTicks - 1));

            int frameIndex = (elapsedTicks * frames.size()) / totalDurationTicks;
            frameIndex = Math.min(frameIndex, frames.size() - 1);

            return frameIndex;
        }

        public String getCurrentFrame() {
            Minecraft minecraft = Minecraft.getInstance();
            return getCurrentFrame(minecraft.player);
        }

        public boolean isExpired(Player player) {
            if (player == null) {
                return true;
            }

            int elapsedTicks = useServerTick ?
                    Math.max(0, player.tickCount - startTickCount) :
                    player.tickCount - startTickCount;
            return elapsedTicks >= totalDurationTicks;
        }

        public boolean isExpired() {
            Minecraft minecraft = Minecraft.getInstance();
            return isExpired(minecraft.player);
        }

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
            int elapsed = useServerTick ?
                    Math.max(0, player.tickCount - startTickCount) :
                    player.tickCount - startTickCount;
            return Math.max(0, elapsed);
        }

        public int getElapsedTicks() {
            Minecraft minecraft = Minecraft.getInstance();
            return getElapsedTicks(minecraft.player);
        }

        public boolean isValidForMultiplayer(Player player) {
            if (player == null) return false;

            int elapsedTicks = getElapsedTicks(player);

            if (elapsedTicks < 0 || elapsedTicks > totalDurationTicks + 40) {
                return false;
            }

            return true;
        }

        public long getCreationTime() {
            return creationTime;
        }

        public String getItemId() {
            return itemId;
        }
    }
}
