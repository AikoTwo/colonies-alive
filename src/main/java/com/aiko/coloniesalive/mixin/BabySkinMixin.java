package com.aiko.coloniesalive.mixin;

import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.client.ClientFamily;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.client.render.RenderBipedCitizen;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A baby (see {@code Family}) wears a knitted onesie: its child skin {@code minecolonies:textures/entity/citizen/<style>/child...}
 * is swapped for the matching {@code coloniesalive:textures/entity/baby/<style>/child...} (same face, hair and skin tone;
 * made from MineColonies' child skins). Styles without a baby skin (other addons) keep the child one.
 */
@Mixin(value = RenderBipedCitizen.class, remap = false)
public abstract class BabySkinMixin
{
    @Unique
    private static final Map<ResourceLocation, ResourceLocation> coloniesalive$BABY = new ConcurrentHashMap<>();

    @Inject(method = "getTextureLocation(Lcom/minecolonies/api/entity/citizen/AbstractEntityCitizen;)Lnet/minecraft/resources/ResourceLocation;", at = @At("RETURN"), cancellable = true)
    private void coloniesalive$babySkin(final AbstractEntityCitizen citizen, final CallbackInfoReturnable<ResourceLocation> cir)
    {
        final ResourceLocation child = cir.getReturnValue();
        if (child == null || !citizen.isBaby() || !ClientFamily.isInfant(citizen.getId()))
        {
            return;
        }
        final ResourceLocation baby = coloniesalive$BABY.computeIfAbsent(child, BabySkinMixin::coloniesalive$babyFor);
        if (!baby.equals(child))
        {
            cir.setReturnValue(baby);
        }
    }

    @Unique
    private static ResourceLocation coloniesalive$babyFor(final ResourceLocation child)
    {
        final String prefix = "textures/entity/citizen/";
        final String path = child.getPath();
        final int file = path.lastIndexOf('/');
        if (!path.startsWith(prefix) || file < 0 || !path.startsWith("child", file + 1))
        {
            return child;
        }
        final ResourceLocation baby = ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, "textures/entity/baby/" + path.substring(prefix.length()));
        return Minecraft.getInstance().getResourceManager().getResource(baby).isPresent() ? baby : child;
    }
}
