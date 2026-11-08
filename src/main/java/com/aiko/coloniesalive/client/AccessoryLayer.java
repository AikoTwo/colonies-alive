package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.ChildAnimation;
import com.minecolonies.api.client.render.modeltype.CitizenModel;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dresses babies and children in today's {@link Accessories.Outfit}, and everyone for the moment:
 * <ul>
 *     <li>asleep: a nightcap instead of the head piece (babies keep theirs), nothing on the face, back or in the hand;</li>
 *     <li>December: a Santa hat instead of the head piece;</li>
 *     <li>a baby's toy only while it sits; a child's toy only while it just stands
 *     or walks around with an empty hand;</li>
 *     <li>a band-aid on the cheek for two minutes after crying.</li>
 * </ul>
 * Nothing on the head under a helmet.
 */
public class AccessoryLayer extends RenderLayer<AbstractEntityCitizen, CitizenModel<AbstractEntityCitizen>>
{
    private static final long BAND_AID_TICKS = 20 * 120;

    private static final Map<Integer, Long> LAST_CRY = new ConcurrentHashMap<>();

    public AccessoryLayer(final RenderLayerParent<AbstractEntityCitizen, CitizenModel<AbstractEntityCitizen>> parent)
    {
        super(parent);
    }

    /** Today's outfit of a baby or child, or null for grown-ups. */
    public static Accessories.Outfit outfitOf(final AbstractEntityCitizen citizen)
    {
        if (!citizen.isBaby() || Minecraft.getInstance().level == null)
        {
            return null;
        }
        final boolean infant = ClientFamily.isInfant(citizen.getId());
        final long day = Minecraft.getInstance().level.getDayTime() / 24000L;
        return Accessories.outfit(citizen.getId(), citizen.getUUID(), citizen.isFemale(), infant, !infant && isPupil(citizen), day);
    }

    /** True if a head accessory is drawn on this citizen now (the boys' little sailor hat then steps aside). */
    public static boolean wearsHeadPiece(final AbstractEntityCitizen citizen)
    {
        if (Showcase.fashionSubject == citizen.getId())
        {
            return Showcase.fashionItem != null && Showcase.fashionItem.slot() == Accessories.Slot.HEAD;
        }
        if (Accessories.santaSeason())
        {
            return true;
        }
        final Accessories.Outfit outfit = outfitOf(citizen);
        return outfit != null && outfit.head() != null && citizen.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
    }

    @Override
    public void render(final PoseStack poseStack, final MultiBufferSource buffer, final int packedLight, final AbstractEntityCitizen citizen,
                       final float limbSwing, final float limbSwingAmount, final float partialTick, final float ageInTicks,
                       final float netHeadYaw, final float headPitch)
    {
        if (citizen.isInvisible() || Minecraft.getInstance().level == null)
        {
            return;
        }
        Accessories.ensureUploaded();
        final CitizenModel<AbstractEntityCitizen> model = getParentModel();
        final long now = Minecraft.getInstance().level.getGameTime();
        final ClientAnimations.Active active = ClientAnimations.get(citizen.getId(), now);
        final ChildAnimation anim = active == null ? ChildAnimation.NONE : active.animation();
        if (anim == ChildAnimation.CRY || anim == ChildAnimation.BABY_HELD_CRY)
        {
            LAST_CRY.put(citizen.getId(), now);
        }

        final boolean headFree = citizen.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && model.head.visible;
        final boolean sleeping = citizen.getPose() == Pose.SLEEPING || citizen.isSleeping();
        final boolean santa = Accessories.santaSeason() && !sleeping;
        final int seed = citizen.getUUID().hashCode();
        final VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(Accessories.ATLAS));
        final Ctx ctx = new Ctx(poseStack, vc, packedLight, LivingEntityRenderer.getOverlayCoords(citizen, 0.0f), model, ageInTicks);
        final boolean infant = citizen.isBaby() && ClientFamily.isInfant(citizen.getId());
        final int babyColor = Accessories.babyColor(citizen.isFemale(), citizen.getTextureId());

