package com.aiko.coloniesalive.server;

import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Walking after (or away from) something that moves. A new path starts at the centre of the current block, often
 * slightly behind the child, so asking for a new one every few ticks made children flick around and their heads shake.
 * Here a new path is only asked for when the target really moved away from where the current path leads, or when the
 * current path is finished.
 */
public final class Moves
{
    /** How far (in blocks) the target may move before the path is renewed. */
    private static final int RETARGET_DISTANCE_SQ = 3 * 3;

    private static final Map<Entity, BlockPos> GOALS = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    private Moves() {}

    /** Walks to {@code target}; true when close enough. */
    public static boolean follow(final EntityCitizen mover, final Entity target, final double speed)
    {
        final BlockPos goal = GOALS.get(mover);
        final BlockPos now = target.blockPosition();
        if (goal == null || mover.getNavigation().isDone() || goal.distSqr(now) >= RETARGET_DISTANCE_SQ)
        {
            GOALS.put(mover, now);
            return EntityNavigationUtils.walkToPos(mover, now, 1, false, speed);
        }
        return false;
    }

    /** Runs away from {@code chaser}, renewing the escape only when needed. */
    public static void flee(final EntityCitizen mover, final Entity chaser, final int distance, final double speed)
    {
        final BlockPos from = GOALS.get(mover);
        final BlockPos now = chaser.blockPosition();
        if (from == null || mover.getNavigation().isDone() || from.distSqr(now) >= RETARGET_DISTANCE_SQ)
        {
            GOALS.put(mover, now);
            EntityNavigationUtils.walkAwayFrom(mover, now, distance, speed);
        }
    }

    /** Forget the remembered goal (e.g. when roles swap). */
    public static void reset(final Entity mover)
    {
        GOALS.remove(mover);
    }
}
