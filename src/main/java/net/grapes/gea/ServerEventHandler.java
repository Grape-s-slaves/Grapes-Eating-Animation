package net.grapes.gea;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = GrapesEatingAnimation.MODID)
public class ServerEventHandler {

    private static final ConcurrentHashMap<ServerPlayer, EatingState> serverEatingStates = new ConcurrentHashMap<>();

    private static final int PERIODIC_SYNC_INTERVAL = 40; // 2 seconds
    private static final int FAST_SYNC_INTERVAL = 10; // 0.5 seconds
    private static final int FAST_SYNC_DURATION = 100; // 5 seconds
    private static final int NEW_PLAYER_SYNC_DELAY = 30; // 1.5 seconds
    private static final double SYNC_DISTANCE = 64.0; // 64 blocks

    private static int serverTick = 0;
    private static final ConcurrentHashMap<ServerPlayer, Integer> newPlayerConnections = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        serverTick++;

        handleNewPlayerSync();

        if (serverTick % PERIODIC_SYNC_INTERVAL == 0) {
            performPeriodicSync();
        }

        if (serverTick % FAST_SYNC_INTERVAL == 0) {
            performFastSync();
        }
    }

    @SubscribeEvent
    public static void onItemUseStart(LivingEntityUseItemEvent.Start event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }

        ServerPlayer player = (ServerPlayer) event.getEntity();
        ItemStack itemStack = event.getItem();

        if (!itemStack.isEdible()) {
            return;
        }

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(itemStack.getItem());
        if (itemId == null || !EatingAnimationConfig.hasAnimation(itemId)) {
            return;
        }

        int currentTick = player.tickCount;
        EatingState state = new EatingState(itemId.toString(), itemStack.getUseDuration(), currentTick, serverTick);
        serverEatingStates.put(player, state);

        broadcastEatingAnimation(player, itemId.toString(), itemStack.getUseDuration(), true, currentTick);

        GrapesEatingAnimation.LOGGER.debug("GEA: Player {} started eating {} at server tick {} (broadcast to {} players)",
                player.getName().getString(), itemId, currentTick,
                getNearbyPlayers(player, SYNC_DISTANCE).size());
    }

    @SubscribeEvent
    public static void onItemUseStop(LivingEntityUseItemEvent.Stop event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }

        ServerPlayer player = (ServerPlayer) event.getEntity();
        stopEatingAnimation(player);
    }

    @SubscribeEvent
    public static void onItemUseFinish(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }

        ServerPlayer player = (ServerPlayer) event.getEntity();
        stopEatingAnimation(player);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer)) {
            return;
        }

        ServerPlayer player = (ServerPlayer) event.player;
        EatingState state = serverEatingStates.get(player);

        if (state != null) {
            if (!player.isUsingItem()) {
                stopEatingAnimation(player);
                return;
            }

            ItemStack currentItem = player.getUseItem();
            if (!currentItem.isEdible()) {
                stopEatingAnimation(player);
                return;
            }

            ResourceLocation currentItemId = ForgeRegistries.ITEMS.getKey(currentItem.getItem());
            if (currentItemId == null || !currentItemId.toString().equals(state.itemId)) {
                stopEatingAnimation(player);
                return;
            }

            int elapsedTicks = player.tickCount - state.startTick;
            if (elapsedTicks >= state.duration) {
                stopEatingAnimation(player);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            ServerPlayer player = (ServerPlayer) event.getEntity();
            serverEatingStates.remove(player);
            newPlayerConnections.remove(player);
            GrapesEatingAnimation.LOGGER.debug("GEA: Cleaned up server eating state for disconnected player");
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            ServerPlayer player = (ServerPlayer) event.getEntity();

            serverEatingStates.remove(player);
            newPlayerConnections.put(player, serverTick);

            GrapesEatingAnimation.LOGGER.debug("GEA: Scheduled delayed sync for respawned player");
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            ServerPlayer player = (ServerPlayer) event.getEntity();

            newPlayerConnections.put(player, serverTick);

            GrapesEatingAnimation.LOGGER.debug("GEA: Scheduled delayed sync for newly connected player");
        }
    }

    private static void handleNewPlayerSync() {
        if (newPlayerConnections.isEmpty()) {
            return;
        }

        List<ServerPlayer> playersToSync = new ArrayList<>();

        newPlayerConnections.entrySet().removeIf(entry -> {
            ServerPlayer player = entry.getKey();
            int connectionTick = entry.getValue();

            if (serverTick - connectionTick >= NEW_PLAYER_SYNC_DELAY) {
                if (player.isAlive() && !player.isRemoved()) {
                    playersToSync.add(player);
                }
                return true;
            }
            return false;
        });

        for (ServerPlayer newPlayer : playersToSync) {
            syncAllEatingStatesTo(newPlayer);
        }
    }

    private static void performPeriodicSync() {
        if (serverEatingStates.isEmpty()) {
            return;
        }

        int syncedAnimations = 0;
        for (ServerPlayer eatingPlayer : serverEatingStates.keySet()) {
            EatingState state = serverEatingStates.get(eatingPlayer);
            if (state != null && eatingPlayer.isUsingItem()) {
                syncEatingStateToNearbyPlayers(eatingPlayer, state);
                syncedAnimations++;
            }
        }

        if (syncedAnimations > 0) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Periodic sync completed for {} eating animations", syncedAnimations);
        }
    }

    private static void performFastSync() {
        if (serverEatingStates.isEmpty()) {
            return;
        }

        int fastSyncCount = 0;
        for (ServerPlayer eatingPlayer : serverEatingStates.keySet()) {
            EatingState state = serverEatingStates.get(eatingPlayer);
            if (state != null && eatingPlayer.isUsingItem()) {
                int animationAge = serverTick - state.creationServerTick;
                if (animationAge <= FAST_SYNC_DURATION) {
                    syncEatingStateToNearbyPlayers(eatingPlayer, state);
                    fastSyncCount++;
                }
            }
        }

        if (fastSyncCount > 0) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Fast sync completed for {} recent animations", fastSyncCount);
        }
    }

    private static void syncAllEatingStatesTo(ServerPlayer targetPlayer) {
        int syncedCount = 0;

        for (ServerPlayer eatingPlayer : serverEatingStates.keySet()) {
            if (eatingPlayer == targetPlayer) {
                continue;
            }

            EatingState state = serverEatingStates.get(eatingPlayer);
            if (state != null && eatingPlayer.isUsingItem()) {
                if (arePlayersInSyncRange(eatingPlayer, targetPlayer)) {
                    NetworkHandler.EatingAnimationPacket packet = new NetworkHandler.EatingAnimationPacket(
                            eatingPlayer.getId(),
                            state.itemId,
                            state.duration,
                            true,
                            state.startTick
                    );

                    NetworkHandler.INSTANCE.send(
                            PacketDistributor.PLAYER.with(() -> targetPlayer),
                            packet
                    );
                    syncedCount++;
                }
            }
        }

        if (syncedCount > 0) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Synced {} eating animations to newly connected player {}",
                    syncedCount, targetPlayer.getName().getString());
        }
    }

    private static void syncEatingStateToNearbyPlayers(ServerPlayer eatingPlayer, EatingState state) {
        List<ServerPlayer> nearbyPlayers = getNearbyPlayers(eatingPlayer, SYNC_DISTANCE);

        if (nearbyPlayers.isEmpty()) {
            return;
        }

        NetworkHandler.EatingAnimationPacket packet = new NetworkHandler.EatingAnimationPacket(
                eatingPlayer.getId(),
                state.itemId,
                state.duration,
                true,
                state.startTick
        );

        for (ServerPlayer nearbyPlayer : nearbyPlayers) {
            if (nearbyPlayer != eatingPlayer) {
                NetworkHandler.INSTANCE.send(
                        PacketDistributor.PLAYER.with(() -> nearbyPlayer),
                        packet
                );
            }
        }
    }

    private static void broadcastEatingAnimation(ServerPlayer eatingPlayer, String itemId, int duration, boolean isEating, int startTick) {
        List<ServerPlayer> nearbyPlayers = getNearbyPlayers(eatingPlayer, SYNC_DISTANCE);

        NetworkHandler.EatingAnimationPacket packet = new NetworkHandler.EatingAnimationPacket(
                eatingPlayer.getId(),
                itemId,
                duration,
                isEating,
                startTick
        );

        for (ServerPlayer player : nearbyPlayers) {
            NetworkHandler.INSTANCE.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    packet
            );
        }
    }

    private static List<ServerPlayer> getNearbyPlayers(ServerPlayer centerPlayer, double maxDistance) {
        List<ServerPlayer> nearbyPlayers = new ArrayList<>();

        if (centerPlayer.level() instanceof ServerLevel serverLevel) {
            Vec3 centerPos = centerPlayer.position();

            for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
                if (player.level() == serverLevel) {
                    if (player == centerPlayer) {
                        nearbyPlayers.add(player);
                    } else {
                        double distance = player.position().distanceTo(centerPos);
                        if (distance <= maxDistance) {
                            nearbyPlayers.add(player);
                        }
                    }
                }
            }
        }

        return nearbyPlayers;
    }

    private static boolean arePlayersInSyncRange(ServerPlayer player1, ServerPlayer player2) {
        if (player1.level() != player2.level()) {
            return false;
        }

        double distance = player1.position().distanceTo(player2.position());
        return distance <= SYNC_DISTANCE;
    }

    private static void stopEatingAnimation(ServerPlayer player) {
        EatingState state = serverEatingStates.remove(player);
        if (state != null) {
            List<ServerPlayer> nearbyPlayers = getNearbyPlayers(player, SYNC_DISTANCE);

            NetworkHandler.EatingAnimationPacket packet = new NetworkHandler.EatingAnimationPacket(
                    player.getId(),
                    null,
                    0,
                    false,
                    0
            );

            for (ServerPlayer nearbyPlayer : nearbyPlayers) {
                NetworkHandler.INSTANCE.send(
                        PacketDistributor.PLAYER.with(() -> nearbyPlayer),
                        packet
                );
            }

            GrapesEatingAnimation.LOGGER.debug("GEA: Player {} stopped eating (broadcast to {} players)",
                    player.getName().getString(), nearbyPlayers.size());
        }
    }


    public static String getDebugInfo() {
        return String.format("ServerEventHandler{activeAnimations=%d, newConnections=%d, serverTick=%d, syncRange=%.1f}",
                serverEatingStates.size(), newPlayerConnections.size(), serverTick, SYNC_DISTANCE);
    }

    public static int getActiveAnimationCount() {
        return serverEatingStates.size();
    }

    public static void forceSync() {
        performPeriodicSync();
        GrapesEatingAnimation.LOGGER.info("GEA: Forced synchronization completed");
    }

    private static class EatingState {
        final String itemId;
        final int duration;
        final int startTick;
        final int creationServerTick;

        EatingState(String itemId, int duration, int startTick, int creationServerTick) {
            this.itemId = itemId;
            this.duration = duration;
            this.startTick = startTick;
            this.creationServerTick = creationServerTick;
        }
    }
}