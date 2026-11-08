package com.aiko.coloniesalive.social;

import com.aiko.coloniesalive.social.Topics.Topic;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * When a player is around, citizens strike up a conversation about how they are doing right now: hungry, sick, no home
 * or job, the rain, the night, their work, their mood, what they think of the player, their best friend, partner or
 * children... Every topic waits in the MineColonies chat, so there is always something to answer (and the answer
 * changes the affinity, see {@link Topics#answered}).
 */
public final class SmallTalk
{
    /** Looks for something to say every this many ticks. */
    private static final int INTERVAL = 20 * 10;
    /** On top of the usual gap between topics, so the topics about real events come first. */
    private static final int EXTRA_GAP = 20 * 30;
    private static final double PLAYER_RADIUS = 10;

    private static final Random RAND = new Random();

    private SmallTalk() {}

    private record Option(Topic topic, int weight, Object[] args) {}

    public static void tick(final EntityCitizen citizen)
    {
        if ((citizen.tickCount + citizen.getId() * 7) % INTERVAL != 0 || citizen.getServer() == null)
        {
            return;
        }
        final ICitizenData data = citizen.getCitizenData();
        if (data == null || data.getColony() == null || data.isAsleep() || !citizen.isAlive() || RAND.nextInt(3) == 0 || Family.isInfant(citizen))
        {
            return;
        }
        final ServerPlayer player = closestPlayer(citizen);
        if (player == null)
        {
            return;
        }
        final SocialData social = Social.data(citizen.getServer());
        final String key = Social.key(citizen);
        final SocialData.Person person = social.person(key);
        if (person.openTopic != null || citizen.level().getGameTime() < person.nextTopicTick + EXTRA_GAP)
        {
            return;
        }

        final List<Option> options = options(citizen, data, social, key, player);
        int total = 0;
        for (final Option option : options)
        {
            total += option.weight();
        }
        int roll = RAND.nextInt(total);
        for (final Option option : options)
        {
            roll -= option.weight();
            if (roll < 0)
            {
                Topics.raise(citizen, option.topic(), option.args());
                return;
            }
        }
    }

    private static List<Option> options(final EntityCitizen citizen, final ICitizenData data, final SocialData social, final String key, final ServerPlayer player)
    {
        final List<Option> options = new ArrayList<>();
        final boolean child = citizen.isBaby();
        final Level level = citizen.level();
        final IColony colony = data.getColony();

        // needs first: they are what weighs on the citizen
        if (data.getCitizenDiseaseHandler() != null && data.getCitizenDiseaseHandler().isSick())
        {
            options.add(new Option(Topic.SICK, 6, new Object[0]));
        }
        if (data.getSaturation() < 6)
        {
            options.add(new Option(Topic.HUNGRY, 5, new Object[0]));
        }
        if (data.getHomeBuilding() == null)
        {
            options.add(new Option(Topic.HOMELESS, 4, new Object[0]));
        }
        if (!child)
        {
            if (data.getJob() == null)
            {
                options.add(new Option(Topic.JOBLESS, 3, new Object[0]));
            }
            else
            {
                options.add(new Option(Topic.WORK, 2, new Object[] {Component.translatable(data.getJob().getJobRegistryEntry().getTranslationKey())}));
            }
        }
        else
        {
            options.add(new Option(Topic.WORK, 1, new Object[0]));
        }

        // how it feels, and what it thinks of you
        final float mood = Social.mood(citizen);
        if (mood >= 5)
        {
            options.add(new Option(Topic.CHEERFUL, 3, new Object[0]));
        }
        else if (mood <= -4)
        {
            options.add(new Option(Topic.GLOOMY, 4, new Object[0]));
        }
        final int affinity = social.person(key).affinity.getOrDefault(player.getUUID(), 0);
        if (affinity >= 45)
        {
            options.add(new Option(Topic.LIKES_YOU, 3, new Object[] {player.getName()}));
        }
        else if (affinity <= -25)
        {
            options.add(new Option(Topic.DISLIKES_YOU, 4, new Object[] {player.getName()}));
        }

        // the people in its life
        final String friend = bestFriend(social, key, colony);
        if (friend != null)
        {
            options.add(new Option(Topic.FRIEND_TALK, 3, new Object[] {friend}));
        }
        if (!child && data.getPartner() != null)
        {
            options.add(new Option(Topic.PARTNER_TALK, 2, new Object[] {data.getPartner().getName()}));
            // a couple that could have a baby talks about it; a kind answer from the player and it is on its way
            if (Family.coupleCanHaveBaby(citizen))
            {
                options.add(new Option(Topic.BABY_WISH, 3, new Object[] {data.getPartner().getName()}));
            }
        }
        if (Family.isPregnant(citizen))
        {
            options.add(new Option(Topic.PREGNANT_TALK, 6, new Object[0]));
        }
        if (!child && !data.getChildren().isEmpty())
        {
            final ICitizenData kid = colony.getCitizenManager().getCivilian(data.getChildren().get(RAND.nextInt(data.getChildren().size())));
            if (kid != null)
            {
                options.add(new Option(Topic.KIDS_TALK, 2, new Object[] {kid.getName()}));
            }
        }

        // the world around it
        if (level.isRainingAt(citizen.blockPosition().above()))
        {
            options.add(new Option(Topic.RAIN, 3, new Object[0]));
        }
        if (level.isNight())
        {
            options.add(new Option(Topic.NIGHT, 2, new Object[0]));
        }

        options.add(new Option(Topic.CHATTER, 3, new Object[0]));
        return options;
    }

    /** Name of its closest friend in the colony (friendship at least {@link Social#FRIEND}), or null. */
    private static String bestFriend(final SocialData social, final String self, final IColony colony)
    {
        final String prefix = colony.getID() + ":";
        String best = null;
        int bestValue = Social.FRIEND - 1;
        for (final Map.Entry<String, SocialData.Bond> entry : social.bonds.entrySet())
        {
            final SocialData.Bond bond = entry.getValue();
            if (bond.friendship <= bestValue)
            {
                continue;
            }
            final String[] pair = entry.getKey().split("\\|");
            if (pair.length != 2 || !(pair[0].equals(self) || pair[1].equals(self)))
            {
                continue;
            }
            final String other = pair[0].equals(self) ? pair[1] : pair[0];
            if (!other.startsWith(prefix))
            {
                continue;
            }
            try
            {
                final ICitizenData data = colony.getCitizenManager().getCivilian(Integer.parseInt(other.substring(prefix.length())));
                if (data != null)
                {
                    best = data.getName();
                    bestValue = bond.friendship;
                }
            }
            catch (final NumberFormatException ignored)
            {
                // not one of ours
            }
        }
        return best;
    }

    private static ServerPlayer closestPlayer(final EntityCitizen citizen)
    {
        ServerPlayer best = null;
        double bestDistance = PLAYER_RADIUS * PLAYER_RADIUS;
        for (final ServerPlayer player : ((net.minecraft.server.level.ServerLevel) citizen.level()).players())
        {
            final double distance = player.distanceToSqr(citizen);
            if (!player.isSpectator() && distance < bestDistance)
            {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }
}
