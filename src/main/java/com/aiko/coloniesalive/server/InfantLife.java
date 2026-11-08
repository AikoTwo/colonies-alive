package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ChildAnimation;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.other.SittingEntity;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import net.minecraft.core.BlockPos;

/**
 * What a baby (see {@code Family}) does instead of the children's games. Sitting on the floor is where it always comes
 * back to; from there it:
 * <ul>
 *     <li>babbles, looks around, shakes its arms, now and then claps and laughs (eyes squeezed shut: a big smile);</li>
 *     <li>crawls a few blocks on hands and knees (its usual way of getting around);</li>
 *     <li>tries to walk: gets up very slowly, wobbles a few steps... and plops back down on its bottom;</li>
 *     <li>lights up when an older child comes to play peekaboo with it ({@link ChildLifeAI#cheerBaby}): sits facing the
 *     child, claps and laughs.</li>
 * </ul>
 * Too far from home, it crawls back.
 */
final class InfantLife
{
    /** How far from home a baby goes. */
    private static final int    ROAM          = 4;
    private static final int    TOO_FAR_SQ    = 10 * 10;
    private static final double CRAWL_SPEED   = 0.5;
    private static final double TODDLE_SPEED  = 0.55;

    private InfantLife() {}

    static ChildActivity next(final ChildLifeAI ai)
    {
        final EntityCitizen baby = ai.child();
        // in a grown-up's arms (being rocked after a bully hit it): nothing else until it is put down
        final BabyCare holder = BabyCare.carerOf(baby);
        if (holder != null && holder.isCarrying(baby) && !ai.isBabyNight())
        {
            return (a, time) -> {
                final BabyCare still = BabyCare.carerOf(baby);
                return still != null && still.isCarrying(baby);
            };
        }
        // first its needs: getting well, then sleep
        if (ai.isBabyUnwell())
        {
            return sickRest(ai);
        }
        if (ai.isBabyNight() || ai.babySleep != ChildLifeAI.BabySleep.AWAKE)
        {
            return bedtime(ai);
        }
        final EntityCitizen cheerer = ai.cheeringKid();
        if (cheerer != null)
        {
            return cheered(ai, cheerer);
        }
        final BlockPos home = home(baby);
        final boolean plopDown = ai.takeBabySitNext();
        if (home.distSqr(baby.blockPosition()) > TOO_FAR_SQ && !plopDown)
        {
            // back home a bit at a time: crawls a while, then sits down for a rest
            ai.babySitNext();
            return crawl(ai, home, 20 * 8);
        }
        final int roll = ai.rand().nextInt(100);
        // its mood shows: a sad baby mostly sits and whimpers (no wobbly walking), a happy one is up to everything
        final float mood = com.aiko.coloniesalive.social.Social.mood(baby);
        final int sitChance = mood <= SAD_MOOD ? 80 : mood >= HAPPY_MOOD ? 45 : 60;
        final int crawlChance = mood <= SAD_MOOD ? 100 : mood >= HAPPY_MOOD ? 70 : 82;
        // sitting is where a baby always comes back to (always right after trying to walk), so it is the most likely by far
        if (roll < sitChance || plopDown)
        {
            final ChildActivity sit = sit(ai, 300 + ai.rand().nextInt(300));
            if (sit != null)
            {
                return sit;
            }
            if (plopDown && !baby.onGround())
            {
                // still dropping (just put down): lands first, then sits
                ai.babySitNext();
                final long since = baby.level().getGameTime();
                return (a, time) -> !baby.onGround() && time - since < 60;
            }
        }
        final BlockPos spot = home.offset(ai.rand().nextInt(ROAM * 2 + 1) - ROAM, 0, ai.rand().nextInt(ROAM * 2 + 1) - ROAM);
        return roll < crawlChance ? crawl(ai, spot, 20 * 12) : tryToWalk(ai, spot);
    }

    /** The baby's mood (-10..10, see Social) at or below which it is sad, at or above which it is happy. */
    static final float SAD_MOOD   = -3f;
    static final float HAPPY_MOOD = 3f;

    /** Grown-ups that set off for it but gave up (couldn't get there) before it falls asleep where it is. */
    private static final int MAX_FAILED_TRIPS = 3;

