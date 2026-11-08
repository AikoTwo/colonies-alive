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
import com.minecolonies.core.entity.ai.minimal.EntityAICitizenWander;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.world.entity.ai.goal.Goal;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Random;

/**
 * The adults' social life, only in their free time (strolling at a leisure site, or idle without a job), so work is
 * never interrupted. Two free adults close to each other may:
 * <ul>
 *     <li>chat (taking turns talking and listening) - friendship grows, and between two free adults who get along
 *     well, romance too; once in love they confess and become a couple (also as MineColonies partners)</li>
 *     <li>spend time together as a couple: hug, hearts</li>
 *     <li>argue, if they can't stand each other - friendship drops and they hold a grudge</li>
 * </ul>
 */
public class AdultSocialAI implements IStateAI
{
    public enum State implements IAIState
    {
        SOCIALIZING;

        @Override
        public boolean isOkayToEat()
        {
            return true;
        }
    }

    private enum Kind
    {
        CHAT,
        DATE,
        ARGUE
    }

    private static final int MIN_GAP = 20 * 30;
    private static final int MAX_GAP = 20 * 90;
    private static final double MEET_RADIUS = 10;

    private final EntityCitizen citizen;
    private final Random        rand = new Random();

    private long nextTry;

    // session (both sides keep a reference; the leader drives it)
    private AdultSocialAI partner;
    private boolean       leader;
    private Kind          kind;
    private boolean       joined;
    private int           phase;
    private long          phaseStart;
    private long          endTick;
    private boolean       over;
    private boolean       lookLocked;

