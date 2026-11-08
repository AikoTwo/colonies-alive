package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.ChildAnimation;
import com.minecolonies.api.client.render.modeltype.CitizenModel;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Pose;

import java.util.Optional;

/**
 * Draws closed eyelids over the citizen's eyes: blinking now and then, while sleeping, laughing or getting hurt.
 * The lids come from {@link EyelidTextures} and are drawn on the head part only.
 */
public class EyelidLayer extends RenderLayer<AbstractEntityCitizen, CitizenModel<AbstractEntityCitizen>>
{
    /** A blink lasts this many ticks. */
    private static final float BLINK_TICKS = 2.5f;

    public EyelidLayer(final RenderLayerParent<AbstractEntityCitizen, CitizenModel<AbstractEntityCitizen>> parent)
    {
        super(parent);
    }

    @Override
    public void render(final PoseStack poseStack, final MultiBufferSource buffer, final int packedLight, final AbstractEntityCitizen citizen,
                       final float limbSwing, final float limbSwingAmount, final float partialTick, final float ageInTicks,
                       final float netHeadYaw, final float headPitch)
    {
        final CitizenModel<AbstractEntityCitizen> model = getParentModel();
        if (citizen.isInvisible() || !model.head.visible || !eyesClosed(citizen, ageInTicks))
        {
            return;
        }

        final Optional<ResourceLocation> lids = EyelidTextures.get(getTextureLocation(citizen));
        if (lids.isEmpty())
        {
            return;
        }

        poseStack.pushPose();
        if (model.young)
        {
            // same transform the humanoid model uses for baby heads
            poseStack.scale(0.75f, 0.75f, 0.75f);
            poseStack.translate(0.0f, 1.0f, 0.0f);
        }

        final ModelPart head = model.head;
        final float sx = head.xScale;
        final float sy = head.yScale;
        final float sz = head.zScale;
        // a hair bigger than the head so the lids never flicker with the face underneath
        head.xScale = sx * 1.002f;
        head.yScale = sy * 1.002f;
        head.zScale = sz * 1.002f;
        head.render(poseStack, buffer.getBuffer(RenderType.entityCutoutNoCull(lids.get())), packedLight,
          LivingEntityRenderer.getOverlayCoords(citizen, 0.0f));
        head.xScale = sx;
        head.yScale = sy;
        head.zScale = sz;
        poseStack.popPose();
    }

    private static boolean eyesClosed(final AbstractEntityCitizen citizen, final float ageInTicks)
    {
        if (citizen.getPose() == Pose.SLEEPING || citizen.isSleeping() || citizen.hurtTime > 0 || Showcase.forceEyesClosed)
        {
            return true;
        }

        if (Minecraft.getInstance().level != null)
        {
            final ClientAnimations.Active active = ClientAnimations.get(citizen.getId(), Minecraft.getInstance().level.getGameTime());
            if (active != null && (active.animation() == ChildAnimation.GIGGLE || active.animation() == ChildAnimation.CRY
                                     || active.animation() == ChildAnimation.GRAB_SCARED || active.animation() == ChildAnimation.DOZE
                                     || active.animation() == ChildAnimation.BABY_CLAP))
            {
                return true;
            }
        }

        // Each citizen has its own rhythm, between 3 and 6.5 seconds, some blink twice
        final int id = citizen.getId();
        final int period = 60 + Math.floorMod(id * 37, 70);
        final float phase = (ageInTicks + Math.floorMod(id * 13, period)) % period;
        if (phase < BLINK_TICKS)
        {
            return true;
        }
        return Math.floorMod(id, 3) == 0 && phase > 5 && phase < 5 + BLINK_TICKS;
    }
}
