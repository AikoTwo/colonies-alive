package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ChildAnimation;
import com.aiko.coloniesalive.network.ChildAnimPayload;
import com.aiko.coloniesalive.social.Social;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.ai.IStateAI;
import com.minecolonies.api.entity.ai.statemachine.AIEventTarget;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIBlockingEventType;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.core.colony.jobs.JobTeacher;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.other.SittingEntity;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A pupil who should be in class but is out in town (skipping school, wandering off, stuck on the way) gets fetched:
 * a parent notices, walks over, gives it a telling-off for a few seconds, picks it up and carries it to school. A child
 * without parents around (none, or asleep, sick...) is fetched by a teacher of its school.
 *
 * <p>One instance per citizen; it only acts while grown up. The pupil's side is in {@link ChildLifeAI} (it stops and
 * waits once the adult is close, and is held while carried).
 */
public class SchoolEscort implements IStateAI
{
    public enum State implements IAIState
    {
        ESCORTING;

        @Override
        public boolean isOkayToEat()
        {
            return false;
        }
    }

    private enum Phase
    {
        GO,
        SCOLD,
        CARRY
    }

    /** Class starts at 6:00; from 7:00 on a pupil who isn't at school is missed. */
    private static final long CLASS_SETTLED = 1000;
    /** How long a pupil may be away before someone goes for it (on the way to school, or doing something else). */
    private static final int  GRACE_WORKING = 20 * 60;
    private static final int  GRACE_OTHER   = 20 * 20;
    /** After a fetch failed, leave the child alone for a while. */
    private static final int  RETRY_AFTER   = 20 * 120;
    private static final int  GO_TIMEOUT    = 20 * 60;
    private static final int  CARRY_TIMEOUT = 20 * 120;
    private static final double MAX_FETCH_DISTANCE = 128;
    /** The child notices the adult coming and stops. */
    public static final double NOTICE_DISTANCE = 8;
    private static final double REACH_SQR = 2.2 * 2.2;

    /** Development: -Dcoloniesalive.anyescort=true lets any free adult fetch a pupil (test worlds without teacher). */
    private static final boolean ANY_ADULT = Boolean.getBoolean("coloniesalive.anyescort");

    private static final Map<EntityCitizen, Long>          MISSING_SINCE = new com.google.common.collect.MapMaker().weakKeys().makeMap();
    private static final Map<EntityCitizen, Long>          RETRY_AT      = new com.google.common.collect.MapMaker().weakKeys().makeMap();
    private static final Map<EntityCitizen, SchoolEscort>  FETCHER_OF    = new com.google.common.collect.MapMaker().weakKeys().makeMap();
    private static final Map<EntityCitizen, SchoolEscort>  ALL           = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    private final EntityCitizen adult;

    /** Child it was asked to fetch, picked up by the next state check. */
    private EntityCitizen pending;
    private EntityCitizen child;
    private Phase         phase;
    private long          phaseStart;
    private int           scoldTicks;
    private boolean       lookLocked;

