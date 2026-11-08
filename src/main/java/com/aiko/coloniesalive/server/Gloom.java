package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.network.GloomPayload;
import com.aiko.coloniesalive.social.Social;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A citizen whose mood is low stays visibly down: it walks hunched with the head hanging (client side, see
 * {@code ChildPoses.gloomyWalk}) and a bit slower, until the mood gets better. Before, sadness only showed in a short
 * standing pose, and in between the citizen went back to its cheerful walk.
 */
public final class Gloom
{
    /** Becomes gloomy at this mood or below... */
    private static final float ENTER = -3f;
    /** ...and only cheers up above this one, so it doesn't flicker around the threshold. */
    private static final float LEAVE = -1.5f;

    private static final int CHECK_INTERVAL = 40;
    private static final int RESYNC_INTERVAL = CHECK_INTERVAL * 10;

    private static final ResourceLocation SLOW_ID = ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, "gloomy_slow");

    private static final Map<EntityCitizen, Boolean> STATE = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    private Gloom() {}

    public static boolean isGloomy(final EntityCitizen citizen)
    {
        return STATE.getOrDefault(citizen, false);
    }

    public static void tick(final EntityCitizen citizen)
    {
        if ((citizen.tickCount + citizen.getId()) % CHECK_INTERVAL != 0 || citizen.getCitizenData() == null)
        {
            return;
        }
        final boolean was = isGloomy(citizen);
        final float mood = Social.mood(citizen);
        final boolean now = was ? mood <= LEAVE : mood <= ENTER;
        if (now != was)
        {
            STATE.put(citizen, now);
            setSlow(citizen, now);
        }
        if (now != was || (citizen.tickCount + citizen.getId()) % RESYNC_INTERVAL == 0)
        {
            // also now and then, so a client that missed the change (wasn't nearby then) never keeps an old hunched walk
            PacketDistributor.sendToPlayersTrackingEntity(citizen, new GloomPayload(citizen.getId(), now));
        }
    }

    /** Always the current state: before, only "gloomy" was sent, so a citizen that cheered up while nobody was around
     *  kept walking hunched on the client even with a radiant mood. */
    public static void syncTo(final EntityCitizen citizen, final ServerPlayer player)
    {
        PacketDistributor.sendToPlayer(player, new GloomPayload(citizen.getId(), isGloomy(citizen)));
    }

    private static void setSlow(final EntityCitizen citizen, final boolean slow)
    {
        final AttributeInstance speed = citizen.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null)
        {
            return;
        }
        if (slow && !speed.hasModifier(SLOW_ID))
        {
            speed.addTransientModifier(new AttributeModifier(SLOW_ID, -0.2, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        else if (!slow && speed.hasModifier(SLOW_ID))
        {
            speed.removeModifier(SLOW_ID);
        }
    }
}
