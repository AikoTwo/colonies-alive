package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ChildAnimation;
import com.aiko.coloniesalive.network.ChildAnimPayload;
import com.aiko.coloniesalive.social.Social;
import com.aiko.coloniesalive.social.Topics;
import com.minecolonies.api.entity.ai.IStateAI;
import com.minecolonies.api.entity.ai.statemachine.AIEventTarget;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIBlockingEventType;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.core.entity.ai.minimal.EntityAICitizenChild;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.other.SittingEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * Gives MineColonies children a life: from IDLE a child picks something to do, either alone ({@link ChildActivity}),
 * with the world around it (animals, flowers, water, music) or a game together with other children ({@link ChildPlaySession}).
 * It hooks into the citizen's own state machine; the chosen pose is mirrored to tracking clients.
 */
public class ChildLifeAI implements IStateAI
{
    public enum State implements IAIState
    {
        ACTING;

        @Override
        public boolean isOkayToEat()
        {
            return true;
        }
    }

    /** Pause between two activities, in ticks. Short on purpose: children are rarely still. */
    private static final int MIN_COOLDOWN = 20 * 3;
    private static final int MAX_COOLDOWN = 20 * 10;

    /** Duration sent for poses that last until the activity says otherwise. */
    public static final int OPEN_ENDED = 20 * 120;

    /** Development: stops all children from starting activities (used by the screenshot showcase). */
    public static volatile boolean frozen;

    private static final int GROUP_CHANCE = 60;
    private static final int WORLD_CHANCE = 40;
    private static final int JUKEBOX_CHANCE = 80;

    private final EntityCitizen child;
    private final Random        rand = new Random();

    private long nextStart;

    private ChildActivity activity;

    /** Session this child takes part in, if any. */
    private ChildPlaySession session;
    private boolean          sessionLeader;

    /** Invitation by another child that has not been answered yet. */
    private ChildPlaySession invitation;

    /** Last pose sent, so players that start tracking the child later can be told. */
    private ChildAnimation currentAnim = ChildAnimation.NONE;
    private long           animUntil;

    /** Crying after getting hurt (or put down after a scary pick-up). */
    private long cryUntil;
    private long nextSob;

    /** Something (getting hurt, picked up) wants the current activity to stop right away. */
    private boolean interrupt;

    /** Whether the citizen's own look goals are switched off right now. */
    private boolean lookLocked;

    /** Development: -Dcoloniesalive.nolooklock=true keeps the look goals on, to compare head shaking with and without. */
    private static final boolean NO_LOOK_LOCK = Boolean.getBoolean("coloniesalive.nolooklock");

    /** Watching MineColonies' own "visit a hut" state, which leaves children standing at the hut block for minutes. */
    private long visitSeen = -1;

    /**
     * MineColonies' own hut visits are cut right away (our explore is short and ends in a cheer); its "follow someone
     * around" is allowed a little while.
     */
    private static final int MAX_VISIT     = 20;
    private static final int MAX_FOLLOWING = 20 * 10;

    /** After walking over to a friend, the next activity is a game together. */
    private boolean wantsCompany;

    /** School attendance, punishment and class poses. */
    private final ChildSchool school = new ChildSchool(this);
    private long nextRecessTry;

    /** The citizen AI our transitions were added to (MineColonies may make a new one, then they are added again). */
    private Object registeredOn;

    public ChildLifeAI(final EntityCitizen child)
    {
        this.child = child;
        nextStart = child.level().getGameTime() + 20 * 3 + rand.nextInt(20 * 6);
        registerTransitions();
    }

    /** Adds our transitions to the citizen's state machine again if MineColonies replaced it since. */
    private void checkTransitions()
    {
        if (child.getCitizenAI() != null && child.getCitizenAI() != registeredOn)
        {
            debug("citizen AI was replaced: transitions added again");
            registerTransitions();
        }
    }

