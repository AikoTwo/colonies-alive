package com.aiko.coloniesalive.mixin;

import com.aiko.coloniesalive.client.Accessories;
import com.minecolonies.api.client.render.modeltype.CitizenModel;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In December everyone wears a Santa hat ({@link Accessories}): the job hats MineColonies draws (they ask
 * {@code displayHat}, which also hides them while sleeping or under a helmet) step aside for it.
 */
@Mixin(value = CitizenModel.class, remap = false)
public abstract class AccessoryHatMixin
{
    @Inject(method = "displayHat", at = @At("HEAD"), cancellable = true)
    private void coloniesalive$santaInstead(final AbstractEntityCitizen citizen, final CallbackInfoReturnable<Boolean> cir)
    {
        if (Accessories.santaSeason())
        {
            cir.setReturnValue(false);
        }
    }
}
