package com.aiko.coloniesalive.social;

import com.aiko.coloniesalive.server.Fx;
import com.aiko.coloniesalive.social.SocialInteraction.Answer;
import com.aiko.coloniesalive.social.SocialInteraction.Tone;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.Random;

/**
 * Things citizens want to tell the player about. A citizen has at most one open topic, which waits in the MineColonies
 * chat until the player answers it. Topics come from what happened (played with a friend, a fight, got hurt...) or,
 * when a player is around, from small talk about how the citizen is doing right now ({@link SmallTalk}).
 *
 * <p>What the player answers matters: kind answers raise how much the citizen likes them and its mood, a brush-off
 * lowers both a little, and a rude answer a lot (a child may pout or burst into tears). The citizen's reply shows in
 * the chat window ({@link SocialInteraction#onClientResponseTriggered}).
 */
public final class Topics
{
    /** Minimum time between two topics of the same citizen. */
    static final int TOPIC_GAP = 20 * 90;

    private static final Random RAND = new Random();

    public enum Topic
    {
        // things that happened (raised by the activities)
        PLAYED(Tone.HAPPY, 5),
        BEST_FRIEND(Tone.HAPPY, 5),
        MADE_UP(Tone.HAPPY, 4),
        GOOD_CHAT(Tone.HAPPY, 4),
        FIGHT(Tone.CONFLICT, 5),
        HURT(Tone.SAD, 5),
        SAD(Tone.SAD, 6),
        CRUSH(Tone.ROMANCE, 5),
        DATING(Tone.ROMANCE, 5),
        SCHOOL_DAY(Tone.HAPPY, 5),
        SCHOOL_TRUANT(Tone.SAD, 4),
        /** Hit by the player: wants an apology. */
        HIT_BY_YOU(Tone.COLD, 5),
        /** Family (see Family): she is expecting, he is going to be a father, the baby was born, a little sibling. */
        PREGNANT(Tone.HAPPY, 5),
        FATHER_TO_BE(Tone.HAPPY, 5),
        BABY_BORN(Tone.HAPPY, 5),
        BIG_SIBLING(Tone.HAPPY, 5),
        /** A couple thinking about a baby: a kind answer from the player and it is on its way. */
        BABY_WISH(Tone.FAMILY, 5),

        // small talk about how the citizen is doing (see SmallTalk)
        RAIN(Tone.HAPPY, Tone.WORRY, 5),
        NIGHT(Tone.SAD, Tone.CHAT, 5),
        HUNGRY(Tone.WORRY, 5),
        SICK(Tone.WORRY, 5),
        HOMELESS(Tone.WORRY, 4),
        WORK(Tone.HAPPY, Tone.CHAT, 6),
        JOBLESS(Tone.WORRY, 4),
        CHEERFUL(Tone.HAPPY, 6),
        GLOOMY(Tone.SAD, 5),
        LIKES_YOU(Tone.AFFECTION, 6),
        DISLIKES_YOU(Tone.COLD, 5),
        FRIEND_TALK(Tone.HAPPY, 5),
        PARTNER_TALK(Tone.ROMANCE, 5),
        KIDS_TALK(Tone.HAPPY, 5),
        /** Expecting: the baby kicked, cravings, names... */
        PREGNANT_TALK(Tone.HAPPY, 6),
        CHATTER(Tone.CHAT, 10);

        final Tone childTone;
        final Tone adultTone;
        final int  variants;

        Topic(final Tone tone, final int variants)
        {
            this(tone, tone, variants);
        }

        Topic(final Tone childTone, final Tone adultTone, final int variants)
        {
            this.childTone = childTone;
            this.adultTone = adultTone;
            this.variants = variants;
        }

        boolean isSmallTalk()
        {
            return ordinal() >= RAIN.ordinal();
        }
    }

    private Topics() {}

    /**
     * The citizen wants to talk about this. Ignored if it already has something to say or talked recently; a topic
     * about something that happened replaces open small talk, though.
     *
     * @param args names or other text filled into the line
     */
    public static void raise(final EntityCitizen citizen, final Topic topic, final Object... args)
    {
        raise(citizen, topic, false, args);
    }