        // development: one accessory at a time on the test subject
        if (Showcase.fashionSubject == citizen.getId() && Showcase.fashionItem != null)
        {
            final Accessories.Accessory item = Showcase.fashionItem;
            final boolean clothing = item.slot() == Accessories.Slot.NECK || item.slot() == Accessories.Slot.BACK
                                       || item.slot() == Accessories.Slot.HANDS || item.slot() == Accessories.Slot.FEET;
            if (item == Accessories.BAND_AID_FOREHEAD)
            {
                // the set of a child under half its hearts
                final float down = Mth.clamp(MouthRows.get(getTextureLocation(citizen)) - 6f, -1f, 0.9f);
                draw(ctx, Accessories.BAND_AID_CHEEK, Accessories.bandAidColor(seed), down);
                draw(ctx, Accessories.BAND_AID_CHEEK_LEFT, Accessories.bandAidColor(seed + 1), down);
                draw(ctx, Accessories.BAND_AID_FOREHEAD, Accessories.bandAidColor(seed + 4));
                draw(ctx, Accessories.BAND_AID_ARM, Accessories.bandAidColor(seed + 2));
                return;
            }
            if (item == Accessories.HEAD_BANDAGE)
            {
                // the whole set at once, as on a badly hurt child
                final float down = Mth.clamp(MouthRows.get(getTextureLocation(citizen)) - 6f, -1f, 0.9f);
                draw(ctx, Accessories.BAND_AID_CHEEK, Accessories.bandAidColor(seed), down);
                draw(ctx, Accessories.BAND_AID_CHEEK_LEFT, Accessories.bandAidColor(seed + 1), down);
                draw(ctx, Accessories.BAND_AID_NOSE, Accessories.bandAidColor(seed + 5), Math.max(down, -0.4f));
                draw(ctx, Accessories.BAND_AID_ARM, Accessories.bandAidColor(seed + 2));
                draw(ctx, Accessories.BAND_AID_KNEE, Accessories.bandAidColor(seed + 3));
                draw(ctx, Accessories.HEAD_BANDAGE, -1);
                return;
            }
            if (item == Accessories.BAND_AID_CHEEK || item == Accessories.BAND_AID_CHEEK_LEFT || item == Accessories.PACIFIER)
            {
                final int mouth = MouthRows.get(getTextureLocation(citizen));
                draw(ctx, item, item == Accessories.PACIFIER ? babyColor : Accessories.bandAidColor(seed),
                  item == Accessories.PACIFIER ? Math.min(mouth - 5.7f, 1.1f) : Mth.clamp(mouth - 6f, -1f, 0.9f));
                return;
            }
            draw(ctx, item, infant ? (clothing ? Accessories.babyAccent(seed, babyColor) : babyColor) : Accessories.kidColor(seed, item.slot()));
            return;
        }

        if (!citizen.isBaby())
        {
            if (headFree)
            {
                if (sleeping)
                {
                    draw(ctx, Accessories.NIGHTCAP, Accessories.nightcapColor(seed));
                }
                else if (santa)
                {
                    draw(ctx, Accessories.SANTA_HAT, -1);
                }
            }
            return;
        }

        final Accessories.Outfit outfit = outfitOf(citizen);
        if (outfit == null)
        {
            return;
        }
        if (headFree)
        {
            if (santa)
            {
                draw(ctx, Accessories.SANTA_HAT, -1);
            }
            else if (sleeping && !infant)
            {
                draw(ctx, Accessories.NIGHTCAP, Accessories.nightcapColor(seed));
            }
            else if (outfit.head() != null && hurtLevel(citizen) < 3)
            {
                // (really hurt: the bandage round the head instead)
                draw(ctx, outfit.head(), color(infant, babyColor, seed, Accessories.Slot.HEAD));
            }
        }