    public AdultSocialAI(final EntityCitizen citizen)
    {
        this.citizen = citizen;
        nextTry = citizen.level().getGameTime() + 20 * 20 + rand.nextInt(20 * 40);
        citizen.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldStart, this::start, 60));
        citizen.getCitizenAI().addTransition(new AIEventTarget(AIBlockingEventType.EVENT, this::shouldJoin, this::join, 10));
        citizen.getCitizenAI().addTransition(new AITarget(State.SOCIALIZING, this::tickSession, 2));
    }

    private long now()
    {
        return citizen.level().getGameTime();
    }

    /** Free time: strolling at a leisure site, or idle without a job. */
    private boolean isFree()
    {
        final IState state = citizen.getCitizenAI().getState();
        return !citizen.isBaby() && citizen.getCitizenData() != null && citizen.getVehicle() == null
                 && (state == EntityAICitizenWander.WanderState.WANDER_AT_LEISURE_SITE
                       || (state == CitizenAIState.IDLE && citizen.getCitizenData().getJob() == null)
                       || (everyoneOffWork && state != State.SOCIALIZING && state != CitizenAIState.SLEEP && state != CitizenAIState.FLEE));
    }

    /** Development: treat every adult as having free time (the observe test uses it to exercise the adults' social life). */
    public static volatile boolean everyoneOffWork;

    private boolean isSocializing()
    {
        return citizen.getCitizenAI().getState() == State.SOCIALIZING;
    }

    // ------------------------------------------------------------------------------------------------------------------

    private boolean shouldStart()
    {
        return partner == null && now() >= nextTry && isFree() && rand.nextInt(3) == 0;
    }

    private IState start()
    {
        nextTry = now() + MIN_GAP + rand.nextInt(MAX_GAP - MIN_GAP);
        final AdultSocialAI other = ChildLifeEvents.findFreeAdult(this, everyoneOffWork ? MEET_RADIUS * 3 : MEET_RADIUS);
        if (other == null)
        {
            if (ChildLifeAI.DEBUG)
            {
                com.aiko.coloniesalive.ColoniesAlive.LOGGER.info("[adult {}] nobody free to talk to nearby", citizen.getName().getString());
            }
            return citizen.getCitizenAI().getState();
        }

        final EntityCitizen them = other.citizen;
        final int friendship = Social.friendship(citizen, them);
        if (Social.hasGrudge(citizen, them))
        {
            return citizen.getCitizenAI().getState();
        }
        final Kind chosen;
        if (Social.arePartners(citizen, them))
        {
            chosen = rand.nextInt(3) == 0 ? Kind.CHAT : Kind.DATE;
        }
        else if (friendship <= Social.RIVAL && rand.nextInt(2) == 0)
        {
            chosen = Kind.ARGUE;
        }
        else
        {
            chosen = Kind.CHAT;
        }

        partner = other;
        leader = true;
        kind = chosen;
        joined = false;
        over = false;
        phase = 0;
        phaseStart = now();
        other.partner = this;
        other.leader = false;
        other.kind = chosen;
        other.over = false;
        citizen.getNavigation().stop();
        if (ChildLifeAI.DEBUG)
        {
            com.aiko.coloniesalive.ColoniesAlive.LOGGER.info("[adult {}] {} with {} (friendship {}, romance {})", citizen.getName().getString(), chosen,
              them.getName().getString(), friendship, Social.romance(citizen, them));
        }
        return State.SOCIALIZING;
    }

    private boolean shouldJoin()
    {
        return partner != null && !leader && !isSocializing() && isFree();
    }

    private IState join()
    {
        if (partner == null || partner.over || !partner.isSocializing())
        {
            reset();
            return citizen.getCitizenAI().getState();
        }
        joined = true;
        partner.joined = true;
        citizen.getNavigation().stop();
        return State.SOCIALIZING;
    }

    private void reset()
    {
        partner = null;
        leader = false;
        over = false;
        joined = false;
    }

    // ------------------------------------------------------------------------------------------------------------------

    private IState tickSession()
    {
        if (partner == null || over)
        {
            return finish();
        }
        if (!leader)
        {
            return partner.over || partner.partner != this ? finish() : State.SOCIALIZING;
        }

        final long time = now();
        final EntityCitizen them = partner.citizen;
        if (!them.isAlive() || them.level() != citizen.level() || citizen.distanceToSqr(them) > 24 * 24)
        {
            return finish();
        }
        if (!joined)
        {
            return time - phaseStart > 20 * 8 ? finish() : State.SOCIALIZING;
        }
        if (!partner.isSocializing())
        {
            return finish();
        }

        if (phase == 0)
        {
            // walk up to each other
            Fx.face(them, citizen);
            if (citizen.distanceToSqr(them) > 1.8 * 1.8)
            {
                if (time - phaseStart > 20 * 15)
                {
                    return finish();
                }
                Moves.follow(citizen, them, 0.9);
                return State.SOCIALIZING;
            }
            citizen.getNavigation().stop();
            them.getNavigation().stop();
            phase = 1;
            phaseStart = time;
            endTick = time + switch (kind)
            {
                case CHAT -> 20 * 12 + rand.nextInt(20 * 10);
                case DATE -> 20 * 10 + rand.nextInt(20 * 6);
                case ARGUE -> 20 * 5 + rand.nextInt(20 * 3);
            };
            onStart();
        }

        Fx.face(citizen, them);
        Fx.face(them, citizen);
        final long elapsed = time - phaseStart;
        switch (kind)
        {
            case CHAT -> tickChat(elapsed);
            case DATE -> tickDate(elapsed);
            case ARGUE -> tickArgue(elapsed);
        }

        if (time >= endTick)
        {
            onEnd();
            return finish();
        }
        return State.SOCIALIZING;
    }

    private void onStart()
    {
        switch (kind)
        {
            case CHAT -> turn(true);
            case DATE ->
            {
                broadcast(this, ChildAnimation.HUG, 60);
                broadcast(partner, ChildAnimation.HUG, 60);
                Fx.hearts(citizen, 3);
            }
            case ARGUE ->
            {
                broadcast(this, ChildAnimation.ARGUE, (int) (endTick - now()));
                broadcast(partner, ChildAnimation.ARGUE, (int) (endTick - now()));
                Fx.grumble(citizen);
            }
        }
    }

    /** Who talks and who listens; they take turns. */
    private void turn(final boolean leaderTalks)
    {
        broadcast(this, leaderTalks ? ChildAnimation.TALK : ChildAnimation.LISTEN, ChildLifeAI.OPEN_ENDED);
        broadcast(partner, leaderTalks ? ChildAnimation.LISTEN : ChildAnimation.TALK, ChildLifeAI.OPEN_ENDED);
    }

    private void tickChat(final long elapsed)
    {
        if (elapsed > 0 && elapsed % 60 == 0)
        {
            turn((elapsed / 60) % 2 == 0);
        }
        if (elapsed % 90 == 45 && rand.nextInt(3) == 0)
        {
            Fx.giggle(rand.nextBoolean() ? citizen : partner.citizen);
        }
        if (Social.arePartners(citizen, partner.citizen) && elapsed % 40 == 0)
        {
            Fx.hearts(rand.nextBoolean() ? citizen : partner.citizen, 1);
        }
    }

    private void tickDate(final long elapsed)
    {
        if (elapsed == 60)
        {
            turn(true);
        }
        if (elapsed > 60 && elapsed % 60 == 0)
        {
            turn((elapsed / 60) % 2 == 0);
        }
        if (elapsed % 25 == 0)
        {
            Fx.hearts(rand.nextBoolean() ? citizen : partner.citizen, 1);
        }
    }

    private void tickArgue(final long elapsed)
    {
        if (elapsed % 15 == 0)
        {
            Fx.angry(rand.nextBoolean() ? citizen : partner.citizen);
        }
        if (elapsed % 35 == 20)
        {
            Fx.grumble(rand.nextBoolean() ? citizen : partner.citizen);
        }
    }

    private void onEnd()
    {
        final EntityCitizen them = partner.citizen;
        switch (kind)
        {
            case CHAT ->
            {
                Social.addFriendship(citizen, them, 4);
                Social.addMood(citizen, 1f);
                Social.addMood(them, 1f);
                if (Social.canDate(citizen, them) && Social.friendship(citizen, them) >= Social.FRIEND)
                {
                    final int romance = Social.addRomance(citizen, them, 4 + rand.nextInt(5));
                    if (romance >= Social.IN_LOVE)
                    {
                        // the confession
                        broadcast(this, ChildAnimation.HUG, 60);
                        broadcast(partner, ChildAnimation.HUG, 60);
                        Fx.hearts(citizen, 6);
                        Fx.hearts(them, 6);
                        Social.becomeCouple(citizen, them);
                        return;
                    }
                }
                if (rand.nextInt(5) == 0)
                {
                    Topics.raise(citizen, Topics.Topic.GOOD_CHAT, them.getCitizenData().getName());
                }
            }
            case DATE ->
            {
                Social.addFriendship(citizen, them, 2);
                Social.addMood(citizen, 1.5f);
                Social.addMood(them, 1.5f);
            }
            case ARGUE ->
            {
                Social.addFriendship(citizen, them, -8);
                Social.addMood(citizen, -2f);
                Social.addMood(them, -2f);
                Social.setGrudge(citizen, them, 20 * 60 * 5);
                broadcast(this, ChildAnimation.POUT, 60);
                broadcast(partner, ChildAnimation.POUT, 60);
                Topics.raise(rand.nextBoolean() ? citizen : them, Topics.Topic.FIGHT,
                  (rand.nextBoolean() ? them : citizen).getCitizenData().getName());
            }
        }
    }

    /** Ends this side; the other side notices on its next tick (or when it would have joined) and ends too. */
    private IState finish()
    {
        if (partner != null && partner.partner == this)
        {
            partner.over = true;
        }
        broadcast(this, ChildAnimation.NONE, 0);
        reset();
        return CitizenAIState.IDLE;
    }

    private long nextLecture;

    /** Every tick: while socializing, MineColonies' own look-at goals must not turn the head elsewhere. */
    public void tickEffects()
    {
        // the teacher explains things while pupils sit in class
        if (School.isTeacher(citizen) && School.isSchoolTime(citizen) && citizen.getCitizenAI().getState() == CitizenAIState.WORKING
              && now() >= nextLecture)
        {
            nextLecture = now() + 100;
            final boolean pupilsSeated = !citizen.level().getEntitiesOfClass(EntityCitizen.class, citizen.getBoundingBox().inflate(12),
              c -> School.isPupil(c) && c.getVehicle() != null).isEmpty();
            if (pupilsSeated)
            {
                broadcast(this, ChildAnimation.TALK, 80);
            }
        }

        final boolean inControl = isSocializing();
        if (inControl != lookLocked)
        {
            lookLocked = inControl;
            if (inControl)
            {
                citizen.goalSelector.disableControlFlag(Goal.Flag.LOOK);
            }
            else
            {
                citizen.goalSelector.enableControlFlag(Goal.Flag.LOOK);
            }
        }
    }

    public boolean isAvailable()
    {
        return partner == null && isFree();
    }

    public EntityCitizen citizen()
    {
        return citizen;
    }

    private static void broadcast(final AdultSocialAI who, final ChildAnimation animation, final int duration)
    {
        PacketDistributor.sendToPlayersTrackingEntity(who.citizen, new ChildAnimPayload(who.citizen.getId(), animation.id(), duration));
    }
}
