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

    // Queue for delayed packet processing
    private static final ConcurrentLinkedQueue<DelayedPacket> delayedPackets = new ConcurrentLinkedQueue<>();
    private static boolean registered = false;

    // Register this class to receive tick events
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

        processDelayedPackets();
    }

    private static void processDelayedPackets() {
        Iterator<DelayedPacket> iterator = delayedPackets.iterator();
        while (iterator.hasNext()) {
            DelayedPacket delayed = iterator.next();

            // Increase timeout for multiplayer (network delays)
            if (System.currentTimeMillis() - delayed.timestamp > 10000) { // 10 second timeout
                iterator.remove();
                GrapesEatingAnimation.LOGGER.warn("GEA: Dropped delayed packet after timeout for entity {}",
                        delayed.packet.getPlayerId());
                continue;
            }

            // Try to process again
            if (handleEatingAnimationPacketInternal(delayed.packet)) {
                iterator.remove(); // Success, remove from queue
                GrapesEatingAnimation.LOGGER.debug("GEA: Successfully processed delayed packet for entity {}",
                        delayed.packet.getPlayerId());
            } else {
                // Add more detailed logging for failed attempts
                GrapesEatingAnimation.LOGGER.debug("GEA: Still waiting for entity {} (attempt age: {}ms)",
                        delayed.packet.getPlayerId(), System.currentTimeMillis() - delayed.timestamp);
            }
        }
    }

    public static void handleEatingAnimationPacket(NetworkHandler.EatingAnimationPacket packet) {
        GrapesEatingAnimation.LOGGER.info("GEA: Received eating animation packet - Player: {}, Item: {}, Eating: {}",
                packet.getPlayerId(), packet.getItemId(), packet.isEating());

        if (!handleEatingAnimationPacketInternal(packet)) {
            // If we couldn't process it immediately, queue it for later
            delayedPackets.offer(new DelayedPacket(packet));
            GrapesEatingAnimation.LOGGER.info("GEA: Queued packet for delayed processing (entity {} not found)",
                    packet.getPlayerId());
        } else {
            GrapesEatingAnimation.LOGGER.info("GEA: Successfully processed packet immediately for entity {}",
                    packet.getPlayerId());
        }
    }

    private static class DelayedPacket {
        final NetworkHandler.EatingAnimationPacket packet;
        final long timestamp;

        DelayedPacket(NetworkHandler.EatingAnimationPacket packet) {
            this.packet = packet;
            this.timestamp = System.currentTimeMillis();
        }
    }

    // Add this to your ClientNetworkHandler.handleEatingAnimationPacketInternal method
    private static boolean handleEatingAnimationPacketInternal(NetworkHandler.EatingAnimationPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();

        GrapesEatingAnimation.LOGGER.info("GEA: [DEBUG] Processing packet - Player ID: {}, Item: {}, Eating: {}",
                packet.getPlayerId(), packet.getItemId(), packet.isEating());

        if (minecraft.level == null) {
            GrapesEatingAnimation.LOGGER.warn("GEA: [DEBUG] Client level is null, ignoring packet");
            return false;
        }

        // Log current world state
        GrapesEatingAnimation.LOGGER.info("GEA: [DEBUG] Current world has {} players, {} total entities",
                minecraft.level.players().size(),
                minecraft.level.entitiesForRendering().spliterator().estimateSize());

        if (minecraft.player != null) {
            GrapesEatingAnimation.LOGGER.info("GEA: [DEBUG] Local player ID: {}, UUID: {}",
                    minecraft.player.getId(), minecraft.player.getUUID());
        }

        Entity entity = findEntity(minecraft, packet.getPlayerId());

        if (entity == null) {
            GrapesEatingAnimation.LOGGER.warn("GEA: [DEBUG] Could not find entity with ID {} in client world. Available players:",
                    packet.getPlayerId());

            for (Player player : minecraft.level.players()) {
                GrapesEatingAnimation.LOGGER.info("GEA: [DEBUG] Available player - ID: {}, Name: {}, UUID: {}",
                        player.getId(), player.getName().getString(), player.getUUID());
            }

            return false;
        }

        GrapesEatingAnimation.LOGGER.info("GEA: [DEBUG] Found entity - ID: {}, Type: {}, Name: {}",
                entity.getId(), entity.getClass().getSimpleName(),
                entity instanceof Player ? ((Player)entity).getName().getString() : "N/A");

        if (!(entity instanceof Player)) {
            GrapesEatingAnimation.LOGGER.warn("GEA: [DEBUG] Entity with ID {} is not a player", packet.getPlayerId());
            return true;
        }

        Player player = (Player) entity;

        if (packet.isEating() && packet.getItemId() != null) {
            try {
                ResourceLocation itemId = new ResourceLocation(packet.getItemId());
                if (EatingAnimationConfig.hasAnimation(itemId)) {
                    EatingAnimationHandler.EatingAnimationState state =
                            new EatingAnimationHandler.EatingAnimationState(itemId, packet.getUseDuration(), packet.getStartTick());
                    EatingAnimationHandler.setAnimationState(player, state);

                    GrapesEatingAnimation.LOGGER.info("GEA: [DEBUG] ✓ Started eating animation for player {} with item {}",
                            player.getName().getString(), packet.getItemId());
                } else {
                    GrapesEatingAnimation.LOGGER.warn("GEA: [DEBUG] No animation configured for item {}", packet.getItemId());
                }
            } catch (Exception e) {
                GrapesEatingAnimation.LOGGER.error("GEA: [DEBUG] Failed to start eating animation: {}", e.getMessage(), e);
            }
        } else {
            EatingAnimationHandler.clearAnimationState(player);
            GrapesEatingAnimation.LOGGER.info("GEA: [DEBUG] ✓ Stopped eating animation for player {}",
                    player.getName().getString());
        }

        return true;
    }

    private static Entity findEntity(Minecraft minecraft, int entityId) {
        // Method 1: Direct lookup
        try {
            Entity entity = minecraft.level.getEntity(entityId);
            if (entity != null) {
                GrapesEatingAnimation.LOGGER.debug("GEA: Found entity {} via direct lookup", entityId);
                return entity;
            }
        } catch (Exception e) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Direct entity lookup failed: {}", e.getMessage());
        }

        try {
            if (minecraft.player != null && minecraft.player.getId() == entityId) {
                GrapesEatingAnimation.LOGGER.debug("GEA: Entity {} is the local player", entityId);
                return minecraft.player;
            }
        } catch (Exception e) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Local player check failed: {}", e.getMessage());
        }

        try {
            for (Player player : minecraft.level.players()) {
                if (player != null && player.getId() == entityId) {
                    GrapesEatingAnimation.LOGGER.debug("GEA: Found entity {} through player iteration", entityId);
                    return player;
                }
            }

            if (minecraft.getConnection() != null && minecraft.getConnection().getOnlinePlayers() != null) {
                for (var playerInfo : minecraft.getConnection().getOnlinePlayers()) {
                    if (playerInfo.getProfile().getId().equals(minecraft.player.getGameProfile().getId())) {
                        continue;
                    }
                }
            }
        } catch (Exception e) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Player iteration failed: {}", e.getMessage());
        }

        try {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity != null && entity.getId() == entityId && entity instanceof Player) {
                    GrapesEatingAnimation.LOGGER.debug("GEA: Found entity {} through entity iteration", entityId);
                    return entity;
                }
            }
        } catch (Exception e) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Entity iteration failed: {}", e.getMessage());
        }

        try {
            if (minecraft.level instanceof net.minecraft.client.multiplayer.ClientLevel) {
                net.minecraft.client.multiplayer.ClientLevel clientLevel = (net.minecraft.client.multiplayer.ClientLevel) minecraft.level;
                for (Entity entity : clientLevel.entitiesForRendering()) {
                    if (entity instanceof Player && entity.getId() == entityId) {
                        GrapesEatingAnimation.LOGGER.debug("GEA: Found player {} in client level", entityId);
                        return entity;
                    }
                }
            }
        } catch (Exception e) {
            GrapesEatingAnimation.LOGGER.debug("GEA: ClientLevel search failed: {}", e.getMessage());
        }

        GrapesEatingAnimation.LOGGER.warn("GEA: Could not find entity with ID {} after all lookup methods", entityId);
        return null;
    }
}