package com.aiko.coloniesalive.social;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Entry point for the social life of the citizens: mood, friendships and rivalries, romance and how much they like
 * each player. Server side only. All values live in {@link SocialData}.
 *
 * <p>The mood is purely the addon's: it changes what citizens do and how they react, never MineColonies' happiness.
 * MineColonies' happiness only nudges where the mood settles over time.
 */
public final class Social
{
    public static final int BEST_FRIEND = 60;
    public static final int FRIEND      = 25;
    public static final int RIVAL       = -25;
    public static final int CRUSH       = 30;
    public static final int IN_LOVE     = 55;

    /** The mood moves one point towards its resting value per game minute. */
    private static final int MOOD_DECAY_TICKS = 1200;

    /** Rewards from the same player to the same citizen at most this often (e.g. picking up again and again). */
    private static final long AFFINITY_GAIN_COOLDOWN = 20 * 60 * 2;
    private static final Map<String, Long> GAIN_READY = new HashMap<>();

    private Social() {}

    // ------------------------------------------------------------------------------------------------------------------
    // Keys and storage
    // ------------------------------------------------------------------------------------------------------------------

    static SocialData data(final MinecraftServer server)
    {
        return SocialData.get(server);
    }

    private static SocialData data(final EntityCitizen citizen)
    {
        return SocialData.get(citizen.getServer());
    }

    public static String key(final EntityCitizen citizen)
    {
        return SocialData.personKey(citizen.getCitizenColonyHandler().getColonyId(), citizen.getCivilianID());
    }

    public static String key(final ICitizenData data)
    {
        return SocialData.personKey(data.getColony().getID(), data.getId());
    }

