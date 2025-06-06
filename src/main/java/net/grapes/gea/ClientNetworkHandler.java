// TODO: Avoid double lookup of entity between handleEatingAnimationPacket and handleEatingAnimationPacketInternal
// TODO: Consider making clientFullyInitialized and similar flags volatile or use Atomic types for safety
// TODO: Extract magic numbers like timeout and maxAllowedDifference into named constants or config
// TODO: Add guard to prevent excessively verbose logging in production environments
// TODO: Investigate if returning true for non-Player entities is correct behavior (could suppress retry unnecessarily)
// TODO: Consider basing timeout and timing logic on tickCount or a game-tick timer rather than System.currentTimeMillis()
// TODO: Optionally, improve memory handling for delayedPackets by limiting queue size or handling repeated failures

package net.grapes.gea;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Iterator;

@OnlyIn(Dist.CLIENT)
public class ClientNetworkHandler {

    private static final ConcurrentLinkedQueue<DelayedPacket> delayedPackets = new ConcurrentLinkedQueue<>();
    private static boolean registered = false;

    private static boolean clientFullyInitialized = false;
    private static int initializationTicks = 0;
    private static final int INITIALIZATION_WAIT_TICKS = 20;

    private static boolean hasConnectedBefore = false;
    private static long connectionTime = 0;
    private static final long CONNECTION_STABILIZATION_TIME = 1000;

