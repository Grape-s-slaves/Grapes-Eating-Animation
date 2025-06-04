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

        // Find the player by ID - fixed to only call getEntity() once
        Entity entity = minecraft.level.getEntity(packet.getPlayerId());
        Player player = entity instanceof Player ? (Player) entity : null;

        if (player == null) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Could not find player with ID {}", packet.getPlayerId());
            return;
        }

        if (packet.isEating() && packet.getItemId() != null) {
            // Start eating animation
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
            // Stop eating animation
            EatingAnimationHandler.clearAnimationState(player);
            GrapesEatingAnimation.LOGGER.debug("GEA: Stopped eating animation for player {}",
                    player.getName().getString());
        }
    }
}