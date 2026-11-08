package com.aiko.coloniesalive.mixin;

import com.aiko.coloniesalive.client.AccessoryLayer;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.client.model.MaleChildModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The boys' little sailor hat steps aside for a head accessory (or the Santa hat). The boy's model switches it on again
 * in its own setupAnim, after everything CitizenModel does, so it is switched off here, at the very end.
 */
@Mixin(value = MaleChildModel.class, remap = false)
public abstract class SailorHatMixin
{
    @Inject(method = "setupAnim(Lcom/minecolonies/api/entity/citizen/AbstractEntityCitizen;FFFFF)V", at = @At("TAIL"))
    private void coloniesalive$hatOff(final AbstractEntityCitizen citizen, final float limbSwing, final float limbSwingAmount, final float ageInTicks,
                                      final float netHeadYaw, final float headPitch, final CallbackInfo ci)
    {
        final MaleChildModel model = (MaleChildModel) (Object) this;
        if (model.head.hasChild("littleSailorHat") && AccessoryLayer.wearsHeadPiece(citizen))
        {
            model.head.getChild("littleSailorHat").visible = false;
        }
    }
}
