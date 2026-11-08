package com.aiko.coloniesalive.server;

import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.world.entity.Mob;

import java.util.Comparator;
import java.util.List;

/**
 * What happens right after a bully hit a child ({@link Bully}): who saw it, who steps in, and the child's side.
 */
public final class Drama
{
    /** Grown-ups this close who can see the child or the bully saw it happen. */
    private static final double WITNESS_RADIUS = 20;
    private static final int    MAX_DEFENDERS  = 2;
    private static final double HEAR_RADIUS    = 8;

    private Drama() {}

    /** The citizen is in the middle of the drama: MineColonies must not switch it to something else (CitizenAIMixin). */
    public static boolean holdsOff(final EntityCitizen citizen)
    {
        final var state = citizen.getCitizenAI() == null ? null : citizen.getCitizenAI().getState();
        if (state == Brawl.State.BRAWLING)
        {
            return true;
        }
        if (state == ChildLifeAI.State.ACTING)
        {
            final ChildLifeAI ai = ChildLifeEvents.get(citizen);
            return ai != null && ai.isUpset();
        }
        return false;
    }

    public static void bullied(final EntityCitizen victim, final Mob bully)
    {
        final List<EntityCitizen> around = victim.level().getEntitiesOfClass(EntityCitizen.class, victim.getBoundingBox().inflate(WITNESS_RADIUS));
        around.sort(Comparator.comparingDouble(a -> a.distanceToSqr(victim)));
        int defenders = 0;
        boolean familySaw = false;
        for (final EntityCitizen adult : around)
        {
            if (adult == victim || adult.isBaby() || !adult.isAlive())
            {
                continue;
            }
            if (adult.isSleeping() || adult.getCitizenSleepHandler().isAsleep() || adult.getCitizenData() == null
                  || adult.getCitizenAI().getState() == CitizenAIState.SLEEP
                  || adult.getCitizenColonyHandler().getColonyId() != victim.getCitizenColonyHandler().getColonyId()
                  || !(adult.hasLineOfSight(victim) || adult.hasLineOfSight(bully)
                         // family close by hears the scream even through a wall
                         || (adult.distanceToSqr(victim) < HEAR_RADIUS * HEAR_RADIUS && BabyCare.isFamilyOf(adult, victim))))
            {
                if (ChildLifeAI.DEBUG)
                {
                    Bully.debug(adult.getName().getString() + " didn't see it (state " + adult.getCitizenAI().getState() + ", sight "
                                  + adult.hasLineOfSight(victim) + ")");
                }
                continue;
            }
            final boolean family = BabyCare.isFamilyOf(adult, victim);
            if (!family && !ChildLifeEvents.isGuard(adult))
            {
                // anybody else just gasps
                Fx.angry(adult);
                continue;
            }
            if (defenders < MAX_DEFENDERS && Brawl.defend(adult, bully, victim))
            {
                defenders++;
                familySaw |= family;
                Bully.debug(adult.getName().getString() + (family ? " (family)" : " (guard)") + " saw it and steps in");
            }
            else
            {
                Bully.debug(adult.getName().getString() + (family ? " (family)" : " (guard)") + " saw it but can't step in (state "
                              + adult.getCitizenAI().getState() + ")");
            }
        }
        final ChildLifeAI ai = ChildLifeEvents.get(victim);
        if (ai != null)
        {
            ai.onBullied(bully, familySaw);
        }
    }
}
