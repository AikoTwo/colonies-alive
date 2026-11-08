package com.aiko.coloniesalive.mixin;

import com.aiko.coloniesalive.client.ChildPoses;
import com.aiko.coloniesalive.client.ClientAnimations;
import com.aiko.coloniesalive.client.KawaiiMotion;
import com.minecolonies.api.client.render.modeltype.CitizenModel;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the running {@link com.aiko.coloniesalive.ChildAnimation} (or the walk style) on top of whatever
 * MineColonies already did to the model this frame. Works for every citizen model since they all extend CitizenModel.
 */
@Mixin(value = CitizenModel.class, remap = false)
public abstract class CitizenModelMixin
{
    /**
     * In 1.21.1 nothing resets the whole pose each frame: vanilla only rewrites some fields (never the roll of head and
     * body, for instance). Our layers add rotations, so without this they would pile up frame after frame, and a roll
     * set by one pose would stay after the pose ended.
     */
    @Inject(method = "setupAnim(Lcom/minecolonies/api/entity/citizen/AbstractEntityCitizen;FFFFF)V", at = @At("HEAD"))
    private void coloniesalive$resetPose(final AbstractEntityCitizen citizen, final float limbSwing, final float limbSwingAmount,
                                         final float ageInTicks, final float netHeadYaw, final float headPitch, final CallbackInfo ci)
    {
        final HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        model.head.resetPose();
        model.hat.resetPose();
        model.body.resetPose();
        model.rightArm.resetPose();
        model.leftArm.resetPose();
        model.rightLeg.resetPose();
        model.leftLeg.resetPose();
    }

    @Inject(method = "setupAnim(Lcom/minecolonies/api/entity/citizen/AbstractEntityCitizen;FFFFF)V", at = @At("TAIL"))
    private void coloniesalive$applyAnimation(final AbstractEntityCitizen citizen, final float limbSwing, final float limbSwingAmount,
                                              final float ageInTicks, final float netHeadYaw, final float headPitch, final CallbackInfo ci)
    {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
        {
            return;
        }

        final HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        // the boys' little sailor hat makes room for a head accessory or the Santa hat (MineColonies sets it again every frame)
        if (model.head.hasChild("littleSailorHat") && com.aiko.coloniesalive.client.AccessoryLayer.wearsHeadPiece(citizen))
        {
            model.head.getChild("littleSailorHat").visible = false;
        }
        final ClientAnimations.Active active = ClientAnimations.get(citizen.getId(), mc.level.getGameTime());
        if (active == null)
        {
            if (com.aiko.coloniesalive.client.ClientGloom.isGloomy(citizen.getId()) && citizen.getPose() == net.minecraft.world.entity.Pose.STANDING
                  && citizen.getVehicle() == null)
            {
                ChildPoses.gloomyWalk(model, limbSwingAmount, ageInTicks);
            }
            else
            {
                KawaiiMotion.apply(model, citizen, limbSwing, limbSwingAmount, ageInTicks);
            }
            final float pregnancy = com.aiko.coloniesalive.client.ClientFamily.pregnancy(citizen.getId());
            if (pregnancy > 0.3f && !model.young && citizen.getPose() == net.minecraft.world.entity.Pose.STANDING)
            {
                ChildPoses.pregnant(model, limbSwingAmount, ageInTicks);
            }
            ChildPoses.afterMotion(model);
        }
        else
        {
            ChildPoses.elapsed = (mc.level.getGameTime() - active.startTick()) + (ageInTicks - (float) Math.floor(ageInTicks));
            ChildPoses.apply(model, citizen, active.animation(), ageInTicks);
        }
    }
}
