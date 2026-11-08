package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ChildAnimation;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.world.entity.Mob;

/**
 * A child that was hit by a bully (and no relative saw it) runs crying to mum, dad or a grown-up brother or sister to
 * tell: sobbing all the way with tears flying, then standing in front of them, telling between sobs (talking / crying
 * in turns), and waiting to be hugged while the grown-up deals with it ({@link Brawl}). With nobody to tell, it mopes
 * alone in tears.
 */
final class TellParent implements ChildActivity
{
    private enum Phase
    {
        RUN,
        TELL,
        WAIT,
        SULK
    }

    private static final double RUN_SPEED  = 1.45;
    private static final int    RUN_MAX    = 20 * 45;
    private static final int    TELL_TICKS = 20 * 5;
    private static final int    WAIT_MAX   = 20 * 40;
    private static final int    SULK_TICKS = 20 * 20;
    private static final double REACH_SQR  = 2.0 * 2.0;

    private final Mob     bully;
    private EntityCitizen adult;
    private Phase         phase = Phase.RUN;
    private long          phaseStart = -1;
    private long          began = -1;
    private long          nextSob;
    private boolean       talking;

    TellParent(final Mob bully)
    {
        this.bully = bully;
    }

    @Override
    public boolean tick(final ChildLifeAI ai, final long time)
    {
        final EntityCitizen child = ai.child();
        if (phaseStart < 0)
        {
            adult = BabyCare.closestFamily(child);
            phaseStart = time;
            nextSob = time;
            if (adult == null)
            {
                next(Phase.SULK, time);
                ai.broadcast(ChildAnimation.SAD, SULK_TICKS + 10);
                ai.debug("was bullied and has nobody to tell: mopes");
            }
            else
            {
                ai.broadcast(ChildAnimation.CRY, ChildLifeAI.OPEN_ENDED);
                ai.debug("was bullied and runs crying to " + adult.getName().getString());
            }
        }
        if (began < 0)
        {
            began = time;
        }
        if (ai.wasComfortedSince(began))
        {
            return false;
        }
        // tears and sobs the whole time
        if (time % 4 == 0)
        {
            Fx.tears(child);
        }
        if (time >= nextSob)
        {
            Fx.cry(child);
            nextSob = time + 35 + ai.rand().nextInt(30);
        }
        final long t = time - phaseStart;
        switch (phase)
        {
            case RUN ->
            {
                if (adult == null || !adult.isAlive() || adult.level() != child.level() || t > RUN_MAX)
                {
                    next(Phase.SULK, time);
                    ai.broadcast(ChildAnimation.SAD, SULK_TICKS + 10);
                    return true;
                }
                if (child.distanceToSqr(adult) <= REACH_SQR)
                {
                    child.getNavigation().stop();
                    Fx.face(child, adult);
                    if (!Brawl.hearComplaint(adult, child, bully) && !Brawl.isBusy(adult))
                    {
                        // can't listen right now (sick...): a pat on the head at least
                        Fx.hearts(child, 2);
                    }
                    next(Phase.TELL, time);
                    ai.broadcast(ChildAnimation.TALK, 30);
                    talking = true;
                }
                else
                {
                    Moves.follow(child, adult, RUN_SPEED);
                }
            }
            case TELL ->
            {
                child.getNavigation().stop();
                Fx.face(child, adult);
                // telling between sobs
                if (t % 30 == 0)
                {
                    talking = !talking;
                    ai.broadcast(talking ? ChildAnimation.TALK : ChildAnimation.CRY, 32);
                }
                if (t >= TELL_TICKS)
                {
                    next(Phase.WAIT, time);
                    ai.broadcast(ChildAnimation.CRY, WAIT_MAX);
                }
            }
            case WAIT ->
            {
                // waits for the hug, watching the grown-up (and the fight, if it goes after the bully)
                child.getNavigation().stop();
                if (adult != null && adult.isAlive())
                {
                    Fx.face(child, adult);
                }
                if (t > WAIT_MAX || adult == null || !adult.isAlive() || !Brawl.isBusy(adult) && t > 40)
                {
                    return false;
                }
            }
            case SULK ->
            {
                child.getNavigation().stop();
                if (t >= SULK_TICKS)
                {
                    return false;
                }
            }
        }
        return true;
    }

    private void next(final Phase phase, final long time)
    {
        this.phase = phase;
        this.phaseStart = time;
    }

    @Override
    public void stop(final ChildLifeAI ai)
    {
        ai.child().getNavigation().stop();
        Moves.reset(ai.child());
    }
}
