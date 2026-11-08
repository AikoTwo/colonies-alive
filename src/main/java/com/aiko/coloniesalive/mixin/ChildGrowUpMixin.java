package com.aiko.coloniesalive.mixin;

import com.aiko.coloniesalive.social.Family;
import com.minecolonies.core.entity.ai.minimal.EntityAICitizenChild;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A baby (see {@code Family}) first becomes a child; MineColonies may only make it an adult after that. */
@Mixin(value = EntityAICitizenChild.class, remap = false)
public abstract class ChildGrowUpMixin
{
    @Shadow
    @Final
    protected EntityCitizen child;

    @Inject(method = "tryGrowUp", at = @At("HEAD"), cancellable = true)
    private void coloniesalive$babiesDontGrowUp(final CallbackInfoReturnable<Boolean> cir)
    {
        if (Family.isInfant(child))
        {
            cir.setReturnValue(false);
        }
    }
}
