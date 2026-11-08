package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ColoniesAlive;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.mobs.EntityMercenary;
import com.minecolonies.core.entity.visitor.VisitorCitizen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Random;

/**
 * Now and then a mean adventurer (tavern visitor) or mercenary walks up to a child or a baby playing nearby and hits it:
 * a real hit (it has to reach it, in sight, arm swing, red flash, knockback), then it sneers and walks off. What
 * happens next is in {@link Drama}: a guard or a relative who sees it picks a real fight with it ({@link Brawl}), the
 * child runs crying to tell mum or dad, a baby wails until someone comes to pick it up.
 *
 * <p>The hits use a damage source without an attacker on purpose: MineColonies would otherwise make the child flee and
 * call every guard around with their weapons (and the visitor's friends), and the fight would not be ours to stage.
 * Nobody dies from it: every hit stops short of the last two hearts ({@link #hit}).
 */
public final class Bully
{
    private enum Phase
    {
        APPROACH,
        GLOAT,
        FIGHT,
        LEAVE,
        FLEE
    }

    /** One in this many checks (every 10 s, only with a child close by) a bully goes for it. -Dcoloniesalive.bullychance=N */
    private static final int    CHANCE         = Integer.getInteger("coloniesalive.bullychance", 90);
    private static final int    CHECK_INTERVAL = 20 * 10;
    private static final double SPOT_RADIUS    = 10;
    private static final int    APPROACH_MAX   = 20 * 15;
    private static final int    GLOAT_TICKS    = 50;
    private static final int    LEAVE_TICKS    = 20 * 8;
    private static final int    FIGHT_MAX      = 20 * 30;
    private static final int    SWING_PERIOD   = 16;
    /** Same bully again only after this long. */
    private static final int    COOLDOWN       = 20 * 60 * 10;
    /** And nobody bullies anybody in the same world for this long after one did. */
    private static final int    WORLD_COOLDOWN = 20 * 60 * 4;

    private static final Map<Mob, Bully> ACTIVE     = new com.google.common.collect.MapMaker().weakKeys().makeMap();
    private static final Map<Mob, Long>  NEXT_CHECK = new com.google.common.collect.MapMaker().weakKeys().makeMap();
    private static final Map<ServerLevel, Long> WORLD_NEXT = new com.google.common.collect.MapMaker().weakKeys().makeMap();
    private static final Random RAND = new Random();

    private final Mob           mob;
    private final EntityCitizen victim;
    private Phase               phase = Phase.APPROACH;
    private long                phaseStart;
    private long                nextSwing;
    private LivingEntity        opponent;

    private Bully(final Mob mob, final EntityCitizen victim)
    {
        this.mob = mob;
        this.victim = victim;
        this.phaseStart = mob.level().getGameTime();
    }

    public static boolean canBully(final Object entity)
    {
        return entity instanceof EntityMercenary || entity instanceof VisitorCitizen;
    }