    private void registerTransitions()
    {
        registeredOn = child.getCitizenAI();
        if (registeredOn == null)
        {
            return;
        }
        // Event targets are evaluated before the state specific ones, so we are not shadowed by the built-in IDLE transitions.
        child.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldBeEscorted, this::startEscorted, 3));
        child.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldReclaimBaby, this::reclaimBaby, 1));
        child.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldCry, this::startCrying, 5));
        child.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldRevenge, this::startRevenge, 2));
        child.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldTell, this::startTell, 2));
        child.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldCutVisit, this::cutVisit, 20));
        child.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldStartRecessGame, this::startRecessGame, 40));
        child.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldStart, this::start, 40));
        // a baby is never left standing around between two things: checked every tick
        child.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, () -> isInfant() && shouldStart(), this::start, 1));
        // safety net: a baby left out of its own life for a while (some state of MineColonies we don't handle) gets back
        child.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::babyStuck, this::unstickBaby, 20));
        child.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldAcceptInvitation, this::acceptInvitation, 10));
        child.getCitizenAI().addTransition(new AITarget(State.ACTING, this::tickActivity, 2));
    }

    public EntityCitizen child()
    {
        return child;
    }

    public Random rand()
    {
        return rand;
    }

    public long now()
    {
        return child.level().getGameTime();
    }

    public boolean isActing()
    {
        return child.getCitizenAI().getState() == State.ACTING;
    }

    /**
     * @return whether this child is free to be invited to a game right now
     */
    public boolean canBeInvited()
    {
        return child.isAlive()
                 && !isCrying()
                 && !GrabCarry.isHeld(child)
                 && child.isBaby()
                 && child.getCitizenData() != null
                 && session == null
                 && invitation == null
                 && child.getVehicle() == null
                 && child.getCitizenAI().getState() == CitizenAIState.IDLE
                 && !School.inClass(child)
                 && !school.isGrounded()
                 && !isInfant();
    }

    /** A baby (see {@link com.aiko.coloniesalive.social.Family}): no games with the others, no school, just baby things. */
    public boolean isInfant()
    {
        return com.aiko.coloniesalive.social.Family.isInfant(child);
    }

    /** An older child is playing peekaboo with this baby until then. */
    private EntityCitizen cheerKid;
    private long          cheerUntil;

    /**
     * An older child came to play with this baby (see {@link Activities#visitBaby}): it drops what it was doing, sits
     * facing the child and claps and laughs.
     *
     * @return false if this is no baby or it is busy with MineColonies (sleeping, eating...)
     */
    public boolean cheerBaby(final EntityCitizen kid, final int ticks)
    {
        if (!child.isBaby() || !isInfant() || !(isActing() || child.getCitizenAI().getState() == CitizenAIState.IDLE) || isCrying() || GrabCarry.isHeld(child))
        {
            return false;
        }
        final boolean already = cheeringKid() == kid;
        cheerKid = kid;
        cheerUntil = now() + ticks;
        if (!already && (activity != null || session != null))
        {
            interrupt = true;
        }
        nextStart = now();
        return true;
    }

    /** Development/tests: the activity to start next, instead of choosing one. */
    private Function<ChildLifeAI, ChildActivity> testNext;

    public void testNext(final Function<ChildLifeAI, ChildActivity> next)
    {
        testNext = next;
        nextStart = now();
        if (activity != null || session != null)
        {
            interrupt = true;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // A baby's night and its health (see InfantLife.bedtime / sickRest and BabyCare)
    // ------------------------------------------------------------------------------------------------------------------

    public enum BabySleep
    {
        AWAKE,
        IN_BED,
        ON_FLOOR
    }

    BabySleep babySleep = BabySleep.AWAKE;

    /** A baby's night: from dusk until just before sunrise. */
    public boolean isBabyNight()
    {
        final long time = child.level().getDayTime() % 24000;
        return time >= 12500 && time < 23400;
    }

    /** Sick, or hurt badly enough that MineColonies would send it to the hospital. */
    public boolean isBabyUnwell()
    {
        final com.minecolonies.api.colony.ICitizenData data = child.getCitizenData();
        return data != null && (data.getCitizenDiseaseHandler().isSick() || data.getCitizenDiseaseHandler().isHurt());
    }

    /** Called by the grown-up who carried it: lying in its bed now, or (no bed) asleep where it was put down. */
    void babyPutToBed(final boolean inBed)
    {
        if (inBed)
        {
            babySleep = BabySleep.IN_BED;
            // no more "held" arms: lying in bed
            broadcast(ChildAnimation.NONE, 0);
        }
        else
        {
            sleepOnFloor();
        }
    }

    /** Falls asleep right where it is, lying on the floor. */
    void sleepOnFloor()
    {
        InfantLife.standUp(child);
        child.getNavigation().stop();
        child.setPose(net.minecraft.world.entity.Pose.SLEEPING);
        babySleep = BabySleep.ON_FLOOR;
        broadcast(ChildAnimation.NONE, 0);
        debug("falls asleep on the floor");
    }

    /** Wakes up (morning, or got hit). */
    void wakeBaby()
    {
        if (babySleep == BabySleep.ON_FLOOR && child.getPose() == net.minecraft.world.entity.Pose.SLEEPING)
        {
            child.setPose(net.minecraft.world.entity.Pose.STANDING);
        }
        if (child.getCitizenSleepHandler().isAsleep())
        {
            child.getCitizenSleepHandler().onWakeUp();
        }
        babySleep = BabySleep.AWAKE;
    }

    /**
     * MineColonies sends citizens off to bed at night, to the hospital when sick or hurt and to eat when hungry. A baby
     * does none of that on its own legs: we take it back (a grown-up carries it to bed, the healer comes to it, it gets
     * fed where it is).
     */
    private boolean shouldReclaimBaby()
    {
        if (!child.isBaby() || !isInfant() || GrabCarry.isHeld(child) || frozen)
        {
            return false;
        }
        final BabyCare carer = BabyCare.carerOf(child);
        if (carer != null && carer.isCarrying(child))
        {
            return false;
        }
        final IState state = child.getCitizenAI().getState();
        return state == CitizenAIState.SLEEP || state == CitizenAIState.SICK || state == CitizenAIState.EATING;
    }

    private IState reclaimBaby()
    {
        if (child.getCitizenAI().getState() == CitizenAIState.EATING && child.getCitizenData() != null)
        {
            // fed by the family, no walking to a restaurant
            child.getCitizenData().increaseSaturation(10);
        }
        child.getNavigation().stop();
        return nextBabyThing();
    }

    /** Since when the baby is out of its own activities without a reason we know of (-1: it isn't). */
    private long babyIdleSince = -1;
    private static final int BABY_STUCK_TICKS = 20 * 5;

    private boolean babyStuck()
    {
        final BabyCare carer = BabyCare.carerOf(child);
        final boolean excused = !child.isBaby() || !isInfant() || isActing() || frozen || isCrying() || GrabCarry.isHeld(child)
                                  || (carer != null && carer.isCarrying(child)) || child.getCitizenData() == null || child.isNoAi()
                                  || child.getCitizenSleepHandler().isAsleep();
        if (excused)
        {
            babyIdleSince = -1;
            return false;
        }
        if (babyIdleSince < 0)
        {
            babyIdleSince = now();
        }
        return now() - babyIdleSince > BABY_STUCK_TICKS;
    }

    private IState unstickBaby()
    {
        com.aiko.coloniesalive.ColoniesAlive.LOGGER.info("[child] baby {} was stuck ({}), back to its own life: {}",
          child.getName().getString(), now() - babyIdleSince, whyNotStarting());
        babyIdleSince = -1;
        child.getNavigation().stop();
        if (child.getVehicle() != null && !(child.getVehicle() instanceof SittingEntity))
        {
            child.stopRiding();
        }
        return nextBabyThing();
    }

    /** After trying to walk the baby plops down: the next thing it does is sit. Also the very first thing after birth. */
    private boolean babySitNext = true;

    void babySitNext()
    {
        babySitNext = true;
    }

    boolean takeBabySitNext()
    {
        final boolean sit = babySitNext;
        babySitNext = false;
        return sit;
    }

    EntityCitizen cheeringKid()
    {
        return cheerKid != null && cheerKid.isAlive() && now() < cheerUntil ? cheerKid : null;
    }

    /** Free to be played with by an older child. */
    public boolean canBeCheered()
    {
        return child.isAlive() && child.isBaby() && isInfant() && !isCrying() && !GrabCarry.isHeld(child) && cheeringKid() == null
                 && !isBabyNight() && babySleep == BabySleep.AWAKE && !isBabyUnwell() && BabyCare.carerOf(child) == null
                 && (isActing() || child.getCitizenAI().getState() == CitizenAIState.IDLE);
    }

    /** Free to join a game at recess, in the school yard of {@code leader}'s school. */
    public boolean canJoinRecess(final EntityCitizen leader)
    {
        return child.isAlive()
                 && !isCrying()
                 && !GrabCarry.isHeld(child)
                 && session == null
                 && invitation == null
                 && child.getVehicle() == null
                 && School.inClass(child)
                 && School.inRecess(child)
                 && School.sameSchool(child, leader)
                 && child.getCitizenAI().getState() == CitizenAIState.WORKING
                 && !isInfant();
    }

    public boolean isTruantToday()
    {
        return school.isTruantToday();
    }

    /** Development/test: this child skips school today. */
    public void forceTruantToday()
    {
        school.forceTruantToday();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Recess at school
    // ------------------------------------------------------------------------------------------------------------------

    private boolean shouldStartRecessGame()
    {
        return !frozen
                 && session == null
                 && invitation == null
                 && now() >= nextRecessTry
                 && child.getVehicle() == null
                 && School.inClass(child)
                 && School.inRecess(child)
                 && child.getCitizenAI().getState() == CitizenAIState.WORKING
                 && !isInfant();
    }

    private IState startRecessGame()
    {
        nextRecessTry = now() + 20 * 5;
        final ChildPlaySession created = ChildPlaySession.tryCreateRecess(this);
        if (created == null)
        {
            return child.getCitizenAI().getState();
        }
        activity = null;
        session = created;
        sessionLeader = true;
        child.getNavigation().stop();
        debug("recess game: " + created.describe());
        return State.ACTING;
    }

    public void invite(final ChildPlaySession invitation)
    {
        this.invitation = invitation;
    }

    public void cancelInvitation(final ChildPlaySession from)
    {
        if (invitation == from)
        {
            invitation = null;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Crying
    // ------------------------------------------------------------------------------------------------------------------

    public boolean isCrying()
    {
        return now() < cryUntil;
    }

    /** Got hit: stop whatever it was doing and cry for a while. Hit by a player, it gets back at them afterwards. */
    public void onHurt(final net.minecraft.world.entity.Entity attacker)
    {
        if (!child.isBaby())
        {
            return;
        }
        final boolean wasRevenging = activity instanceof ChildRevenge;
        if (activity instanceof ChildRevenge revenge)
        {
            revenge.giveUp();
        }
        startCryingFor(100 + rand.nextInt(60));
        debug("got hurt and cries");

        if (attacker instanceof net.minecraft.world.entity.player.Player player && !player.isSpectator() && !isInfant())
        {
            if (wasRevenging || now() - revengeEnded < REVENGE_COOLDOWN)
            {
                // hit again while (or right after) getting back at them: gives up, only cries
                revengeOn = null;
            }
            else
            {
                revengeOn = player.getUUID();
                revengeSince = now();
                revengeResumes = 0;
            }
            return;
        }
        if (rand.nextInt(5) < 2)
        {
            Topics.raise(child, Topics.Topic.HURT);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Getting back at a player who hit it (see ChildRevenge)
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * How long the child still goes after the player once it stops crying. Counted from the end of the tears, not from
     * the hit: MineColonies first makes it flee, and the tears plus the flight used to eat the whole window.
     */
    private static final int REVENGE_WINDOW   = 20 * 20;
    /** Hit again this soon after getting back at them: no second round, only tears. */
    private static final int REVENGE_COOLDOWN = 20 * 10;

    private java.util.UUID revengeOn;
    private long           revengeSince;
    private long           revengeEnded = -REVENGE_COOLDOWN;
    /** Times the current revenge was picked up again after MineColonies cut it off. */
    private int            revengeResumes;
    private static final int MAX_REVENGE_RESUMES = 3;

    private boolean shouldRevenge()
    {
        if (revengeOn == null)
        {
            return false;
        }
        if (now() - Math.max(revengeSince, cryUntil) > REVENGE_WINDOW || !child.isBaby())
        {
            debug("revenge window over in state " + child.getCitizenAI().getState());
            revengeOn = null;
            return false;
        }
        return !frozen
                 && !isCrying()
                 && !GrabCarry.isHeld(child)
                 && child.getVehicle() == null
                 && SchoolEscort.fetcherOf(child) == null
                 && canBreakOffForRevenge(child.getCitizenAI().getState());
    }

    /**
     * States the child may leave to go after the player. Before, only IDLE counted, but after a hit MineColonies puts it
     * in FLEE (and its own child AI may start a hut visit or follow someone right after): the revenge then often never
     * started. Sleeping, eating, sick or in class it still stays put.
     */
    private boolean canBreakOffForRevenge(final IState state)
    {
        return state == CitizenAIState.IDLE
                 || state == CitizenAIState.FLEE
                 || state instanceof com.minecolonies.core.entity.ai.minimal.EntityAICitizenAvoidEntity.FleeStates
                 || state instanceof EntityAICitizenChild.State
                 || (state == State.ACTING && session == null && !(activity instanceof ChildRevenge) && !(activity instanceof Escorted)
                       && !school.isGrounded());
    }

    private IState startRevenge()
    {
        cleanUp();
        activity = new ChildRevenge(revengeOn);
        revengeOn = null;
        session = null;
        child.getNavigation().stop();
        debug("goes after the player who hit it");
        return State.ACTING;
    }

    void revengeOver()
    {
        revengeEnded = now();
    }

    /** Development/test: forgets any pending or running revenge, so the next hit by a player starts a fresh one. */
    public void forgetRevenge()
    {
        revengeOn = null;
        if (activity instanceof ChildRevenge)
        {
            cleanUp(); // the state machine leaves ACTING on its next tick (no activity left)
        }
        revengeEnded = -REVENGE_COOLDOWN;
        cryUntil = 0;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Hit by a bully (see Bully, Drama, TellParent, Brawl)
    // ------------------------------------------------------------------------------------------------------------------

    /** The bully to tell mum or dad about once the first tears are over (null: nothing to tell). */
    private net.minecraft.world.entity.Mob tellOn;
    private long                           tellSince;
    private static final int               TELL_WINDOW = 20 * 60;

    /** A bullied baby wails on and on until someone comes to pick it up (or this long at most). */
    private boolean wailing;
    private long    wailUntil;
    private long    nextComfortCall;
    private long    bulliedAt = Long.MIN_VALUE / 2;
    private static final int WAIL_MAX = 20 * 90;

    /** A grown-up came to hug it: when, and the hug to show next. */
    private long          comfortedAt = Long.MIN_VALUE;
    private EntityCitizen hugBy;
    private long          hugUntil;

    /**
     * Hit by a bully: heartbroken (deep in a bad mood: walks hunched for minutes). A child cries on the spot and then runs
     * to tell (unless a relative saw it and is already dealing with it); a baby can't run anywhere: it wails and wails,
     * and the family comes to pick it up.
     */
    public void onBullied(final net.minecraft.world.entity.Mob bully, final boolean familySaw)
    {
        if (!child.isBaby())
        {
            return;
        }
        Social.addMood(child, -6f);
        bulliedAt = now();
        if (isInfant())
        {
            wailing = true;
            nextComfortCall = now() + 20 * 5;
            wailUntil = now() + WAIL_MAX;
            startCryingFor(20 * 25);
            Fx.cry(child);
            if (!familySaw && BabyCare.carerOf(child) == null)
            {
                BabyCare.requestComfort(child);
            }
            debug("was hit by a bully and wails");
            return;
        }
        // stunned, sobbing on the spot first
        startCryingFor(50 + rand.nextInt(20));
        if (!familySaw)
        {
            tellOn = bully;
            tellSince = now();
        }
        debug("was hit by a bully" + (familySaw ? ", family saw it" : ", will tell"));
    }

    /** A grown-up hugs it (or rocks it, a baby): the tears stop and it feels a lot better. */
    public void comforted(final EntityCitizen by, final int ticks)
    {
        comfortedAt = now();
        wailing = false;
        tellOn = null;
        cryUntil = now();
        Social.addMood(child, 4f);
        Fx.hearts(child, 4);
        if (isInfant())
        {
            return;
        }
        hugBy = by;
        hugUntil = now() + ticks;
        nextStart = now();
        if (activity != null || session != null)
        {
            interrupt = true;
        }
        broadcast(ChildAnimation.HUG, ticks);
    }

    public boolean wasComfortedSince(final long time)
    {
        return comfortedAt >= time;
    }

    /** Hit by a bully a moment ago: still crying, about to tell, telling, or being hugged. */
    public boolean isUpset()
    {
        return tellOn != null || activity instanceof TellParent || hugBy != null || (isCrying() && now() - bulliedAt < 20 * 60)
                 || now() - bulliedAt < 20 * 20;
    }

    private boolean shouldTell()
    {
        if (tellOn == null)
        {
            return false;
        }
        if (now() - tellSince > TELL_WINDOW || !child.isBaby() || isInfant())
        {
            tellOn = null;
            return false;
        }
        return !frozen && !isCrying() && !GrabCarry.isHeld(child) && child.getVehicle() == null && SchoolEscort.fetcherOf(child) == null
                 && (canBreakOffForRevenge(child.getCitizenAI().getState()) || (isActing() && activity != null && !(activity instanceof TellParent)
                                                                                   && !(activity instanceof Escorted)));
    }

    private IState startTell()
    {
        cleanUp();
        activity = new TellParent(tellOn);
        tellOn = null;
        session = null;
        child.getNavigation().stop();
        return State.ACTING;
    }

    /** Hugged by a grown-up: stands in its arms a moment, sniffling, hearts. */
    private ChildActivity hugged(final EntityCitizen by, final long until)
    {
        return (ai, time) -> {
            child.getNavigation().stop();
            if (by.isAlive())
            {
                Fx.face(child, by);
            }
            if (time % 20 == 0)
            {
                Fx.hearts(child, 1);
            }
            if (time >= until)
            {
                Fx.giggle(child);
                return false;
            }
            return true;
        };
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Fetched to school by a parent or teacher (see SchoolEscort)
    // ------------------------------------------------------------------------------------------------------------------

    /** Marks the activity of waiting for / being carried by the adult. */
    private final class Escorted implements ChildActivity
    {
        @Override
        public boolean tick(final ChildLifeAI ai, final long time)
        {
            final SchoolEscort escort = SchoolEscort.fetcherOf(child);
            if (escort == null)
            {
                return false;
            }
            if (!escort.isCarrying(child))
            {
                child.getNavigation().stop();
                Fx.face(child, escort.adult());
            }
            return true;
        }
    }

    private boolean shouldBeEscorted()
    {
        final SchoolEscort escort = SchoolEscort.fetcherOf(child);
        return escort != null && escort.isCatching(child) && !(activity instanceof Escorted) && !GrabCarry.isHeld(child) && !isUpset();
    }

    private IState startEscorted()
    {
        cleanUp();
        cryUntil = 0;
        if (child.getVehicle() != null)
        {
            child.stopRiding();
        }
        child.getNavigation().stop();
        activity = new Escorted();
        // caught: looking at the feet, hands behind the back (or grumpy in the adult's arms)
        final SchoolEscort escort = SchoolEscort.fetcherOf(child);
        broadcast(escort != null && escort.isCarrying(child) ? ChildAnimation.GRAB_GRUMPY : ChildAnimation.SORRY, OPEN_ENDED);
        debug("caught out of school");
        return State.ACTING;
    }

    /** The adult tells it off: no more skipping today, and no punishment at home on top. */
    void onScolded()
    {
        school.caught();
        broadcast(ChildAnimation.SORRY, OPEN_ENDED);
    }

    void onEscortOver()
    {
        if (activity instanceof Escorted)
        {
            interrupt = true;
        }
    }

    public void startCryingFor(final int ticks)
    {
        final long now = now();
        final boolean already = now < cryUntil;
        cryUntil = Math.max(cryUntil, now + ticks);
        broadcast(ChildAnimation.CRY, (int) (cryUntil - now));
        if (!already)
        {
            Fx.cry(child);
            nextSob = now + 45 + rand.nextInt(30);
        }
        if (activity != null || session != null)
        {
            interrupt = true;
        }
    }

    private boolean shouldCry()
    {
        if (!isCrying() || GrabCarry.isHeld(child))
        {
            return false;
        }
        final IState state = child.getCitizenAI().getState();
        if (state == CitizenAIState.IDLE)
        {
            return true;
        }
        // a hurt baby doesn't run away (MineColonies makes citizens flee from whoever hit them): it sits and cries
        return isInfant() && !(activity instanceof BabyCry)
                 && (state == CitizenAIState.FLEE || state instanceof com.minecolonies.core.entity.ai.minimal.EntityAICitizenAvoidEntity.FleeStates
                       || state == State.ACTING || state instanceof EntityAICitizenChild.State);
    }

    /** Marks a baby crying on its bottom. */
    private interface BabyCry extends ChildActivity {}

    private IState startCrying()
    {
        child.getNavigation().stop();
        if (isInfant())
        {
            if (activity != null && !(activity instanceof BabyCry))
            {
                activity.stop(this);
            }
            // a hit wakes it up, the only thing that does
            wakeBaby();
            session = null;
            InfantLife.sitDown(child, (int) Math.max(40, cryUntil - now() + 20));
            broadcast(ChildAnimation.CRY, (int) Math.max(1, cryUntil - now()));
            activity = (BabyCry) (ai, time) -> {
                child.getNavigation().stop();
                // couldn't sit down yet (still landing, seat not free yet): keeps trying, it cries on its bottom
                final BabyCare holder = BabyCare.carerOf(child);
                if (child.getVehicle() == null && child.onGround() && (holder == null || !holder.isCarrying(child)))
                {
                    InfantLife.sitDown(child, (int) Math.max(40, cryUntil - time + 20));
                }
                if (time >= cryUntil)
                {
                    babySitNext();
                    return false;
                }
                return true;
            };
            return State.ACTING;
        }
        activity = (ai, time) -> {
            child.getNavigation().stop();
            return time < cryUntil;
        };
        return State.ACTING;
    }

    /** Where the baby was last tick (null: not tracked). */
    private net.minecraft.world.phys.Vec3 babyLastPos;

    /**
     * MineColonies moves citizens around on its own: out of bed onto a "spawn point" (can be outside the house), along the
     * path when it thinks they are stuck, out of a wall. A baby can't get anywhere fast, so a jump of more than a couple
     * of blocks that we didn't make (arms, bed) is undone: back where it was, or the closest free spot next to that.
     */
    private void guardBabyPosition()
    {
        final BabyCare carer = BabyCare.carerOf(child);
        if (!child.isBaby() || !isInfant() || GrabCarry.isHeld(child) || (carer != null && carer.isCarrying(child)) || child.isNoAi()
              || (child.getVehicle() != null && !(child.getVehicle() instanceof SittingEntity)))
        {
            babyLastPos = null;
            return;
        }
        if (child.getCitizenSleepHandler().isAsleep())
        {
            // in bed: waking up, MineColonies puts it on a "spawn point" that may be outside; it gets up on the bed instead
            babyLastPos = child.position();
            return;
        }
        final net.minecraft.world.phys.Vec3 now = child.position();
        final net.minecraft.world.phys.Vec3 before = babyLastPos;
        babyLastPos = now;
        // MineColonies' own little moves are short; a long jump is meant (recall to the town hall, a command): left alone
        final double jump = before == null ? 0 : before.distanceToSqr(now);
        if (before == null || jump < 2.5 * 2.5 || jump > 16 * 16 || child.getVehicle() != null)
        {
            return;
        }
        final net.minecraft.world.phys.Vec3 back = freeSpotNear(before);
        if (back != null)
        {
            child.getNavigation().stop();
            child.teleportTo(back.x, back.y, back.z);
            babyLastPos = back;
            debug("jumped " + String.format("%.1f", Math.sqrt(before.distanceToSqr(now))) + " blocks (state " + child.getCitizenAI().getState() + "): put back");
        }
    }

    /** {@code pos} if the baby fits there and has a floor under it, else the closest such spot within 2 blocks, or null. */
    private net.minecraft.world.phys.Vec3 freeSpotNear(final net.minecraft.world.phys.Vec3 pos)
    {
        if (fitsAt(pos))
        {
            return pos;
        }
        final net.minecraft.core.BlockPos center = net.minecraft.core.BlockPos.containing(pos);
        net.minecraft.world.phys.Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final net.minecraft.core.BlockPos candidate : net.minecraft.core.BlockPos.betweenClosed(center.offset(-2, -1, -2), center.offset(2, 1, 2)))
        {
            final net.minecraft.world.phys.Vec3 spot = net.minecraft.world.phys.Vec3.atBottomCenterOf(candidate);
            final double distance = spot.distanceToSqr(pos);
            if (distance < bestDistance && fitsAt(spot))
            {
                best = spot;
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean fitsAt(final net.minecraft.world.phys.Vec3 pos)
    {
        final net.minecraft.world.phys.AABB body = child.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(pos);
        return child.level().noCollision(child, body) && !child.level().noCollision(child, body.move(0, -0.6, 0))
                 && !child.level().containsAnyLiquid(body);
    }

    /** A player put this baby down: it sits again right away. */
    void babyPutDown()
    {
        babySitNext = true;
        broadcast(ChildAnimation.NONE, 0);
    }

    /**
     * Called every server tick: tears and sobs while crying, whatever state the citizen is in
     * (when hurt by a monster MineColonies makes it flee, and it flees crying).
     */
    public void tickEffects()
    {
        checkTransitions();
        guardBabyPosition();
        // While we are in charge of the child, MineColonies' "look at player / citizen / anything" goals are switched off:
        // they aimed the head somewhere else every tick and it flicked between both targets.
        final boolean inControl = !NO_LOOK_LOCK && child.isBaby() && (isActing() || GrabCarry.isHeld(child) || isCrying());
        if (inControl != lookLocked)
        {
            lookLocked = inControl;
            if (inControl)
            {
                child.goalSelector.disableControlFlag(Goal.Flag.LOOK);
            }
            else
            {
                child.goalSelector.enableControlFlag(Goal.Flag.LOOK);
            }
        }

        smoothHead(inControl && !GrabCarry.isHeld(child));

        // MineColonies may switch the child to something else in the middle of an activity (school, eating, ...):
        // clean up what the activity left behind (item in hand, the game for the others)
        if (!isActing() && (activity != null || session != null))
        {
            debug("MineColonies took over: state " + child.getCitizenAI().getState());
            // MineColonies' decideAiTask forces its own state over ours whenever its plan changes (e.g. the flight after
            // the hit is over): a revenge cut off like that is picked up again, a few times at most
            final java.util.UUID resume = activity instanceof ChildRevenge revenge && revenge.unfinished() && revengeResumes < MAX_REVENGE_RESUMES
                                            ? revenge.playerId() : null;
            cleanUp();
            final IState takenBy = child.getCitizenAI().getState();
            if (isInfant() && takenBy != CitizenAIState.FLEE && !(takenBy instanceof com.minecolonies.core.entity.ai.minimal.EntityAICitizenAvoidEntity.FleeStates))
            {
                // MineColonies needs the baby (sleep, food...): off the floor. Not for running away: a baby doesn't, it
                // stays on its bottom and cries (see shouldCry)
                InfantLife.standUp(child);
            }
            if (resume != null)
            {
                revengeResumes++;
                revengeOn = resume;
                revengeSince = now();
                revengeEnded = -REVENGE_COOLDOWN;
                debug("revenge cut off, will pick it up again");
            }
        }

        school.tick();

        final long now = now();
        if (wailing)
        {
            // a bullied baby: wails on until someone picks it up and rocks it (BabyCare), or it runs out of tears
            final BabyCare wailCarer = BabyCare.carerOf(child);
            if (now >= wailUntil || !child.isBaby() || !isInfant())
            {
                wailing = false;
            }
            else
            {
                if (cryUntil - now < 30 && !GrabCarry.isHeld(child) && (wailCarer == null || !wailCarer.isCarrying(child)))
                {
                    startCryingFor(20 * 6);
                }
                // nobody coming yet (all busy): keeps calling until someone of the family is free
                if (wailCarer == null && now >= nextComfortCall && !GrabCarry.isHeld(child))
                {
                    nextComfortCall = now + 20 * 5;
                    if (BabyCare.requestComfort(child))
                    {
                        debug("someone of the family is coming to comfort it");
                    }
                }
            }
        }
        if (now < cryUntil)
        {
            if (now % (wailing ? 3 : 5) == 0)
            {
                Fx.tears(child);
            }
            if (now >= nextSob)
            {
                Fx.cry(child);
                // a wailing baby barely catches its breath between sobs
                nextSob = now + (wailing ? 22 + rand.nextInt(15) : 45 + rand.nextInt(30));
            }
        }
    }

    /** Head yaw after smoothing, NaN when not smoothing. */
    private float smoothedHead = Float.NaN;

    /** Turns smaller than this (degrees per tick) are ignored as noise. */
    private static final float HEAD_DEADBAND = 6f;

    /**
     * Runs after the entity tick, so it has the last word on the head yaw for this tick: small back-and-forth turns are
     * ignored and bigger ones are eased in, so the head turns calmly instead of twitching.
     */
    private void smoothHead(final boolean active)
    {
        if (!active)
        {
            smoothedHead = Float.NaN;
            return;
        }
        final float target = child.getYHeadRot();
        if (Float.isNaN(smoothedHead))
        {
            smoothedHead = target;
            return;
        }
        final float delta = net.minecraft.util.Mth.wrapDegrees(target - smoothedHead);
        if (Math.abs(delta) >= HEAD_DEADBAND)
        {
            smoothedHead = net.minecraft.util.Mth.wrapDegrees(smoothedHead + net.minecraft.util.Mth.clamp(delta * 0.5f, -20f, 20f));
        }
        child.setYHeadRot(smoothedHead);
    }

    /** Reached a friend (see {@link Activities#seekFriend}): play together next, and soon. */
    public void wantCompany()
    {
        wantsCompany = true;
        nextStart = now() + 10;
    }

    /** Picked up by a player: drop the current activity. */
    public void onGrabbed()
    {
        if (isInfant())
        {
            wakeBaby();
        }
        if (activity != null || session != null)
        {
            interrupt = true;
        }
        child.getNavigation().stop();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Hut visits of the built-in child AI
    // ------------------------------------------------------------------------------------------------------------------

    private boolean shouldCutVisit()
    {
        final IState state = child.getCitizenAI().getState();
        final boolean infant = child.isBaby() && isInfant();
        final int limit = state == EntityAICitizenChild.State.VISITING ? (infant ? 0 : MAX_VISIT)
                            : state == EntityAICitizenChild.State.FOLLOWING ? (infant ? 0 : MAX_FOLLOWING) : -1;
        if (limit < 0)
        {
            visitSeen = -1;
            return false;
        }
        final long now = now();
        if (visitSeen < 0)
        {
            visitSeen = now;
        }
        return now - visitSeen > limit;
    }

    private IState cutVisit()
    {
        debug("stops " + child.getCitizenAI().getState() + " (MineColonies)");
        child.getNavigation().stop();
        visitSeen = -1;
        nextStart = now();
        return CitizenAIState.IDLE;
    }

    // ------------------------------------------------------------------------------------------------------------------

    /** Development: why {@link #shouldStart} says no. */
    public String whyNotStarting()
    {
        return "frozen=" + frozen + " crying=" + isCrying() + " held=" + GrabCarry.isHeld(child) + " baby=" + child.isBaby()
                 + " data=" + (child.getCitizenData() != null) + " state=" + child.getCitizenAI().getState() + " invite=" + (invitation != null)
                 + " vehicle=" + child.getVehicle() + " onGround=" + child.onGround() + " inClass=" + School.inClass(child) + " infant=" + isInfant()
                 + " ai=" + System.identityHashCode(child.getCitizenAI());
    }

    private boolean shouldStart()
    {
        return !frozen
                 && !isCrying()
                 && !GrabCarry.isHeld(child)
                 && child.isBaby()
                 && child.getCitizenData() != null
                 && child.getCitizenAI().getState() == CitizenAIState.IDLE
                 && invitation == null
                 && (child.getVehicle() == null ? child.onGround() : child.getVehicle() instanceof SittingEntity && isInfant())
                 && !School.inClass(child)
                 // a baby is never left standing around: it goes straight back to sitting (or whatever comes next)
                 && (isInfant() || hugBy != null || (now() >= nextStart && rand.nextInt(2) == 0));
    }

    private IState start()
    {
        activity = null;
        session = null;
        child.getNavigation().stop();

        // development: a test asked for this activity next
        if (testNext != null)
        {
            activity = testNext.apply(this);
            testNext = null;
            if (activity != null)
            {
                return State.ACTING;
            }
        }

        // a grown-up came to hug it after a bully hit it
        if (hugBy != null)
        {
            final EntityCitizen by = hugBy;
            hugBy = null;
            if (now() < hugUntil && !isInfant())
            {
                activity = hugged(by, hugUntil);
                return State.ACTING;
            }
        }

        // a baby only sits, claps and toddles around near home
        if (isInfant())
        {
            activity = InfantLife.next(this);
            return activity == null ? CitizenAIState.IDLE : State.ACTING;
        }

        // skipped school today: punishment first, then grounded (no games with the others)
        if (school.needsPunishment())
        {
            activity = school.punishment();
            return State.ACTING;
        }
        if (school.isGrounded())
        {
            activity = rand.nextBoolean() ? Activities.sad(this) : Activities.daydream(this);
            if (activity == null)
            {
                activity = Activities.sad(this);
            }
            return State.ACTING;
        }

        // Music playing nearby beats everything else
        if (rand.nextInt(100) < JUKEBOX_CHANCE)
        {
            activity = Activities.jukeboxDance(this);
        }

        // A sad child often just mopes around, and may want to tell the player
        final float mood = Social.mood(child);
        if (activity == null && mood < -3 && rand.nextBoolean())
        {
            activity = rand.nextBoolean() ? Activities.daydream(this) : null;
            if (activity == null)
            {
                activity = Activities.sad(this);
            }
            if (mood < -5 && rand.nextInt(3) == 0)
            {
                Topics.raise(child, Topics.Topic.SAD);
            }
        }

        final int roll = rand.nextInt(100);
        // happy children seek company more often; one that just walked over to a friend always does
        final int groupChance = wantsCompany ? 100 : GROUP_CHANCE + (mood > 3 ? 15 : 0) - (mood < -3 ? 20 : 0);
        wantsCompany = false;
        if (activity == null && roll < groupChance)
        {
            final ChildPlaySession created = ChildPlaySession.tryCreate(this);
            if (created != null)
            {
                session = created;
                sessionLeader = true;
                debug("invites friends to " + created.describe());
                return State.ACTING;
            }
            // nobody close enough: go find someone
            activity = Activities.seekFriend(this);
        }

        if (activity == null && roll < groupChance + WORLD_CHANCE)
        {
            activity = firstAvailable(List.of(
              Activities::seekFriend, Activities::explore, Activities::animals, Activities::flowers, Activities::water, Activities::wave,
              Activities::visitBaby));
        }

        if (activity == null)
        {
            activity = firstAvailable(List.of(
              Activities::hop, Activities::spin, Activities::daydream, Activities::crouchLook, Activities::soloDance, Activities::seekFriend));
        }

        if (activity != null)
        {
            debug("starts " + activity.getClass().getName().replace("com.aiko.coloniesalive.server.", ""));
        }
        return activity == null ? CitizenAIState.IDLE : State.ACTING;
    }

    /** Development log, enabled with -Dcoloniesalive.debug=true. */
    public static final boolean DEBUG = Boolean.getBoolean("coloniesalive.debug");

    public void debug(final String message)
    {
        if (DEBUG)
        {
            com.aiko.coloniesalive.ColoniesAlive.LOGGER.info("[child {} #{}] {}", child.getName().getString(), child.getId(), message);
        }
    }

    /** Tries the options in random order and returns the first one that is possible here. */
    private ChildActivity firstAvailable(final List<Function<ChildLifeAI, ChildActivity>> options)
    {
        final List<Function<ChildLifeAI, ChildActivity>> shuffled = new ArrayList<>(options);
        Collections.shuffle(shuffled, rand);
        for (final Function<ChildLifeAI, ChildActivity> option : shuffled)
        {
            final ChildActivity created = option.apply(this);
            if (created != null)
            {
                return created;
            }
        }
        return null;
    }

    private boolean shouldAcceptInvitation()
    {
        if (invitation == null)
        {
            return false;
        }
        final IState state = child.getCitizenAI().getState();
        return invitation.isRecess() ? state == CitizenAIState.WORKING && School.inRecess(child) : state == CitizenAIState.IDLE;
    }

    private IState acceptInvitation()
    {
        final ChildPlaySession accepted = invitation;
        invitation = null;
        if (accepted.isOver() || !child.isBaby() || !accepted.onJoined(this))
        {
            return child.getCitizenAI().getState();
        }

        session = accepted;
        sessionLeader = false;
        activity = null;
        child.getNavigation().stop();
        return State.ACTING;
    }

    private IState tickActivity()
    {
        if (frozen || interrupt)
        {
            interrupt = false;
            if (!frozen && session == null && child.isBaby() && isInfant())
            {
                return nextBabyThing();
            }
            return finish();
        }

        // a baby goes straight from one thing to the next, without standing around in between
        if (session == null && activity != null && child.isBaby() && isInfant())
        {
            return activity.tick(this, now()) ? State.ACTING : nextBabyThing();
        }

        if (session != null)
        {
            if (sessionLeader)
            {
                session.tick();
            }
            return session.isOver() || !session.isMember(this) ? finish() : State.ACTING;
        }

        if (activity == null || !child.isBaby() || !activity.tick(this, now()))
        {
            return finish();
        }
        return State.ACTING;
    }

    /** Ends the baby's current activity (keeping its seat and its pose until the next one shows) and starts the next. */
    private IState nextBabyThing()
    {
        if (activity != null)
        {
            activity.stop(this);
            activity = null;
        }
        if (GrabCarry.isHeld(child))
        {
            // in someone's arms: nothing to do until put down (then it sits again)
            babySitNext = true;
            activity = (ai, time) -> GrabCarry.isHeld(child);
            return State.ACTING;
        }
        if (isCrying())
        {
            return startCrying();
        }
        if (testNext != null)
        {
            activity = testNext.apply(this);
            testNext = null;
            if (activity != null)
            {
                return State.ACTING;
            }
        }
        activity = InfantLife.next(this);
        return activity == null ? finish() : State.ACTING;
    }

    private IState finish()
    {
        cleanUp();
        // a baby stays sitting: whatever comes next starts from the floor (crawling and walking get up themselves)
        if (child.getVehicle() instanceof SittingEntity && !isInfant())
        {
            child.stopRiding();
            child.setPos(child.getX(), child.getY() + 1, child.getZ());
        }
        // right after finding a friend, no pause: the game should start while they are still together
        nextStart = wantsCompany ? now() + 10 : now() + MIN_COOLDOWN + rand.nextInt(MAX_COOLDOWN - MIN_COOLDOWN);
        return CitizenAIState.IDLE;
    }

    /** Ends the activity or game (items, the other players, the pose), without touching the citizen's state. */
    private void cleanUp()
    {
        if (activity != null)
        {
            activity.stop(this);
            activity = null;
        }

        if (session != null)
        {
            if (sessionLeader)
            {
                session.end();
            }
            else
            {
                session.leave(this);
            }
            session = null;
            child.getNavigation().stop();
        }

        // crying and being held have their own pose, which must survive the activity ending
        if (!isCrying() && !GrabCarry.isHeld(child))
        {
            broadcast(ChildAnimation.NONE, 0);
        }
    }

    public ChildAnimation currentAnimation()
    {
        return now() < animUntil ? currentAnim : ChildAnimation.NONE;
    }

    /** Shows a pose until the activity changes it. */
    public void play(final ChildAnimation animation)
    {
        broadcast(animation, OPEN_ENDED);
    }

    public void broadcast(final ChildAnimation animation, final int duration)
    {
        if (animation == currentAnim && animation == ChildAnimation.NONE)
        {
            return;
        }
        debug("pose " + animation);
        currentAnim = animation;
        animUntil = now() + duration;
        PacketDistributor.sendToPlayersTrackingEntity(child, new ChildAnimPayload(child.getId(), animation.id(), duration));
    }

    /** Tells a player who just started seeing this child what it is doing. */
    public void syncTo(final ServerPlayer player)
    {
        final long remaining = animUntil - now();
        if (currentAnim != ChildAnimation.NONE && remaining > 0)
        {
            PacketDistributor.sendToPlayer(player, new ChildAnimPayload(child.getId(), currentAnim.id(), (int) remaining));
        }
    }
}
