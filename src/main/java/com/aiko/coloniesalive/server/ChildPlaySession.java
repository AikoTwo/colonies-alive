package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ChildAnimation;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import com.aiko.coloniesalive.social.Social;
import com.aiko.coloniesalive.social.Topics;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A game played by two or more children. The leader (who started it) drives the game; the others are told what to do.
 */
public class ChildPlaySession
{
    public enum Kind
    {
        PATTY_CAKE(2, 2, 3),
        TAG(2, 2, 3),
        HUG(2, 2, 2),
        WHISPER(2, 2, 2),
        CIRANDA(3, 4, 3),
        FOLLOW_LEADER(2, 4, 2),
        /** Not picked at random: only between rivals, or as an occasional squabble. */
        ARGUE(2, 2, 0),
        /** Not picked at random: after a fight, once the grudge has worn off. */
        MAKE_UP(2, 2, 0);

        final int min;
        final int max;
        final int weight;

        Kind(final int min, final int max, final int weight)
        {
            this.min = min;
            this.max = max;
            this.weight = weight;
        }
    }

    /** How far away a child looks for someone to play with. */
    public static final double SEARCH_RADIUS = 24;

    private static final int    JOIN_TIMEOUT    = 20 * 8;
    private static final int    GATHER_TIMEOUT  = 20 * 20;
    private static final int    PATH_REFRESH    = 12;
    private static final int    TAG_FREEZE      = 40;
    private static final double TAG_DISTANCE_SQ = 1.8 * 1.8;
    private static final Random RAND            = new Random();

    private final Kind              kind;
    private final ChildLifeAI       leader;
    /** Everyone taking part, leader first. */
    private final List<ChildLifeAI> members = new ArrayList<>();
    private final Set<ChildLifeAI>  joined  = new HashSet<>();
    private final long              createdTick;

    private boolean started;
    /** Played at recess in the school yard. */
    private boolean recess;
    private boolean over;
    /** Free per-game phase counter and its start time. */
    private int     phase;
    private long    phaseStart;
    private long    endTick;

    // tag
    private ChildLifeAI chaser;
    private ChildLifeAI runner;
    private long        freezeUntil;
    private long        nextPath;

    // ciranda
    private double centerX;
    private double centerY;
    private double centerZ;
    private double angle;
    private double spinSpeed;

    // follow the leader
    private IBuilding waypoint;
    private int       waypointsLeft;

    private ChildPlaySession(final Kind kind, final ChildLifeAI leader, final List<ChildLifeAI> partners)
    {
        this.kind = kind;
        this.leader = leader;
        this.members.add(leader);
        this.members.addAll(partners);
        this.joined.add(leader);
        this.createdTick = leader.now();
    }

    /**
     * Tries to set up a game for this child with free children nearby.
     *
     * @return the new session or null if there is nobody to play with
     */
    public static ChildPlaySession tryCreate(final ChildLifeAI leader)
    {
        final List<ChildLifeAI> available = ChildLifeEvents.findPlayMates(leader, 3);
        if (available.isEmpty())
        {
            return null;
        }

        // First the feelings: somebody to make up with, or somebody we can't stand
        final EntityCitizen self = leader.child();
        for (final ChildLifeAI other : available)
        {
            if (Social.wantsToMakeUp(self, other.child()))
            {
                return invite(Kind.MAKE_UP, leader, List.of(other));
            }
        }
        final ChildLifeAI closest = available.get(0);
        final int friendship = Social.friendship(self, closest.child());
        if ((friendship <= Social.RIVAL && RAND.nextBoolean()) || (friendship < 10 && RAND.nextInt(100) < 6))
        {
            return invite(Kind.ARGUE, leader, List.of(closest));
        }

        final Kind kind = pickKind(available.size() + 1);
        return invite(kind, leader, new ArrayList<>(available.subList(0, Math.min(available.size(), kind.max - 1))));
    }

    private static ChildPlaySession invite(final Kind kind, final ChildLifeAI leader, final List<ChildLifeAI> partners)
    {
        final ChildPlaySession session = new ChildPlaySession(kind, leader, partners);
        partners.forEach(p -> p.invite(session));
        return session;
    }

    /** Games that fit in a school yard: no running off. */
    private static final Kind[] RECESS_GAMES = {Kind.PATTY_CAKE, Kind.WHISPER, Kind.HUG};

