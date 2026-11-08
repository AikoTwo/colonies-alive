package com.aiko.coloniesalive.network;

import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.client.ClientFamily;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server to client: a citizen is pregnant or a baby (the belly, the pose and the time shown on the name tag).
 *
 * @param stage     0 nothing, 1 pregnant, 2 baby
 * @param progress  0..1 of the pregnancy or of the baby time
 * @param remaining ticks left until the birth / until it is a child
 */
public record FamilyPayload(int entityId, int stage, float progress, int remaining) implements CustomPacketPayload
{
    public static final int NONE     = 0;
    public static final int PREGNANT = 1;
    public static final int INFANT   = 2;

    public static final Type<FamilyPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, "family"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FamilyPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, FamilyPayload::entityId,
      ByteBufCodecs.VAR_INT, FamilyPayload::stage,
      ByteBufCodecs.FLOAT, FamilyPayload::progress,
      ByteBufCodecs.VAR_INT, FamilyPayload::remaining,
      FamilyPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public static void handle(final FamilyPayload payload, final IPayloadContext context)
    {
        context.enqueueWork(() -> ClientFamily.set(payload));
    }
}
