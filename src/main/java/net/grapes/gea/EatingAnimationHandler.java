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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public class EatingAnimationHandler {
    // Thread-safe map to prevent ConcurrentModificationException
    private static final ConcurrentHashMap<Player, EatingAnimationState> activeAnimations = new ConcurrentHashMap<>();

    // Track last update time to prevent excessive cleanup calls
    private static long lastCleanupTime = 0;
    private static final long CLEANUP_INTERVAL = 5000; // 5 seconds

    public EatingAnimationHandler() {
        // Register for player disconnect events to prevent memory leaks
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        updateAnimationState(player);

        // Periodic cleanup to prevent memory leaks
        performPeriodicCleanup();
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // Clean up animation state when player disconnects
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
                    GrapesEatingAnimation.LOGGER.info("GEA: Starting new eating animation for {} (duration: {})", itemId, activeItem.getUseDuration());
                    return new EatingAnimationState(itemId, activeItem.getUseDuration());
                });

        state.update(player);
    }

    public static EatingAnimationState getAnimationState(Player player) {
        return activeAnimations.get(player);
    }

    public static void clearAnimationState(Player player) {
        activeAnimations.remove(player);
    }

    private static void performPeriodicCleanup() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCleanupTime > CLEANUP_INTERVAL) {
            lastCleanupTime = currentTime;

            // Remove any stale animation states
            activeAnimations.entrySet().removeIf(entry -> {
                Player player = entry.getKey();
                EatingAnimationState state = entry.getValue();

                // Remove if player is null, not using item, or animation has expired
                if (player == null || !player.isUsingItem() || state.isExpired()) {
                    GrapesEatingAnimation.LOGGER.debug("GEA: Cleaning up stale animation state");
                    return true;
                }
                return false;
            });
        }
    }

    public static class EatingAnimationState {
        private final List<String> frames;
        private final int totalDurationTicks;
        private final int frameTime;
        private final long startTimeMillis;
        private final int startTickCount;
        private String lastFrame = null;

        public EatingAnimationState(ResourceLocation itemId, int useDuration) {
            this.frames = EatingAnimationConfig.getAnimationFrames(itemId);
            this.totalDurationTicks = useDuration;
            this.frameTime = frames.isEmpty() ? 1 : totalDurationTicks / frames.size();
            this.startTimeMillis = System.currentTimeMillis();
            this.startTickCount = Minecraft.getInstance().player != null ?
                    Minecraft.getInstance().player.tickCount : 0;

            GrapesEatingAnimation.LOGGER.info("GEA: Animation state created - {} frames, {} total duration, {} ticks per frame",
                    frames.size(), totalDurationTicks, frameTime);
        }

        public void update(Player player) {
            // Update with current player context for better accuracy
            // This method can be used for any per-tick updates if needed in the future
        }

        public String getCurrentFrame() {
            if (frames.isEmpty()) {
                return null;
            }

            Player player = Minecraft.getInstance().player;
            if (player == null) {
                return frames.get(0); // Return first frame as fallback
            }

            // Use game ticks for consistent timing that matches Minecraft's tick rate
            int currentTick = player.tickCount;
            int elapsedTicks = currentTick - startTickCount;

            // Ensure we don't go beyond the total duration
            elapsedTicks = Math.min(elapsedTicks, totalDurationTicks);

            // Calculate current frame index
            int currentFrameIndex;
            if (frameTime <= 0) {
                currentFrameIndex = 0;
            } else {
                currentFrameIndex = Math.min(elapsedTicks / frameTime, frames.size() - 1);
            }

            String frame = frames.get(currentFrameIndex);

            if (!frame.equals(lastFrame)) {
                GrapesEatingAnimation.LOGGER.debug("GEA: Animation frame changed to: {} (index: {}, elapsed ticks: {})",
                        frame, currentFrameIndex, elapsedTicks);
                lastFrame = frame;
            }

            return frame;
        }

        public boolean isExpired() {
            Player player = Minecraft.getInstance().player;
            if (player == null) {
                return true;
            }

            int elapsedTicks = player.tickCount - startTickCount;
            return elapsedTicks >= totalDurationTicks;
        }

        public List<String> getFrames() {
            return frames;
        }

        public int getTotalDurationTicks() {
            return totalDurationTicks;
        }

        public int getElapsedTicks() {
            Player player = Minecraft.getInstance().player;
            if (player == null) {
                return 0;
            }
            return Math.max(0, player.tickCount - startTickCount);
        }
    }
}