    public SchoolEscort(final EntityCitizen adult)
    {
        this.adult = adult;
        ALL.put(adult, this);
        adult.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldStart, this::begin, 20));
        adult.getCitizenAI().addTransition(new AITarget(State.ESCORTING, this::tick, 1));
    }

    private long now()
    {
        return adult.level().getGameTime();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Noticing a missing pupil (called for every child, see ChildLifeEvents)
    // ------------------------------------------------------------------------------------------------------------------

    public static void checkPupil(final EntityCitizen kid)
    {
        if ((kid.tickCount + kid.getId()) % 40 != 0)
        {
            return;
        }
        if (!isMissing(kid))
        {
            MISSING_SINCE.remove(kid);
            return;
        }
        final long now = kid.level().getGameTime();
        final long since = MISSING_SINCE.computeIfAbsent(kid, k -> now);
        final int grace = kid.getCitizenAI().getState() == CitizenAIState.WORK ? GRACE_WORKING : GRACE_OTHER;
        if (now - since < grace || FETCHER_OF.containsKey(kid) || now < RETRY_AT.getOrDefault(kid, 0L))
        {
            return;
        }
        final SchoolEscort fetcher = findFetcher(kid);
        if (fetcher == null)
        {
            if (ChildLifeAI.DEBUG)
            {
                final StringBuilder why = new StringBuilder();
                for (final ICitizenData other : kid.getCitizenData().getColony().getCitizenManager().getCitizens())
                {
                    final boolean parent = other.getName().equals(kid.getCitizenData().getParents().getA()) || other.getName().equals(kid.getCitizenData().getParents().getB());
                    if (parent || other.getJob() instanceof JobTeacher)
                    {
                        final EntityCitizen entity = other.getEntity().orElse(null) instanceof EntityCitizen e ? e : null;
                        final SchoolEscort escort = entity == null ? null : ALL.get(entity);
                        why.append(String.format(" %s(%s loaded=%s free=%s state=%s school=%s)", other.getName(), parent ? "parent" : "teacher", entity != null,
                          escort != null && escort.canGo(), entity == null ? "-" : entity.getCitizenAI().getState(), other.getWorkBuilding() == kid.getCitizenData().getWorkBuilding()));
                    }
                }
                debug(kid, "is missed, nobody can come. parents '" + kid.getCitizenData().getParents().getA() + "' '" + kid.getCitizenData().getParents().getB() + "':" + why);
            }
            RETRY_AT.put(kid, now + RETRY_AFTER);
            return;
        }
        fetcher.pending = kid;
        FETCHER_OF.put(kid, fetcher);
        debug(kid, "is missed at school; " + fetcher.adult.getName().getString() + " goes to get it");
    }

    /** Should be in class, but isn't in the school building (and isn't eating, sick, mourning...). */
    private static boolean isMissing(final EntityCitizen kid)
    {
        if (!School.isPupil(kid) || !kid.isAlive() || kid.isSleeping() || GrabCarry.isHeld(kid)
              || kid.level().getDayTime() % 24000 < CLASS_SETTLED || !School.isSchoolTime(kid))
        {
            return false;
        }
        // grieving children stay home (MineColonies), and the sick ones go to the hospital
        if (kid.getCitizenData().getCitizenMournHandler().isMourning() || kid.getCitizenData().getCitizenDiseaseHandler().isSick())
        {
            return false;
        }
        // just hit by a bully: crying, running to tell, being hugged... nobody drags it to class right now
        final ChildLifeAI ai = ChildLifeEvents.get(kid);
        if (ai != null && ai.isUpset())
        {
            return false;
        }
        final IBuilding school = kid.getCitizenData().getWorkBuilding();
        if (school == null || school.isInBuilding(kid.blockPosition()))
        {
            return false;
        }
        final IState state = kid.getCitizenAI().getState();
        return state != CitizenAIState.SICK && state != CitizenAIState.MOURN && state != CitizenAIState.SLEEP && state != CitizenAIState.FLEE
                 && state != CitizenAIState.EATING;
    }

    /** A parent nearby who can come (closest first), else a teacher of the child's school. */
    private static SchoolEscort findFetcher(final EntityCitizen kid)
    {
        final ICitizenData data = kid.getCitizenData();
        final IColony colony = data.getColony();
        final String parentA = data.getParents().getA();
        final String parentB = data.getParents().getB();
        final IBuilding school = data.getWorkBuilding();
        SchoolEscort bestParent = null;
        SchoolEscort bestTeacher = null;
        double parentDistance = MAX_FETCH_DISTANCE * MAX_FETCH_DISTANCE;
        double teacherDistance = MAX_FETCH_DISTANCE * MAX_FETCH_DISTANCE;
        for (final ICitizenData other : colony.getCitizenManager().getCitizens())
        {
            if (other.isChild() || !(other.getEntity().orElse(null) instanceof EntityCitizen entity))
            {
                continue;
            }
            final SchoolEscort escort = ALL.get(entity);
            if (escort == null || !escort.canGo() || entity.level() != kid.level())
            {
                continue;
            }
            final double distance = entity.distanceToSqr(kid);
            final boolean parent = !other.getName().isEmpty() && (other.getName().equals(parentA) || other.getName().equals(parentB));
            if (parent && distance < parentDistance)
            {
                parentDistance = distance;
                bestParent = escort;
            }
            else if (!parent && (ANY_ADULT || other.getJob() instanceof JobTeacher && other.getWorkBuilding() == school) && distance < teacherDistance)
            {
                teacherDistance = distance;
                bestTeacher = escort;
            }
        }
        return bestParent != null ? bestParent : bestTeacher;
    }

    /** Awake, healthy, not already busy with another child or a conversation. */
    private boolean canGo()
    {
        if (pending != null || child != null || !adult.isAlive() || adult.isBaby() || adult.isSleeping())
        {
            return false;
        }
        final IState state = adult.getCitizenAI().getState();
        if (state == CitizenAIState.SLEEP || state == CitizenAIState.SICK || state == CitizenAIState.MOURN || state == CitizenAIState.FLEE
              || state == CitizenAIState.EATING || state instanceof AdultSocialAI.State)
        {
            return false;
        }
        return adult.getCitizenData() != null && !adult.getCitizenData().getCitizenDiseaseHandler().isSick();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The adult's errand
    // ------------------------------------------------------------------------------------------------------------------

    private boolean shouldStart()
    {
        return pending != null && adult.getCitizenAI().getState() != State.ESCORTING;
    }

    private IState begin()
    {
        child = pending;
        pending = null;
        if (child == null || !child.isAlive())
        {
            abort();
            return CitizenAIState.IDLE;
        }
        if (adult.getVehicle() instanceof SittingEntity)
        {
            adult.stopRiding();
        }
        phase = Phase.GO;
        phaseStart = now();
        return State.ESCORTING;
    }

    /** The child stops and waits once the adult is this close (or already being told off / carried). */
    public boolean isCatching(final EntityCitizen kid)
    {
        return kid == child && (phase != Phase.GO || adult.distanceToSqr(kid) < NOTICE_DISTANCE * NOTICE_DISTANCE);
    }

    public boolean isCarrying(final EntityCitizen kid)
    {
        return kid == child && phase == Phase.CARRY;
    }

    public EntityCitizen adult()
    {
        return adult;
    }

    /** Who is coming for this child, or null. */
    public static SchoolEscort fetcherOf(final EntityCitizen kid)
    {
        final SchoolEscort escort = FETCHER_OF.get(kid);
        return escort != null && escort.child == kid ? escort : null;
    }

    private IState tick()
    {
        if (child == null || !child.isAlive() || !adult.isAlive() || child.level() != adult.level() || !child.isBaby() || GrabCarry.isHeld(child))
        {
            abort();
            return CitizenAIState.IDLE;
        }
        final long t = now() - phaseStart;
        switch (phase)
        {
            case GO ->
            {
                if (!isMissing(child) && !isCatching(child))
                {
                    // it went to school on its own after all
                    debug(child, "made it to school alone");
                    abort();
                    return CitizenAIState.IDLE;
                }
                if (adult.distanceToSqr(child) <= REACH_SQR)
                {
                    adult.getNavigation().stop();
                    phase = Phase.SCOLD;
                    phaseStart = now();
                    scoldTicks = 40 + adult.getRandom().nextInt(81);
                    broadcast(adult, ChildAnimation.ARGUE, scoldTicks);
                    final ChildLifeAI ai = ChildLifeEvents.get(child);
                    if (ai != null)
                    {
                        ai.onScolded();
                    }
                    Social.addMood(child, -1.5f);
                    debug(child, "gets told off by " + adult.getName().getString());
                }
                else if (t > GO_TIMEOUT)
                {
                    RETRY_AT.put(child, now() + RETRY_AFTER);
                    abort();
                    return CitizenAIState.IDLE;
                }
                else
                {
                    Moves.follow(adult, child, 1.15);
                }
            }
            case SCOLD ->
            {
                adult.getNavigation().stop();
                Fx.face(adult, child);
                if (t % 25 == 0)
                {
                    Fx.angry(adult);
                }
                if (t % 40 == 0 && adult.getCitizenData() != null)
                {
                    // the adult's own MineColonies voice (job, gender, voice profile), not the vanilla villager
                    com.minecolonies.api.util.SoundUtils.playSoundAtCitizenWith(adult.level(), adult.blockPosition(),
                      com.minecolonies.api.sounds.EventType.UNHAPPY, adult.getCitizenData(), 100);
                }
                if (t >= scoldTicks)
                {
                    pickUp();
                }
            }
            case CARRY ->
            {
                final IBuilding school = child.getCitizenData() == null ? null : child.getCitizenData().getWorkBuilding();
                if (school == null)
                {
                    abort();
                    return CitizenAIState.IDLE;
                }
                final boolean there = EntityNavigationUtils.walkToBuilding(adult, school);
                if (there || t > CARRY_TIMEOUT || !School.isSchoolTime(child))
                {
                    debug(child, there ? "was brought to school" : "carry gave up");
                    abort();
                    return CitizenAIState.IDLE;
                }
            }
        }
        return State.ESCORTING;
    }

    private void pickUp()
    {
        phase = Phase.CARRY;
        phaseStart = now();
        if (child.getVehicle() != null)
        {
            child.stopRiding();
        }
        child.getNavigation().stop();
        child.setNoGravity(true);
        child.noPhysics = true;
        broadcast(adult, ChildAnimation.CARRY_CHILD, ChildLifeAI.OPEN_ENDED);
        final ChildLifeAI ai = ChildLifeEvents.get(child);
        if (ai != null)
        {
            ai.broadcast(ChildAnimation.GRAB_GRUMPY, ChildLifeAI.OPEN_ENDED);
        }
        Fx.grumble(child);
    }

    /** Runs after the adult's entity tick: keeps the carried child in its arms. */
    public void tickEffects()
    {
        final boolean active = child != null && adult.getCitizenAI().getState() == State.ESCORTING;
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
        if (child != null && phase == Phase.CARRY && child.isAlive())
        {
            final float yaw = adult.yBodyRot;
            final double rad = Math.toRadians(yaw);
            final Vec3 front = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
            child.setPos(adult.getX() + front.x * 0.6, adult.getY() + 0.6, adult.getZ() + front.z * 0.6);
            child.setDeltaMovement(Vec3.ZERO);
            child.resetFallDistance();
            child.getNavigation().stop();
            child.setYRot(yaw);
            child.yBodyRot = yaw;
            child.setYHeadRot(Mth.wrapDegrees(yaw + Mth.sin(adult.tickCount * 0.1f) * 25f));
        }
        else if (child == null && adult.getCitizenAI().getState() == State.ESCORTING)
        {
            // lost the child somewhere (unloaded...)
            broadcast(adult, ChildAnimation.NONE, 0);
        }
    }

    /** Ends the errand: puts the child down if carried and lets both go back to their day. */
    private void abort()
    {
        final EntityCitizen kid = child;
        child = null;
        pending = null;
        adult.getNavigation().stop();
        Moves.reset(adult);
        broadcast(adult, ChildAnimation.NONE, 0);
        if (kid != null)
        {
            FETCHER_OF.remove(kid);
            MISSING_SINCE.remove(kid);
            if (kid.isNoGravity() && !GrabCarry.isHeld(kid))
            {
                kid.setNoGravity(false);
                kid.noPhysics = false;
                kid.setPos(adult.getX(), adult.getY(), adult.getZ());
            }
            final ChildLifeAI ai = ChildLifeEvents.get(kid);
            if (ai != null)
            {
                ai.onEscortOver();
            }
        }
    }

    private static void broadcast(final EntityCitizen who, final ChildAnimation animation, final int duration)
    {
        PacketDistributor.sendToPlayersTrackingEntity(who, new ChildAnimPayload(who.getId(), animation.id(), duration));
    }

    private static void debug(final EntityCitizen kid, final String message)
    {
        if (ChildLifeAI.DEBUG)
        {
            com.aiko.coloniesalive.ColoniesAlive.LOGGER.info("[school] {} {}", kid.getName().getString(), message);
        }
    }
}
