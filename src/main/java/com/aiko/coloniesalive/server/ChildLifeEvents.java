package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.social.Social;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public class ChildLifeEvents
{
    /** Citizens that already got their ChildLifeAI. */
    private static final Map<EntityCitizen, ChildLifeAI> ATTACHED = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    /**
     * Every citizen gets the AI (children grow up and, in principle, can also become children again);
     * the AI itself only acts while the citizen is a child.
     */
    @SubscribeEvent
    public static void onJoin(final EntityJoinLevelEvent event)
    {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof EntityCitizen citizen))
        {
            return;
        }

        GrabCarry.fixOrphan(citizen);
        ATTACHED.computeIfAbsent(citizen, ChildLifeAI::new);
        ADULTS.computeIfAbsent(citizen, AdultSocialAI::new);
        ESCORTS.computeIfAbsent(citizen, SchoolEscort::new);
        CARE.computeIfAbsent(citizen, BabyCare::new);
        BRAWLS.computeIfAbsent(citizen, Brawl::new);
    }

    /** Every grown-up can stand up for a child that a bully hit. */
    private static final Map<EntityCitizen, Brawl> BRAWLS = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    /** Every citizen can look after a baby (bedtime, healer) or go home to give birth (only acts while grown up). */
    private static final Map<EntityCitizen, BabyCare> CARE = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    /** Every citizen can fetch a pupil to school (only acts while grown up). */
    private static final Map<EntityCitizen, SchoolEscort> ESCORTS = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    /** The adult side of every citizen (only acts while grown up). */
    private static final Map<EntityCitizen, AdultSocialAI> ADULTS = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    /** Closest other adult with free time nearby, or null. */
    public static AdultSocialAI findFreeAdult(final AdultSocialAI seeker, final double radius)
    {
        final EntityCitizen self = seeker.citizen();
        AdultSocialAI best = null;
        double bestDistance = radius * radius;
        synchronized (ADULTS)
        {
            for (final AdultSocialAI other : ADULTS.values())
            {
                final EntityCitizen them = other.citizen();
                if (other == seeker || them.level() != self.level() || !other.isAvailable()
                      || them.getCitizenColonyHandler().getColonyId() != self.getCitizenColonyHandler().getColonyId())
                {
                    continue;
                }
                final double distance = them.distanceToSqr(self);
                if (distance < bestDistance)
                {
                    bestDistance = distance;
                    best = other;
                }
            }
        }
        return best;
    }

    /** A child that gets hurt cries. */
    @SubscribeEvent
    public static void onDamage(final LivingDamageEvent.Post event)
    {
        if (!(event.getEntity() instanceof EntityCitizen citizen) || citizen.level().isClientSide())
        {
            return;
        }
        // nobody likes being hit: by a player it costs a lot of trust. Guards (knights, rangers, druids...) getting hit
        // by monsters is just their job: that doesn't make them sad
        final boolean byPlayer = event.getSource().getEntity() instanceof Player;
        // (and a grown-up fighting a bully is furious, not sad)
        if (byPlayer || (!isGuard(citizen) && !Brawl.isBusy(citizen)))
        {
            Social.addMood(citizen, -3f);
        }
        if (event.getSource().getEntity() instanceof Player player)
        {
            Social.addAffinity(citizen, player, -15);
            if (!citizen.isBaby() && !player.isSpectator())
            {
                // an adult gets cross and wants an apology later
                Fx.angry(citizen);
                com.aiko.coloniesalive.social.Topics.raiseNow(citizen, com.aiko.coloniesalive.social.Topics.Topic.HIT_BY_YOU);
            }
        }
        if (citizen.isBaby())
        {
            final ChildLifeAI ai = ATTACHED.get(citizen);
            if (ai != null)
            {
                ai.debug("damage " + event.getSource().getMsgId() + " " + event.getNewDamage() + " from " + event.getSource().getEntity());
                ai.onHurt(event.getSource().getEntity());
            }
        }
    }

    /** Part of the colony's protection (every MineColonies guard job: knight, ranger, druid, cavalry...). */
    public static boolean isGuard(final EntityCitizen citizen)
    {
        return citizen.getCitizenData() != null && citizen.getCitizenData().getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobGuard<?>;
    }

    @SubscribeEvent
    public static void onEntityTick(final EntityTickEvent.Post event)
    {
        if (Bully.canBully(event.getEntity()) && event.getEntity() instanceof net.minecraft.world.entity.Mob mob && !mob.level().isClientSide())
        {
            Bully.tick(mob);
            return;
        }
        if (event.getEntity() instanceof EntityCitizen citizen && !citizen.level().isClientSide())
        {
            final ChildLifeAI ai = ATTACHED.get(citizen);
            if (ai != null)
            {
                ai.tickEffects();
            }
            final AdultSocialAI adult = ADULTS.get(citizen);
            if (adult != null)
            {
                adult.tickEffects();
            }
            Gloom.tick(citizen);
            final SchoolEscort escort = ESCORTS.get(citizen);
            if (escort != null)
            {
                escort.tickEffects();
            }
            final BabyCare care = CARE.get(citizen);
            if (care != null)
            {
                care.tickEffects();
            }
            final Brawl brawl = BRAWLS.get(citizen);
            if (brawl != null)
            {
                brawl.tickEffects();
            }
            if (citizen.isBaby())
            {
                SchoolEscort.checkPupil(citizen);
                SchoolLunch.tick(citizen);
            }
            com.aiko.coloniesalive.social.SmallTalk.tick(citizen);
            com.aiko.coloniesalive.social.Family.tick(citizen);
            GrowUp.tick(citizen);
        }
    }

    /** A player that comes close to a child in the middle of an activity sees the right pose too. */
    @SubscribeEvent
    public static void onStartTracking(final PlayerEvent.StartTracking event)
    {
        if (event.getTarget() instanceof EntityCitizen citizen && event.getEntity() instanceof ServerPlayer player)
        {
            final ChildLifeAI ai = ATTACHED.get(citizen);
            if (ai != null)
            {
                ai.syncTo(player);
            }
            Gloom.syncTo(citizen, player);
            com.aiko.coloniesalive.social.Family.syncTo(citizen, player);
        }
    }

    /**
     * Finds the closest free children of the same colony nearby, to play with.
     *
     * @return up to {@code max} playmates, closest first
     */
    public static List<ChildLifeAI> findPlayMates(final ChildLifeAI seeker, final int max)
    {
        final EntityCitizen self = seeker.child();
        final double maxDistance = ChildPlaySession.SEARCH_RADIUS * ChildPlaySession.SEARCH_RADIUS;
        final List<ChildLifeAI> found = new ArrayList<>();

        synchronized (ATTACHED)
        {
            for (final ChildLifeAI other : ATTACHED.values())
            {
                final EntityCitizen candidate = other.child();
                if (other != seeker
                      && candidate.level() == self.level()
                      && other.canBeInvited()
                      && candidate.getCitizenColonyHandler().getColonyId() == self.getCitizenColonyHandler().getColonyId()
                      && candidate.distanceToSqr(self) < maxDistance
                      && !Social.hasGrudge(self, candidate))
                {
                    found.add(other);
                }
            }
        }

        // friends first: each friendship point counts like being a bit closer
        found.sort(Comparator.comparingDouble(o -> Math.sqrt(o.child().distanceToSqr(self)) - Social.friendship(self, o.child()) * 0.1));
        return found.size() > max ? new ArrayList<>(found.subList(0, max)) : found;
    }

    /** Pupils of the same school on recess nearby, closest first. */
    public static List<ChildLifeAI> findRecessMates(final ChildLifeAI seeker, final int max)
    {
        final EntityCitizen self = seeker.child();
        final List<ChildLifeAI> found = new ArrayList<>();
        synchronized (ATTACHED)
        {
            for (final ChildLifeAI other : ATTACHED.values())
            {
                if (other != seeker && other.child().level() == self.level() && other.child().distanceToSqr(self) < 16 * 16 && other.canJoinRecess(self))
                {
                    found.add(other);
                }
            }
        }
        found.sort(Comparator.comparingDouble(o -> o.child().distanceToSqr(self)));
        return found.size() > max ? new ArrayList<>(found.subList(0, max)) : found;
    }

    /** The closest other child of the same colony (busy or not), or null. */
    public static EntityCitizen closestOtherChild(final ChildLifeAI seeker, final double radius)
    {
        final EntityCitizen self = seeker.child();
        EntityCitizen best = null;
        double bestDistance = radius * radius;
        synchronized (ATTACHED)
        {
            for (final ChildLifeAI other : ATTACHED.values())
            {
                final EntityCitizen candidate = other.child();
                if (other == seeker || !candidate.isAlive() || !candidate.isBaby() || candidate.level() != self.level()
                      || candidate.getCitizenColonyHandler().getColonyId() != self.getCitizenColonyHandler().getColonyId()
                      || Social.hasGrudge(self, candidate))
                {
                    continue;
                }
                final double distance = candidate.distanceToSqr(self);
                if (distance < bestDistance)
                {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** The closest baby of the same colony that can be played with right now, or null. */
    public static ChildLifeAI closestBaby(final ChildLifeAI seeker, final double radius)
    {
        final EntityCitizen self = seeker.child();
        ChildLifeAI best = null;
        double bestDistance = radius * radius;
        synchronized (ATTACHED)
        {
            for (final ChildLifeAI other : ATTACHED.values())
            {
                final EntityCitizen baby = other.child();
                if (other == seeker || baby.level() != self.level() || !other.canBeCheered()
                      || baby.getCitizenColonyHandler().getColonyId() != self.getCitizenColonyHandler().getColonyId())
                {
                    continue;
                }
                final double distance = baby.distanceToSqr(self);
                if (distance < bestDistance)
                {
                    bestDistance = distance;
                    best = other;
                }
            }
        }
        return best;
    }

    /** Debug/showcase access. */
    public static ChildLifeAI get(final EntityCitizen citizen)
    {
        return ATTACHED.get(citizen);
    }
}
