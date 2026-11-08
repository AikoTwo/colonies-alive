package com.aiko.coloniesalive.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where the mouth is on each skin (face row 5, 6 or 7, see {@link EyeFinder#mouthRow}), so the pacifier sits on the
 * mouth and not on the nose of the faces drawn a row lower. Read once per skin.
 */
public final class MouthRows
{
    private static final Map<ResourceLocation, Integer> CACHE = new HashMap<>();

    private MouthRows() {}

    public static int get(final ResourceLocation skin)
    {
        return CACHE.computeIfAbsent(skin, MouthRows::read);
    }

    public static void clear()
    {
        CACHE.clear();
    }

    private static int read(final ResourceLocation skin)
    {
        final Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(skin);
        if (resource.isEmpty())
        {
            return 6;
        }
        try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream))
        {
            return EyeFinder.mouthRow(new EyeFinder.Pixels()
            {
                @Override
                public int width()
                {
                    return image.getWidth();
                }

                @Override
                public int height()
                {
                    return image.getHeight();
                }

                @Override
                public int argb(final int x, final int y)
                {
                    final int c = image.getPixelRGBA(x, y);
                    return (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16);
                }
            });
        }
        catch (final Exception e)
        {
            return 6;
        }
    }
}
