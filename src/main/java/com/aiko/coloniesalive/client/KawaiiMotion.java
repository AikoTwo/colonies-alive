package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.ColoniesAlive;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.neoforged.neoforge.client.entity.animation.json.AnimationLoader;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "kawaii" movement layer from our MCA addon, on MineColonies citizens: loops in JSON
 * ({@code assets/coloniesalive/neoforge/animations/entity/kawaii_*.json}, editable by resource packs) added on top of
 * the vanilla pose and mixed by speed: idle (little hand sway and breathing), a bouncy anime-style walk (one for girls,
 * one for boys), a run with the arms back, a two-phase jump (arms up going up, arms open coming down), two slow
 * "looking around" loops and, in bed, breathing and little sleep movements.
 *
 * <p>Children get the full layer, adults a softer one.
 */
public final class KawaiiMotion
{
    private static final float CHILD_INTENSITY = 1.0f;
    private static final float ADULT_INTENSITY = 0.6f;

    private static final ResourceLocation IDLE         = id("kawaii_idle");
    private static final ResourceLocation WALK_GIRL    = id("kawaii_walk_girl");
    private static final ResourceLocation WALK_BOY     = id("kawaii_walk_boy");
    private static final ResourceLocation RUN          = id("kawaii_run");
    private static final ResourceLocation JUMP_UP      = id("kawaii_jump_up");
    private static final ResourceLocation JUMP_DOWN    = id("kawaii_jump_down");
    private static final ResourceLocation LOOK_A       = id("kawaii_look_a");
    private static final ResourceLocation LOOK_B       = id("kawaii_look_b");
    private static final ResourceLocation SLEEP_BREATH = id("kawaii_sleep_breath");
    private static final ResourceLocation SLEEP_EVENTS = id("kawaii_sleep_events");

    /** One walk loop per step cycle of the legs (vanilla limb swing: 2*PI / 0.6662). */
    private static final float WALK_CYCLE = (float) (2.0 * Math.PI / 0.6662);

    /** Smoothing of the in-air weight (per second): rises fast, falls slower. Steps and stairs don't count as jumps. */
    private static final float AIR_RISE_RATE = 12.0f;
    private static final float AIR_FALL_RATE = 5.0f;
    private static final float PHASE_RATE    = 10.0f;
    private static final float AIR_ONSET     = 0.10f;
    private static final float AIR_RAMP      = 0.12f;

    private static final class Blend
    {
        float air;
        float airTime;
        float phase = 0.5f;
        long  lastNanos;
    }

    private static final Map<Integer, Blend> BLEND  = new HashMap<>();
    private static final Vector3f            VECTOR = new Vector3f();

    private KawaiiMotion() {}

