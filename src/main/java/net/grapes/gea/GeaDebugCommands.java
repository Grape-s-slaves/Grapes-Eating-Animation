package net.grapes.gea;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = GrapesEatingAnimation.MODID)
public class GeaDebugCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("gea")
                .then(Commands.literal("animations")
                        .executes(GeaDebugCommands::showAnimationsCount))
                .then(Commands.literal("get-loaded")
                        .executes(GeaDebugCommands::giveLoadedItems))
                .then(Commands.literal("reload")
                        .executes(GeaDebugCommands::reloadConfig))
                .then(Commands.literal("info")
                        .executes(GeaDebugCommands::showPlayerInfo))
        );

        GrapesEatingAnimation.LOGGER.info("GEA: Registered debug commands");
    }

    private static int showAnimationsCount(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // Check if player is in creative mode
        if (!isCreativeMode(context)) {
            context.getSource().sendFailure(Component.literal("§cThis command only works in Creative mode!"));
            return 0;
        }

        Map<String, List<String>> animations = EatingAnimationConfig.getAllAnimations();
        int count = animations.size();

        context.getSource().sendSuccess(
                () -> Component.literal("§aAnimations loaded: §f" + count),
                false
        );

        // Show detailed info if there are animations
        if (count > 0) {
            context.getSource().sendSuccess(
                    () -> Component.literal("§7Animated items:"),
                    false
            );

            for (Map.Entry<String, List<String>> entry : animations.entrySet()) {
                String itemId = entry.getKey();
                int frameCount = entry.getValue().size();
                context.getSource().sendSuccess(
                        () -> Component.literal("§7  - §f" + itemId + "§7 (§f" + frameCount + "§7 frames)"),
                        false
                );
            }
        }

        return count;
    }

    private static int giveLoadedItems(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // Check if player is in creative mode
        if (!isCreativeMode(context)) {
            context.getSource().sendFailure(Component.literal("§cThis command only works in Creative mode!"));
            return 0;
        }

        ServerPlayer player = context.getSource().getPlayerOrException();
        Map<String, List<String>> animations = EatingAnimationConfig.getAllAnimations();

        if (animations.isEmpty()) {
            context.getSource().sendFailure(Component.literal("§cNo animations loaded!"));
            return 0;
        }

        int itemsGiven = 0;
        int itemsFailed = 0;

        for (String itemIdString : animations.keySet()) {
            try {
                ResourceLocation itemId = new ResourceLocation(itemIdString);
                Item item = ForgeRegistries.ITEMS.getValue(itemId);

                if (item != null) {
                    ItemStack stack = new ItemStack(item, 64); // Give a full stack

                    // Only give edible items (since that's what the animation is for)
                    if (stack.isEdible()) {
                        player.getInventory().add(stack);
                        itemsGiven++;
                        GrapesEatingAnimation.LOGGER.debug("GEA: Gave {} x64 to player", itemId);
                    } else {
                        GrapesEatingAnimation.LOGGER.debug("GEA: Skipped non-edible item: {}", itemId);
                    }
                } else {
                    itemsFailed++;
                    GrapesEatingAnimation.LOGGER.warn("GEA: Could not find item: {}", itemIdString);
                }
            } catch (Exception e) {
                itemsFailed++;
                GrapesEatingAnimation.LOGGER.warn("GEA: Error giving item {}: {}", itemIdString, e.getMessage());
            }
        }

        // Create final copies for use in lambdas
        final int finalItemsGiven = itemsGiven;
        final int finalItemsFailed = itemsFailed;

        if (itemsGiven > 0) {
            context.getSource().sendSuccess(
                    () -> Component.literal("§aGave §f" + finalItemsGiven + "§a animated food items!"),
                    false
            );
        }

        if (itemsFailed > 0) {
            context.getSource().sendSuccess(
                    () -> Component.literal("§6Warning: §f" + finalItemsFailed + "§6 items could not be given"),
                    false
            );
        }

        return itemsGiven;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // Check if player is in creative mode
        if (!isCreativeMode(context)) {
            context.getSource().sendFailure(Component.literal("§cThis command only works in Creative mode!"));
            return 0;
        }

        try {
            EatingAnimationConfig.reloadConfig();
            int count = EatingAnimationConfig.getAllAnimations().size();

            context.getSource().sendSuccess(
                    () -> Component.literal("§aReloaded animation config! §f" + count + "§a animations loaded."),
                    false
            );

            GrapesEatingAnimation.LOGGER.info("GEA: Config reloaded via command, {} animations loaded", count);
            return 1;

        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("§cFailed to reload config: " + e.getMessage()));
            GrapesEatingAnimation.LOGGER.error("GEA: Failed to reload config via command", e);
            return 0;
        }
    }

    private static int showPlayerInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // Check if player is in creative mode
        if (!isCreativeMode(context)) {
            context.getSource().sendFailure(Component.literal("§cThis command only works in Creative mode!"));
            return 0;
        }

        ServerPlayer player = context.getSource().getPlayerOrException();

        context.getSource().sendSuccess(
                () -> Component.literal("§aPlayer Debug Info:"),
                false
        );

        context.getSource().sendSuccess(
                () -> Component.literal("§7  - Using item: §f" + player.isUsingItem()),
                false
        );

        if (player.isUsingItem()) {
            ItemStack useItem = player.getUseItem();
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(useItem.getItem());

            context.getSource().sendSuccess(
                    () -> Component.literal("§7  - Item: §f" + (itemId != null ? itemId.toString() : "null")),
                    false
            );

            context.getSource().sendSuccess(
                    () -> Component.literal("§7  - Edible: §f" + useItem.isEdible()),
                    false
            );

            context.getSource().sendSuccess(
                    () -> Component.literal("§7  - Use duration: §f" + useItem.getUseDuration()),
                    false
            );

            if (itemId != null) {
                boolean hasAnimation = EatingAnimationConfig.hasAnimation(itemId);
                context.getSource().sendSuccess(
                        () -> Component.literal("§7  - Has animation: §f" + hasAnimation),
                        false
                );

                if (hasAnimation) {
                    List<String> frames = EatingAnimationConfig.getAnimationFrames(itemId);
                    context.getSource().sendSuccess(
                            () -> Component.literal("§7  - Animation frames: §f" + (frames != null ? frames.size() : 0)),
                            false
                    );
                }
            }
        }

        return 1;
    }

    private static boolean isCreativeMode(CommandContext<CommandSourceStack> context) {
        try {
            if (context.getSource().getEntity() instanceof Player) {
                Player player = (Player) context.getSource().getEntity();
                return player.isCreative();
            }
            return false;
        } catch (Exception e) {
            GrapesEatingAnimation.LOGGER.debug("GEA: Could not check creative mode: {}", e.getMessage());
            return false;
        }
    }
}