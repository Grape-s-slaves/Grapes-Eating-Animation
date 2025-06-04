package net.grapes.gea;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ClientNetworkHandler {

    public static void handleEatingAnimationPacket(NetworkHandler.EatingAnimationPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        Entity entity = null;
        try {
            entity = minecraft.level.getEntity(packet.getPlayerId());
        } catch (Exception e) {
            GrapesEatingAnimation.LOGGER.warn("GEA: Failed to get entity with ID {}: {}", packet.getPlayerId(), e.getMessage());
            return;
        }

        if (!(entity instanceof Player)) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Entity with ID {} is not a player", packet.getPlayerId());
            return;
        }

        Player player = (Player) entity;

        if (packet.isEating() && packet.getItemId() != null) {
            try {
                ResourceLocation itemId = new ResourceLocation(packet.getItemId());
                if (EatingAnimationConfig.hasAnimation(itemId)) {
                    EatingAnimationHandler.EatingAnimationState state =
                            new EatingAnimationHandler.EatingAnimationState(itemId, packet.getUseDuration(), packet.getStartTick());
                    EatingAnimationHandler.setAnimationState(player, state);

                    GrapesEatingAnimation.LOGGER.debug("GEA: Started eating animation for player {} with item {}",
                            player.getName().getString(), packet.getItemId());
                }
            } catch (Exception e) {
                GrapesEatingAnimation.LOGGER.warn("GEA: Failed to start eating animation: {}", e.getMessage());
            }
        } else {
            EatingAnimationHandler.clearAnimationState(player);
            GrapesEatingAnimation.LOGGER.debug("GEA: Stopped eating animation for player {}",
                    player.getName().getString());
        }
    }
}