    public static void init() {
        if (!registered) {
            MinecraftForge.EVENT_BUS.register(ClientNetworkHandler.class);
            registered = true;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (!clientFullyInitialized) {
            initializationTicks++;

            boolean hasValidWorldAndPlayer = minecraft.level != null &&
                    minecraft.player != null &&
                    minecraft.player.tickCount > 5;

            boolean connectionStabilized = System.currentTimeMillis() - connectionTime > CONNECTION_STABILIZATION_TIME;

            if (initializationTicks >= INITIALIZATION_WAIT_TICKS &&
                    hasValidWorldAndPlayer &&
                    connectionStabilized) {
                clientFullyInitialized = true;
                GrapesEatingAnimation.LOGGER.info("GEA: Client fully initialized after {} ticks (world: {}, player: {}, connection stabilized: {})",
                        initializationTicks, minecraft.level != null, minecraft.player != null, connectionStabilized);
            }
        }

        processDelayedPackets();
    }

    private static void processDelayedPackets() {
        if (delayedPackets.isEmpty()) {
            return;
        }

        Iterator<DelayedPacket> iterator = delayedPackets.iterator();
        int processedCount = 0;
        int droppedCount = 0;

        while (iterator.hasNext()) {
            DelayedPacket delayed = iterator.next();

            long timeout = 10000;

            if (System.currentTimeMillis() - delayed.timestamp > timeout) {
                iterator.remove();
                droppedCount++;
                GrapesEatingAnimation.LOGGER.warn("GEA: Dropped delayed packet after timeout for entity {}",
                        delayed.packet.getPlayerId());
                continue;
            }

            if (handleEatingAnimationPacketInternal(delayed.packet)) {
                iterator.remove();
                processedCount++;
                GrapesEatingAnimation.LOGGER.debug("GEA: Successfully processed delayed packet for entity {}",
                        delayed.packet.getPlayerId());
            }
        }

        if (processedCount > 0 || droppedCount > 0) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Processed {} delayed packets, dropped {} expired packets. Remaining: {}",
                    processedCount, droppedCount, delayedPackets.size());
        }
    }

    public static void handleEatingAnimationPacket(NetworkHandler.EatingAnimationPacket packet) {
        GrapesEatingAnimation.LOGGER.debug("GEA: Received eating animation packet - Player: {}, Item: {}, Eating: {}, StartTick: {}",
                packet.getPlayerId(), packet.getItemId(), packet.isEating(), packet.getStartTick());

        if (handleEatingAnimationPacketInternal(packet)) {
            return;
        }

        delayedPackets.offer(new DelayedPacket(packet));
        GrapesEatingAnimation.LOGGER.debug("GEA: Queued packet for delayed processing (client initialized: {}, entity found: {})",
                clientFullyInitialized, findEntity(Minecraft.getInstance(), packet.getPlayerId()) != null);
    }

    private static class DelayedPacket {
        final NetworkHandler.EatingAnimationPacket packet;
        final long timestamp;

        DelayedPacket(NetworkHandler.EatingAnimationPacket packet) {
            this.packet = packet;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static boolean handleEatingAnimationPacketInternal(NetworkHandler.EatingAnimationPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null || minecraft.player == null) {
            return false;
        }

        Entity entity = findEntity(minecraft, packet.getPlayerId());

        if (entity == null) {
            return false;
        }

        if (!(entity instanceof Player)) {
            return true;
        }

        Player player = (Player) entity;

        if (packet.isEating() && packet.getItemId() != null) {
            try {
                ResourceLocation itemId = new ResourceLocation(packet.getItemId());
                if (EatingAnimationConfig.hasAnimation(itemId)) {
                    int adjustedStartTick = calculateAdjustedStartTick(packet, player);

                    EatingAnimationHandler.clearAnimationState(player);

                    EatingAnimationHandler.EatingAnimationState state =
                            new EatingAnimationHandler.EatingAnimationState(
                                    itemId,
                                    packet.getUseDuration(),
                                    adjustedStartTick,
                                    true
                            );
                    EatingAnimationHandler.setAnimationState(player, state);

                    GrapesEatingAnimation.LOGGER.debug("GEA: Started eating animation for player {} with item {} (adjusted start tick: {}, server tick: {}, current tick: {})",
                            player.getName().getString(), packet.getItemId(), adjustedStartTick, packet.getStartTick(), player.tickCount);
                }
            } catch (Exception e) {
                GrapesEatingAnimation.LOGGER.error("GEA: Failed to start eating animation: {}", e.getMessage());
            }
        } else {
            EatingAnimationHandler.clearAnimationState(player);
            GrapesEatingAnimation.LOGGER.debug("GEA: Stopped eating animation for player {}",
                    player.getName().getString());
        }

        return true;
    }

    private static int calculateAdjustedStartTick(NetworkHandler.EatingAnimationPacket packet, Player player) {
        int serverStartTick = packet.getStartTick();
        int currentClientTick = player.tickCount;

        int tickDifference = currentClientTick - serverStartTick;

        GrapesEatingAnimation.LOGGER.debug("GEA: Tick analysis - Server: {}, Client: {}, Difference: {}",
                serverStartTick, currentClientTick, tickDifference);

        int maxAllowedDifference = 150;

        if (Math.abs(tickDifference) > maxAllowedDifference) {
            int adjustedStartTick = currentClientTick - Math.min(10, packet.getUseDuration() / 8);
            GrapesEatingAnimation.LOGGER.debug("GEA: Large tick difference detected ({}), adjusting start tick from {} to {}",
                    tickDifference, serverStartTick, adjustedStartTick);
            return adjustedStartTick;
        }

        if (tickDifference < -5) {
            int adjustedStartTick = currentClientTick - 2;
            GrapesEatingAnimation.LOGGER.debug("GEA: Server tick in future detected, adjusting start tick from {} to {}",
                    serverStartTick, adjustedStartTick);
            return adjustedStartTick;
        }

        if (tickDifference > packet.getUseDuration()) {
            int adjustedStartTick = currentClientTick - Math.min(5, packet.getUseDuration() / 4);
            GrapesEatingAnimation.LOGGER.debug("GEA: Very old packet detected, starting short animation from tick {}",
                    adjustedStartTick);
            return adjustedStartTick;
        }

        return serverStartTick;
    }

    private static Entity findEntity(Minecraft minecraft, int entityId) {
        if (minecraft.level == null) {
            return null;
        }

        if (minecraft.player != null && minecraft.player.getId() == entityId) {
            return minecraft.player;
        }

        try {
            Entity entity = minecraft.level.getEntity(entityId);
            if (entity != null) {
                return entity;
            }
        } catch (Exception ignored) {}

        try {
            for (Player player : minecraft.level.players()) {
                if (player != null && player.getId() == entityId) {
                    return player;
                }
            }
        } catch (Exception ignored) {}

        try {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity != null && entity.getId() == entityId && entity instanceof Player) {
                    return entity;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    public static void resetInitializationState() {
        clientFullyInitialized = false;
        initializationTicks = 0;
        connectionTime = System.currentTimeMillis();
        hasConnectedBefore = true;

        GrapesEatingAnimation.LOGGER.debug("GEA: Reset client initialization state (keeping {} delayed packets)",
                delayedPackets.size());
    }

    @SubscribeEvent
    public static void onPlayerJoinWorld(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            Minecraft minecraft = Minecraft.getInstance();

            if (player == minecraft.player) {
                resetInitializationState();
                GrapesEatingAnimation.LOGGER.info("GEA: Local player joined world, reset initialization state");
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLeaveWorld(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            Minecraft minecraft = Minecraft.getInstance();

            if (player == minecraft.player) {
                delayedPackets.clear();
                clientFullyInitialized = false;
                GrapesEatingAnimation.LOGGER.info("GEA: Local player left world, cleared state");
            }
        }
    }

    public static void forceInitialization() {
        clientFullyInitialized = true;
        GrapesEatingAnimation.LOGGER.info("GEA: Forced client initialization");
    }
    
    public static String getDebugInfo() {
        return String.format("ClientNetworkHandler{initialized=%s, ticks=%d, delayedPackets=%d, hasConnectedBefore=%s, timeSinceConnection=%d}",
                clientFullyInitialized, initializationTicks, delayedPackets.size(), hasConnectedBefore,
                System.currentTimeMillis() - connectionTime);
    }
}