        if (infant)
        {
            // the pacifier stays in (it used to vanish while clapping or crying and pop back afterwards)
            if (headFree && outfit.face() != null)
            {
                // the pacifier is modelled around y -1.8 (about face row 6); some skins draw the mouth a row lower (it
                // sat on the nose there) or higher
                final int mouth = MouthRows.get(getTextureLocation(citizen));
                draw(ctx, outfit.face(), babyColor, Math.min(mouth - 5.7f, 1.1f));
            }
            final int accent = Accessories.babyAccent(seed, babyColor);
            if (outfit.neck() != null)
            {
                draw(ctx, outfit.neck(), accent);
            }
            if (outfit.back() != null && !sleeping)
            {
                draw(ctx, outfit.back(), accent);
            }
            if (outfit.hands() != null)
            {
                draw(ctx, outfit.hands(), accent);
            }
            if (outfit.feet() != null)
            {
                draw(ctx, outfit.feet(), accent);
            }
            if (outfit.hand() != null && anim == ChildAnimation.BABY_SIT)
            {
                draw(ctx, outfit.hand(), babyColor);
            }
        }
        else
        {
            if (headFree && !sleeping && outfit.face() != null)
            {
                draw(ctx, outfit.face(), Accessories.kidColor(seed, Accessories.Slot.FACE));
            }
            if (outfit.neck() != null)
            {
                draw(ctx, outfit.neck(), Accessories.kidColor(seed, Accessories.Slot.NECK));
            }
            if (outfit.back() != null && !sleeping && (outfit.back() != Accessories.BACKPACK || schoolBagOn(citizen)))
            {
                draw(ctx, outfit.back(), Accessories.kidColor(seed, Accessories.Slot.BACK));
            }
            if (outfit.waist() != null)
            {
                draw(ctx, outfit.waist(), Accessories.kidColor(seed, Accessories.Slot.WAIST));
            }
            if (outfit.hand() != null && !sleeping && anim == ChildAnimation.NONE && citizen.getMainHandItem().isEmpty())
            {
                draw(ctx, outfit.hand(), Accessories.kidColor(seed, Accessories.Slot.HAND));
            }
        }

