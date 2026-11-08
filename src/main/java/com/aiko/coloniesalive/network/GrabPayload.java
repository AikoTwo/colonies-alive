package com.aiko.coloniesalive.network;

import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.server.GrabCarry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: start holding a child, put it down, or move it closer/farther.
 */
public record GrabPayload(int action, int entityId, float value) implements CustomPacketPayload
{
    public static final int START    = 0;
    public static final int DROP     = 1;
    public static final int DISTANCE = 2;

    public static final Type<GrabPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, "grab"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GrabPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, GrabPayload::action,
      ByteBufCodecs.VAR_INT, GrabPayload::entityId,
      ByteBufCodecs.FLOAT, GrabPayload::value,
      GrabPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public static void handle(final GrabPayload payload, final IPayloadContext context)
    {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player)
            {
                GrabCarry.onPayload(player, payload);
            }
        });
    }
}
