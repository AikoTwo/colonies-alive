package com.aiko.coloniesalive.mixin;

import com.aiko.coloniesalive.social.Family;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MineColonies makes a citizen that got hurt run away from whoever hit it. A baby (see {@code Family}) can't run: it
 * stays on its bottom and cries (ChildLifeAI). Running away also pulled it off its seat again and again.
 */
@Mixin(value = EntityCitizen.class, remap = false)
public abstract class BabyNoFleeMixin
{
    @Inject(method = "performMoveAway", at = @At("HEAD"), cancellable = true)
    private void coloniesalive$babiesDontRun(final Entity attacker, final CallbackInfo ci)
    {
        if (Family.isInfant((EntityCitizen) (Object) this))
        {
            ci.cancel();
        }
    }
}