    public static boolean isBullying(final Mob mob)
    {
        return ACTIVE.containsKey(mob);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Picking a victim
    // ------------------------------------------------------------------------------------------------------------------

    /** Every server tick of a visitor or mercenary. */
    public static void tick(final Mob mob)
    {
        final Bully active = ACTIVE.get(mob);
        if (active != null)
        {
            if (!active.tickActive())
            {
                ACTIVE.remove(mob);
            }
            return;
        }
        final long now = mob.level().getGameTime();
        final Long next = NEXT_CHECK.get(mob);
        if (next == null)
        {
            NEXT_CHECK.put(mob, now + CHECK_INTERVAL + RAND.nextInt(CHECK_INTERVAL));
            return;
        }
        if (now < next)
        {
            return;
        }
        NEXT_CHECK.put(mob, now + CHECK_INTERVAL);
        if (!(mob.level() instanceof ServerLevel level) || now < WORLD_NEXT.getOrDefault(level, 0L) || ChildLifeAI.frozen)
        {
            return;
        }
        if (!mob.isAlive() || mob.getVehicle() != null || mob.isSleeping() || mob.getTarget() != null || mob.isBaby())
        {
            return;
        }
        final EntityCitizen victim = pickVictim(mob);
        if (victim == null || RAND.nextInt(Math.max(1, CHANCE)) != 0)
        {
            return;
        }
        start(mob, victim);
    }

    /** Development: this bully goes for the closest child right away (-1 range: anywhere loaded). */
    public static boolean start(final Mob mob, final EntityCitizen victim)
    {
        if (ACTIVE.containsKey(mob) || victim == null)
        {
            return false;
        }
        if (mob.level() instanceof ServerLevel level)
        {
            WORLD_NEXT.put(level, mob.level().getGameTime() + WORLD_COOLDOWN);
        }
        NEXT_CHECK.put(mob, mob.level().getGameTime() + COOLDOWN);
        if (mob.getVehicle() != null)
        {
            mob.stopRiding();
        }
        mob.setTarget(null);
        ACTIVE.put(mob, new Bully(mob, victim));
        debug(mob.getName().getString() + " goes for " + victim.getName().getString());
        return true;
    }

    private static EntityCitizen pickVictim(final Mob mob)
    {
        EntityCitizen best = null;
        double bestDistance = SPOT_RADIUS * SPOT_RADIUS;
        for (final EntityCitizen child : mob.level().getEntitiesOfClass(EntityCitizen.class, mob.getBoundingBox().inflate(SPOT_RADIUS)))
        {
            if (!child.isBaby() || !child.isAlive() || GrabCarry.isHeld(child) || child.getCitizenSleepHandler().isAsleep() || child.isSleeping()
                  || School.inClass(child) || child.getHealth() < child.getMaxHealth() * 0.6f || !mob.hasLineOfSight(child))
            {
                continue;
            }
            final BabyCare carer = BabyCare.carerOf(child);
            if (carer != null && carer.isCarrying(child))
            {
                continue;
            }
            final double distance = child.distanceToSqr(mob);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = child;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------------------------------------------------------

    private long now()
    {
        return mob.level().getGameTime();
    }

    private void next(final Phase phase)
    {
        this.phase = phase;
        this.phaseStart = now();
    }

    /** @return false once it is over */
    private boolean tickActive()
    {
        if (!mob.isAlive() || mob.isRemoved())
        {
            return false;
        }
        final long t = now() - phaseStart;
        switch (phase)
        {
            case APPROACH ->
            {
                if (!victim.isAlive() || victim.level() != mob.level() || GrabCarry.isHeld(victim) || t > APPROACH_MAX
                      || victim.distanceToSqr(mob) > 24 * 24 || mob.getTarget() != null)
                {
                    debug(mob.getName().getString() + " lost interest");
                    return false;
                }
                if (inReach(mob, victim) && mob.hasLineOfSight(victim))
                {
                    mob.getNavigation().stop();
                    mob.getLookControl().setLookAt(victim, 60, 60);
                    strikeVictim();
                    next(Phase.GLOAT);
                }
                else if (t % 10 == 0)
                {
                    mob.getNavigation().moveTo(victim, 1.0);
                }
            }
            case GLOAT ->
            {
                // stands over it, smug, and laughs
                mob.getNavigation().stop();
                mob.getLookControl().setLookAt(victim, 30, 30);
                if (t == 12 || t == 30)
                {
                    sneer();
                }
                if (t >= GLOAT_TICKS)
                {
                    next(Phase.LEAVE);
                }
            }
            case FIGHT ->
            {
                if (opponent == null || !opponent.isAlive() || opponent.level() != mob.level() || t > FIGHT_MAX)
                {
                    next(Phase.LEAVE);
                    return true;
                }
                if (mob.getHealth() <= mob.getMaxHealth() * 0.4f)
                {
                    // beaten: runs off
                    debug(mob.getName().getString() + " lost the fight and runs");
                    Fx.angry(mob);
                    next(Phase.FLEE);
                    return true;
                }
                mob.getLookControl().setLookAt(opponent, 60, 60);
                if (inReach(mob, opponent) && mob.hasLineOfSight(opponent))
                {
                    mob.getNavigation().stop();
                    if (now() >= nextSwing)
                    {
                        nextSwing = now() + SWING_PERIOD + RAND.nextInt(6);
                        hit(mob, opponent, 2f, 0.45);
                    }
                }
                else if (t % 8 == 0)
                {
                    mob.getNavigation().moveTo(opponent, 1.15);
                }
            }
            case LEAVE, FLEE ->
            {
                if (t == 0 || mob.getNavigation().isDone())
                {
                    final LivingEntity from = opponent != null && opponent.isAlive() ? opponent : victim;
                    walkAway(from, phase == Phase.FLEE ? 1.35 : 0.9);
                }
                if (phase == Phase.FLEE && t % 15 == 0)
                {
                    Fx.angry(mob);
                }
                if (t >= LEAVE_TICKS)
                {
                    debug(mob.getName().getString() + " is gone");
                    return false;
                }
            }
        }
        return true;
    }

    private void strikeVictim()
    {
        // a baby is smaller and weaker: a shove more than a punch
        final boolean infant = com.aiko.coloniesalive.social.Family.isInfant(victim);
        if (victim.getVehicle() != null)
        {
            victim.stopRiding();
        }
        hit(mob, victim, infant ? 1f : 2f, infant ? 0.3 : 0.55);
        debug(mob.getName().getString() + " hit " + victim.getName().getString());
        Drama.bullied(victim, mob);
    }

    private void sneer()
    {
        mob.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PILLAGER_CELEBRATE, SoundSource.NEUTRAL, 0.8f, 1.1f + RAND.nextFloat() * 0.2f);
        Fx.angry(mob);
    }

    private void walkAway(final LivingEntity from, final double speed)
    {
        Vec3 away = from == null ? Vec3.ZERO : mob.position().subtract(from.position()).multiply(1, 0, 1);
        away = away.lengthSqr() < 1.0e-3 ? new Vec3(RAND.nextDouble() - 0.5, 0, RAND.nextDouble() - 0.5).normalize() : away.normalize();
        final BlockPos target = BlockPos.containing(mob.position().add(away.scale(14)));
        mob.getNavigation().moveTo(target.getX() + 0.5, mob.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ()),
          target.getZ() + 0.5, speed);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Called by Brawl
    // ------------------------------------------------------------------------------------------------------------------

    /** Someone stands up to it: it fights back. */
    public static void engage(final Mob mob, final LivingEntity opponent)
    {
        Bully bully = ACTIVE.get(mob);
        if (bully == null)
        {
            // already walking off: comes back for the fight
            bully = new Bully(mob, null);
            ACTIVE.put(mob, bully);
        }
        if (bully.phase == Phase.FLEE)
        {
            return;
        }
        bully.opponent = opponent;
        bully.nextSwing = bully.now() + 10;
        bully.next(Phase.FIGHT);
        mob.setTarget(null);
    }

    /** Its opponent gave up beaten: it gloats a moment and leaves. */
    public static void won(final Mob mob)
    {
        final Bully bully = ACTIVE.get(mob);
        if (bully != null && bully.phase == Phase.FIGHT)
        {
            bully.sneer();
            bully.next(Phase.LEAVE);
        }
    }

    public static boolean isBeaten(final Mob mob)
    {
        final Bully bully = ACTIVE.get(mob);
        return bully != null && bully.phase == Phase.FLEE || mob.getHealth() <= mob.getMaxHealth() * 0.4f;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Real hits
    // ------------------------------------------------------------------------------------------------------------------

    /** Close enough to land a hit: both bodies' half widths plus an arm. */
    public static boolean inReach(final LivingEntity attacker, final LivingEntity target)
    {
        final double reach = attacker.getBbWidth() * 0.5 + target.getBbWidth() * 0.5 + 1.0;
        return attacker.distanceToSqr(target) <= reach * reach && Math.abs(attacker.getY() - target.getY()) < 1.6;
    }

    /**
     * A real hit: arm swing, damage (red flash, hurt sound) and knockback away from the attacker; never the last two
     * hearts. The damage source has no attacker (see the class comment).
     */
    public static void hit(final LivingEntity attacker, final LivingEntity target, final float damage, final double knockback)
    {
        attacker.swing(InteractionHand.MAIN_HAND, true);
        final float allowed = Math.min(damage, target.getHealth() - 4f);
        if (allowed > 0)
        {
            // a fist fight, not a sword fight: armor doesn't soak it up and the hurt cooldown doesn't swallow it
            target.invulnerableTime = 0;
            final float before = target.getHealth();
            final boolean landed = target.hurt(attacker.level().damageSources().source(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL), allowed);
            if (ChildLifeAI.DEBUG)
            {
                debug(attacker.getName().getString() + " hits " + target.getName().getString() + ": " + landed + " "
                        + String.format("%.1f -> %.1f / %.1f", before, target.getHealth(), target.getMaxHealth()));
            }
        }
        else
        {
            // can't take any more: only the shove and the thud
            target.hurtMarked = true;
        }
        target.knockback(knockback, attacker.getX() - target.getX(), attacker.getZ() - target.getZ());
        attacker.level().playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.NEUTRAL, 0.7f, 0.9f + RAND.nextFloat() * 0.3f);
    }

    static void debug(final String message)
    {
        ColoniesAlive.LOGGER.info("[bully] {}", message);
    }
}
