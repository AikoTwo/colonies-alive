package com.aiko.coloniesalive.mixin;

import com.minecolonies.core.colony.managers.ReproductionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MineColonies makes a child appear every 5 to 15 minutes, with no pregnancy and random parents. Children come from
 * our pregnancies instead (see {@code Family}). Only this random birth is switched off: the first settlers of a new
 * colony (CitizenManager, "move in" at the town hall) keep arriving as usual.
 */
@Mixin(value = ReproductionManager.class, remap = false)
public abstract class ReproductionManagerMixin
{
    @Inject(method = "trySpawnChild", at = @At("HEAD"), cancellable = true)
    private void coloniesalive$noRandomBirths(final CallbackInfo ci)
    {
        ci.cancel();
    }
}
