package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ChildAnimation;
import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.network.ChildAnimPayload;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.ai.IStateAI;
import com.minecolonies.api.entity.ai.statemachine.AIEventTarget;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIBlockingEventType;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.modules.AbstractAssignedCitizenModule;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.jobs.JobHealer;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.other.SittingEntity;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Grown-ups looking after babies (see {@code Family}). One instance per citizen; it only acts while grown up.
 * <ul>
 *     <li><b>Bedtime</b>: at night a parent (else a grown-up brother or sister) comes, picks the baby up and puts it to
 *     bed in its home. Nobody in the family can come: the baby falls asleep where it is (see InfantLife).</li>
 *     <li><b>Healer</b>: a sick or badly hurt baby doesn't walk to the hospital; the colony's healer comes to it, looks
 *     after it for a moment and it is well again.</li>
 *     <li><b>Labour</b>: a mother about to give birth walks home first; the baby is born there.</li>
 * </ul>
 */
public class BabyCare implements IStateAI
{
    public enum State implements IAIState
    {
        CARING;

        @Override
        public boolean isOkayToEat()
        {
            return false;
        }
    }

    private enum Kind
    {
        BEDTIME,
        HEAL,
        LABOR,
        /** A bully hit the baby: picked up and rocked until it calms down. */
        COMFORT
    }

    private static final int ROCK_CALM_TICKS = 20 * 5;
    private static final int ROCK_TICKS      = 20 * 9;

    private enum Phase
    {
        GO,
        CARRY,
        TREAT,
        WALK_HOME
    }

    private static final double MAX_DISTANCE  = 96;
    private static final double REACH_SQR     = 2.0 * 2.0;
    private static final int    GO_TIMEOUT    = 20 * 90;
    private static final int    CARRY_TIMEOUT = 20 * 75;
    private static final int    TREAT_TICKS   = 20 * 6;
    private static final int    LABOR_TIMEOUT = 20 * 90;

    private static final Map<EntityCitizen, BabyCare> ALL      = new com.google.common.collect.MapMaker().weakKeys().makeMap();
    private static final Map<EntityCitizen, BabyCare> CARER_OF = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    private final EntityCitizen adult;

    private Kind          pendingKind;
    private EntityCitizen pendingBaby;
    private Runnable      pendingDone;

    private Kind          kind;
    private EntityCitizen baby;
    private Runnable      done;
    private Phase         phase;
    private long          phaseStart;
    private BlockPos      bed;
    private boolean       lookLocked;