    /**
     * A game at recess, with pupils of the same school that are on recess too. Afterwards MineColonies sends them
     * back to class.
     */
    public static ChildPlaySession tryCreateRecess(final ChildLifeAI leader)
    {
        final List<ChildLifeAI> mates = ChildLifeEvents.findRecessMates(leader, 3);
        if (mates.isEmpty())
        {
            return null;
        }
        final Kind kind = mates.size() >= 2 && RAND.nextInt(3) == 0 ? Kind.CIRANDA : RECESS_GAMES[RAND.nextInt(RECESS_GAMES.length)];
        final ChildPlaySession session = new ChildPlaySession(kind, leader, new ArrayList<>(mates.subList(0, Math.min(mates.size(), kind.max - 1))));
        session.recess = true;
        session.members.stream().skip(1).forEach(p -> p.invite(session));
        return session;
    }

    public boolean isRecess()
    {
        return recess;
    }

    /**
     * A game ended well: everybody likes everybody a bit more, and is in a better mood. Sometimes one of them
     * wants to tell the player about it.
     */
    private void reward(final int friendshipGain)
    {
        for (int i = 0; i < members.size(); i++)
        {
            Social.addMood(members.get(i).child(), 1.5f);
            for (int j = i + 1; j < members.size(); j++)
            {
                Social.addFriendship(members.get(i).child(), members.get(j).child(), friendshipGain);
            }
        }
        if (RAND.nextInt(4) == 0 && members.size() >= 2)
        {
            final int teller = RAND.nextInt(members.size());
            final EntityCitizen friend = members.get((teller + 1) % members.size()).child();
            Topics.raise(members.get(teller).child(), Topics.Topic.PLAYED, friend.getCitizenData().getName(),
              Component.translatable("coloniesalive.game." + kind.name().toLowerCase()));
        }
    }

    private static Kind pickKind(final int players)
    {
        int total = 0;
        for (final Kind k : Kind.values())
        {
            if (k.min <= players)
            {
                total += k.weight;
            }
        }
        int roll = RAND.nextInt(total);
        for (final Kind k : Kind.values())
        {
            if (k.min <= players)
            {
                roll -= k.weight;
                if (roll < 0)
                {
                    return k;
                }
            }
        }
        return Kind.PATTY_CAKE;
    }

    public boolean isOver()
    {
        return over;
    }

    public String describe()
    {
        return kind + " with " + (members.size() - 1) + " friend(s)";
    }

    public boolean isMember(final ChildLifeAI ai)
    {
        return members.contains(ai);
    }

    /**
     * Called by an invited child that accepted.
     *
     * @return false if the game already started without it
     */
    public boolean onJoined(final ChildLifeAI ai)
    {
        if (started || over || !members.contains(ai))
        {
            return false;
        }
        joined.add(ai);
        return true;
    }

    /** A member dropped out (interrupted by hunger, a raid, ...). */
    public void leave(final ChildLifeAI ai)
    {
        members.remove(ai);
        joined.remove(ai);
    }

