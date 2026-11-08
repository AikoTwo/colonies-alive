package com.aiko.coloniesalive.network;

import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.client.ClientGloom;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server to client: a citizen fell into (or came out of) a bad mood, which changes how it walks.
 */
public record GloomPayload(int entityId, boolean gloomy) implements CustomPacketPayload
{
    public static final Type<GloomPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, "gloom"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GloomPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, GloomPayload::entityId,
      ByteBufCodecs.BOOL, GloomPayload::gloomy,
      GloomPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public static void handle(final GloomPayload payload, final IPayloadContext context)
    {
        context.enqueueWork(() -> ClientGloom.set(payload.entityId(), payload.gloomy()));
    }
}
