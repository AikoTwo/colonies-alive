package com.aiko.coloniesalive.mixin;

import com.aiko.coloniesalive.ChildAnimation;
import com.aiko.coloniesalive.client.ChildPoses;
import com.aiko.coloniesalive.client.ClientAnimations;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves a baby's whole body where model poses alone can't, right after the renderer turned it to face its way (so only
 * the body moves: the name tag and the icon above it, drawn later, stay put):
 * <ul>
 *     <li>{@link ChildAnimation#BABY_CRAWL}: tipped forward around the hips, so it rests on hands and knees (the pose
 *     points arms and legs "forward", which is then down);</li>
 *     <li>{@link ChildAnimation#BABY_RISE}: starts a leg's length lower, like sitting on the floor, and comes up slowly
 *     while the legs swing down under it.</li>
 * </ul>
 * Here the body faces -Z and everything is already scaled by the entity's scale; the child model is 18 units high
 * (legs 6, body 6, head 6).
 */
@Mixin(value = LivingEntityRenderer.class, remap = false)
public abstract class BabyBodyRotationMixin
{
    @Inject(method = "setupRotations", at = @At("TAIL"))
    private void coloniesalive$babyBody(final LivingEntity entity, final PoseStack pose, final float bob, final float yaw,
                                        final float partialTick, final float scale, final CallbackInfo ci)
    {
        if (!(entity instanceof AbstractEntityCitizen citizen) || !citizen.isBaby() || Minecraft.getInstance().level == null)
        {
            return;
        }
        final ClientAnimations.Active active = ClientAnimations.get(citizen.getId(), Minecraft.getInstance().level.getGameTime());
        if (active == null || (active.animation() != ChildAnimation.BABY_CRAWL && active.animation() != ChildAnimation.BABY_RISE))
        {
            return;
        }
        // one model unit, in this (already scaled) space
        final float unit = citizen.getBbHeight() / 18f / Math.max(0.01f, citizen.getScale());
        if (active.animation() == ChildAnimation.BABY_RISE)
        {
            final float elapsed = Minecraft.getInstance().level.getGameTime() - active.startTick() + partialTick;
            pose.translate(0f, -6f * unit * (1f - ChildPoses.riseProgress(elapsed)), 0f);
            return;
        }
        // crawling: the body reaches forward from the hips once tipped, so pull it back a little over its own spot
        final float hip = 6f * unit;
        pose.translate(0f, hip, 5f * unit);
        pose.mulPose(Axis.XP.rotationDegrees(-80f));
        pose.translate(0f, -hip, 0f);
    }
}