    public void end()
    {
        over = true;
        for (final ChildLifeAI member : members)
        {
            member.cancelInvitation(this);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Driven by the leader's tick.
     */
    public void tick()
    {
        if (over)
        {
            return;
        }
        final long time = leader.now();

        if (!started)
        {
            if (joined.size() < members.size() && time - createdTick < JOIN_TIMEOUT)
            {
                return;
            }
            // Play with whoever showed up
            for (final ChildLifeAI member : new ArrayList<>(members))
            {
                if (!joined.contains(member))
                {
                    member.cancelInvitation(this);
                    members.remove(member);
                }
            }
            // a ring dance with only two of the invited showing up is still fun, so two is always enough
            if (members.size() < 2)
            {
                end();
                return;
            }
            started = true;
            setPhase(0, time);
        }

        // Drop children that can't play anymore
        for (final ChildLifeAI member : new ArrayList<>(members))
        {
            final EntityCitizen child = member.child();
            if (!child.isAlive() || !child.isBaby() || !member.isActing() || child.level() != leader.child().level()
                  || child.distanceToSqr(leader.child()) > 32 * 32)
            {
                members.remove(member);
            }
        }
        if (!members.contains(leader) || members.size() < 2)
        {
            end();
            return;
        }

        switch (kind)
        {
            case PATTY_CAKE -> tickPattyCake(time);
            case TAG -> tickTag(time);
            case HUG -> tickHug(time);
            case WHISPER -> tickWhisper(time);
            case CIRANDA -> tickCiranda(time);
            case FOLLOW_LEADER -> tickFollowLeader(time);
            case ARGUE -> tickArgue(time);
            case MAKE_UP -> tickMakeUp(time);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------

    private void tickArgue(final long time)
    {
        switch (phase)
        {
            case 0 ->
            {
                if (gather(time))
                {
                    final int duration = 60 + RAND.nextInt(40);
                    broadcastAll(ChildAnimation.ARGUE, duration);
                    Fx.grumble(a());
                    setPhase(1, time);
                }
            }
            case 1 ->
            {
                faceEachOther();
                final long elapsed = time - phaseStart;
                if (elapsed % 14 == 0)
                {
                    Fx.angry(RAND.nextBoolean() ? a() : b());
                }
                if (elapsed % 30 == 15)
                {
                    Fx.grumble(RAND.nextBoolean() ? a() : b());
                }
                if (elapsed >= 70)
                {
                    // turn their backs on each other and sulk
                    broadcastAll(ChildAnimation.POUT, 50);
                    Fx.face(a(), a().getX() * 2 - b().getX(), a().getZ() * 2 - b().getZ());
                    Fx.face(b(), b().getX() * 2 - a().getX(), b().getZ() * 2 - a().getZ());
                    setPhase(2, time);
                }
            }
            default ->
            {
                if (time - phaseStart >= 50)
                {
                    Social.addFriendship(a(), b(), -12);
                    Social.addMood(a(), -2f);
                    Social.addMood(b(), -2f);
                    Social.setGrudge(a(), b(), 20 * 60 * 4 + RAND.nextInt(20 * 60 * 2));
                    final boolean first = RAND.nextBoolean();
                    Topics.raise(first ? a() : b(), Topics.Topic.FIGHT, (first ? b() : a()).getCitizenData().getName());
                    end();
                }
            }
        }
    }

    private void tickMakeUp(final long time)
    {
        switch (phase)
        {
            case 0 ->
            {
                if (gather(time))
                {
                    // the one who comes over says sorry first
                    members.get(0).broadcast(ChildAnimation.SORRY, 50);
                    setPhase(1, time);
                }
            }
            case 1 ->
            {
                faceEachOther();
                if (time - phaseStart >= 50)
                {
                    broadcastAll(ChildAnimation.HUG, 60);
                    Fx.hearts(a(), 3);
                    Fx.hearts(b(), 3);
                    setPhase(2, time);
                }
            }
            default ->
            {
                faceEachOther();
                if (time - phaseStart == 30)
                {
                    giggleAll();
                }
                if (time - phaseStart >= 60)
                {
                    Social.setGrudge(a(), b(), 0);
                    Social.addFriendship(a(), b(), 12);
                    Social.addMood(a(), 2f);
                    Social.addMood(b(), 2f);
                    if (RAND.nextInt(3) == 0)
                    {
                        Topics.raise(a(), Topics.Topic.MADE_UP, b().getCitizenData().getName());
                    }
                    end();
                }
            }
        }
    }

    private void setPhase(final int phase, final long time)
    {
        this.phase = phase;
        this.phaseStart = time;
    }

    private EntityCitizen a()
    {
        return members.get(0).child();
    }

    private EntityCitizen b()
    {
        return members.get(1).child();
    }

    /**
     * Phase 0 of the two-player games: both walk towards each other (the partner waits once they are close).
     *
     * @return true once they are standing next to each other
     */
    private boolean gather(final long time)
    {
        final EntityCitizen a = a();
        final EntityCitizen b = b();
        if (time - phaseStart > GATHER_TIMEOUT)
        {
            end();
            return false;
        }
        final double distanceSq = a.distanceToSqr(b);
        if (distanceSq > 1.4 * 1.4)
        {
            if (time >= nextPath)
            {
                nextPath = time + 10;
                Moves.follow(a, b, 1.15);
                if (distanceSq > 4 * 4)
                {
                    Moves.follow(b, a, 1.15);
                }
                else
                {
                    b.getNavigation().stop();
                    Fx.face(b, a);
                }
            }
            return false;
        }
        a.getNavigation().stop();
        b.getNavigation().stop();
        return true;
    }

    private void faceEachOther()
    {
        Fx.face(a(), b());
        Fx.face(b(), a());
    }

    private void broadcastAll(final ChildAnimation animation, final int duration)
    {
        members.forEach(m -> m.broadcast(animation, duration));
    }

    private void giggleAll()
    {
        members.forEach(m -> Fx.giggle(m.child()));
    }

    // ------------------------------------------------------------------------------------------------------------------

    private void tickPattyCake(final long time)
    {
        if (phase == 0)
        {
            if (gather(time))
            {
                final int duration = 20 * 8 + RAND.nextInt(20 * 8);
                endTick = time + duration;
                broadcastAll(ChildAnimation.PATTY_CAKE, duration);
                setPhase(1, time);
            }
            return;
        }

        faceEachOther();
        final long elapsed = time - phaseStart;
        // one clap roughly every 10 ticks, matching the client animation speed
        if (elapsed % 10 == 0)
        {
            Fx.between(a(), b(), ParticleTypes.CRIT, 3);
        }
        if (elapsed % 20 == 0)
        {
            Fx.note(RAND.nextBoolean() ? a() : b());
        }
        if (time >= endTick)
        {
            giggleAll();
            members.forEach(m -> Fx.happy(m.child(), 4));
            reward(5);
            end();
        }
    }

    private void tickTag(final long time)
    {
        if (phase == 0)
        {
            final boolean leaderChases = RAND.nextBoolean();
            chaser = leaderChases ? members.get(0) : members.get(1);
            runner = leaderChases ? members.get(1) : members.get(0);
            final int duration = 20 * 25 + RAND.nextInt(20 * 20);
            endTick = time + duration;
            freezeUntil = time + TAG_FREEZE;
            assignTagRoles(duration);
            Fx.giggle(runner.child());
            setPhase(1, time);
        }

        if (time >= endTick)
        {
            giggleAll();
            reward(4);
            end();
            return;
        }

        final EntityCitizen chaserEntity = chaser.child();
        final EntityCitizen runnerEntity = runner.child();

        if (time < freezeUntil)
        {
            // The one who was just tagged counts to three before chasing
            chaserEntity.getNavigation().stop();
            Fx.face(chaserEntity, runnerEntity);
        }
        else if (chaserEntity.distanceToSqr(runnerEntity) <= TAG_DISTANCE_SQ)
        {
            // Tagged! Swap roles
            Fx.happy(runnerEntity, 6);
            Fx.giggle(chaserEntity);
            Fx.giggle(runnerEntity);
            final ChildLifeAI previousChaser = chaser;
            chaser = runner;
            runner = previousChaser;
            freezeUntil = time + TAG_FREEZE;
            assignTagRoles((int) (endTick - time));
            chaser.child().getNavigation().stop();
            runner.child().getNavigation().stop();
            Moves.reset(chaser.child());
            Moves.reset(runner.child());
            return;
        }

        if (time >= nextPath)
        {
            nextPath = time + PATH_REFRESH;
            if (time >= freezeUntil)
            {
                Moves.follow(chaserEntity, runnerEntity, 1.3);
            }
            Moves.flee(runnerEntity, chaserEntity, 12, 1.3);
        }
    }

    private void assignTagRoles(final int duration)
    {
        chaser.broadcast(ChildAnimation.TAG_CHASER, duration);
        runner.broadcast(ChildAnimation.TAG_RUNNER, duration);
    }

    private void tickHug(final long time)
    {
        if (phase == 0)
        {
            if (gather(time))
            {
                final int duration = 60 + RAND.nextInt(40);
                endTick = time + duration;
                broadcastAll(ChildAnimation.HUG, duration);
                Fx.hearts(a(), 3);
                setPhase(1, time);
            }
            return;
        }
        faceEachOther();
        if ((time - phaseStart) % 20 == 10)
        {
            Fx.hearts(RAND.nextBoolean() ? a() : b(), 2);
        }
        if (time >= endTick)
        {
            Fx.giggle(b());
            reward(8);
            end();
        }
    }

    private void tickWhisper(final long time)
    {
        switch (phase)
        {
            case 0 ->
            {
                if (gather(time))
                {
                    members.get(0).broadcast(ChildAnimation.WHISPER, 60);
                    setPhase(1, time);
                }
            }
            case 1 ->
            {
                faceEachOther();
                if (time - phaseStart >= 60)
                {
                    broadcastAll(ChildAnimation.GIGGLE, 60);
                    giggleAll();
                    setPhase(2, time);
                }
            }
            default ->
            {
                faceEachOther();
                if (time - phaseStart == 30)
                {
                    Fx.giggle(b());
                }
                if (time - phaseStart >= 60)
                {
                    reward(6);
                    end();
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------

    private void tickCiranda(final long time)
    {
        final int count = members.size();
        final double radius = 0.9 + 0.25 * count;

        if (phase == 0 && time == phaseStart)
        {
            centerX = members.stream().mapToDouble(m -> m.child().getX()).average().orElse(leader.child().getX());
            centerZ = members.stream().mapToDouble(m -> m.child().getZ()).average().orElse(leader.child().getZ());
            centerY = leader.child().getY();
            angle = RAND.nextDouble() * Math.PI * 2;
            spinSpeed = 0.035;
        }

        if (phase == 0)
        {
            // walk to the spots around the circle
            boolean allThere = true;
            for (int i = 0; i < count; i++)
            {
                final EntityCitizen child = members.get(i).child();
                final double sx = centerX + Math.cos(angle + i * Math.PI * 2 / count) * radius;
                final double sz = centerZ + Math.sin(angle + i * Math.PI * 2 / count) * radius;
                if (child.distanceToSqr(sx, child.getY(), sz) > 0.6 * 0.6)
                {
                    allThere = false;
                    child.getMoveControl().setWantedPosition(sx, centerY, sz, 0.9);
                }
                else
                {
                    child.getNavigation().stop();
                    Fx.face(child, centerX, centerZ);
                }
            }
            if (allThere || time - phaseStart > 20 * 6)
            {
                final int duration = 20 * 15 + RAND.nextInt(20 * 10);
                endTick = time + duration;
                broadcastAll(ChildAnimation.CIRANDA, duration);
                giggleAll();
                setPhase(1, time);
            }
            return;
        }

        final long elapsed = time - phaseStart;
        // change direction halfway, like a real ciranda
        if (elapsed == (endTick - phaseStart) / 2)
        {
            spinSpeed = -spinSpeed;
            giggleAll();
        }
        angle += spinSpeed * 2; // this runs every 2 ticks
        for (int i = 0; i < count; i++)
        {
            final EntityCitizen child = members.get(i).child();
            final double sx = centerX + Math.cos(angle + i * Math.PI * 2 / count) * radius;
            final double sz = centerZ + Math.sin(angle + i * Math.PI * 2 / count) * radius;
            child.getNavigation().stop();
            child.getMoveControl().setWantedPosition(sx, centerY, sz, 0.7);
            Fx.face(child, centerX, centerZ);
        }
        if (elapsed % 12 == 0)
        {
            Fx.note(members.get(RAND.nextInt(count)).child());
        }
        if (elapsed % 90 == 45)
        {
            Fx.giggle(members.get(RAND.nextInt(count)).child());
        }
        if (time >= endTick)
        {
            members.forEach(m -> Fx.happy(m.child(), 4));
            reward(3);
            end();
        }
    }

    private void tickFollowLeader(final long time)
    {
        final EntityCitizen head = leader.child();

        if (phase == 0)
        {
            waypointsLeft = 2 + RAND.nextInt(3);
            waypoint = Activities.randomBuilding(leader);
            if (waypoint == null)
            {
                end();
                return;
            }
            endTick = time + 20 * 60;
            leader.play(ChildAnimation.LEADER);
            for (int i = 1; i < members.size(); i++)
            {
                members.get(i).play(ChildAnimation.MARCH);
            }
            Fx.giggle(head);
            setPhase(1, time);
        }

        if (time >= endTick)
        {
            giggleAll();
            reward(3);
            end();
            return;
        }

        if (EntityNavigationUtils.walkToBuilding(head, waypoint))
        {
            waypointsLeft--;
            Fx.happy(head, 3);
            waypoint = Activities.randomBuilding(leader);
            if (waypointsLeft <= 0 || waypoint == null)
            {
                giggleAll();
                reward(3);
                end();
                return;
            }
        }

        if (time >= nextPath)
        {
            nextPath = time + 10;
            // each follower trails the one in front of it
            for (int i = 1; i < members.size(); i++)
            {
                final EntityCitizen follower = members.get(i).child();
                final EntityCitizen ahead = members.get(i - 1).child();
                if (follower.distanceToSqr(ahead) > 2.2 * 2.2)
                {
                    Moves.follow(follower, ahead, 1.15);
                }
                else
                {
                    follower.getNavigation().stop();
                }
            }
        }

        if ((time - phaseStart) % 40 == 0)
        {
            Fx.note(head);
        }
    }
}
