package com.aiko.coloniesalive.mixin;

import com.aiko.coloniesalive.client.SocialTab;
import com.ldtteam.blockui.PaneBuilders;
import com.ldtteam.blockui.controls.ButtonImage;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import com.minecolonies.core.client.gui.citizen.AbstractWindowCitizen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the "social life" tab (a heart) to every page of the MineColonies citizen window, above MineColonies' own
 * tabs, where the left side still has room.
 */
@Mixin(value = AbstractWindowCitizen.class, remap = false)
public abstract class CitizenWindowTabMixin
{
    @Shadow @Final protected ICitizenDataView citizen;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void coloniesalive$addSocialTab(final ICitizenDataView citizen, final ResourceLocation ui, final CallbackInfo ci)
    {
        final AbstractWindowSkeleton window = (AbstractWindowSkeleton) (Object) this;

        final ButtonImage tab = new ButtonImage();
        tab.setID("coloniesaliveSocialTab");
        tab.setImage(ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/tab_left_side2.png"));
        tab.setSize(32, 26);
        tab.setPosition(0, 8);

        final ButtonImage icon = new ButtonImage();
        icon.setID("coloniesaliveSocialIcon");
        icon.setImage(ResourceLocation.withDefaultNamespace("textures/gui/sprites/hud/heart/full.png"));
        icon.setSize(18, 18);
        icon.setPosition(6, 12);

        // index 0: drawn under the paper like MineColonies' tabs
        window.addChild(icon, 0);
        window.addChild(tab, 0);

        window.registerButton("coloniesaliveSocialTab", () -> SocialTab.request(this.citizen));
        window.registerButton("coloniesaliveSocialIcon", () -> SocialTab.request(this.citizen));
        PaneBuilders.singleLineTooltip(Component.translatable("coloniesalive.gui.social.tab"), icon);
    }
}
