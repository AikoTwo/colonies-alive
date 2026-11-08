package com.aiko.coloniesalive.client;

import java.util.HashSet;
import java.util.Set;

/**
 * Client side: which citizens are in a bad mood right now (they walk hunched and slow). Free of client-only imports so
 * the payload handler can reference it.
 */
public final class ClientGloom
{
    private static final Set<Integer> GLOOMY = new HashSet<>();

    private ClientGloom() {}

    public static void set(final int entityId, final boolean gloomy)
    {
        if (gloomy)
        {
            GLOOMY.add(entityId);
        }
        else
        {
            GLOOMY.remove(entityId);
        }
    }

    public static boolean isGloomy(final int entityId)
    {
        return GLOOMY.contains(entityId);
    }

    public static void clear()
    {
        GLOOMY.clear();
    }
}
