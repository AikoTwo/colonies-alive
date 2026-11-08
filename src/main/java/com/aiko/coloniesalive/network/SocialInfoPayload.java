package com.aiko.coloniesalive.network;

import com.aiko.coloniesalive.ColoniesAlive;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: the social life of one citizen, shown in the "social life" tab of its window.
 *
 * @param mood     -10 .. 10
 * @param affinity -100 .. 100, how much the citizen likes the player who asked
 * @param partner  partner's name, empty if none
 * @param bonds    friends and rivals, best friend first
 */
public record SocialInfoPayload(int citizenId, float mood, int affinity, String partner, List<Bond> bonds, Family family) implements CustomPacketPayload
{
    /**
     * Pregnancy or baby time (see {@code Family}).
     *
     * @param stage     {@link FamilyPayload#NONE}, {@link FamilyPayload#PREGNANT} or {@link FamilyPayload#INFANT}
     * @param elapsed   ticks since it started
     * @param total     ticks it lasts
     */
    public record Family(int stage, int elapsed, int total)
    {
        public static final StreamCodec<ByteBuf, Family> CODEC = StreamCodec.composite(
          ByteBufCodecs.VAR_INT, Family::stage,
          ByteBufCodecs.VAR_INT, Family::elapsed,
          ByteBufCodecs.VAR_INT, Family::total,
          Family::new);
    }

    /**
     * @param friendship -100 .. 100
     * @param romance    0 .. 100
     * @param grudge     they are cross with each other right now
     * @param partner    MineColonies partner
     */
    public record Bond(String name, int friendship, int romance, boolean grudge, boolean partner)
    {
        public static final StreamCodec<ByteBuf, Bond> CODEC = StreamCodec.composite(
          ByteBufCodecs.STRING_UTF8, Bond::name,
          ByteBufCodecs.INT, Bond::friendship,
          ByteBufCodecs.VAR_INT, Bond::romance,
          ByteBufCodecs.BOOL, Bond::grudge,
          ByteBufCodecs.BOOL, Bond::partner,
          Bond::new);
    }

    public static final Type<SocialInfoPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, "social_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SocialInfoPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, SocialInfoPayload::citizenId,
      ByteBufCodecs.FLOAT, SocialInfoPayload::mood,
      ByteBufCodecs.INT, SocialInfoPayload::affinity,
      ByteBufCodecs.STRING_UTF8, SocialInfoPayload::partner,
      Bond.CODEC.apply(ByteBufCodecs.collection(ArrayList::new)), SocialInfoPayload::bonds,
      Family.CODEC, SocialInfoPayload::family,
      SocialInfoPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public static void handle(final SocialInfoPayload payload, final IPayloadContext context)
    {
        context.enqueueWork(() -> com.aiko.coloniesalive.client.SocialTab.onInfo(payload));
    }
}
