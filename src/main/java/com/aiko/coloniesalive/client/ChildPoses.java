package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.ChildAnimation;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;

/**
 * Poses for the child animations and the walk style, applied after MineColonies set up the model.
 *
 * <p>Rotation conventions of the vanilla humanoid model (part rotations are applied X, then Y, then Z):
 * <ul>
 *     <li>arm {@code xRot}: negative lifts the arm forward; -PI/2 is straight ahead, -PI straight up</li>
 *     <li>arm {@code zRot} with the arm hanging: positive moves the right arm outwards (left arm: negative).
 *     With the arm raised above the head the sign flips: negative spreads the right arm outwards</li>
 *     <li>arm {@code yRot} with the arm pointing forward: negative turns the right hand towards the middle (left: positive)</li>
 *     <li>{@code body.xRot} positive leans forward; the other parts have to be moved like the vanilla crouch does,
 *     otherwise the arms end up inside the torso and the legs come loose</li>
 * </ul>
 */
public final class ChildPoses
{
    private ChildPoses() {}

    /** Ticks since the current animation started (set by the model mixin right before {@link #apply}). */
    public static float elapsed;

    public static void apply(final HumanoidModel<?> m, final AbstractEntityCitizen citizen, final ChildAnimation animation, final float t)
    {
        switch (animation)
        {
            case HOP_JOY ->
            {
                // arms up in a V, waving
                m.rightArm.xRot = -2.9f + Mth.sin(t * 0.9f) * 0.25f;
                m.leftArm.xRot = -2.9f - Mth.sin(t * 0.9f) * 0.25f;
                m.rightArm.zRot = -0.45f;
                m.leftArm.zRot = 0.45f;
            }
            case SPIN ->
            {
                // arms stretched out to the sides like a plane
                m.rightArm.xRot = 0f;
                m.leftArm.xRot = 0f;
                m.rightArm.zRot = 1.45f + Mth.sin(t * 0.5f) * 0.1f;
                m.leftArm.zRot = -1.45f - Mth.sin(t * 0.5f) * 0.1f;
                m.head.xRot = -0.3f;
            }
            case DAYDREAM ->
            {
                // sitting on the ground (riding pose), hands on the knees, looking slowly around
                m.head.yRot = Mth.sin(t * 0.04f) * 0.8f;
                m.head.xRot = -0.25f + Mth.sin(t * 0.07f) * 0.1f;
                m.rightArm.xRot = -0.75f;
                m.leftArm.xRot = -0.75f;
                m.rightArm.zRot = 0.12f;
                m.leftArm.zRot = -0.12f;
            }
            case CROUCH_LOOK ->
            {
                lean(m, 0.6f);
                m.head.xRot = 0.6f;
                m.rightArm.xRot = -0.9f + Mth.sin(t * 0.35f) * 0.3f;
                m.leftArm.xRot = -0.5f;
                m.rightArm.zRot = 0.1f;
                m.leftArm.zRot = -0.25f;
            }
            case PATTY_CAKE ->
            {
                // facing a friend, hands held out in front, clapping
                final float clap = Math.max(0f, Mth.sin(t * 0.63f));
                m.rightArm.xRot = -1.45f + Mth.sin(t * 0.315f) * 0.25f;
                m.leftArm.xRot = -1.45f - Mth.sin(t * 0.315f) * 0.25f;
                m.rightArm.yRot = -0.05f - clap * 0.4f;
                m.leftArm.yRot = 0.05f + clap * 0.4f;
                m.rightArm.zRot = 0f;
                m.leftArm.zRot = 0f;
            }
            case TAG_CHASER ->
            {
                lean(m, 0.3f);
                m.rightArm.xRot = -1.45f + Mth.sin(t * 0.8f) * 0.15f;
                m.leftArm.xRot = -1.45f - Mth.sin(t * 0.8f) * 0.15f;
                m.rightArm.yRot = 0.2f;
                m.leftArm.yRot = -0.2f;
                m.head.xRot = -0.2f;
            }
            case TAG_RUNNER ->
            {
                lean(m, 0.25f);
                m.rightArm.xRot = Mth.sin(t * 0.9f) * 1.2f;
                m.leftArm.xRot = -Mth.sin(t * 0.9f) * 1.2f;
                m.rightArm.zRot = 0.15f;
                m.leftArm.zRot = -0.15f;
                // glancing back over the shoulder
                m.head.yRot = Mth.sin(t * 0.12f) * 0.9f;
            }
            case DANCE ->
            {
                // arms up in a V, swaying together from side to side
                final float beat = Mth.sin(t * 0.35f);
                m.rightArm.xRot = -2.95f + Math.abs(beat) * 0.15f;
                m.leftArm.xRot = -2.95f + Math.abs(beat) * 0.15f;
                m.rightArm.zRot = -0.5f + beat * 0.3f;
                m.leftArm.zRot = 0.5f + beat * 0.3f;
                m.body.yRot = beat * 0.2f;
                m.head.zRot = beat * 0.12f;
                followTorso(m);
                m.rightLeg.zRot = Math.max(0f, beat) * 0.15f;
                m.leftLeg.zRot = Math.min(0f, beat) * 0.15f;
            }
            case CIRANDA ->
            {
                // holding hands with the neighbours: arms out to the sides and a bit down, swinging along
                final float swing = Mth.sin(t * 0.3f) * 0.12f;
                m.rightArm.xRot = -0.3f + swing;
                m.leftArm.xRot = -0.3f - swing;
                m.rightArm.zRot = 1.0f;
                m.leftArm.zRot = -1.0f;
                m.head.zRot = Mth.sin(t * 0.15f) * 0.1f;
            }
            case HUG ->
            {
                lean(m, 0.15f);
                // arms around the friend at chest height
                m.rightArm.xRot = -1.05f;
                m.leftArm.xRot = -1.05f;
                m.rightArm.yRot = -0.75f;
                m.leftArm.yRot = 0.75f;
                m.rightArm.zRot = 0f;
                m.leftArm.zRot = 0f;
                // head resting to the side, slowly rocking
                m.head.yRot = 0.35f;
                m.head.zRot = 0.15f + Mth.sin(t * 0.1f) * 0.05f;
            }
            case WHISPER ->
            {
                lean(m, 0.15f);
                // hand next to the mouth
                m.rightArm.xRot = -2.1f;
                m.rightArm.yRot = -0.6f;
                m.rightArm.zRot = 0.2f;
                m.head.zRot = 0.2f;
                m.head.yRot = 0.2f;
            }
            case GIGGLE ->
            {
                // hands on the belly, bobbing with laughter (gently: a fast shake read as trembling)
                final float shake = Mth.sin(t * 0.7f);
                m.rightArm.xRot = -0.75f + shake * 0.06f;
                m.leftArm.xRot = -0.75f - shake * 0.06f;
                m.rightArm.yRot = -0.55f;
                m.leftArm.yRot = 0.55f;
                m.rightArm.zRot = 0f;
                m.leftArm.zRot = 0f;
                m.head.xRot = 0.15f + shake * 0.05f;
                lean(m, 0.12f + shake * 0.03f);
            }
            case CHASE_ANIMAL ->
            {
                lean(m, 0.3f);
                m.rightArm.xRot = -1.2f + Mth.sin(t * 0.8f) * 0.2f;
                m.leftArm.xRot = -1.2f - Mth.sin(t * 0.8f) * 0.2f;
                m.rightArm.yRot = 0.35f;
                m.leftArm.yRot = -0.35f;
            }
            case PET_ANIMAL ->
            {
                lean(m, 0.7f);
                m.head.xRot = 0.5f;
                m.rightArm.xRot = -0.9f + Mth.sin(t * 0.3f) * 0.25f;
                m.rightArm.yRot = -0.15f;
                m.leftArm.xRot = -0.3f;
                m.leftArm.zRot = -0.15f;
            }
            case PICK_FLOWER ->
            {
                lean(m, 0.8f);
                m.head.xRot = 0.6f;
                m.rightArm.xRot = -0.55f + Mth.sin(t * 0.25f) * 0.12f;
                m.rightArm.yRot = -0.2f;
                m.leftArm.xRot = -0.25f;
                m.leftArm.zRot = -0.15f;
            }
            case GIVE_FLOWER ->
            {
                // flower held out in front, other hand shyly behind the back
                m.rightArm.xRot = -1.3f + Mth.sin(t * 0.2f) * 0.05f;
                m.rightArm.yRot = -0.1f;
                m.rightArm.zRot = 0f;
                m.leftArm.xRot = 0.45f;
                m.leftArm.zRot = -0.1f;
                m.head.xRot = 0.1f;
                m.head.zRot = 0.12f;
                m.body.yRot = Mth.sin(t * 0.15f) * 0.12f;
                followTorso(m);
            }
            case SPLASH ->
            {
                lean(m, 0.25f);
                // arms go up and slap down on the water, out of step with each other
                m.rightArm.xRot = -1.6f + Mth.sin(t * 0.45f) * 0.9f;
                m.leftArm.xRot = -1.6f + Mth.sin(t * 0.45f + 1.4f) * 0.9f;
                m.rightArm.zRot = 0.15f;
                m.leftArm.zRot = -0.15f;
            }
            case WAVE ->
            {
                m.rightArm.xRot = -2.8f;
                m.rightArm.zRot = -0.2f + Mth.sin(t * 0.6f) * 0.45f;
                m.head.zRot = 0.12f;
            }
            case MARCH ->
            {
                final float step = Mth.sin(t * 0.5f);
                m.rightArm.xRot = step * 1.1f;
                m.leftArm.xRot = -step * 1.1f;
                m.rightLeg.xRot = -step * 0.8f;
                m.leftLeg.xRot = step * 0.8f;
                m.head.xRot = -0.15f;
            }
            case LEADER ->
            {
                final float step = Mth.sin(t * 0.5f);
                // pointing the way, marching
                m.rightArm.xRot = -2.2f + step * 0.1f;
                m.rightArm.zRot = 0f;
                m.leftArm.xRot = -step * 1.0f;
                m.rightLeg.xRot = -step * 0.8f;
                m.leftLeg.xRot = step * 0.8f;
                m.head.xRot = -0.2f;
            }
            case CRY ->
            {
                // fists rubbing the eyes, head down, sobbing slowly
                final float sob = Mth.sin(t * 0.45f);
                lean(m, 0.15f + sob * 0.03f);
                m.head.xRot = 0.35f;
                m.rightArm.xRot = -2.25f + Mth.sin(t * 0.5f) * 0.1f;
                m.leftArm.xRot = -2.25f - Mth.sin(t * 0.5f) * 0.1f;
                m.rightArm.yRot = -0.55f;
                m.leftArm.yRot = 0.55f;
                m.rightArm.zRot = 0f;
                m.leftArm.zRot = 0f;
            }
            case GRAB_WHEE ->
            {
                m.rightArm.xRot = -2.9f + Mth.sin(t * 0.4f) * 0.2f;
                m.leftArm.xRot = -2.9f - Mth.sin(t * 0.4f) * 0.2f;
                m.rightArm.zRot = -0.55f;
                m.leftArm.zRot = 0.55f;
                kickLegs(m, t, 0.35f, 0.6f);
                m.head.xRot = -0.2f;
            }
            case GRAB_SCARED ->
            {
                m.rightArm.xRot = -1.6f + Mth.sin(t * 0.75f) * 0.7f;
                m.leftArm.xRot = -1.6f - Mth.sin(t * 0.75f) * 0.7f;
                m.rightArm.zRot = 0.5f;
                m.leftArm.zRot = -0.5f;
                kickLegs(m, t, 0.6f, 0.7f);
            }
            case GRAB_GRUMPY, POUT ->
            {
                // arms crossed over the chest, looking away
                m.rightArm.xRot = -1.0f;
                m.rightArm.yRot = -0.95f;
                m.leftArm.xRot = -0.9f;
                m.leftArm.yRot = 0.9f;
                m.rightArm.zRot = 0f;
                m.leftArm.zRot = 0f;
                m.head.yRot = 0.55f;
                m.head.xRot = -0.15f;
                if (animation == ChildAnimation.GRAB_GRUMPY)
                {
                    kickLegs(m, t, 0.2f, 0.3f);
                }
            }
            case GRAB_SHY ->
            {
                // hands over the face, peeking through now and then
                final float peek = Math.max(0f, Mth.sin(t * 0.08f));
                m.rightArm.xRot = -2.3f + peek * 0.5f;
                m.leftArm.xRot = -2.3f;
                m.rightArm.yRot = -0.5f;
                m.leftArm.yRot = 0.5f;
                m.rightArm.zRot = 0f;
                m.leftArm.zRot = 0f;
                m.head.xRot = 0.25f;
                m.head.zRot = 0.15f;
                m.rightLeg.xRot = -0.2f;
                m.leftLeg.xRot = -0.2f;
            }
            case GRAB_CALM ->
            {
                m.rightArm.xRot = -0.35f;
                m.leftArm.xRot = -0.35f;
                m.rightArm.zRot = 0.2f;
                m.leftArm.zRot = -0.2f;
                kickLegs(m, t, 0.15f, 0.45f);
                m.head.yRot = Mth.sin(t * 0.05f) * 0.5f;
            }
            case TALK ->
            {
                // one hand moving while explaining, head bobbing along
                m.rightArm.xRot = -0.9f + Mth.sin(t * 0.35f) * 0.3f;
                m.rightArm.yRot = -0.25f + Mth.sin(t * 0.2f) * 0.2f;
                m.rightArm.zRot = 0.1f;
                m.leftArm.xRot = -0.2f + Mth.sin(t * 0.27f) * 0.15f;
                m.head.xRot = Mth.sin(t * 0.4f) * 0.08f;
                m.head.zRot = Mth.sin(t * 0.13f) * 0.08f;
            }
            case LISTEN ->
            {
                // hands behind the back, nodding every now and then
                m.rightArm.xRot = 0.35f;
                m.leftArm.xRot = 0.35f;
                m.rightArm.zRot = -0.12f;
                m.leftArm.zRot = 0.12f;
                m.head.xRot = 0.1f + Math.max(0f, Mth.sin(t * 0.25f)) * 0.25f;
                m.head.zRot = 0.06f;
            }
            case ARGUE ->
            {
                // left hand on the hip, right hand pointing and shaking
                m.leftArm.xRot = -0.35f;
                m.leftArm.yRot = 0.6f;
                m.leftArm.zRot = -0.75f;
                m.rightArm.xRot = -1.45f + Mth.sin(t * 0.9f) * 0.15f;
                m.rightArm.yRot = -0.1f;
                m.rightArm.zRot = 0f;
                m.head.xRot = -0.1f + Mth.sin(t * 0.5f) * 0.06f;
            }
            case SORRY ->
            {
                // looking at the feet, hands behind the back, swaying a little
                m.head.xRot = 0.55f;
                m.rightArm.xRot = 0.45f;
                m.leftArm.xRot = 0.45f;
                m.rightArm.zRot = -0.15f;
                m.leftArm.zRot = 0.15f;
                m.body.yRot = Mth.sin(t * 0.1f) * 0.1f;
                followTorso(m);
            }
            case SAD ->
            {
                lean(m, 0.12f);
                m.head.xRot = 0.5f;
                m.rightArm.xRot = 0.05f;
                m.leftArm.xRot = 0.05f;
                m.rightArm.zRot = 0.02f;
                m.leftArm.zRot = -0.02f;
            }
            case READ ->
            {
                // sitting, paper held low in front with both hands, eyes on it
                m.head.xRot = 0.45f + Mth.sin(t * 0.05f) * 0.05f;
                m.rightArm.xRot = -0.95f;
                m.leftArm.xRot = -0.95f;
                m.rightArm.yRot = -0.35f;
                m.leftArm.yRot = 0.35f;
                m.rightArm.zRot = 0f;
                m.leftArm.zRot = 0f;
            }
            case WRITE ->
            {
                // left hand holds the paper, right hand scribbling
                m.head.xRot = 0.5f;
                m.head.yRot = 0.1f;
                m.leftArm.xRot = -0.85f;
                m.leftArm.yRot = 0.3f;
                m.rightArm.xRot = -0.9f + Mth.sin(t * 0.8f) * 0.08f;
                m.rightArm.yRot = -0.25f + Mth.cos(t * 0.8f) * 0.08f;
                m.rightArm.zRot = 0f;
                m.leftArm.zRot = 0f;
            }
            case RAISE_HAND ->
            {
                // "me, me!": hand up and a bit out to the side (straight up it hides behind the big child head)
                m.rightArm.xRot = -2.75f;
                m.rightArm.zRot = -0.55f + Mth.sin(t * 0.5f) * 0.12f;
                m.leftArm.xRot = -0.4f;
                m.head.xRot = -0.15f;
            }
            case DOZE ->
            {
                // nodding off, head slowly dropping and jerking back up
                final float nod = (t * 0.02f) % 1f;
                m.head.xRot = 0.15f + nod * 0.55f;
                m.rightArm.xRot = -0.3f;
                m.leftArm.xRot = -0.3f;
                m.rightArm.zRot = 0.05f;
                m.leftArm.zRot = -0.05f;
            }
            case KID_PUNCH ->
            {
                // fists in front, punching in turns (one punch every 6 ticks), the torso twisting with each one
                final float p = t * 0.524f;
                final float right = Math.max(0f, Mth.sin(p));
                final float left = Math.max(0f, -Mth.sin(p));
                m.rightArm.xRot = -0.95f - right * 0.65f;
                m.leftArm.xRot = -0.95f - left * 0.65f;
                m.rightArm.yRot = -0.25f + right * 0.2f;
                m.leftArm.yRot = 0.25f - left * 0.2f;
                m.rightArm.zRot = 0f;
                m.leftArm.zRot = 0f;
                m.body.yRot = (left - right) * 0.3f;
                followTorso(m);
                m.head.xRot = 0.05f + Mth.sin(t * 0.9f) * 0.05f;
                kickLegs(m, t, 0.9f, 0.35f);
            }
            case CARRY_CHILD ->
            {
                // both arms forward and in, holding the child against the chest, looking at it now and then
                m.rightArm.xRot = -1.05f + Mth.sin(t * 0.3f) * 0.03f;
                m.leftArm.xRot = -1.05f - Mth.sin(t * 0.3f) * 0.03f;
                m.rightArm.yRot = -0.45f;
                m.leftArm.yRot = 0.45f;
                m.rightArm.zRot = 0f;
                m.leftArm.zRot = 0f;
                m.head.xRot = 0.25f;
            }
            case BABY_SIT ->
            {
                // sitting (riding pose), little arms forward bobbing up and down in turns, looking slowly around
                final float shake = Mth.sin(t * 0.45f);
                m.rightArm.xRot = -1.15f + shake * 0.35f;
                m.leftArm.xRot = -1.15f - shake * 0.35f;
                m.rightArm.zRot = 0.2f;
                m.leftArm.zRot = -0.2f;
                m.head.yRot = Mth.sin(t * 0.05f) * 0.6f;
                m.head.xRot = -0.1f + Mth.sin(t * 0.11f) * 0.08f;
                m.body.zRot = Mth.sin(t * 0.08f) * 0.05f;
            }
            case BABY_TODDLE ->
            {
                // short, careful little steps (the walk animation barely moves the legs at a baby's pace), arms up
                // and out for balance, just a slight sway from one foot to the other
                final float step = Mth.sin(t * 0.5f);
                m.rightLeg.xRot = step * 0.55f;
                m.leftLeg.xRot = -step * 0.55f;
                m.rightLeg.zRot = 0.06f;
                m.leftLeg.zRot = -0.06f;
                m.rightArm.xRot = -1.2f - step * 0.1f;
                m.leftArm.xRot = -1.2f + step * 0.1f;
                m.rightArm.zRot = 0.5f;
                m.leftArm.zRot = -0.5f;
                m.body.zRot = step * 0.035f;
                m.head.zRot = -step * 0.03f;
                m.head.xRot = 0.15f;
            }
            case BABY_CLAP ->
            {
                // sitting, clapping the hands in front of the chest, head bobbing with joy
                final float clap = Math.max(0f, Mth.sin(t * 0.7f));
                m.rightArm.xRot = -1.35f;
                m.leftArm.xRot = -1.35f;
                m.rightArm.yRot = -0.1f - clap * 0.45f;
                m.leftArm.yRot = 0.1f + clap * 0.45f;
                m.rightArm.zRot = 0f;
                m.leftArm.zRot = 0f;
                m.head.xRot = -0.2f + Mth.sin(t * 0.7f) * 0.08f;
            }
            case BABY_CRAWL ->
            {
                // the renderer lays the whole body down (BabyBodyRender): "forward" in the model is down in the world,
                // so arms and legs pointing forward stand on the floor, and the head looks "up" to see ahead.
                // Diagonal pairs move together, like real crawling.
                final float step = Mth.sin(t * 0.32f) * 0.38f;
                m.rightArm.xRot = -1.57f + step;
                m.leftArm.xRot = -1.57f - step;
                m.rightLeg.xRot = -1.57f - step;
                m.leftLeg.xRot = -1.57f + step;
                m.rightArm.zRot = 0.08f;
                m.leftArm.zRot = -0.08f;
                m.rightLeg.zRot = 0f;
                m.leftLeg.zRot = 0f;
                m.rightArm.yRot = 0f;
                m.leftArm.yRot = 0f;
                m.head.xRot = -1.15f + Mth.sin(t * 0.16f) * 0.08f;
                m.head.yRot = Mth.sin(t * 0.05f) * 0.3f;
                m.body.zRot = Mth.sin(t * 0.32f) * 0.05f;
            }
            case BABY_RISE ->
            {
                // getting up from sitting: the legs swing down from the sitting position while the renderer lifts the body
                // (BabyBodyRender), arms out to keep the balance, a wobble that fades as it stands
                final float e = riseProgress(elapsed);
                final float wobble = Mth.sin(t * 0.6f) * (0.05f + 0.05f * (1f - e));
                m.rightLeg.xRot = -1.41f * (1f - e);
                m.leftLeg.xRot = -1.41f * (1f - e);
                m.rightLeg.yRot = 0.31f * (1f - e);
                m.leftLeg.yRot = -0.31f * (1f - e);
                m.rightLeg.zRot = 0.08f;
                m.leftLeg.zRot = -0.08f;
                m.rightArm.xRot = -1.0f - 0.4f * (1f - e) + wobble;
                m.leftArm.xRot = -1.0f - 0.4f * (1f - e) - wobble;
                m.rightArm.zRot = 0.65f;
                m.leftArm.zRot = -0.65f;
                m.body.zRot = wobble * 0.3f;
                m.head.xRot = 0.25f * (1f - e);
            }
            case BABY_HELD, BABY_HELD_CRY ->
            {
                // sitting in the arms: legs forward like sitting on the floor, kicking a little
                final float kick = Mth.sin(t * 0.35f) * (animation == ChildAnimation.BABY_HELD ? 0.18f : 0.06f);
                m.rightLeg.xRot = -1.41f + kick;
                m.leftLeg.xRot = -1.41f - kick;
                m.rightLeg.yRot = 0.31f;
                m.leftLeg.yRot = -0.31f;
                m.rightLeg.zRot = 0.08f;
                m.leftLeg.zRot = -0.08f;
                if (animation == ChildAnimation.BABY_HELD)
                {
                    // little arms reaching out to whoever holds it
                    m.rightArm.xRot = -1.9f + Mth.sin(t * 0.3f) * 0.15f;
                    m.leftArm.xRot = -1.9f - Mth.sin(t * 0.3f) * 0.15f;
                    m.rightArm.zRot = 0.15f;
                    m.leftArm.zRot = -0.15f;
                    m.head.xRot = -0.25f;
                }
                else
                {
                    // fists rubbing the eyes, sobbing
                    m.rightArm.xRot = -2.1f + Mth.sin(t * 0.5f) * 0.1f;
                    m.leftArm.xRot = -2.1f - Mth.sin(t * 0.5f) * 0.1f;
                    m.rightArm.yRot = -0.5f;
                    m.leftArm.yRot = 0.5f;
                    m.rightArm.zRot = 0f;
                    m.leftArm.zRot = 0f;
                    m.head.xRot = 0.3f + Mth.sin(t * 0.8f) * 0.05f;
                }
            }
            case PEEKABOO ->
            {
                // crouched in front of the baby: hands over the eyes... then "boo!", arms thrown open, and again
                lean(m, 0.3f);
                final float cycle = (t % 45f) / 45f;
                if (cycle < 0.6f)
                {
                    // hands in front of the face, not inside the (big) head of the child model: arms well forward,
                    // only a little turned in, head kept up and back
                    m.rightArm.xRot = -2.08f;
                    m.leftArm.xRot = -2.08f;
                    m.rightArm.yRot = -0.2f;
                    m.leftArm.yRot = 0.2f;
                    m.rightArm.zRot = 0f;
                    m.leftArm.zRot = 0f;
                    m.head.xRot = 0.12f;
                }
                else
                {
                    m.rightArm.xRot = -2.6f;
                    m.leftArm.xRot = -2.6f;
                    m.rightArm.yRot = 0f;
                    m.leftArm.yRot = 0f;
                    m.rightArm.zRot = -0.6f;
                    m.leftArm.zRot = 0.6f;
                    m.head.xRot = 0.1f;
                }
            }
            default ->
            {
            }
        }
        m.hat.copyFrom(m.head);
    }

