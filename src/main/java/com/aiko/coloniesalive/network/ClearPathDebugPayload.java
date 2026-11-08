package com.aiko.coloniesalive.network;

import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.client.ClientPathDebug;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server to client: wipe MineColonies' path debug drawing.
 */
public record ClearPathDebugPayload() implements CustomPacketPayload
{
    public static final Type<ClearPathDebugPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, "clear_path_debug"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClearPathDebugPayload> CODEC = StreamCodec.unit(new ClearPathDebugPayload());

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public static void handle(final ClearPathDebugPayload payload, final IPayloadContext context)
    {
        context.enqueueWork(() -> ClientPathDebug.clear());
    }
}