    private static boolean ready(final EntityCitizen citizen)
    {
        return citizen.getServer() != null && citizen.getCitizenData() != null;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Mood
    // ------------------------------------------------------------------------------------------------------------------

    /** -10 (miserable) .. +10 (overjoyed). */
    public static float mood(final EntityCitizen citizen)
    {
        if (!ready(citizen))
        {
            return 0;
        }
        final SocialData.Person person = data(citizen).person(key(citizen));
        settleMood(citizen, person);
        return person.mood;
    }

    public static void addMood(final EntityCitizen citizen, final float delta)
    {
        if (!ready(citizen))
        {
            return;
        }
        final SocialData data = data(citizen);
        final SocialData.Person person = data.person(key(citizen));
        settleMood(citizen, person);
        person.mood = Mth.clamp(person.mood + delta, -10f, 10f);
        data.setDirty();
    }

    /** Lets the mood drift towards its resting value for the time that passed since it was last looked at. */
    private static void settleMood(final EntityCitizen citizen, final SocialData.Person person)
    {
        final long now = citizen.level().getGameTime();
        if (person.moodTick == 0)
        {
            person.moodTick = now;
            return;
        }
        final long steps = (now - person.moodTick) / MOOD_DECAY_TICKS;
        if (steps <= 0)
        {
            return;
        }
        person.moodTick += steps * MOOD_DECAY_TICKS;
        final float rest = restingMood(citizen);
        final float diff = rest - person.mood;
        person.mood += Math.signum(diff) * Math.min(Math.abs(diff), steps);
    }

    /** A happy colony (MineColonies' happiness, 0..10) lets moods settle a little above neutral, an unhappy one below. */
    private static float restingMood(final EntityCitizen citizen)
    {
        final ICitizenData data = citizen.getCitizenData();
        final IColony colony = citizen.getCitizenColonyHandler().getColony();
        if (data == null || colony == null)
        {
            return 0;
        }
        return (float) Mth.clamp((data.getCitizenHappinessHandler().getHappiness(colony, data) - 5.0) * 0.6, -3.0, 3.0);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Bonds between citizens
    // ------------------------------------------------------------------------------------------------------------------

    public static int friendship(final EntityCitizen a, final EntityCitizen b)
    {
        if (!ready(a) || !ready(b))
        {
            return 0;
        }
        final SocialData.Bond bond = data(a).peekBond(key(a), key(b));
        return bond == null ? 0 : bond.friendship;
    }

    /**
     * Changes the friendship and tells about milestones (new best friend, new rival).
     *
     * @return the new value
     */
    public static int addFriendship(final EntityCitizen a, final EntityCitizen b, final int delta)
    {
        if (!ready(a) || !ready(b))
        {
            return 0;
        }
        final SocialData data = data(a);
        final SocialData.Bond bond = data.bond(key(a), key(b));
        final int before = bond.friendship;
        bond.friendship = SocialData.clamp100(before + delta);
        data.setDirty();

        if (before < BEST_FRIEND && bond.friendship >= BEST_FRIEND)
        {
            Topics.raise(a, Topics.Topic.BEST_FRIEND, b.getCitizenData().getName());
            Topics.raise(b, Topics.Topic.BEST_FRIEND, a.getCitizenData().getName());
        }
        return bond.friendship;
    }

    public static int romance(final EntityCitizen a, final EntityCitizen b)
    {
        if (!ready(a) || !ready(b))
        {
            return 0;
        }
        final SocialData.Bond bond = data(a).peekBond(key(a), key(b));
        return bond == null ? 0 : bond.romance;
    }

    public static int addRomance(final EntityCitizen a, final EntityCitizen b, final int delta)
    {
        if (!ready(a) || !ready(b))
        {
            return 0;
        }
        final SocialData data = data(a);
        final SocialData.Bond bond = data.bond(key(a), key(b));
        final int before = bond.romance;
        bond.romance = Mth.clamp(before + delta, 0, 100);
        data.setDirty();
        if (before < CRUSH && bond.romance >= CRUSH)
        {
            Topics.raise(a, Topics.Topic.CRUSH, b.getCitizenData().getName());
        }
        return bond.romance;
    }

    /** True while the two are cross with each other (they won't play together). */
    public static boolean hasGrudge(final EntityCitizen a, final EntityCitizen b)
    {
        if (!ready(a) || !ready(b))
        {
            return false;
        }
        final SocialData.Bond bond = data(a).peekBond(key(a), key(b));
        return bond != null && bond.grudgeUntil > a.level().getGameTime();
    }

    /** True if they fell out earlier and the grudge has worn off, but they haven't made up yet. */
    public static boolean wantsToMakeUp(final EntityCitizen a, final EntityCitizen b)
    {
        if (!ready(a) || !ready(b))
        {
            return false;
        }
        final SocialData.Bond bond = data(a).peekBond(key(a), key(b));
        return bond != null && bond.grudgeUntil != 0 && bond.grudgeUntil <= a.level().getGameTime();
    }

    public static void setGrudge(final EntityCitizen a, final EntityCitizen b, final int ticks)
    {
        if (!ready(a) || !ready(b))
        {
            return;
        }
        final SocialData data = data(a);
        data.bond(key(a), key(b)).grudgeUntil = ticks <= 0 ? 0 : a.level().getGameTime() + ticks;
        data.setDirty();
    }

    /** Partner as MineColonies knows it (the addon's romance ends there). */
    public static boolean arePartners(final EntityCitizen a, final EntityCitizen b)
    {
        final ICitizenData da = a.getCitizenData();
        return da != null && da.getPartner() != null && b.getCitizenData() != null && da.getPartner().getId() == b.getCitizenData().getId();
    }

    /** Can these two fall in love? Adults, both free, not family. */
    public static boolean canDate(final EntityCitizen a, final EntityCitizen b)
    {
        final ICitizenData da = a.getCitizenData();
        final ICitizenData db = b.getCitizenData();
        return da != null && db != null && !a.isBaby() && !b.isBaby()
                 && da.getPartner() == null && db.getPartner() == null
                 && !da.isRelatedTo(db);
    }

    /** They become a couple, also for MineColonies (its family system uses partners). */
    public static void becomeCouple(final EntityCitizen a, final EntityCitizen b)
    {
        a.getCitizenData().setPartner(b.getCitizenData().getId());
        b.getCitizenData().setPartner(a.getCitizenData().getId());
        a.getCitizenData().markDirty(0);
        b.getCitizenData().markDirty(0);
        Topics.raise(a, Topics.Topic.DATING, b.getCitizenData().getName());
        Topics.raise(b, Topics.Topic.DATING, a.getCitizenData().getName());
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Players
    // ------------------------------------------------------------------------------------------------------------------

    /** -100 .. 100: how much this citizen likes the player. */
    public static int affinity(final EntityCitizen citizen, final Player player)
    {
        if (!ready(citizen))
        {
            return 0;
        }
        return data(citizen).person(key(citizen)).affinity.getOrDefault(player.getUUID(), 0);
    }

    public static void addAffinity(final EntityCitizen citizen, final Player player, final int delta)
    {
        if (!ready(citizen))
        {
            return;
        }
        if (delta > 0)
        {
            final String gainKey = key(citizen) + "|" + player.getUUID();
            final long now = citizen.level().getGameTime();
            final Long readyAt = GAIN_READY.get(gainKey);
            if (readyAt != null && now < readyAt)
            {
                return;
            }
            GAIN_READY.put(gainKey, now + AFFINITY_GAIN_COOLDOWN);
        }
        addAffinityNow(data(citizen), key(citizen), player.getUUID(), delta);
    }

    static void addAffinityNow(final SocialData data, final String citizenKey, final UUID player, final int delta)
    {
        data.person(citizenKey).affinity.merge(player, delta, (a, b) -> SocialData.clamp100(a + b));
        data.setDirty();
    }
}