    /**
     * Walk of a citizen in a bad mood, instead of the bouncy walk: hunched, head hanging, arms barely swinging,
     * dragging the feet. Works on top of the vanilla walk (which already set arms and legs from the limb swing).
     */
    public static void gloomyWalk(final HumanoidModel<?> m, final float limbSwingAmount, final float t)
    {
        lean(m, 0.14f);
        m.head.xRot = 0.6f + Mth.sin(t * 0.05f) * 0.05f;
        m.head.yRot *= 0.3f;
        m.rightArm.xRot *= 0.3f;
        m.leftArm.xRot *= 0.3f;
        m.rightArm.zRot = 0.03f;
        m.leftArm.zRot = -0.03f;
        m.rightLeg.xRot *= 0.65f;
        m.leftLeg.xRot *= 0.65f;
        // standing still: a slow, heavy breath through the shoulders
        if (limbSwingAmount < 0.1f)
        {
            final float sigh = Math.max(0f, Mth.sin(t * 0.045f));
            m.rightArm.zRot += sigh * 0.04f;
            m.leftArm.zRot -= sigh * 0.04f;
            m.head.xRot += sigh * 0.08f;
        }
    }

    /**
     * A pregnant citizen (see {@code BellyLayer}): standing, a hand resting on the belly and stroking it slowly; walking,
     * the arms swing less.
     */
    public static void pregnant(final HumanoidModel<?> m, final float limbSwingAmount, final float t)
    {
        if (limbSwingAmount < 0.15f)
        {
            // the arm swings a little forward, then tilts in towards the middle: the hand ends up on the belly
            final float stroke = Mth.sin(t * 0.06f);
            m.rightArm.xRot = -0.5f + stroke * 0.04f;
            m.rightArm.yRot = 0f;
            m.rightArm.zRot = -0.38f + stroke * 0.03f;
            m.head.xRot = Math.max(m.head.xRot, 0.12f);
        }
        else
        {
            m.rightArm.xRot *= 0.6f;
            m.leftArm.xRot *= 0.6f;
        }
    }

