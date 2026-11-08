package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ChildAnimation;
import com.aiko.coloniesalive.social.Topics;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * A child hit by a player gets back at them once it stops crying, cute and harmless (as in our MCA addon):
 * <ol>
 *     <li>runs to the player;</li>
 *     <li>a flurry of little punches ({@link ChildAnimation#KID_PUNCH}, 3 s): a puff and a weak thud at each one, NO damage;</li>
 *     <li>ends with arms crossed, looking away ({@link ChildAnimation#POUT}), and later wants an apology (topic
 *     {@link Topics.Topic#HIT_BY_YOU}).</li>
 * </ol>
 * If the player runs off it chases them (twice at most). Hit again during or right after: it gives up and only cries
 * (see {@link ChildLifeAI#onHurt}).
 */
final class ChildRevenge implements ChildActivity
{
    private enum Phase
    {
        APPROACH,
        PUNCH,
        POUT
    }

    private static final int    APPROACH_MAX   = 20 * 10;
    private static final int    PUNCH_TICKS    = 60;
    private static final int    POUT_TICKS     = 60;
    private static final double REACH_SQR      = 1.6 * 1.6;
    private static final double ESCAPE_SQR     = 2.6 * 2.6;
    private static final double GIVE_UP_SQR    = 28 * 28;
    private static final int    MAX_CHASES     = 2;
    private static final int    HIT_PERIOD     = 6;
    private static final double RUN_SPEED      = 1.3;

    private final UUID playerId;
    private Phase      phase = Phase.APPROACH;
    private long       phaseStart = -1;
    private int        chases;

    /** Ran to the end (stomped off after the punches, or the player is gone): nothing left to resume. */
    private boolean done;

    ChildRevenge(final UUID playerId)
    {
        this.playerId = playerId;
    }

    UUID playerId()
    {
        return playerId;
    }

    /** Cut off by MineColonies before the end (so it can be picked up again). */
    boolean unfinished()
    {
        return !done;
    }

    /** Hit again in the middle of it: no picking it up again, only tears. */
    void giveUp()
    {
        done = true;
    }

    @Override
    public boolean tick(final ChildLifeAI ai, final long time)
    {
        final EntityCitizen child = ai.child();
        final ServerPlayer player = child.getServer() == null ? null : child.getServer().getPlayerList().getPlayer(playerId);
        if (player == null || player.level() != child.level() || player.isSpectator() || !player.isAlive())
        {
            done = true;
            return false;
        }
        if (phaseStart < 0)
        {
            phaseStart = time;
            ai.broadcast(ChildAnimation.TAG_CHASER, ChildLifeAI.OPEN_ENDED);
            Fx.grumble(child);
        }
        final long t = time - phaseStart;
        final double distance = child.distanceToSqr(player);
        switch (phase)
        {
            case APPROACH ->
            {
                if (distance <= REACH_SQR)
                {
                    child.getNavigation().stop();
                    Fx.face(child, player);
                    ai.broadcast(ChildAnimation.KID_PUNCH, PUNCH_TICKS);
                    next(Phase.PUNCH, time);
                }
                else if (t > APPROACH_MAX || distance > GIVE_UP_SQR)
                {
                    pout(ai, time); // couldn't catch them: stomps from afar
                }
                else
                {
                    Moves.follow(child, player, RUN_SPEED);
                }
            }
            case PUNCH ->
            {
                child.getNavigation().stop();
                holdArmsLength(child, player, distance);
                Fx.face(child, player);
                if (distance > ESCAPE_SQR)
                {
                    if (chases++ < MAX_CHASES)
                    {
                        Moves.reset(child);
                        ai.broadcast(ChildAnimation.TAG_CHASER, ChildLifeAI.OPEN_ENDED);
                        next(Phase.APPROACH, time);
                    }
                    else
                    {
                        pout(ai, time);
                    }
                    return true;
                }
                if (t % HIT_PERIOD == 3)
                {
                    Fx.punch(child, player, t / HIT_PERIOD);
                }
                if (t >= PUNCH_TICKS)
                {
                    pout(ai, time);
                }
            }
            case POUT ->
            {
                child.getNavigation().stop();
                if (t >= POUT_TICKS)
                {
                    // later it wants an apology
                    Topics.raiseNow(child, Topics.Topic.HIT_BY_YOU);
                    done = true;
                    return false;
                }
            }
        }
        return true;
    }

    /** Arm's length from the player: no sliding into them (the path ends inside the player's block). */
    private static void holdArmsLength(final EntityCitizen child, final ServerPlayer player, final double distanceSqr)
    {
        final net.minecraft.world.phys.Vec3 motion = child.getDeltaMovement();
        if (distanceSqr < 0.9 * 0.9)
        {
            final net.minecraft.world.phys.Vec3 away = child.position().subtract(player.position()).multiply(1, 0, 1);
            final net.minecraft.world.phys.Vec3 push = away.lengthSqr() < 1.0e-4 ? new net.minecraft.world.phys.Vec3(0, 0, 0.1) : away.normalize().scale(0.1);
            child.setDeltaMovement(push.x, motion.y, push.z);
        }
        else
        {
            child.setDeltaMovement(0, motion.y, 0);
        }
    }

    private void next(final Phase phase, final long time)
    {
        this.phase = phase;
        this.phaseStart = time;
    }

    private void pout(final ChildLifeAI ai, final long time)
    {
        final EntityCitizen child = ai.child();
        child.getNavigation().stop();
        ai.broadcast(ChildAnimation.POUT, POUT_TICKS);
        Fx.angry(child);
        Fx.grumble(child);
        next(Phase.POUT, time);
    }

    @Override
    public void stop(final ChildLifeAI ai)
    {
        Moves.reset(ai.child());
        ai.revengeOver();
    }
}