    private static ResourceLocation id(final String name)
    {
        return ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, name);
    }

    public static void apply(final HumanoidModel<?> m, final AbstractEntityCitizen citizen, final float limbSwing, final float limbSwingAmount, final float ageInTicks)
    {
        final float intensity = citizen.isBaby() ? CHILD_INTENSITY : ADULT_INTENSITY;
        // each citizen has its own clock, so they don't all sway in sync
        final long timeMs = (long) ((ageInTicks + citizen.getId() * 7.0f) * 50.0f);
        final long lookOffsetMs = citizen.getId() * 1373L;

        if (citizen.getPose() == Pose.SLEEPING)
        {
            if (citizen.isBaby())
            {
                sample(m, SLEEP_BREATH, timeMs, 1.0f);
                sample(m, SLEEP_EVENTS, timeMs + lookOffsetMs * 11L, 1.0f);
            }
            return;
        }
        if (citizen.getPose() != Pose.STANDING || citizen.getVehicle() != null || citizen.isInWater())
        {
            return;
        }

        if (BLEND.size() > 512)
        {
            BLEND.clear();
        }
        final Blend blend = BLEND.computeIfAbsent(citizen.getId(), k -> new Blend());
        final long now = System.nanoTime();
        final float dt = blend.lastNanos == 0L ? 0.016f : Mth.clamp((now - blend.lastNanos) / 1.0e9f, 0.0f, 0.1f);
        blend.lastNanos = now;

        final boolean airborne = !citizen.onGround() && !citizen.isNoGravity();
        blend.airTime = airborne ? blend.airTime + dt : 0.0f;
        final float airTarget = airborne ? Mth.clamp((blend.airTime - AIR_ONSET) / AIR_RAMP, 0.0f, 1.0f) : 0.0f;
        final float airRate = airTarget > blend.air ? AIR_RISE_RATE : AIR_FALL_RATE;
        blend.air += (airTarget - blend.air) * (1.0f - (float) Math.exp(-airRate * dt));
        // 1 = going up, 0 = coming down
        final float dy = (float) (citizen.getY() - citizen.yo);
        blend.phase += (Mth.clamp(0.5f + dy * 6.0f, 0.0f, 1.0f) - blend.phase) * (1.0f - (float) Math.exp(-PHASE_RATE * dt));

        final float air = blend.air;
        final float ground = 1.0f - air;
        if (air > 0.02f)
        {
            sample(m, JUMP_UP, timeMs, intensity * air * blend.phase);
            sample(m, JUMP_DOWN, timeMs, intensity * air * (1.0f - blend.phase));
        }
        if (ground > 0.02f)
        {
            final float speed = limbSwingAmount;
            final float run = Mth.clamp((speed - 0.55f) / 0.35f, 0.0f, 1.0f);
            final float walk = Mth.clamp(speed / 0.35f, 0.0f, 1.0f) * (1.0f - run);
            final float idle = 1.0f - Mth.clamp(speed / 0.2f, 0.0f, 1.0f);
            final float look = Mth.clamp(idle * 0.8f + walk + run * 0.3f, 0.0f, 1.0f);
            final float scale = intensity * ground;

            sample(m, IDLE, timeMs, scale * idle);
            // the walk follows the legs, so the bounce matches the steps
            final float cycle = limbSwing / WALK_CYCLE;
            final long walkMs = (long) ((cycle - Mth.floor(cycle)) * 1000.0f);
            sample(m, citizen.isFemale() ? WALK_GIRL : WALK_BOY, walkMs, scale * walk);
            sample(m, RUN, timeMs, scale * run);
            sample(m, LOOK_A, timeMs + lookOffsetMs, scale * look);
            sample(m, LOOK_B, timeMs + lookOffsetMs * 3L, scale * look);
        }
    }

    /**
     * Adds the animation at {@code timeMs} with the given weight. The channels are additive rotations, so weighting is
     * just scaling the sampled value. Same sampling as vanilla {@code KeyframeAnimations.animate}, but onto the
     * humanoid parts by name (citizen models are not hierarchical models).
     */
    private static void sample(final HumanoidModel<?> m, final ResourceLocation id, final long timeMs, final float weight)
    {
        if (weight <= 0.01f)
        {
            return;
        }
        final AnimationDefinition definition = AnimationLoader.INSTANCE.getAnimation(id);
        if (definition == null)
        {
            return;
        }
        final float seconds = definition.looping() ? (timeMs / 1000.0f) % definition.lengthInSeconds() : timeMs / 1000.0f;
        for (final Map.Entry<String, List<AnimationChannel>> entry : definition.boneAnimations().entrySet())
        {
            final ModelPart part = part(m, entry.getKey());
            if (part == null)
            {
                continue;
            }
            for (final AnimationChannel channel : entry.getValue())
            {
                final Keyframe[] keyframes = channel.keyframes();
                final int i = Math.max(0, Mth.binarySearch(0, keyframes.length, k -> seconds <= keyframes[k].timestamp()) - 1);
                final int j = Math.min(keyframes.length - 1, i + 1);
                final float alpha = j != i
                                      ? Mth.clamp((seconds - keyframes[i].timestamp()) / (keyframes[j].timestamp() - keyframes[i].timestamp()), 0.0f, 1.0f)
                                      : 0.0f;
                keyframes[j].interpolation().apply(VECTOR, alpha, keyframes, i, j, weight);
                channel.target().apply(part, VECTOR);
            }
        }
    }

    private static ModelPart part(final HumanoidModel<?> m, final String bone)
    {
        return switch (bone)
        {
            case "head" -> m.head;
            case "body" -> m.body;
            case "right_arm" -> m.rightArm;
            case "left_arm" -> m.leftArm;
            case "right_leg" -> m.rightLeg;
            case "left_leg" -> m.leftLeg;
            default -> null;
        };
    }
}
