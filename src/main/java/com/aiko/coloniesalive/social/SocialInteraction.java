package com.aiko.coloniesalive.social;

import com.minecolonies.api.colony.ICitizen;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.interactionhandling.ChatPriority;
import com.minecolonies.api.colony.interactionhandling.IInteractionResponseHandler;
import com.minecolonies.api.colony.interactionhandling.InteractionValidatorRegistry;
import com.minecolonies.api.util.Tuple;
import com.minecolonies.core.colony.interactionhandling.ServerCitizenInteraction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * A citizen wants to tell the player something (shown in MineColonies' own chat tab, with the speech bubble over its
 * head). The player picks one of four answers, in random order: two kind ones, a brush-off and a rude one. The answer
 * changes how much the citizen likes them and its mood, and the citizen replies (see {@link Topics#answered}).
 *
 * <p>MineColonies looks interaction types up in its own namespace when loading, so this one is registered as
 * {@code minecolonies:coloniesalive_social}.
 */
public class SocialInteraction extends ServerCitizenInteraction
{
    public static final String    TYPE      = "coloniesalive_social";
    public static final Component VALIDATOR = Component.translatable("coloniesalive.social.validator");

    public enum Tone
    {
        HAPPY,
        SAD,
        CONFLICT,
        ROMANCE,
        /** Plain small talk. */
        CHAT,
        /** Something is wrong: hungry, sick, no home, no job... */
        WORRY,
        /** Likes the player a lot and says so. */
        AFFECTION,
        /** Is cross with the player (hit it, or just dislikes them): a kind answer is an apology. */
        COLD,
        /** A couple thinking about having a baby: a kind answer encourages them. */
        FAMILY;

        String key()
        {
            return name().toLowerCase();
        }
    }

    public enum Answer
    {
        KIND,
        NO,
        RUDE
    }

    /** How many lines of each kind the language file has per tone. */
    private static final int KIND_LINES = 6;
    private static final int NO_LINES   = 3;
    private static final int RUDE_LINES = 4;

    private static final Random RAND = new Random();

    private Tone     tone    = Tone.HAPPY;
    private boolean  child;
    /** What each button means, in the order shown. Older saves had kind, kind, brush-off. */
    private Answer[] answers = {Answer.KIND, Answer.KIND, Answer.NO};

    /** Used by MineColonies when loading from NBT. */
    public SocialInteraction(final ICitizen data)
    {
        super(data);
    }

    public SocialInteraction(final Component inquiry, final Tone tone, final boolean child)
    {
        this(inquiry, tone, child, build(tone));
    }

    private SocialInteraction(final Component inquiry, final Tone tone, final boolean child, final Built built)
    {
        super(inquiry, true, ChatPriority.CHITCHAT, InteractionValidatorRegistry.getStandardInteractionValidatorPredicate(VALIDATOR), VALIDATOR,
          built.tuples());
        this.tone = tone;
        this.child = child;
        this.answers = built.answers();
    }

    private record Built(Tuple<Component, Component>[] tuples, Answer[] answers) {}

    /** Two different kind lines, a brush-off and a rude one, shuffled. */
    @SuppressWarnings("unchecked")
    private static Built build(final Tone tone)
    {
        final int first = 1 + RAND.nextInt(KIND_LINES);
        int second = 1 + RAND.nextInt(KIND_LINES - 1);
        if (second >= first)
        {
            second++;
        }
        final String base = "coloniesalive.chat." + tone.key();
        final List<Tuple<Component, Answer>> options = new ArrayList<>();
        options.add(new Tuple<>(Component.translatable(base + ".kind." + first), Answer.KIND));
        options.add(new Tuple<>(Component.translatable(base + ".kind." + second), Answer.KIND));
        options.add(new Tuple<>(Component.translatable(base + ".no." + (1 + RAND.nextInt(NO_LINES))), Answer.NO));
        options.add(new Tuple<>(Component.translatable(base + ".rude." + (1 + RAND.nextInt(RUDE_LINES))), Answer.RUDE));
        Collections.shuffle(options, RAND);

        final Tuple<Component, Component>[] tuples = (Tuple<Component, Component>[]) new Tuple[options.size()];
        final Answer[] answers = new Answer[options.size()];
        for (int i = 0; i < options.size(); i++)
        {
            tuples[i] = new Tuple<>(options.get(i).getA(), Component.empty());
            answers[i] = options.get(i).getB();
        }
        return new Built(tuples, answers);
    }

    /** Position of the first button with this meaning, or -1 (tests). */
    public int indexOf(final Answer answer)
    {
        for (int i = 0; i < answers.length; i++)
        {
            if (answers[i] == answer)
            {
                return i;
            }
        }
        return -1;
    }

    /** The topic stays in the chat until it is answered (see {@link Topics#isOpen}). */
    public static void registerValidator()
    {
        InteractionValidatorRegistry.registerStandardPredicate(VALIDATOR, Topics::isOpen);
    }

    /** Client only: after the player answered, the window shows the citizen's reply with a single "bye" button. */
    private Component clientReply;

    @Override
    public Component getInquiry(final Player player)
    {
        return clientReply != null ? clientReply : super.getInquiry(player);
    }

    @Override
    public List<Component> getPossibleResponses()
    {
        return clientReply != null ? List.of(Component.translatable("coloniesalive.chat.continue")) : super.getPossibleResponses();
    }

    @Override
    public boolean onClientResponseTriggered(final int responseId, final Player player, final com.minecolonies.api.colony.ICitizenDataView data,
                                             final com.ldtteam.blockui.views.BOWindow window)
    {
        if (clientReply != null)
        {
            // "bye": on to the next interaction (the server already took this one out of the chat)
            return true;
        }
        super.onClientResponseTriggered(responseId, player, data, window);
        final Answer answer = responseId >= 0 && responseId < answers.length ? answers[responseId] : Answer.NO;
        clientReply = Component.translatable(replyKey(tone, child, answer));
        // stay on this interaction: it now shows the reply
        return false;
    }

    /** One of the citizen's reply lines for this answer (the language file has {@link #REPLY_LINES} per group). */
    private static String replyKey(final Tone tone, final boolean child, final Answer answer)
    {
        final String group = switch (answer)
        {
            case KIND -> switch (tone)
            {
                case SAD, WORRY -> "comfort";
                case COLD -> "forgive";
                case AFFECTION, ROMANCE -> "love";
                case FAMILY -> "family";
                default -> "kind";
            };
            case NO -> "no";
            case RUDE -> tone == Tone.SAD || tone == Tone.WORRY ? "rude_hurt" : tone == Tone.COLD ? "rude_cold" : "rude";
        };
        final int lines = answer == Answer.NO ? 4 : REPLY_LINES;
        return "coloniesalive.reply." + group + "." + (child ? "child" : "adult") + "." + (1 + RAND.nextInt(lines));
    }

    private static final int REPLY_LINES = 5;

    @Override
    public void onServerResponseTriggered(final int responseId, final Player player, final ICitizenData data)
    {
        super.onServerResponseTriggered(responseId, player, data);
        final Answer answer = responseId >= 0 && responseId < answers.length ? answers[responseId] : Answer.NO;
        Topics.answered(data, player, tone, child, answer);
    }

    @Override
    public List<IInteractionResponseHandler> genChildInteractions()
    {
        return Collections.emptyList();
    }

    @Override
    public String getType()
    {
        return TYPE;
    }

    @Override
    public CompoundTag serializeNBT(@NotNull final HolderLookup.Provider provider)
    {
        final CompoundTag tag = super.serializeNBT(provider);
        tag.putString("coloniesalive_tone", tone.name());
        tag.putBoolean("coloniesalive_child", child);
        final StringBuilder order = new StringBuilder();
        for (final Answer answer : answers)
        {
            order.append(answer.name().charAt(0));
        }
        tag.putString("coloniesalive_answers", order.toString());
        return tag;
    }

    @Override
    public void deserializeNBT(@NotNull final HolderLookup.Provider provider, @NotNull final CompoundTag tag)
    {
        super.deserializeNBT(provider, tag);
        try
        {
            tone = Tone.valueOf(tag.getString("coloniesalive_tone"));
        }
        catch (final IllegalArgumentException e)
        {
            tone = Tone.HAPPY;
        }
        child = tag.getBoolean("coloniesalive_child");
        final String order = tag.getString("coloniesalive_answers");
        if (!order.isEmpty())
        {
            answers = new Answer[order.length()];
            for (int i = 0; i < order.length(); i++)
            {
                answers[i] = switch (order.charAt(i))
                {
                    case 'K' -> Answer.KIND;
                    case 'R' -> Answer.RUDE;
                    default -> Answer.NO;
                };
            }
        }
    }
}
