package com.aiko.coloniesalive.mixin;

import com.minecolonies.api.colony.interactionhandling.ChatPriority;
import com.minecolonies.api.colony.interactionhandling.IInteractionResponseHandler;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.CitizenDataView;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * The icon over a citizen's head: MineColonies draws its status icon (at home, rain, hungry, sick, mourning...) INSTEAD
 * of the speech bubble whenever it has one, so a citizen with something to say often showed a house or a cloud and the
 * player never knew. Here a pending conversation wins over those statuses; only sleeping and a raid keep their icon.
 */
@Mixin(value = CitizenDataView.class, remap = false)
public abstract class CitizenStatusIconMixin
{
    @Shadow private VisibleCitizenStatus statusIcon;
    @Shadow private List<IInteractionResponseHandler> sortedInteractions;

    @Unique
    private static final ResourceLocation coloniesalive$BLOCKING = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/icons/blocking.png");
    @Unique
    private static final ResourceLocation coloniesalive$PENDING  = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/icons/warning.png");

    @Inject(method = "getStatusIcon", at = @At("HEAD"), cancellable = true)
    private void coloniesalive$chatFirst(final CallbackInfoReturnable<ResourceLocation> cir)
    {
        if (statusIcon == null || !statusIcon.shouldRender() || statusIcon == VisibleCitizenStatus.SLEEP || statusIcon == VisibleCitizenStatus.RAIDED
              || sortedInteractions == null || sortedInteractions.isEmpty())
        {
            return;
        }
        boolean visible = false;
        boolean blocking = false;
        for (final IInteractionResponseHandler interaction : sortedInteractions)
        {
            final int priority = interaction.getPriority().getPriority();
            visible |= priority >= ChatPriority.CHITCHAT.getPriority();
            blocking |= priority >= ChatPriority.IMPORTANT.getPriority();
        }
        if (!visible)
        {
            return;
        }
        final ResourceLocation icon = sortedInteractions.get(0).getInteractionIcon();
        cir.setReturnValue(icon != null ? icon : blocking ? coloniesalive$BLOCKING : coloniesalive$PENDING);
    }
}
