package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ModSounds;
import com.minecolonies.api.util.LookHandler;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * Particles and sounds used by the activities.
 */
public final class Fx
{
    private static final Random RAND = new Random();

    /** Minimum ticks between two giggles of the same child, so games don't turn into a laugh track. */
    private static final int GIGGLE_GAP = 50;
    private static final Map<Entity, Long> LAST_GIGGLE = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    private Fx() {}

    private static ServerLevel level(final Entity entity)
    {
        return entity.level() instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    private static void above(final Entity entity, final ParticleOptions particle, final int count, final double spread)
    {
        final ServerLevel level = level(entity);
        if (level != null)
        {
            level.sendParticles(particle, entity.getX(), entity.getY() + entity.getBbHeight() + 0.3, entity.getZ(), count, spread, 0.15, spread, 0.0);
        }
    }

    /** A single musical note with a random colour, above the head. */
    public static void note(final Entity entity)
    {
        final ServerLevel level = level(entity);
        if (level != null)
        {
            // count 0 makes the x speed the note colour
            level.sendParticles(ParticleTypes.NOTE,
              entity.getX() + (RAND.nextDouble() - 0.5) * 0.6, entity.getY() + entity.getBbHeight() + 0.35, entity.getZ() + (RAND.nextDouble() - 0.5) * 0.6,
              0, RAND.nextInt(25) / 24.0, 0, 0, 1);
        }
    }

    public static void hearts(final Entity entity, final int count)
    {
        above(entity, ParticleTypes.HEART, count, 0.3);
    }

    public static void happy(final Entity entity, final int count)
    {
        above(entity, ParticleTypes.HAPPY_VILLAGER, count, 0.35);
    }

    /** Little burst between two entities, e.g. clapping hands. */
    public static void between(final Entity a, final Entity b, final ParticleOptions particle, final int count)
    {
        final ServerLevel level = level(a);
        if (level != null)
        {
            level.sendParticles(particle, (a.getX() + b.getX()) / 2, a.getY() + a.getBbHeight() * 0.75, (a.getZ() + b.getZ()) / 2, count, 0.1, 0.1, 0.1, 0.0);
        }
    }

    public static void splash(final Entity entity, final BlockPos water)
    {
        final ServerLevel level = level(entity);
        if (level != null)
        {
            level.sendParticles(ParticleTypes.SPLASH, water.getX() + 0.5, water.getY() + 1.0, water.getZ() + 0.5, 12, 0.35, 0.1, 0.35, 0.1);
            level.sendParticles(ParticleTypes.BUBBLE_POP, water.getX() + 0.5, water.getY() + 0.95, water.getZ() + 0.5, 4, 0.3, 0.05, 0.3, 0.0);
            if (RAND.nextInt(3) == 0)
            {
                level.playSound(null, water, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 0.35f, 1.3f + RAND.nextFloat() * 0.3f);
            }
        }
    }

    /** Child giggle, respecting a small gap per child. */
    public static void giggle(final EntityCitizen child)
    {
        final long now = child.level().getGameTime();
        final Long last = LAST_GIGGLE.get(child);
        if (last != null && now - last < GIGGLE_GAP)
        {
            return;
        }
        LAST_GIGGLE.put(child, now);
        if (com.aiko.coloniesalive.social.Family.isInfant(child))
        {
            // a real baby laugh
            child.playSound(ModSounds.BABY_LAUGH.get(), 0.75f, 0.95f + RAND.nextFloat() * 0.15f);
            return;
        }
        child.playSound(child.isFemale() ? ModSounds.GIGGLE_GIRL.get() : ModSounds.GIGGLE_BOY.get(), 0.8f, voicePitch(child));
    }

    /** A baby babbling ("ah!", "uh?"). */
    public static void babble(final EntityCitizen baby)
    {
        baby.playSound(ModSounds.BABY_COO.get(), 0.55f, 0.95f + RAND.nextFloat() * 0.2f);
    }

    /** The voices are child voices; adults get them a good bit lower, back to a grown-up tone. */
    private static float voicePitch(final EntityCitizen citizen)
    {
        // babies a good bit higher still
        final float base = !citizen.isBaby() ? 0.74f : com.aiko.coloniesalive.social.Family.isInfant(citizen) ? 1.35f : 1.0f;
        return base * (0.92f + RAND.nextFloat() * 0.16f);
    }

    /**
     * Turns {@code who} towards a point. The body turns right away; the head is left to the citizen's own look control,
     * which turns it smoothly. Setting the head rotation directly fought with that control every tick and made heads shake.
     */
    public static void face(final Entity who, final double x, final double y, final double z)
    {
        final double dx = x - who.getX();
        final double dz = z - who.getZ();
        if (dx * dx + dz * dz < 1.0E-4)
        {
            return;
        }
        final float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        who.setYRot(yaw);
        if (who instanceof Mob mob)
        {
            mob.yBodyRot = yaw;
            mob.getLookControl().setLookAt(x, y, z);
            if (mob.getLookControl() instanceof LookHandler lookHandler)
            {
                lookHandler.setLookAtCooldown(10);
            }
        }
    }

    public static void face(final Entity who, final double x, final double z)
    {
        face(who, x, who.getEyeY(), z);
    }

    public static void face(final Entity who, final Entity target)
    {
        face(who, target.getX(), target.getEyeY(), target.getZ());
    }

    /** Sobbing, with the MCA cry voice pitched up. */
    public static void cry(final EntityCitizen child)
    {
        child.playSound(child.isFemale() ? ModSounds.CRY_GIRL.get() : ModSounds.CRY_BOY.get(), 0.9f, voicePitch(child));
    }

    /** A small, soft sob (a sad baby whimpering). */
    public static void whimper(final EntityCitizen child)
    {
        child.playSound(child.isFemale() ? ModSounds.CRY_GIRL.get() : ModSounds.CRY_BOY.get(), 0.4f, voicePitch(child) * 1.1f);
    }

    /** A tear falling from each eye, following where the head is turned. */
    public static void tears(final EntityCitizen child)
    {
        final ServerLevel level = level(child);
        if (level == null)
        {
            return;
        }
        final double yaw = Math.toRadians(child.getYHeadRot());
        // forward and right of the face, in world space
        final double fx = -Math.sin(yaw);
        final double fz = Math.cos(yaw);
        final double rx = -fz;
        final double rz = fx;
        final double eyeY = child.getEyeY() - 0.05;
        final double front = child.getBbWidth() * 0.55;
        final double side = child.isBaby() ? 0.09 : 0.13;
        for (final int s : new int[] {-1, 1})
        {
            level.sendParticles(ParticleTypes.FALLING_WATER,
              child.getX() + fx * front + rx * side * s, eyeY, child.getZ() + fz * front + rz * side * s, 1, 0.0, 0.0, 0.0, 0.0);
        }
        if (RAND.nextInt(4) == 0)
        {
            level.sendParticles(ParticleTypes.SPLASH, child.getX() + fx * front, eyeY - 0.1, child.getZ() + fz * front, 2, 0.08, 0.02, 0.08, 0.0);
        }
    }

    public static void angry(final Entity entity)
    {
        above(entity, ParticleTypes.ANGRY_VILLAGER, 1, 0.2);
    }

    /** A little punch landing (no damage): puff where the fist hits, a weak thud, now and then an angry cloud. */
    public static void punch(final EntityCitizen child, final Entity target, final long tick)
    {
        final ServerLevel level = level(child);
        if (level == null)
        {
            return;
        }
        double dx = target.getX() - child.getX();
        double dz = target.getZ() - child.getZ();
        final double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-3)
        {
            dx = 0;
            dz = 1;
        }
        else
        {
            dx /= length;
            dz /= length;
        }
        final double x = child.getX() + dx * 0.55;
        final double y = child.getY() + child.getBbHeight() * 0.62;
        final double z = child.getZ() + dz * 0.55;
        level.sendParticles(ParticleTypes.POOF, x, y, z, 1, 0.03, 0.03, 0.03, 0.005);
        level.playSound(null, x, y, z, SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.NEUTRAL, 0.35f, 1.5f + RAND.nextFloat() * 0.4f);
        if (tick % 3 == 0)
        {
            angry(child);
        }
    }

    /** Annoyed grumble, with the MCA angry voice pitched up. */
    public static void grumble(final EntityCitizen child)
    {
        child.playSound(child.isFemale() ? ModSounds.ANGRY_GIRL.get() : ModSounds.ANGRY_BOY.get(), 0.85f, voicePitch(child));
    }
}
