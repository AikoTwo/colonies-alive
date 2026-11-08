package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ChildAnimation;
import com.aiko.coloniesalive.network.ChildAnimPayload;
import com.aiko.coloniesalive.social.Social;
import com.minecolonies.api.entity.ai.IStateAI;
import com.minecolonies.api.entity.ai.statemachine.AIEventTarget;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIBlockingEventType;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.other.SittingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.Random;

/**
 * A grown-up standing up for a child that was hit by a bully ({@link Bully}). One instance per citizen; only acts while
 * grown up.
 * <ul>
 *     <li><b>Defend</b>: a guard or a relative saw it happen. It shouts, runs at the bully and they fight for real: both
 *     have to get close (in sight) to land a hit, every hit hurts and knocks back. The bully runs off once beaten; a
 *     grown-up that takes too much gives up (the bully sneers and leaves). Nobody dies.</li>
 *     <li><b>Listen</b>: the child ran crying to tell mum or dad. The grown-up stops, listens, gets furious: if the
 *     bully is still around it goes for it, else it comforts the child.</li>
 *     <li><b>Comfort</b>: afterwards it goes to the child and hugs it (a baby is picked up and rocked, see
 *     {@link BabyCare}).</li>
 * </ul>
 */
public class Brawl implements IStateAI
{
    public enum State implements IAIState
    {
        BRAWLING;

        @Override
        public boolean isOkayToEat()
        {
            return false;
        }
    }

    private enum Phase
    {
        LISTEN,
        CHARGE,
        FIGHT,
        COMFORT_GO,
        HUG
    }

    private static final int    LISTEN_TICKS  = 20 * 5;
    private static final int    CHARGE_MAX    = 20 * 30;
    private static final int    FIGHT_MAX     = 20 * 35;
    private static final int    COMFORT_MAX   = 20 * 40;
    private static final int    HUG_TICKS     = 20 * 5;
    private static final int    SWING_PERIOD  = 14;
    /** Goes after a bully it was told about only this close. */
    private static final double HUNT_RADIUS   = 48;

    private static final Map<EntityCitizen, Brawl> ALL = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    private final EntityCitizen adult;
    private final Random        rand = new Random();

    private Phase         pendingPhase;
    private Mob           pendingBully;
    private EntityCitizen pendingChild;

    private Phase         phase;
    private Mob           bully;
    private EntityCitizen child;
    private long          phaseStart;
    private long          nextSwing;
    private boolean       lookLocked;
    private boolean       lost;

