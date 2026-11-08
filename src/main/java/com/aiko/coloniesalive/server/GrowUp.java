package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.social.Family;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.colony.eventhooks.citizenEvents.CitizenGrownUpEvent;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Development/testing: {@code /coloniesalive adultos [segundos]} makes every child in the world grow up after that many
 * seconds (MineColonies itself takes about an hour, at random). The growing up is done the way MineColonies does it
 * (colony event log, child flag off on the entity and the citizen data, new adult skin). Babies are left out: they
 * become children first ({@code /coloniesalive crescer}).
 */
public final class GrowUp
{
    private static final Map<EntityCitizen, Long> DUE = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    private GrowUp() {}

    /** @return how many children will grow up */
    public static int scheduleAll(final ServerLevel level, final int seconds)
    {
        final List<EntityCitizen> children = new ArrayList<>();
        level.getEntities(EntityTypeTest.forClass(EntityCitizen.class), c -> c.isAlive() && c.isBaby() && c.getCitizenData() != null && !Family.isInfant(c), children);
        final long at = level.getGameTime() + seconds * 20L;
        for (final EntityCitizen child : children)
        {
            DUE.put(child, at);
        }
        ColoniesAlive.LOGGER.info("[growup] {} children grow up in {} s", children.size(), seconds);
        return children.size();
    }

    /** Called every tick for every citizen (server). */
    public static void tick(final EntityCitizen citizen)
    {
        final Long at = DUE.get(citizen);
        if (at == null || citizen.level().getGameTime() < at)
        {
            return;
        }
        DUE.remove(citizen);
        if (!citizen.isBaby() || citizen.getCitizenData() == null)
        {
            return;
        }
        final IColony colony = citizen.getCitizenColonyHandler().getColonyOrRegister();
        if (colony != null)
        {
            colony.getEventDescriptionManager().addEventDescription(new CitizenGrownUpEvent(citizen.blockPosition(), citizen.getCitizenData().getName()));
        }
        citizen.stopRiding();
        citizen.setIsChild(false);
        citizen.setTextureDirty();
        citizen.getCitizenData().setIsChild(false);
        citizen.getCitizenData().markDirty(0);
        Fx.happy(citizen, 8);
        ColoniesAlive.LOGGER.info("[growup] {} grew up", citizen.getCitizenData().getName());
    }
}
