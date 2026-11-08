package com.aiko.coloniesalive.mixin;

import com.aiko.coloniesalive.social.Family;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.buildings.modules.ChildrenBuildingModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The school signs up every child without a job on its own, babies included. For the school a baby (see {@code Family})
 * is not a child: it isn't signed up, and one already signed up (also by hand in the window) is taken off the list.
 */
@Mixin(value = ChildrenBuildingModule.class, remap = false)
public abstract class SchoolNoBabiesMixin
{
    @Redirect(method = "onColonyTick", at = @At(value = "INVOKE", target = "Lcom/minecolonies/api/colony/ICitizenData;isChild()Z"))
    private boolean coloniesalive$babiesAreNoPupils(final ICitizenData data)
    {
        return data.isChild() && !Family.isInfant(data);
    }
}