    /** Raises the topic even if the citizen talked recently or has something else open. */
    public static void raiseNow(final EntityCitizen citizen, final Topic topic, final Object... args)
    {
        raise(citizen, topic, true, args);
    }

    private static void raise(final EntityCitizen citizen, final Topic topic, final boolean force, final Object... args)
    {
        final ICitizenData data = citizen.getCitizenData();
        if (data == null || citizen.getServer() == null)
        {
            return;
        }
        // babies don't talk: only their babbling, laughs and cries
        if (Family.isInfant(citizen))
        {
            return;
        }
        final SocialData social = Social.data(citizen.getServer());
        final SocialData.Person person = social.person(Social.key(citizen));
        final long now = citizen.level().getGameTime();
        if (!force)
        {
            final boolean openSmallTalk = person.openTopic != null && isSmallTalk(person.openTopic);
            if (person.openTopic != null && !(openSmallTalk && !topic.isSmallTalk()))
            {
                return;
            }
            if (now < person.nextTopicTick && !(openSmallTalk && !topic.isSmallTalk()))
            {
                return;
            }
        }
        if (person.openTopic != null)
        {
            dropFromChat(data);
        }
        person.openTopic = topic.name();
        person.nextTopicTick = now + TOPIC_GAP;
        social.setDirty();

        // every topic has a few ways of saying it
        final boolean child = citizen.isBaby();
        final String age = child ? "child" : "adult";
        final int variant = 1 + RAND.nextInt(topic.variants);
        final Component inquiry = Component.translatable("coloniesalive.topic." + topic.name().toLowerCase() + "." + age + "." + variant, args);
        data.triggerInteraction(new SocialInteraction(inquiry, child ? topic.childTone : topic.adultTone, child));
        lastInquiry = inquiry;
        if (com.aiko.coloniesalive.server.ChildLifeAI.DEBUG)
        {
            com.aiko.coloniesalive.ColoniesAlive.LOGGER.info("[social] {} wants to talk: {}", data.getName(), inquiry.getString());
        }
    }

    private static boolean isSmallTalk(final String name)
    {
        try
        {
            return Topic.valueOf(name).isSmallTalk();
        }
        catch (final IllegalArgumentException e)
        {
            return true;
        }
    }

    /** Development: the last topic raised, so a test can answer it. */
    public static Component lastInquiry;

    /** Validator: the interaction stays while the topic is open. */
    public static boolean isOpen(final ICitizenData data)
    {
        if (data.getColony() == null || data.getColony().getWorld() == null || data.getColony().getWorld().getServer() == null)
        {
            return true;
        }
        final SocialData social = Social.data(data.getColony().getWorld().getServer());
        final SocialData.Person person = social.people.get(Social.key(data));
        return person != null && person.openTopic != null;
    }

    /** Has something to say right now (small talk doesn't start then). */
    static boolean hasOpenTopic(final SocialData social, final String key)
    {
        final SocialData.Person person = social.people.get(key);
        return person != null && person.openTopic != null;
    }

    /** Takes back whatever this citizen wanted to say (babies left over from before they stopped talking). */
    static void clear(final SocialData social, final String key, final ICitizenData data)
    {
        final SocialData.Person person = social.people.get(key);
        if (person != null && person.openTopic != null)
        {
            person.openTopic = null;
            social.setDirty();
            dropFromChat(data);
        }
    }