    public BabyCare(final EntityCitizen adult)
    {
        this.adult = adult;
        ALL.put(adult, this);
        adult.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldStart, this::begin, 5));
        adult.getCitizenAI().addTransition(new AITarget(State.CARING, this::tick, 1));
    }

    private long now()
    {
        return adult.level().getGameTime();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Asking for help (from the baby's side, ChildLifeAI / InfantLife; and Family for labour)
    // ------------------------------------------------------------------------------------------------------------------

    /** Someone of the family comes to put the baby to bed. @return false if nobody can */
    public static boolean requestBedtime(final EntityCitizen baby)
    {
        final BabyCare carer = closest(baby, BabyCare::isFamilyOf, true);
        return carer != null && carer.assign(Kind.BEDTIME, baby, null);
    }

    /** A bullied baby wails: someone of the family comes from wherever they are (even out of bed) to pick it up. */
    public static boolean requestComfort(final EntityCitizen baby)
    {
        final BabyCare carer = closest(baby, BabyCare::isFamilyOf, true);
        return carer != null && carer.assign(Kind.COMFORT, baby, null);
    }

    /** This grown-up (who just fought the bully) picks the baby up and rocks it. */
    public static boolean comfortBy(final EntityCitizen adult, final EntityCitizen baby)
    {
        final BabyCare care = ALL.get(adult);
        if (care == null || care.kind != null || care.pendingKind != null || carerOf(baby) != null || !adult.isAlive())
        {
            return false;
        }
        return care.assign(Kind.COMFORT, baby, null);
    }

    /** The closest grown-up of the family (mum and dad first), whatever it is doing, or null. */
    public static EntityCitizen closestFamily(final EntityCitizen child)
    {
        EntityCitizen best = null;
        double bestScore = Double.MAX_VALUE;
        synchronized (ALL)
        {
            for (final BabyCare care : ALL.values())
            {
                final EntityCitizen adult = care.adult;
                if (!adult.isAlive() || adult.isBaby() || adult.level() != child.level() || !isFamilyOf(adult, child))
                {
                    continue;
                }
                final ICitizenData a = adult.getCitizenData();
                final ICitizenData c = child.getCitizenData();
                final boolean parent = a != null && c != null && (a.getName().equals(c.getParents().getA()) || a.getName().equals(c.getParents().getB()));
                // a parent counts as if it were 30 blocks closer
                final double score = Math.sqrt(adult.distanceToSqr(child)) - (parent ? 30 : 0);
                if (score < bestScore)
                {
                    bestScore = score;
                    best = adult;
                }
            }
        }
        return best;
    }

    /** Any grown-up of the family around (busy or asleep or not). */
    public static boolean hasFamily(final EntityCitizen baby)
    {
        synchronized (ALL)
        {
            for (final BabyCare care : ALL.values())
            {
                final EntityCitizen adult = care.adult;
                if (adult.isAlive() && !adult.isBaby() && adult.level() == baby.level() && isFamilyOf(adult, baby))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** The colony's healer comes over. @return false if there is none (or none can come) */
    public static boolean requestHealer(final EntityCitizen baby)
    {
        final BabyCare healer = closest(baby, (adult, b) -> adult.getCitizenData() != null && adult.getCitizenData().getJob() instanceof JobHealer, false);
        return healer != null && healer.assign(Kind.HEAL, baby, null);
    }

    /**
     * A mother about to give birth walks home; {@code birth} runs once she is there (or after a while anyway).
     *
     * @return false if she has no home or is home already: the caller lets the baby come right away
     */
    public static boolean goHomeToGiveBirth(final EntityCitizen mother, final Runnable birth)
    {
        final BabyCare care = ALL.get(mother);
        final IBuilding home = mother.getCitizenData() == null ? null : mother.getCitizenData().getHomeBuilding();
        if (care == null || home == null || home.isInBuilding(mother.blockPosition()) || mother.isSleeping() || care.kind != null || care.pendingKind != null)
        {
            return false;
        }
        care.pendingKind = Kind.LABOR;
        care.pendingBaby = null;
        care.pendingDone = birth;
        debug(mother.getName().getString() + " goes home to give birth");
        return true;
    }

    /** Who is looking after this baby right now, or null. */
    public static BabyCare carerOf(final EntityCitizen baby)
    {
        final BabyCare care = CARER_OF.get(baby);
        return care != null && (care.baby == baby || care.pendingBaby == baby) ? care : null;
    }

    public EntityCitizen adultEntity()
    {
        return adult;
    }

    public boolean isCarrying(final EntityCitizen who)
    {
        return who == baby && phase == Phase.CARRY;
    }

    public boolean isHealing(final EntityCitizen who)
    {
        return who == baby && kind == Kind.HEAL;
    }

    /** The baby stops what it does and waits once the grown-up is this close. */
    public boolean isNear(final EntityCitizen who)
    {
        return who == baby && adult.distanceToSqr(who) < 6 * 6;
    }

    private interface Match
    {
        boolean test(EntityCitizen adult, EntityCitizen baby);
    }

    /** Mother, father, or a grown-up brother or sister. */
    static boolean isFamilyOf(final EntityCitizen adult, final EntityCitizen baby)
    {
        final ICitizenData a = adult.getCitizenData();
        final ICitizenData b = baby.getCitizenData();
        if (a == null || b == null || a.getName().isEmpty())
        {
            return false;
        }
        return a.getName().equals(b.getParents().getA()) || a.getName().equals(b.getParents().getB()) || b.getSiblings().contains(a.getId());
    }

    private static BabyCare closest(final EntityCitizen baby, final Match match, final boolean bedtime)
    {
        BabyCare best = null;
        // bedtime: the family comes from wherever they are
        double bestDistance = bedtime ? Double.MAX_VALUE : MAX_DISTANCE * MAX_DISTANCE;
        synchronized (ALL)
        {
            for (final BabyCare care : ALL.values())
            {
                final EntityCitizen adult = care.adult;
                if (adult.level() != baby.level() || !care.canGo(bedtime) || !match.test(adult, baby)
                      || adult.getCitizenColonyHandler().getColonyId() != baby.getCitizenColonyHandler().getColonyId())
                {
                    continue;
                }
                final double distance = adult.distanceToSqr(baby);
                if (distance < bestDistance)
                {
                    bestDistance = distance;
                    best = care;
                }
            }
        }
        return best;
    }

    /** Awake and free. At bedtime a parent on the way to bed, or already asleep in it, still comes (it gets up for it). */
    private boolean canGo(final boolean bedtime)
    {
        if (kind != null || pendingKind != null || !adult.isAlive() || adult.isBaby() || (adult.isSleeping() && !bedtime) || adult.getCitizenData() == null)
        {
            return false;
        }
        final IState state = adult.getCitizenAI().getState();
        if ((state == CitizenAIState.SLEEP && !bedtime) || state == CitizenAIState.SICK || state == CitizenAIState.MOURN || state == CitizenAIState.FLEE
              || state == SchoolEscort.State.ESCORTING || state == Brawl.State.BRAWLING || Brawl.isBusy(adult))
        {
            return false;
        }
        return !adult.getCitizenData().getCitizenDiseaseHandler().isSick() && !com.aiko.coloniesalive.social.Family.isPregnant(adult);
    }

    private boolean assign(final Kind what, final EntityCitizen who, final Runnable then)
    {
        pendingKind = what;
        pendingBaby = who;
        pendingDone = then;
        CARER_OF.put(who, this);
        debug(adult.getName().getString() + " comes for " + who.getName().getString() + " (" + what + ")");
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The grown-up's errand
    // ------------------------------------------------------------------------------------------------------------------

    private boolean shouldStart()
    {
        return pendingKind != null && adult.getCitizenAI().getState() != State.CARING;
    }

    private IState begin()
    {
        kind = pendingKind;
        baby = pendingBaby;
        done = pendingDone;
        pendingKind = null;
        pendingBaby = null;
        pendingDone = null;
        if (kind != Kind.LABOR && (baby == null || !baby.isAlive()))
        {
            finish();
            return CitizenAIState.IDLE;
        }
        if (adult.getVehicle() instanceof SittingEntity)
        {
            adult.stopRiding();
        }
        if (adult.getCitizenSleepHandler().isAsleep() || adult.isSleeping())
        {
            // gets out of bed for the baby; once it is done MineColonies sends it back to bed (still night: SLEEP)
            adult.getCitizenSleepHandler().onWakeUp();
            if (adult.isSleeping())
            {
                adult.stopSleeping();
            }
            debug(adult.getName().getString() + " gets out of bed to fetch " + baby.getName().getString());
        }
        phase =kind == Kind.LABOR ? Phase.WALK_HOME : Phase.GO;
        phaseStart = now();
        return State.CARING;
    }

    private IState tick()
    {
        final long t = now() - phaseStart;
        if (kind == Kind.LABOR)
        {
            final IBuilding home = adult.getCitizenData() == null ? null : adult.getCitizenData().getHomeBuilding();
            if (home == null || EntityNavigationUtils.walkToBuilding(adult, home) || home.isInBuilding(adult.blockPosition()) || t > LABOR_TIMEOUT)
            {
                final Runnable birth = done;
                finish();
                if (birth != null)
                {
                    birth.run();
                }
                return CitizenAIState.IDLE;
            }
            return State.CARING;
        }
        if (baby == null || !baby.isAlive() || baby.level() != adult.level() || !baby.isBaby() || GrabCarry.isHeld(baby))
        {
            finish();
            return CitizenAIState.IDLE;
        }
        switch (phase)
        {
            case GO ->
            {
                if (adult.distanceToSqr(baby) <= REACH_SQR)
                {
                    adult.getNavigation().stop();
                    Fx.face(adult, baby);
                    if (kind == Kind.BEDTIME || kind == Kind.COMFORT)
                    {
                        pickUp();
                    }
                    else
                    {
                        phase = Phase.TREAT;
                        phaseStart = now();
                        broadcast(adult, ChildAnimation.PET_ANIMAL, TREAT_TICKS + 20);
                    }
                }
                else if (t > GO_TIMEOUT)
                {
                    finish();
                    return CitizenAIState.IDLE;
                }
                else
                {
                    Moves.follow(adult, baby, 1.1);
                }
            }
            case CARRY ->
            {
                if (kind == Kind.COMFORT)
                {
                    return rock(t);
                }
                final IBuilding home = baby.getCitizenData() == null ? null : baby.getCitizenData().getHomeBuilding();
                final boolean there = home != null && bed != null
                                        ? EntityNavigationUtils.walkToPosInBuilding(adult, bed, home, 2)
                                        : home == null || EntityNavigationUtils.walkToBuilding(adult, home);
                if (there || t > CARRY_TIMEOUT)
                {
                    putToBed();
                    finish();
                    return CitizenAIState.IDLE;
                }
            }
            case TREAT ->
            {
                adult.getNavigation().stop();
                Fx.face(adult, baby);
                if (t % 20 == 0)
                {
                    Fx.happy(baby, 3);
                }
                if (t >= TREAT_TICKS)
                {
                    heal();
                    finish();
                    return CitizenAIState.IDLE;
                }
            }
            default ->
            {
            }
        }
        return State.CARING;
    }

    private void pickUp()
    {
        phase = Phase.CARRY;
        phaseStart = now();
        if (baby.getVehicle() != null)
        {
            baby.stopRiding();
        }
        baby.getNavigation().stop();
        baby.setNoGravity(true);
        baby.noPhysics = true;
        bed = kind == Kind.COMFORT ? null : bedOf(baby);
        broadcast(adult, ChildAnimation.CARRY_CHILD, ChildLifeAI.OPEN_ENDED);
        final ChildLifeAI ai = ChildLifeEvents.get(baby);
        if (kind == Kind.COMFORT)
        {
            // still sobbing in its arms at first
            if (ai != null)
            {
                ai.broadcast(ChildAnimation.BABY_HELD_CRY, ChildLifeAI.OPEN_ENDED);
            }
            Fx.cry(baby);
            debug(adult.getName().getString() + " picks up " + baby.getName().getString() + " to calm it down");
            return;
        }
        if (ai != null)
        {
            ai.broadcast(ChildAnimation.BABY_HELD, ChildLifeAI.OPEN_ENDED);
        }
        Fx.giggle(baby);
        debug(adult.getName().getString() + " carries " + baby.getName().getString() + " to bed " + bed);
    }

    /** Rocking the bullied baby in its arms: sobs, then it calms down, smiles, and is put down again. */
    private IState rock(final long t)
    {
        adult.getNavigation().stop();
        final ChildLifeAI ai = ChildLifeEvents.get(baby);
        if (t < ROCK_CALM_TICKS)
        {
            if (t % 4 == 0)
            {
                Fx.tears(baby);
            }
            if (t % 30 == 0)
            {
                Fx.cry(baby);
            }
            if (t % 20 == 10)
            {
                Fx.hearts(baby, 1);
            }
        }
        else if (t == ROCK_CALM_TICKS)
        {
            if (ai != null)
            {
                ai.comforted(adult, 0);
                ai.broadcast(ChildAnimation.BABY_HELD, ChildLifeAI.OPEN_ENDED);
            }
            Fx.giggle(baby);
            Fx.hearts(baby, 5);
            debug(baby.getName().getString() + " calmed down in " + adult.getName().getString() + "'s arms");
        }
        else if (t % 25 == 0)
        {
            Fx.hearts(baby, 1);
        }
        if (t >= ROCK_TICKS)
        {
            releaseBaby();
            if (ai != null)
            {
                ai.babyPutDown();
            }
            finish();
            return CitizenAIState.IDLE;
        }
        return State.CARING;
    }

    /** Its own bed at home, picked the way MineColonies picks one (n-th bed for the n-th resident), else null. */
    private static BlockPos bedOf(final EntityCitizen baby)
    {
        final ICitizenData data = baby.getCitizenData();
        if (data == null || !(data.getHomeBuilding() instanceof AbstractBuilding home) || !home.hasModule(BuildingModules.BED))
        {
            return null;
        }
        final List<BlockPos> beds = home.getModule(BuildingModules.BED).getRegisteredBlocks();
        final AbstractAssignedCitizenModule residents = home.getFirstModuleOccurance(AbstractAssignedCitizenModule.class);
        final int index = residents == null ? -1 : residents.getAssignedCitizen().indexOf(data);
        if (index >= 0 && index < beds.size() && baby.level().getBlockState(beds.get(index)).is(BlockTags.BEDS))
        {
            return beds.get(index);
        }
        return null;
    }

    /** Lays the baby in its bed (or down where they are, if there is no bed or it's taken). */
    private void putToBed()
    {
        releaseBaby();
        final ChildLifeAI ai = ChildLifeEvents.get(baby);
        final boolean inBed = bed != null && baby.getCitizenSleepHandler().trySleep(bed);
        debug(baby.getName().getString() + (inBed ? " was put to bed" : " was put down to sleep on the spot"));
        if (ai != null)
        {
            ai.babyPutToBed(inBed);
        }
    }

    private void heal()
    {
        final ICitizenData data = baby.getCitizenData();
        if (data != null)
        {
            data.getCitizenDiseaseHandler().cure();
        }
        baby.setHealth(baby.getMaxHealth());
        Fx.hearts(baby, 5);
        Fx.giggle(baby);
        debug(adult.getName().getString() + " healed " + baby.getName().getString());
    }

    private void releaseBaby()
    {
        if (baby != null && baby.isNoGravity() && !GrabCarry.isHeld(baby))
        {
            baby.setNoGravity(false);
            baby.noPhysics = false;
            baby.setPos(adult.getX(), adult.getY(), adult.getZ());
        }
    }

    /** Runs after the grown-up's entity tick: keeps a carried baby in its arms. */
    public void tickEffects()
    {
        final boolean active = kind != null && kind != Kind.LABOR && adult.getCitizenAI().getState() == State.CARING;
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
        if (baby != null && phase == Phase.CARRY && baby.isAlive())
        {
            final float yaw = adult.yBodyRot;
            final double rad = Math.toRadians(yaw);
            final Vec3 front = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
            baby.setPos(adult.getX() + front.x * 0.45, adult.getY() + 0.85, adult.getZ() + front.z * 0.45);
            baby.setDeltaMovement(Vec3.ZERO);
            baby.resetFallDistance();
            baby.getNavigation().stop();
            baby.setYRot(yaw + 180);
            baby.yBodyRot = yaw + 180;
            baby.setYHeadRot(Mth.wrapDegrees(yaw + 180 + Mth.sin(adult.tickCount * 0.08f) * 20f));
        }
        if (kind == null && adult.getCitizenAI().getState() == State.CARING)
        {
            broadcast(adult, ChildAnimation.NONE, 0);
        }
    }

    private void finish()
    {
        if (phase == Phase.CARRY)
        {
            releaseBaby();
        }
        if (baby != null)
        {
            CARER_OF.remove(baby);
        }
        kind = null;
        baby = null;
        done = null;
        phase = null;
        bed = null;
        adult.getNavigation().stop();
        Moves.reset(adult);
        broadcast(adult, ChildAnimation.NONE, 0);
    }

    private static void broadcast(final EntityCitizen who, final ChildAnimation animation, final int duration)
    {
        PacketDistributor.sendToPlayersTrackingEntity(who, new ChildAnimPayload(who.getId(), animation.id(), duration));
    }

    private static void debug(final String message)
    {
        ColoniesAlive.LOGGER.info("[babycare] {}", message);
    }
}