    /**
     * Night: someone of the family always comes to put it to bed (a busy one later; a sleeping one gets up for it);
     * meanwhile it sits, sleepy, nodding off. Only without any family (or when nobody manages to get there) it falls
     * asleep where it is. Asleep, nothing wakes it but a hit (ChildLifeAI.startCrying); in the
     * morning it wakes up by itself.
     */
    private static ChildActivity bedtime(final ChildLifeAI ai)
    {
        final EntityCitizen baby = ai.child();
        return new ChildActivity()
        {
            long    nextRequest;
            boolean hadCarer;
            int     failedTrips;

            @Override
            public boolean tick(final ChildLifeAI ai, final long time)
            {
                if (!ai.isBabyNight())
                {
                    // good morning
                    ai.wakeBaby();
                    Fx.babble(baby);
                    ai.babySitNext();
                    return false;
                }
                switch (ai.babySleep)
                {
                    case IN_BED ->
                    {
                        if (baby.getCitizenSleepHandler().isAsleep())
                        {
                            return true;
                        }
                        // got out of bed somehow: sleeps on, where it is
                        ai.sleepOnFloor();
                        return true;
                    }
                    case ON_FLOOR ->
                    {
                        baby.getNavigation().stop();
                        if (baby.getPose() != net.minecraft.world.entity.Pose.SLEEPING)
                        {
                            baby.setPose(net.minecraft.world.entity.Pose.SLEEPING);
                        }
                        return true;
                    }
                    default ->
                    {
                    }
                }
                final BabyCare carer = BabyCare.carerOf(baby);
                if (carer != null && carer.isCarrying(baby))
                {
                    return true;
                }
                if (carer != null)
                {
                    hadCarer = true;
                }
                else
                {
                    if (hadCarer)
                    {
                        // the one who was coming gave up (or couldn't get there): ask again, a few times
                        hadCarer = false;
                        failedTrips++;
                    }
                    if (failedTrips >= MAX_FAILED_TRIPS || !BabyCare.hasFamily(baby))
                    {
                        // nobody in the family at all (or nobody manages to get here): asleep where it is
                        ai.sleepOnFloor();
                        return true;
                    }
                    if (time >= nextRequest)
                    {
                        // family is busy for now: keeps asking (sleeping parents get up for it)
                        nextRequest = time + 20 * 5;
                        if (BabyCare.requestBedtime(baby))
                        {
                            hadCarer = true;
                        }
                    }
                }
                // waiting, sleepy: sitting, nodding off
                sitDown(baby, 200);
                baby.getNavigation().stop();
                if (ai.currentAnimation() != ChildAnimation.DOZE)
                {
                    ai.play(ChildAnimation.DOZE);
                }
                if (carer != null && carer.isNear(baby))
                {
                    Fx.face(baby, carer.adultEntity());
                }
                return true;
            }

            @Override
            public void stop(final ChildLifeAI ai)
            {
                if (ai.babySleep == ChildLifeAI.BabySleep.ON_FLOOR && baby.getPose() == net.minecraft.world.entity.Pose.SLEEPING)
                {
                    baby.setPose(net.minecraft.world.entity.Pose.STANDING);
                }
            }
        };
    }

    /**
     * Sick or badly hurt: it doesn't walk to the hospital, it sits and whimpers now and then; the healer comes to it
     * (BabyCare). Without a healer it rests and gets well slowly by itself.
     */
    private static ChildActivity sickRest(final ChildLifeAI ai)
    {
        final EntityCitizen baby = ai.child();
        return new ChildActivity()
        {
            long start = -1;
            long nextAsk;

            @Override
            public boolean tick(final ChildLifeAI ai, final long time)
            {
                if (start < 0)
                {
                    start = time;
                    nextAsk = time;
                    ai.play(ChildAnimation.SAD);
                }
                if (!ai.isBabyUnwell())
                {
                    ai.babySitNext();
                    return false;
                }
                sitDown(baby, 200);
                baby.getNavigation().stop();
                final BabyCare carer = BabyCare.carerOf(baby);
                if (carer == null && time >= nextAsk)
                {
                    // the healer is called (again, if nobody came)
                    BabyCare.requestHealer(baby);
                    nextAsk = time + 20 * 30;
                }
                if (carer != null && carer.isNear(baby))
                {
                    Fx.face(baby, carer.adultEntity());
                }
                if ((time - start) % 200 == 100)
                {
                    Fx.cry(baby);
                }
                // resting helps, slowly; after three minutes it is over by itself
                if ((time - start) % 40 == 0)
                {
                    baby.heal(0.5f);
                }
                if (time - start > 20 * 180 && baby.getCitizenData() != null)
                {
                    baby.getCitizenData().getCitizenDiseaseHandler().cure();
                    baby.setHealth(baby.getMaxHealth());
                }
                return true;
            }
        };
    }

