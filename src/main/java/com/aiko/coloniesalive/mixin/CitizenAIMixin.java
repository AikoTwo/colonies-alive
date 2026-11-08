package com.aiko.coloniesalive.mixin;

import com.aiko.coloniesalive.server.School;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.entity.ai.workers.CitizenAI;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * School rules inside MineColonies' citizen AI (see {@link School}):
 * <ul>
 *     <li>pupils stay until {@link School#END} instead of noon (the only {@code 6000L} in {@code calculateNextState} is
 *     the pupil's "after noon" check)</li>
 *     <li>no leisure breaks during class</li>
 *     <li>rain doesn't cancel school</li>
 * </ul>
 */
@Mixin(value = CitizenAI.class, remap = false)
public abstract class CitizenAIMixin
{
    @Shadow
    @Final
    private EntityCitizen citizen;

    @ModifyConstant(method = "calculateNextState", constant = @Constant(longValue = 6000L))
    private long coloniesalive$schoolDay(final long noon)
    {
        return School.freeAfter(citizen);
    }

    @Redirect(method = "calculateNextState", at = @At(value = "INVOKE", target = "Lcom/minecolonies/api/colony/ICitizenData;getLeisureTime()I"))
    private int coloniesalive$noBreaksInClass(final ICitizenData data)
    {
        return School.inClass(citizen) ? 0 : data.getLeisureTime();
    }

    /** Pupils don't leave class to eat: they get school lunch instead (see SchoolLunch). */
    @Inject(method = "shouldEat", at = @At("HEAD"), cancellable = true)
    private void coloniesalive$lunchAtSchool(final CallbackInfoReturnable<Boolean> cir)
    {
        if (School.inClass(citizen))
        {
            cir.setReturnValue(false);
        }
    }

    /**
     * In the middle of the drama after a bully hit a child (the child crying and running to tell, the grown-up fighting
     * or hugging), MineColonies doesn't pull them off to school or work: it would cut the scene off halfway.
     */
    @Inject(method = "decideAiTask", at = @At("HEAD"), cancellable = true)
    private void coloniesalive$keepDrama(final CallbackInfoReturnable<com.minecolonies.api.entity.ai.statemachine.states.IState> cir)
    {
        if (com.aiko.coloniesalive.server.Drama.holdsOff(citizen))
        {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "shouldWorkWhileRaining", at = @At("HEAD"), cancellable = true)
    private void coloniesalive$schoolInRain(final CallbackInfoReturnable<Boolean> cir)
    {
        if (School.inClass(citizen))
        {
            cir.setReturnValue(true);
        }
    }
}