    public Brawl(final EntityCitizen adult)
    {
        this.adult = adult;
        ALL.put(adult, this);
        adult.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldStart, this::begin, 1));
        adult.getCitizenAI().addTransition(new AITarget(State.BRAWLING, this::tick, 1));
    }

    private long now()
    {
        return adult.level().getGameTime();
    }

    public static boolean isBusy(final EntityCitizen adult)
    {
        final Brawl brawl = ALL.get(adult);
        return brawl != null && (brawl.phase != null || brawl.pendingPhase != null);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Asking (Drama)
    // ------------------------------------------------------------------------------------------------------------------

    /** Saw the child get hit: goes for the bully right away. @return false if it can't */
    public static boolean defend(final EntityCitizen adult, final Mob bully, final EntityCitizen child)
    {
        final Brawl brawl = ALL.get(adult);
        if (brawl == null || !brawl.canHelp(false))
        {
            return false;
        }
        brawl.ask(Phase.CHARGE, bully, child);
        // the shout, before even running
        Fx.angry(adult);
        Fx.angry(adult);
        Fx.grumble(adult);
        return true;
    }

    /** The child came crying to tell. It even gets out of bed for it. @return false if it can't listen now */
    public static boolean hearComplaint(final EntityCitizen adult, final EntityCitizen child, final Mob bully)
    {
        final Brawl brawl = ALL.get(adult);
        if (brawl == null || !brawl.canHelp(true))
        {
            return false;
        }
        brawl.ask(Phase.LISTEN, bully, child);
        return true;
    }

    private boolean canHelp(final boolean evenAsleep)
    {
        if (phase != null || pendingPhase != null || !adult.isAlive() || adult.isBaby() || adult.getCitizenData() == null
              || (!evenAsleep && (adult.isSleeping() || adult.getCitizenSleepHandler().isAsleep())))
        {
            return false;
        }
        final IState state = adult.getCitizenAI().getState();
        return state != CitizenAIState.SICK && state != CitizenAIState.MOURN && state != CitizenAIState.FLEE
                 && !adult.getCitizenData().getCitizenDiseaseHandler().isSick();
    }

    private void ask(final Phase first, final Mob who, final EntityCitizen kid)
    {
        pendingPhase = first;
        pendingBully = who;
        pendingChild = kid;
    }

    // ------------------------------------------------------------------------------------------------------------------

    private boolean shouldStart()
    {
        return pendingPhase != null && adult.getCitizenAI().getState() != State.BRAWLING;
    }

    private IState begin()
    {
        phase = pendingPhase;
        bully = pendingBully;
        child = pendingChild;
        pendingPhase = null;
        pendingBully = null;
        pendingChild = null;
        lost = false;
        if (adult.getVehicle() instanceof SittingEntity)
        {
            adult.stopRiding();
        }
        if (adult.getCitizenSleepHandler().isAsleep() || adult.isSleeping())
        {
            adult.getCitizenSleepHandler().onWakeUp();
            if (adult.isSleeping())
            {
                adult.stopSleeping();
            }
            debug(adult.getName().getString() + " is woken up by " + (child == null ? "?" : child.getName().getString()));
        }
        adult.getNavigation().stop();
        phaseStart = now();
        if (phase == Phase.CHARGE)
        {
            startCharge();
        }
        else if (phase == Phase.LISTEN)
        {
            broadcast(adult, ChildAnimation.LISTEN, LISTEN_TICKS);
        }
        return State.BRAWLING;
    }

    private void next(final Phase phase)
    {
        this.phase = phase;
        this.phaseStart = now();
        Moves.reset(adult);
    }

    private IState tick()
    {
        if (phase == null || !adult.isAlive())
        {
            return finish();
        }
        final long t = now() - phaseStart;
        switch (phase)
        {
            case LISTEN ->
            {
                adult.getNavigation().stop();
                if (child != null && child.isAlive())
                {
                    Fx.face(adult, child);
                }
                if (t == LISTEN_TICKS / 2 || t == LISTEN_TICKS - 10)
                {
                    // getting angrier and angrier
                    Fx.angry(adult);
                }
                if (t >= LISTEN_TICKS)
                {
                    if (bullyNearby(HUNT_RADIUS))
                    {
                        Fx.grumble(adult);
                        Fx.angry(adult);
                        startCharge();
                    }
                    else
                    {
                        next(Phase.COMFORT_GO);
                    }
                }
            }
            case CHARGE ->
            {
                if (!bullyNearby(HUNT_RADIUS) || t > CHARGE_MAX || Bully.isBeaten(bully))
                {
                    next(Phase.COMFORT_GO);
                    return State.BRAWLING;
                }
                if (Bully.inReach(adult, bully) && adult.hasLineOfSight(bully))
                {
                    adult.getNavigation().stop();
                    nextSwing = now() + 4;
                    next(Phase.FIGHT);
                    debug(adult.getName().getString() + " fights " + bully.getName().getString());
                }
                else
                {
                    Moves.follow(adult, bully, 1.4);
                }
            }
            case FIGHT ->
            {
                if (!bullyNearby(32) || Bully.isBeaten(bully))
                {
                    // won: a triumphant shake of the fist, then to the child
                    debug(adult.getName().getString() + " won against " + (bully == null ? "?" : bully.getName().getString()));
                    broadcast(adult, ChildAnimation.HOP_JOY, 30);
                    Fx.happy(adult, 6);
                    next(Phase.COMFORT_GO);
                    return State.BRAWLING;
                }
                if (adult.getHealth() <= adult.getMaxHealth() * 0.35f || t > FIGHT_MAX)
                {
                    // can't take any more: backs off, beaten
                    debug(adult.getName().getString() + " gives up the fight");
                    lost = true;
                    Bully.won(bully);
                    broadcast(adult, ChildAnimation.SAD, 60);
                    Social.addMood(adult, -2f);
                    next(Phase.COMFORT_GO);
                    return State.BRAWLING;
                }
                Fx.face(adult, bully);
                if (Bully.inReach(adult, bully) && adult.hasLineOfSight(bully))
                {
                    adult.getNavigation().stop();
                    if (now() >= nextSwing)
                    {
                        nextSwing = now() + SWING_PERIOD + rand.nextInt(5);
                        Bully.hit(adult, bully, ChildLifeEvents.isGuard(adult) ? 4f : 3f, 0.5);
                        if (rand.nextInt(3) == 0)
                        {
                            Fx.angry(adult);
                        }
                    }
                }
                else
                {
                    Moves.follow(adult, bully, 1.3);
                }
            }
            case COMFORT_GO ->
            {
                if (child == null || !child.isAlive() || child.level() != adult.level() || GrabCarry.isHeld(child) || t > COMFORT_MAX
                      || child.distanceToSqr(adult) > 64 * 64)
                {
                    return finish();
                }
                if (com.aiko.coloniesalive.social.Family.isInfant(child))
                {
                    // a baby is picked up and rocked (BabyCare takes over once this is over)
                    final EntityCitizen baby = child;
                    final IState done = finish();
                    BabyCare.comfortBy(adult, baby);
                    return done;
                }
                if (adult.distanceToSqr(child) <= 1.8 * 1.8)
                {
                    adult.getNavigation().stop();
                    Fx.face(adult, child);
                    broadcast(adult, ChildAnimation.HUG, HUG_TICKS + 10);
                    final ChildLifeAI ai = ChildLifeEvents.get(child);
                    if (ai != null)
                    {
                        ai.comforted(adult, HUG_TICKS);
                    }
                    next(Phase.HUG);
                }
                else
                {
                    Moves.follow(adult, child, lost ? 0.9 : 1.15);
                }
            }
            case HUG ->
            {
                adult.getNavigation().stop();
                if (child != null && child.isAlive())
                {
                    Fx.face(adult, child);
                }
                if (t % 20 == 10 && child != null)
                {
                    Fx.hearts(child, 2);
                }
                if (t >= HUG_TICKS)
                {
                    return finish();
                }
            }
        }
        return State.BRAWLING;
    }

    private void startCharge()
    {
        next(Phase.CHARGE);
        if (bully != null && bully.isAlive())
        {
            Bully.engage(bully, adult);
        }
    }

    private boolean bullyNearby(final double radius)
    {
        return bully != null && bully.isAlive() && !bully.isRemoved() && bully.level() == adult.level() && bully.distanceToSqr(adult) < radius * radius;
    }

    private IState finish()
    {
        phase = null;
        bully = null;
        child = null;
        adult.getNavigation().stop();
        Moves.reset(adult);
        broadcast(adult, ChildAnimation.NONE, 0);
        return CitizenAIState.IDLE;
    }

    /** Runs after the grown-up's entity tick. */
    public void tickEffects()
    {
        final boolean active = phase != null && adult.getCitizenAI().getState() == State.BRAWLING;
        if (active != lookLocked)
        {
            lookLocked = active;
            if (active)
            {
                adult.goalSelector.disableControlFlag(Goal.Flag.LOOK);
            }
            else
            {
                adult.goalSelector.enableControlFlag(Goal.Flag.LOOK);
            }
        }
        // MineColonies took the citizen elsewhere in the middle of it
        if (phase != null && adult.getCitizenAI().getState() != State.BRAWLING && pendingPhase == null)
        {
            debug(adult.getName().getString() + " was taken over by MineColonies: " + adult.getCitizenAI().getState());
            phase = null;
            bully = null;
            child = null;
            broadcast(adult, ChildAnimation.NONE, 0);
        }
    }

    private static void broadcast(final EntityCitizen who, final ChildAnimation animation, final int duration)
    {
        PacketDistributor.sendToPlayersTrackingEntity(who, new ChildAnimPayload(who.getId(), animation.id(), duration));
    }

    private static void debug(final String message)
    {
        Bully.debug(message);
    }
}
