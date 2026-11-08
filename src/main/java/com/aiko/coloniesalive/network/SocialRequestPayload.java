package com.aiko.coloniesalive.network;

import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.social.SocialSnapshot;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client to server: the player opened the "social life" tab of a citizen's window and wants its data.
 */
public record SocialRequestPayload(ResourceKey<Level> dimension, int colonyId, int citizenId) implements CustomPacketPayload
{
    public static final Type<SocialRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, "social_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SocialRequestPayload> CODEC = StreamCodec.composite(
      ResourceKey.streamCodec(Registries.DIMENSION), SocialRequestPayload::dimension,
      ByteBufCodecs.VAR_INT, SocialRequestPayload::colonyId,
      ByteBufCodecs.VAR_INT, SocialRequestPayload::citizenId,
      SocialRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public static void handle(final SocialRequestPayload payload, final IPayloadContext context)
    {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player)
            {
                final SocialInfoPayload info = SocialSnapshot.build(player, payload.dimension(), payload.colonyId(), payload.citizenId());
                if (info != null)
                {
                    PacketDistributor.sendToPlayer(player, info);
                }
            }
        });
    }
}
