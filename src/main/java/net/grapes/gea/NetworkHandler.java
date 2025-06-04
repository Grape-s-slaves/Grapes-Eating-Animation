package net.grapes.gea;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(GrapesEatingAnimation.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;
    private static int id() {
        return packetId++;
    }

    public static void register() {
        INSTANCE.registerMessage(id(), EatingAnimationPacket.class,
                EatingAnimationPacket::encode,
                EatingAnimationPacket::decode,
                EatingAnimationPacket::handle);

        GrapesEatingAnimation.LOGGER.info("GEA: Network handler registered");
    }

    public static class EatingAnimationPacket {
        private final int playerId;
        private final String itemId;
        private final int useDuration;
        private final boolean isEating;
        private final int startTick;

        public EatingAnimationPacket(int playerId, String itemId, int useDuration, boolean isEating, int startTick) {
            this.playerId = playerId;
            this.itemId = itemId;
            this.useDuration = useDuration;
            this.isEating = isEating;
            this.startTick = startTick;
        }

        public static void encode(EatingAnimationPacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.playerId);
            buf.writeUtf(msg.itemId != null ? msg.itemId : "");
            buf.writeInt(msg.useDuration);
            buf.writeBoolean(msg.isEating);
            buf.writeInt(msg.startTick);
        }

        public static EatingAnimationPacket decode(FriendlyByteBuf buf) {
            int playerId = buf.readInt();
            String itemId = buf.readUtf();
            int useDuration = buf.readInt();
            boolean isEating = buf.readBoolean();
            int startTick = buf.readInt();

            return new EatingAnimationPacket(playerId, itemId.isEmpty() ? null : itemId, useDuration, isEating, startTick);
        }

        public static void handle(EatingAnimationPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                // Handle on client side
                if (context.getDirection().getReceptionSide().isClient()) {
                    ClientNetworkHandler.handleEatingAnimationPacket(msg);
                }
            });
            context.setPacketHandled(true);
        }

        // Getters
        public int getPlayerId() { return playerId; }
        public String getItemId() { return itemId; }
        public int getUseDuration() { return useDuration; }
        public boolean isEating() { return isEating; }
        public int getStartTick() { return startTick; }
    }
}