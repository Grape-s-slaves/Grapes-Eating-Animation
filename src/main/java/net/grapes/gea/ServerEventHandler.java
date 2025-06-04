package net.grapes.gea;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = GrapesEatingAnimation.MODID)
public class ServerEventHandler {

    private static final ConcurrentHashMap<Player, EatingState> serverEatingStates = new ConcurrentHashMap<>();

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

        EatingState state = new EatingState(itemId.toString(), itemStack.getUseDuration(), player.tickCount);
        serverEatingStates.put(player, state);

        NetworkHandler.EatingAnimationPacket packet = new NetworkHandler.EatingAnimationPacket(
                player.getId(),
                itemId.toString(),
                itemStack.getUseDuration(),
                true,
                player.tickCount
        );

        NetworkHandler.INSTANCE.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                packet
        );

        GrapesEatingAnimation.LOGGER.debug("GEA: Player {} started eating {}",
                player.getName().getString(), itemId);
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
            if (!player.isUsingItem() || !player.getUseItem().isEdible()) {
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
            GrapesEatingAnimation.LOGGER.debug("GEA: Cleaned up server eating state for disconnected player");
        }
    }

    private static void stopEatingAnimation(ServerPlayer player) {
        EatingState state = serverEatingStates.remove(player);
        if (state != null) {
            NetworkHandler.EatingAnimationPacket packet = new NetworkHandler.EatingAnimationPacket(
                    player.getId(),
                    null,
                    0,
                    false,
                    0
            );

            NetworkHandler.INSTANCE.send(
                    PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                    packet
            );

            GrapesEatingAnimation.LOGGER.debug("GEA: Player {} stopped eating",
                    player.getName().getString());
        }
    }

    private static class EatingState {
        final String itemId;
        final int duration;
        final int startTick;

        EatingState(String itemId, int duration, int startTick) {
            this.itemId = itemId;
            this.duration = duration;
            this.startTick = startTick;
        }
    }
}