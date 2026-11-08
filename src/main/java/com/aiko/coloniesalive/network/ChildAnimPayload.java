package com.aiko.coloniesalive.network;

import com.aiko.coloniesalive.ChildAnimation;
import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.client.ClientAnimations;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Tells clients which animation a citizen is currently playing.
 */
public record ChildAnimPayload(int entityId, int animId, int durationTicks) implements CustomPacketPayload
{
    public static final Type<ChildAnimPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, "child_anim"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChildAnimPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, ChildAnimPayload::entityId,
      ByteBufCodecs.VAR_INT, ChildAnimPayload::animId,
      ByteBufCodecs.VAR_INT, ChildAnimPayload::durationTicks,
      ChildAnimPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public static void handle(final ChildAnimPayload payload, final IPayloadContext context)
    {
        context.enqueueWork(() -> ClientAnimations.start(
          payload.entityId(),
          ChildAnimation.byId(payload.animId()),
          context.player().level().getGameTime(),
          payload.durationTicks()));
    }
}
