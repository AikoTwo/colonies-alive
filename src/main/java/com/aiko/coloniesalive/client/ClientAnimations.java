package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.ChildAnimation;

import java.util.HashMap;
import java.util.Map;

/**
 * Client side record of which citizens are currently playing an animation.
 * Deliberately free of client-only imports so the payload handler can safely reference it.
 */
public final class ClientAnimations
{
    public record Active(ChildAnimation animation, long startTick, int durationTicks) {}

    private static final Map<Integer, Active> ACTIVE = new HashMap<>();

    private ClientAnimations() {}

    public static void start(final int entityId, final ChildAnimation animation, final long gameTime, final int durationTicks)
    {
        if (animation == ChildAnimation.NONE)
        {
            ACTIVE.remove(entityId);
        }
        else
        {
            ACTIVE.put(entityId, new Active(animation, gameTime, durationTicks));
        }
    }

    /**
     * @return the running animation of this entity, or null. Expired entries are dropped.
     */
    public static Active get(final int entityId, final long gameTime)
    {
        final Active active = ACTIVE.get(entityId);
        if (active == null)
        {
            return null;
        }
        if (gameTime - active.startTick() > active.durationTicks() + 20)
        {
            ACTIVE.remove(entityId);
            return null;
        }
        return active;
    }
}