    /** Tests: crawl / try to walk 3 blocks along x. */
    static ChildActivity testCrawl(final ChildLifeAI ai)
    {
        return crawl(ai, ai.child().blockPosition().offset(3, 0, 0), 20 * 6);
    }

    static ChildActivity testWalk(final ChildLifeAI ai)
    {
        return tryToWalk(ai, ai.child().blockPosition().offset(-3, 0, 0));
    }

    /** Next to the hut block of its home, else where it is. */
    private static BlockPos home(final EntityCitizen baby)
    {
        final ICitizenData data = baby.getCitizenData();
        return data != null && data.getHomeBuilding() != null ? data.getHomeBuilding().getPosition() : baby.blockPosition();
    }

    /** Sits down on the floor where it is (true if it could). */
    static boolean sitDown(final EntityCitizen baby, final int ticks)
    {
        if (baby.getVehicle() instanceof SittingEntity seat)
        {
            // still sitting from before: just stay (getting up and sitting down again in the same tick fails, the old
            // seat keeps its spot taken until it is gone)
            seat.setMaxLifeTime(ticks);
            return true;
        }
        final BlockPos ground = baby.blockPosition().below();
        final double x = baby.getX();
        final double z = baby.getZ();
        if (baby.level().getBlockState(ground).getCollisionShape(baby.level(), ground).isEmpty() || !SittingEntity.sitDown(ground, baby, ticks))
        {
            return false;
        }
        // MineColonies puts the seat in the middle of the block: the baby slid over to it. It sits down right where it is.
        if (baby.getVehicle() instanceof SittingEntity seat)
        {
            seat.setPos(x, seat.getY(), z);
            baby.setPos(x, baby.getY(), z);
        }
        return true;
    }

    /** Off the floor again (the seat puts it back on top of the block it sat on). */
    static void standUp(final EntityCitizen baby)
    {
        if (baby.getVehicle() instanceof SittingEntity)
        {
            baby.stopRiding();
        }
    }

    /**
     * Sitting and playing with its hands, babbling; now and then clapping and laughing. How it feels shows here: a happy
     * baby claps and laughs a lot, sparkling; a sad one never claps, it sits quietly and every so often its face
     * crumples into a little whimper with a few tears.
     */
    private static ChildActivity sit(final ChildLifeAI ai, final int duration)
    {
        final EntityCitizen baby = ai.child();
        if (!sitDown(baby, duration + 40))
        {
            return null;
        }
        return new ChildActivity()
        {
            long start = -1;
            long nextChange;
            long nextBabble;
            boolean clapping;
            long whimperUntil;

            @Override
            public boolean tick(final ChildLifeAI ai, final long time)
            {
                if (start < 0)
                {
                    start = time;
                    nextChange = time;
                    nextBabble = time + 20 + ai.rand().nextInt(60);
                }
                final float mood = com.aiko.coloniesalive.social.Social.mood(baby);
                final boolean sad = mood <= SAD_MOOD;
                final boolean happy = mood >= HAPPY_MOOD;
                if (time >= nextChange)
                {
                    if (sad)
                    {
                        clapping = false;
                        if (ai.rand().nextInt(mood <= -6 ? 2 : 3) == 0)
                        {
                            // a little whimper: face crumples, a few tears, a soft sob
                            whimperUntil = time + 40 + ai.rand().nextInt(30);
                            ai.broadcast(ChildAnimation.CRY, (int) (whimperUntil - time));
                            Fx.whimper(baby);
                            nextChange = whimperUntil;
                        }
                        else
                        {
                            ai.play(ChildAnimation.BABY_SIT);
                            nextChange = time + 80 + ai.rand().nextInt(80);
                        }
                    }
                    else
                    {
                        clapping = !clapping && ai.rand().nextInt(happy ? 2 : 3) == 0;
                        ai.play(clapping ? ChildAnimation.BABY_CLAP : ChildAnimation.BABY_SIT);
                        if (clapping)
                        {
                            Fx.giggle(baby);
                            Fx.happy(baby, happy ? 5 : 2);
                        }
                        nextChange = time + (clapping ? (happy ? 70 : 50) : (happy ? 60 : 100) + ai.rand().nextInt(100));
                    }
                }
                if (time < whimperUntil && time % 5 == 0)
                {
                    Fx.tears(baby);
                }
                if (happy && !clapping && time % 50 == 0)
                {
                    // beaming
                    Fx.happy(baby, 1);
                }
                if (!clapping && time >= whimperUntil && time >= nextBabble)
                {
                    if (happy && ai.rand().nextBoolean())
                    {
                        Fx.giggle(baby);
                    }
                    else if (!sad)
                    {
                        Fx.babble(baby);
                    }
                    nextBabble = time + (sad ? 400 : 200) + ai.rand().nextInt(240);
                }
                baby.getNavigation().stop();
                return time - start < duration && baby.getVehicle() instanceof SittingEntity;
            }
        };
    }

