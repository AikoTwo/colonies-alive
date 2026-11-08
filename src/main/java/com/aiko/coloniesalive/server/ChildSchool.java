package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ChildAnimation;
import com.aiko.coloniesalive.social.Social;
import com.aiko.coloniesalive.social.Topics;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.other.SittingEntity;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;

/**
 * One child's school life: skipping school now and then, the punishment for it, what it does sitting in class, and
 * telling the player about the school day.
 *
 * <ul>
 *     <li>Each school day a pupil may skip school: rarely, more often when in a bad mood or a little rascal (a fixed
 *     trait per child), much less often right after being punished. A child skipping school plays as on a free day.</li>
 *     <li>When school is over, a child that skipped is sent home and has to stand in the corner for two minutes, then
 *     stays grounded until the next morning: no games with the others, just moping.</li>
 *     <li>Sitting in class it reads, writes, listens, raises its hand, and now and then dozes off.</li>
 * </ul>
 */
final class ChildSchool
{
    private static final int CORNER_TICKS = 20 * 120;

    private final ChildLifeAI ai;

    private long    decidedDay = -1;
    private boolean truant;
    private boolean punishedYesterday;
    private long    punishedDay = -1;
    private long    groundedUntil;
    private long    reportedDay = -1;

    private long           nextClassPose;
    private ChildAnimation classPose;

    ChildSchool(final ChildLifeAI ai)
    {
        this.ai = ai;
    }

    private EntityCitizen child()
    {
        return ai.child();
    }

    private long day()
    {
        return child().level().getDayTime() / 24000;
    }

    boolean isTruantToday()
    {
        if (!School.isPupil(child()))
        {
            return false;
        }
        final long today = day();
        if (today != decidedDay)
        {
            decidedDay = today;
            int chance = 5;
            if (Social.mood(child()) < -3)
            {
                chance += 12;
            }
            if (Math.floorMod(child().getUUID().hashCode() >> 8, 100) < 20)
            {
                // a little rascal
                chance += 8;
            }
            if (punishedYesterday)
            {
                chance /= 3;
            }
            punishedYesterday = false;
            truant = ai.rand().nextInt(100) < chance;
            if (truant)
            {
                ai.debug("skips school today");
            }
        }
        return truant;
    }

    /** Fetched to school: it attends after all, and being caught was the punishment. */
    void caught()
    {
        truant = false;
        punishedYesterday = true;
    }

    void forceTruantToday()
    {
        decidedDay = day();
        truant = true;
    }

    boolean isGrounded()
    {
        return ai.now() < groundedUntil;
    }

    /** School is over and this child skipped it: time to face the consequences. */
    boolean needsPunishment()
    {
        return isTruantToday() && !School.isSchoolTime(child()) && punishedDay != day() && child().level().getDayTime() % 24000 < 12000;
    }

    ChildActivity punishment()
    {
        punishedDay = day();
        punishedYesterday = true;
        // grounded until the next morning
        groundedUntil = ai.now() + (24000 - child().level().getDayTime() % 24000);
        Social.addMood(child(), -3f);
        Topics.raise(child(), Topics.Topic.SCHOOL_TRUANT);
        ai.debug("is punished for skipping school");

        final IBuilding home = child().getCitizenData().getHomeBuilding();
        return new ChildActivity()
        {
            long start = -1;
            long cornerStart = -1;

            @Override
            public boolean tick(final ChildLifeAI a, final long time)
            {
                if (start < 0)
                {
                    start = time;
                    a.broadcast(ChildAnimation.SORRY, ChildLifeAI.OPEN_ENDED);
                }
                if (cornerStart < 0)
                {
                    // walk home, head down
                    if (home != null && time - start < 20 * 40 && !EntityNavigationUtils.walkToBuilding(child(), home))
                    {
                        return true;
                    }
                    child().getNavigation().stop();
                    cornerStart = time;
                    a.broadcast(ChildAnimation.SORRY, CORNER_TICKS);
                }
                final long elapsed = time - cornerStart;
                if (elapsed % 120 == 60)
                {
                    Fx.cry(child());
                }
                return elapsed < CORNER_TICKS;
            }
        };
    }

    /** Called every tick: class poses while seated, and the end-of-day story. */
    void tick()
    {
        if (!School.isPupil(child()))
        {
            return;
        }

        final boolean inClass = School.inClass(child()) && child().getCitizenAI().getState() == CitizenAIState.WORKING;
        if (inClass && child().getVehicle() instanceof SittingEntity)
        {
            final long now = ai.now();
            if (now >= nextClassPose)
            {
                classPose = pickClassPose();
                final int duration = 60 + ai.rand().nextInt(60);
                nextClassPose = now + duration;
                ai.broadcast(classPose, duration + 10);
            }
        }
        else if (classPose != null)
        {
            classPose = null;
            if (!ai.isActing())
            {
                ai.broadcast(ChildAnimation.NONE, 0);
            }
        }

        // after a day at school, sometimes tell the player about it
        if (!School.isSchoolTime(child()) && !truant && decidedDay == day() && reportedDay != day())
        {
            reportedDay = day();
            if (ai.rand().nextInt(100) < 15)
            {
                Topics.raise(child(), Topics.Topic.SCHOOL_DAY);
            }
        }
    }

    private ChildAnimation pickClassPose()
    {
        final int roll = ai.rand().nextInt(100);
        if (roll < 35)
        {
            return ChildAnimation.READ;
        }
        if (roll < 65)
        {
            return ChildAnimation.WRITE;
        }
        if (roll < 85)
        {
            return ChildAnimation.LISTEN;
        }
        if (roll < 95)
        {
            return ChildAnimation.RAISE_HAND;
        }
        return ChildAnimation.DOZE;
    }
}
