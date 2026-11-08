package com.aiko.coloniesalive.server;

/**
 * Something a single child does on its own (or with the world around it).
 */
public interface ChildActivity
{
    /**
     * @param ai   the child's AI
     * @param time current game time
     * @return false once the activity is finished
     */
    boolean tick(ChildLifeAI ai, long time);

    /** Clean up (items in hand, animals, ...). Called once, also when interrupted. */
    default void stop(final ChildLifeAI ai) {}
}
