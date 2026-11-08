package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.ColoniesAlive;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * For every citizen skin, a transparent texture of the same size holding only the closed eyelids.
 * Built once per skin on first use; empty if the eyes could not be found.
 */
public final class EyelidTextures
{
    private static final Map<ResourceLocation, Optional<ResourceLocation>> CACHE = new HashMap<>();

    private EyelidTextures() {}

    public static Optional<ResourceLocation> get(final ResourceLocation skin)
    {
        return CACHE.computeIfAbsent(skin, EyelidTextures::build);
    }

    /** Resource packs changed: forget everything. */
    public static void clear()
    {
        CACHE.values().forEach(o -> o.ifPresent(rl -> Minecraft.getInstance().getTextureManager().release(rl)));
        CACHE.clear();
    }

    private static Optional<ResourceLocation> build(final ResourceLocation skin)
    {
        final Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(skin);
        if (resource.isEmpty())
        {
            return Optional.empty();
        }

        try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream))
        {
            final List<EyeFinder.Lid> lids = EyeFinder.find(new EyeFinder.Pixels()
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
                    return abgrToArgb(image.getPixelRGBA(x, y));
                }
            });
            if (lids.isEmpty())
            {
                return Optional.empty();
            }

            final NativeImage overlay = new NativeImage(image.getWidth(), image.getHeight(), true);
            for (final EyeFinder.Lid lid : lids)
            {
                overlay.fillRect(lid.x(), lid.y(), lid.size(), lid.size(), abgrToArgb(lid.argb()));
            }

            final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID,
              "eyelids/" + skin.getNamespace() + "/" + skin.getPath().replaceAll("[^a-z0-9/._-]", "_"));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(overlay));
            return Optional.of(id);
        }
        catch (final Exception e)
        {
            ColoniesAlive.LOGGER.debug("No eyelids for {}", skin, e);
            return Optional.empty();
        }
    }

    /** NativeImage stores ABGR; swapping red and blue converts both ways. */
    private static int abgrToArgb(final int color)
    {
        return (color & 0xFF00FF00) | ((color >> 16) & 0xFF) | ((color & 0xFF) << 16);
    }
}
