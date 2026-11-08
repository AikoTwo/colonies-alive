package com.aiko.coloniesalive.client;

import com.minecolonies.api.client.render.modeltype.CitizenModel;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.neoforged.neoforge.client.ClientHooks;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.util.Mth;

/**
 * The belly of a pregnant citizen: a box in front of the lower torso, painted with the citizen's own skin (the UVs
 * pick the front, sides and top of the torso, so it wears the same clothes). It moves with the torso and grows with
 * the pregnancy: nothing in the first days, then rounder and rounder until the birth.
 */
public class BellyLayer extends RenderLayer<AbstractEntityCitizen, CitizenModel<AbstractEntityCitizen>>
{
    /** The pregnancy shows from here on (0..1). */
    private static final float SHOWS_FROM = 0f;
    /** Depth of the belly at the end, in model pixels (the boxes are 5 deep together, scaled by depth / 4). */
    private static final float MAX_DEPTH = 2.8f;

    private final ModelPart belly;

    public BellyLayer(final RenderLayerParent<AbstractEntityCitizen, CitizenModel<AbstractEntityCitizen>> parent)
    {
        super(parent);
        final MeshDefinition mesh = new MeshDefinition();
        // 8 x 6 x 4 in front of the torso (the torso spans z -2..2, y 0..12); texOffs(16, 21) puts its front face on the
        // torso front rows 25-30 (the belly), its sides on the torso sides and its top on the torso front rows 21-24
        // a smaller box in front of it rounds the shape off (front face on torso front rows 25-28)
        mesh.getRoot().addOrReplaceChild("belly", CubeListBuilder.create()
                                                    .texOffs(16, 21).addBox(-4f, 5f, -6f, 8, 6, 4)
                                                    .texOffs(19, 24).addBox(-3f, 6f, -7f, 6, 4, 1), PartPose.ZERO);
        // MineColonies' citizen skins are 128 x 64 (player layout on the left half)
        this.belly = LayerDefinition.create(mesh, 128, 64).bakeRoot().getChild("belly");

        // the same belly in a chestplate: one pixel bigger all round (like the armor's own torso) and painted with the
        // armor texture (64 x 32, same torso layout), so the belly wears the armor instead of poking through it
        final MeshDefinition armorMesh = new MeshDefinition();
        final CubeDeformation inflate = new CubeDeformation(1.0f);
        armorMesh.getRoot().addOrReplaceChild("belly", CubeListBuilder.create()
                                                         .texOffs(16, 21).addBox(-4f, 5f, -6f, 8, 6, 4, inflate)
                                                         .texOffs(19, 24).addBox(-3f, 6f, -7f, 6, 4, 1, inflate), PartPose.ZERO);
        this.armorBelly = LayerDefinition.create(armorMesh, 64, 32).bakeRoot().getChild("belly");
    }

    private final ModelPart armorBelly;

    @Override
    public void render(final PoseStack poseStack, final MultiBufferSource buffer, final int packedLight, final AbstractEntityCitizen citizen,
                       final float limbSwing, final float limbSwingAmount, final float partialTick, final float ageInTicks,
                       final float netHeadYaw, final float headPitch)
    {
        final CitizenModel<AbstractEntityCitizen> model = getParentModel();
        if (citizen.isInvisible() || model.young || !model.body.visible)
        {
            return;
        }
        final float progress = ClientFamily.pregnancy(citizen.getId());
        if (progress <= SHOWS_FROM)
        {
            return;
        }
        // eases in: grows faster towards the end, like a real one
        final float grown = Mth.clamp((progress - SHOWS_FROM) / (1f - SHOWS_FROM), 0f, 1f);
        final float depth = (0.35f + (MAX_DEPTH - 0.35f) * grown * grown * (3f - 2f * grown)) / 4f;
        // a little breathing
        final float breath = 1f + Mth.sin(ageInTicks * 0.08f) * 0.015f;

        poseStack.pushPose();
        model.body.translateAndRotate(poseStack);
        // scale around the middle of the torso front (y 8, z -2): deeper forward, a bit narrower and shorter early on
        poseStack.translate(0f, 8f / 16f, -2f / 16f);
        poseStack.scale(0.86f + 0.08f * grown, (0.7f + 0.3f * grown) * breath, depth * breath);
        poseStack.translate(0f, -8f / 16f, 2f / 16f);
        final int overlay = LivingEntityRenderer.getOverlayCoords(citizen, 0.0f);
        belly.render(poseStack, buffer.getBuffer(RenderType.entityCutoutNoCull(getTextureLocation(citizen))), packedLight, overlay);
        // early on the armored belly stays inside the chestplate; it shows once the belly is bigger than the armor
        renderArmor(poseStack, buffer, packedLight, overlay, citizen);
        poseStack.popPose();
    }

    /** A guard (or anyone in a chestplate): the belly in the armor's own texture and colour, every layer of it. */
    private void renderArmor(final PoseStack poseStack, final MultiBufferSource buffer, final int packedLight, final int overlay,
                             final AbstractEntityCitizen citizen)
    {
        final ItemStack chest = citizen.getItemBySlot(EquipmentSlot.CHEST);
        if (!(chest.getItem() instanceof ArmorItem armor))
        {
            return;
        }
        final ArmorMaterial material = armor.getMaterial().value();
        final int dye = chest.is(ItemTags.DYEABLE) ? FastColor.ARGB32.opaque(DyedItemColor.getOrDefault(chest, DyedItemColor.LEATHER_COLOR)) : -1;
        for (final ArmorMaterial.Layer layer : material.layers())
        {
            final ResourceLocation texture = ClientHooks.getArmorTexture(citizen, chest, layer, false, EquipmentSlot.CHEST);
            armorBelly.render(poseStack, buffer.getBuffer(RenderType.armorCutoutNoCull(texture)), packedLight, overlay, layer.dyeable() ? dye : -1);
        }
    }
}