    /** Removes our topic from the MineColonies chat right away (MineColonies would only notice later, the client even later). */
    private static void dropFromChat(final ICitizenData data)
    {
        if (data instanceof com.aiko.coloniesalive.mixin.CitizenDataAccessor accessor)
        {
            accessor.coloniesalive$chatOptions().values().removeIf(handler -> handler instanceof SocialInteraction);
            data.markDirty(0);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The player's answer
    // ------------------------------------------------------------------------------------------------------------------

    /** How much an answer changes affinity and mood, by tone. */
    private record Effect(int kindAffinity, float kindMood, int noAffinity, float noMood, int rudeAffinity, float rudeMood) {}

    private static Effect effect(final Tone tone)
    {
        return switch (tone)
        {
            // comforting someone sad counts more, and being mean to them hurts more
            case SAD -> new Effect(5, 2.5f, -3, -1.5f, -16, -4.5f);
            case WORRY -> new Effect(4, 1.5f, -2, -1f, -13, -3.5f);
            case AFFECTION -> new Effect(5, 1.5f, -3, -1f, -16, -4f);
            // an apology after a bad moment: worth a lot, and ignoring it again doesn't help
            case COLD -> new Effect(8, 1.5f, -4, -1f, -10, -2.5f);
            case CONFLICT -> new Effect(4, 1.5f, -2, -0.5f, -12, -3f);
            case ROMANCE, FAMILY -> new Effect(4, 1.5f, -2, -0.5f, -12, -3f);
            case HAPPY, CHAT -> new Effect(3, 1f, -2, -0.5f, -12, -3f);
        };
    }

    static void answered(final ICitizenData data, final Player player, final Tone tone, final boolean child, final Answer answer)
    {
        if (player.getServer() == null)
        {
            return;
        }
        final SocialData social = Social.data(player.getServer());
        final String key = Social.key(data);
        final String topic = social.person(key).openTopic;
        social.person(key).openTopic = null;
        if (answer == Answer.KIND && Topic.BABY_WISH.name().equals(topic))
        {
            // the player cheered the couple on
            Family.encouraged(data);
        }
        social.setDirty();
        dropFromChat(data);
        if (com.aiko.coloniesalive.server.ChildLifeAI.DEBUG)
        {
            com.aiko.coloniesalive.ColoniesAlive.LOGGER.info("[social] {} answered {} ({}) to {}", player.getName().getString(), answer, tone, data.getName());
        }

        final EntityCitizen entity = data.getEntity().orElse(null) instanceof EntityCitizen citizen ? citizen : null;
        final Effect effect = effect(tone);
        switch (answer)
        {
            case KIND ->
            {
                Social.addAffinityNow(social, key, player.getUUID(), effect.kindAffinity() + RAND.nextInt(2));
                if (entity != null)
                {
                    Social.addMood(entity, effect.kindMood());
                    if (tone == Tone.ROMANCE || tone == Tone.AFFECTION || tone == Tone.SAD || tone == Tone.COLD)
                    {
                        Fx.hearts(entity, 3);
                    }
                    else
                    {
                        Fx.happy(entity, 4);
                    }
                    if (entity.isBaby() && tone != Tone.SAD)
                    {
                        Fx.giggle(entity);
                    }
                }
            }
            case NO ->
            {
                Social.addAffinityNow(social, key, player.getUUID(), effect.noAffinity());
                if (entity != null)
                {
                    Social.addMood(entity, effect.noMood());
                }
            }
            case RUDE ->
            {
                Social.addAffinityNow(social, key, player.getUUID(), effect.rudeAffinity());
                if (entity != null)
                {
                    Social.addMood(entity, effect.rudeMood());
                    Fx.angry(entity);
                    hurtFeelings(entity, tone);
                }
            }
        }
    }

    /** A rude answer shows: a child pouts or bursts into tears, an adult gets cross. */
    private static void hurtFeelings(final EntityCitizen entity, final Tone tone)
    {
        if (entity.isBaby())
        {
            final com.aiko.coloniesalive.server.ChildLifeAI ai = com.aiko.coloniesalive.server.ChildLifeEvents.get(entity);
            if (ai == null)
            {
                return;
            }
            final boolean tears = tone == Tone.SAD || tone == Tone.WORRY || tone == Tone.AFFECTION || Social.mood(entity) < -3 || RAND.nextBoolean();
            if (tears)
            {
                ai.startCryingFor(80 + RAND.nextInt(60));
            }
            else
            {
                Fx.grumble(entity);
                ai.broadcast(com.aiko.coloniesalive.ChildAnimation.POUT, 70);
            }
        }
        else
        {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntity(entity,
              new com.aiko.coloniesalive.network.ChildAnimPayload(entity.getId(), com.aiko.coloniesalive.ChildAnimation.ARGUE.id(), 50));
        }
    }
}
