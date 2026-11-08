package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.ColoniesAlive;
import com.minecolonies.api.client.render.modeltype.SimpleModelType;
import com.minecolonies.core.client.render.RenderBipedCitizen;

import java.lang.reflect.Field;

/**
 * MineColonies swaps every citizen to the "nether" skin set from 29 Oct to 2 Nov. It decides this once and keeps the
 * answer in a static, so pre-filling that static with the empty style keeps the regular skins all year.
 * The ghost mode flag is cleared as well, in case a future version enables it.
 */
public final class HalloweenOff
{
    private HalloweenOff() {}

    public static void apply()
    {
        try
        {
            final Field style = SimpleModelType.class.getDeclaredField("cachedHalloweenStyle");
            style.setAccessible(true);
            style.set(null, "");
            RenderBipedCitizen.isItGhostTime = false;
        }
        catch (final ReflectiveOperationException | RuntimeException e)
        {
            ColoniesAlive.LOGGER.warn("Could not disable the MineColonies Halloween skins", e);
        }
    }
}