    /** On hands and knees to {@code target}, slowly, for {@code maxTicks} at most. */
    private static ChildActivity crawl(final ChildLifeAI ai, final BlockPos target, final int maxTicks)
    {
        final EntityCitizen baby = ai.child();
        standUp(baby);
        return new ChildActivity()
        {
            long start = -1;

            @Override
            public boolean tick(final ChildLifeAI ai, final long time)
            {
                if (start < 0)
                {
                    start = time;
                    ai.play(ChildAnimation.BABY_CRAWL);
                }
                final boolean there = EntityNavigationUtils.walkToPos(baby, target, 1, false, CRAWL_SPEED);
                if (ai.rand().nextInt(220) == 0)
                {
                    Fx.babble(baby);
                }
                return !there && time - start < maxTicks;
            }

            @Override
            public void stop(final ChildLifeAI ai)
            {
                baby.getNavigation().stop();
            }
        };
    }

    /** Gets up very slowly, toddles a few wobbly steps towards {@code target}, then plops down on its bottom. */
    private static ChildActivity tryToWalk(final ChildLifeAI ai, final BlockPos target)
    {
        final EntityCitizen baby = ai.child();
        standUp(baby);
        final int steps = 20 * (3 + ai.rand().nextInt(4));
        return new ChildActivity()
        {
            long start = -1;
            boolean walking;

            @Override
            public boolean tick(final ChildLifeAI ai, final long time)
            {
                if (start < 0)
                {
                    start = time;
                    ai.broadcast(ChildAnimation.BABY_RISE, ChildAnimation.BABY_RISE_TICKS + 10);
                }
                final long t = time - start;
                if (t < ChildAnimation.BABY_RISE_TICKS)
                {
                    // getting up takes all its concentration
                    baby.getNavigation().stop();
                    return true;
                }
                if (!walking)
                {
                    walking = true;
                    ai.play(ChildAnimation.BABY_TODDLE);
                    Fx.giggle(baby);
                }
                final boolean there = EntityNavigationUtils.walkToPos(baby, target, 1, false, TODDLE_SPEED);
                if (there || t >= ChildAnimation.BABY_RISE_TICKS + steps)
                {
                    // plop! back on its bottom, a little surprised
                    baby.getNavigation().stop();
                    Fx.babble(baby);
                    ai.babySitNext();
                    return false;
                }
                return true;
            }

            @Override
            public void stop(final ChildLifeAI ai)
            {
                baby.getNavigation().stop();
            }
        };
    }

    /** An older child plays with it: sits facing the child, clapping and laughing, beaming. */
    private static ChildActivity cheered(final ChildLifeAI ai, final EntityCitizen kid)
    {
        final EntityCitizen baby = ai.child();
        baby.getNavigation().stop();
        sitDown(baby, 20 * 20);
        return new ChildActivity()
        {
            long nextLaugh;

            @Override
            public boolean tick(final ChildLifeAI ai, final long time)
            {
                if (nextLaugh == 0)
                {
                    ai.play(ChildAnimation.BABY_CLAP);
                    nextLaugh = time;
                }
                baby.getNavigation().stop();
                Fx.face(baby, kid);
                if (time >= nextLaugh)
                {
                    Fx.giggle(baby);
                    Fx.happy(baby, 3);
                    nextLaugh = time + 45 + ai.rand().nextInt(30);
                }
                return ai.cheeringKid() == kid && kid.isAlive();
            }
        };
    }
}