    /** 0 sitting .. 1 standing, eased, for a baby getting up ({@link ChildAnimation#BABY_RISE}). */
    public static float riseProgress(final float elapsedTicks)
    {
        final float x = Mth.clamp(elapsedTicks / ChildAnimation.BABY_RISE_TICKS, 0f, 1f);
        return x * x * (3f - 2f * x);
    }

    /** Legs swinging back and forth in turns, like dangling from somewhere. */
    private static void kickLegs(final HumanoidModel<?> m, final float t, final float speed, final float amount)
    {
        m.rightLeg.xRot = Mth.sin(t * speed) * amount;
        m.leftLeg.xRot = -Mth.sin(t * speed) * amount;
        m.rightLeg.zRot = 0f;
        m.leftLeg.zRot = 0f;
    }

    /**
     * After the movement layer: shoulders follow a twisted torso and the hat layer follows the head.
     */
    public static void afterMotion(final HumanoidModel<?> m)
    {
        followTorso(m);
        m.hat.copyFrom(m.head);
    }

    /**
     * Leans the torso forward and moves head, arms and legs along, the same way the vanilla crouch does
     * (the vanilla crouch is a lean of 0.5). Also lifts the arms by the same relative amount.
     */
    private static void lean(final HumanoidModel<?> m, final float amount)
    {
        final float k = amount / 0.5f;
        m.body.xRot = amount;
        m.head.y = 4.2f * k * headOffsetScale(m);
        m.body.y = 3.2f * k;
        m.rightArm.y = 2.0f + 3.2f * k;
        m.leftArm.y = 2.0f + 3.2f * k;
        m.rightLeg.z = 4.0f * k;
        m.leftLeg.z = 4.0f * k;
        m.rightLeg.y = 12.0f + 0.2f * k;
        m.leftLeg.y = 12.0f + 0.2f * k;
    }

    /**
     * Baby models draw the head at 3/4 size and the body at half size, so moving both parts by the same amount
     * makes the head sink into the chest. Head offsets get scaled by this to move together with the body.
     */
    private static float headOffsetScale(final HumanoidModel<?> m)
    {
        return m.young ? 2f / 3f : 1f;
    }

    /** Moves the shoulders with a twisted torso, as vanilla does for attacks. */
    private static void followTorso(final HumanoidModel<?> m)
    {
        final float yaw = m.body.yRot;
        m.rightArm.z = Mth.sin(yaw) * 5.0f;
        m.rightArm.x = -Mth.cos(yaw) * 5.0f;
        m.leftArm.z = -Mth.sin(yaw) * 5.0f;
        m.leftArm.x = Mth.cos(yaw) * 5.0f;
        m.rightArm.yRot += yaw;
        m.leftArm.yRot += yaw;
    }
}
