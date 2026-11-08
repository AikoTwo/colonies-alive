package com.aiko.coloniesalive.social;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the addon remembers about the citizens' social lives, saved with the world (overworld data storage).
 * Citizens are identified by colony id and citizen id, which MineColonies keeps stable across reloads.
 *
 * <ul>
 *     <li>{@link Person}: mood (-10 sad .. +10 happy), affinity with each player (-100 .. 100), open conversation topic</li>
 *     <li>{@link Bond}: between two citizens: friendship (-100 rivals .. 100 best friends), romance (0 .. 100),
 *     grudge (no playing together until then)</li>
 * </ul>
 */
public class SocialData extends SavedData
{
    private static final String NAME = "coloniesalive_social";

    /**
     * "Not pregnant" / "not a baby" for the tick fields. Not -1: a young world (game time under three days) puts the start
     * of a pregnancy that is already far along before tick 0, and a negative tick is a real one.
     */
    public static final long NONE = Long.MIN_VALUE;

    public static final class Person
    {
        float             mood;
        long              moodTick;
        final Map<UUID, Integer> affinity = new HashMap<>();
        /** The topic the citizen wants to talk about (shown in the MineColonies chat), or null. */
        String            openTopic;
        long              nextTopicTick;
        /** Family (see {@link Family}): game time the pregnancy started, -1 if not pregnant; the father's citizen id. */
        long              pregnantSince = SocialData.NONE;
        int               fatherId      = -1;
        /** Game time this citizen was born as a baby, -1 once it is a child (or was never a baby of ours). */
        long              infantSince   = SocialData.NONE;
        /** Next daily chance for this couple to have a baby. */
        long              nextConceptionTick;

        CompoundTag save()
        {
            final CompoundTag tag = new CompoundTag();
            if (pregnantSince != SocialData.NONE)
            {
                tag.putLong("pregnantSince", pregnantSince);
                tag.putInt("father", fatherId);
            }
            if (infantSince != SocialData.NONE)
            {
                tag.putLong("infantSince", infantSince);
            }
            tag.putLong("nextConception", nextConceptionTick);
            tag.putFloat("mood", mood);
            tag.putLong("moodTick", moodTick);
            final ListTag list = new ListTag();
            affinity.forEach((player, value) -> {
                final CompoundTag entry = new CompoundTag();
                entry.putUUID("player", player);
                entry.putInt("value", value);
                list.add(entry);
            });
            tag.put("affinity", list);
            if (openTopic != null)
            {
                tag.putString("topic", openTopic);
            }
            tag.putLong("nextTopic", nextTopicTick);
            return tag;
        }

        static Person load(final CompoundTag tag)
        {
            final Person person = new Person();
            person.mood = tag.getFloat("mood");
            person.moodTick = tag.getLong("moodTick");
            for (final Tag t : tag.getList("affinity", Tag.TAG_COMPOUND))
            {
                final CompoundTag entry = (CompoundTag) t;
                person.affinity.put(entry.getUUID("player"), entry.getInt("value"));
            }
            person.openTopic = tag.contains("topic") ? tag.getString("topic") : null;
            person.nextTopicTick = tag.getLong("nextTopic");
            person.pregnantSince = tag.contains("pregnantSince") ? tag.getLong("pregnantSince") : SocialData.NONE;
            person.fatherId = tag.contains("father") ? tag.getInt("father") : -1;
            person.infantSince = tag.contains("infantSince") ? tag.getLong("infantSince") : SocialData.NONE;
            person.nextConceptionTick = tag.getLong("nextConception");
            return person;
        }
    }

    public static final class Bond
    {
        int  friendship;
        int  romance;
        long grudgeUntil;

        CompoundTag save()
        {
            final CompoundTag tag = new CompoundTag();
            tag.putInt("friendship", friendship);
            tag.putInt("romance", romance);
            tag.putLong("grudge", grudgeUntil);
            return tag;
        }

        static Bond load(final CompoundTag tag)
        {
            final Bond bond = new Bond();
            bond.friendship = tag.getInt("friendship");
            bond.romance = tag.getInt("romance");
            bond.grudgeUntil = tag.getLong("grudge");
            return bond;
        }
    }

    final Map<String, Person> people = new HashMap<>();
    final Map<String, Bond>   bonds  = new HashMap<>();

    public static SocialData get(final MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(SocialData::new, SocialData::load, null), NAME);
    }

    static String personKey(final int colony, final int citizen)
    {
        return colony + ":" + citizen;
    }

    static String bondKey(final String a, final String b)
    {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    Person person(final String key)
    {
        return people.computeIfAbsent(key, k -> new Person());
    }

    Bond bond(final String a, final String b)
    {
        return bonds.computeIfAbsent(bondKey(a, b), k -> new Bond());
    }

    /** Read-only peek, no entry gets created. */
    Bond peekBond(final String a, final String b)
    {
        return bonds.get(bondKey(a, b));
    }

    static int clamp100(final int value)
    {
        return Mth.clamp(value, -100, 100);
    }

    @Override
    public @NotNull CompoundTag save(@NotNull final CompoundTag tag, @NotNull final HolderLookup.Provider provider)
    {
        final CompoundTag peopleTag = new CompoundTag();
        people.forEach((key, person) -> peopleTag.put(key, person.save()));
        tag.put("people", peopleTag);
        final CompoundTag bondsTag = new CompoundTag();
        bonds.forEach((key, bond) -> {
            // forget bonds that went back to nothing
            if (bond.friendship != 0 || bond.romance != 0 || bond.grudgeUntil != 0)
            {
                bondsTag.put(key, bond.save());
            }
        });
        tag.put("bonds", bondsTag);
        return tag;
    }

    static SocialData load(final CompoundTag tag, final HolderLookup.Provider provider)
    {
        final SocialData data = new SocialData();
        final CompoundTag peopleTag = tag.getCompound("people");
        for (final String key : peopleTag.getAllKeys())
        {
            data.people.put(key, Person.load(peopleTag.getCompound(key)));
        }
        final CompoundTag bondsTag = tag.getCompound("bonds");
        for (final String key : bondsTag.getAllKeys())
        {
            data.bonds.put(key, Bond.load(bondsTag.getCompound(key)));
        }
        return data;
    }
}