        final Long cried = LAST_CRY.get(citizen.getId());
        // band-aids: the more hurt, the more of them (each its own colour); after crying at least the one on the cheek
        final int hurt = hurtLevel(citizen);
        final boolean cheek = hurt >= 1 || (cried != null && now - cried < BAND_AID_TICKS);
        if (headFree && (cheek || hurt >= 2))
        {
            // the cheeks follow the mouth of this skin
            final float down = Mth.clamp(MouthRows.get(getTextureLocation(citizen)) - 6f, -1f, 0.9f);
            if (cheek)
            {
                draw(ctx, Accessories.BAND_AID_CHEEK, Accessories.bandAidColor(seed), down);
            }
            if (hurt >= 2)
            {
                draw(ctx, Accessories.BAND_AID_CHEEK_LEFT, Accessories.bandAidColor(seed + 1), down);
            }
            if (hurt == 2)
            {
                // (really hurt the bandage covers the forehead)
                draw(ctx, Accessories.BAND_AID_FOREHEAD, Accessories.bandAidColor(seed + 4));
            }
            if (hurt >= 3)
            {
                draw(ctx, Accessories.BAND_AID_NOSE, Accessories.bandAidColor(seed + 5), Math.max(down, -0.4f));
            }
        }
        if (hurt >= 2)
        {
            draw(ctx, Accessories.BAND_AID_ARM, Accessories.bandAidColor(seed + 2));
        }
        if (hurt >= 3)
        {
            draw(ctx, Accessories.BAND_AID_KNEE, Accessories.bandAidColor(seed + 3));
            if (headFree)
            {
                draw(ctx, Accessories.HEAD_BANDAGE, -1);
            }
        }
    }

    /** 0 healthy, 1 under 3/4 of its hearts, 2 under half, 3 under a quarter. */
    private static int hurtLevel(final AbstractEntityCitizen citizen)
    {
        final float ratio = citizen.getMaxHealth() <= 0 ? 1f : citizen.getHealth() / citizen.getMaxHealth();
        return ratio < 0.25f ? 3 : ratio < 0.5f ? 2 : ratio < 0.75f ? 1 : 0;
    }

    private static int color(final boolean infant, final int babyColor, final int seed, final Accessories.Slot slot)
    {
        return infant ? babyColor : Accessories.kidColor(seed, slot);
    }

    /**
     * The school bag only on the way to school: from getting up until class is over (noon, see the addon's school day
     * 0-6000), and put away as soon as the pupil sits down in class (or anywhere).
     */
    private static boolean schoolBagOn(final AbstractEntityCitizen citizen)
    {
        final long time = citizen.level().getDayTime() % 24000L;
        final boolean schoolMorning = time >= 22500L || time < 6000L;
        return schoolMorning && citizen.getVehicle() == null;
    }

    /** Signed up at a school (MineColonies' pupil job). */
    private static boolean isPupil(final AbstractEntityCitizen citizen)
    {
        final ICitizenDataView view = citizen.getCitizenDataView();
        return view != null && view.getJobView() != null && view.getJobView().getEntry() != null
                 && view.getJobView().getEntry().getKey() != null && "pupil".equals(view.getJobView().getEntry().getKey().getPath());
    }

    private record Ctx(PoseStack pose, VertexConsumer vc, int light, int overlay, CitizenModel<AbstractEntityCitizen> model, float t) {}

    /** Draws an accessory's pieces on their parts, with the same transforms the model uses (babies: smaller body, bigger head). */
    private static void draw(final Ctx ctx, final Accessories.Accessory accessory, final int tint)
    {
        draw(ctx, accessory, tint, 0f);
    }

    /** Same, the head pieces moved down by {@code headDown} model pixels (to fit the face of the skin). */
    private static void draw(final Ctx ctx, final Accessories.Accessory accessory, final int tint, final float headDown)
    {
        final CitizenModel<AbstractEntityCitizen> model = ctx.model();
        for (final Accessories.Piece piece : accessory.pieces())
        {
            ctx.pose().pushPose();
            final boolean head = piece.anchor() == Accessories.Anchor.HEAD;
            if (model.young)
            {
                if (head)
                {
                    ctx.pose().scale(0.75f, 0.75f, 0.75f);
                    ctx.pose().translate(0.0f, 1.0f, 0.0f);
                }
                else
                {
                    ctx.pose().scale(0.5f, 0.5f, 0.5f);
                    ctx.pose().translate(0.0f, 1.5f, 0.0f);
                }
            }
            switch (piece.anchor())
            {
                case HEAD ->
                {
                    model.head.translateAndRotate(ctx.pose());
                    if (headDown != 0f)
                    {
                        ctx.pose().translate(0f, headDown / 16f, 0f);
                    }
                }
                case BODY -> model.body.translateAndRotate(ctx.pose());
                case RIGHT_ARM -> model.rightArm.translateAndRotate(ctx.pose());
                case LEFT_ARM -> model.leftArm.translateAndRotate(ctx.pose());
                case RIGHT_LEG -> model.rightLeg.translateAndRotate(ctx.pose());
                case LEFT_LEG -> model.leftLeg.translateAndRotate(ctx.pose());
            }
            final float t = ctx.t();
            switch (piece.motion())
            {
                case SPIN_Y -> piece.part().yRot = t * 0.5f;
                case SPIN_Z -> piece.part().zRot = t * 0.35f;
                case FLAP_LEFT -> piece.part().yRot = -0.35f - Mth.sin(t * 0.25f) * 0.35f;
                case FLAP_RIGHT -> piece.part().yRot = 0.35f + Mth.sin(t * 0.25f) * 0.35f;
                default ->
                {
                }
            }
            final int color = piece.tinted() && tint != -1 ? 0xFF000000 | tint : -1;
            piece.part().render(ctx.pose(), ctx.vc(), ctx.light(), ctx.overlay(), color);
            ctx.pose().popPose();
        }
    }
}
