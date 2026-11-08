package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.network.SocialInfoPayload;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Gradient;
import com.ldtteam.blockui.controls.Image;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.core.client.gui.citizen.AbstractWindowCitizen;
import com.aiko.coloniesalive.social.Social;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

/**
 * "Social life" page of the MineColonies citizen window: the addon's mood, how much the citizen likes you
 * (hearts), its partner and its friends and rivals.
 */
public class SocialWindowCitizen extends AbstractWindowCitizen
{
    private static final ResourceLocation HEART_FULL      = ResourceLocation.withDefaultNamespace("textures/gui/sprites/hud/heart/full.png");
    private static final ResourceLocation HEART_HALF      = ResourceLocation.withDefaultNamespace("textures/gui/sprites/hud/heart/half.png");
    private static final ResourceLocation HEART_CONTAINER = ResourceLocation.withDefaultNamespace("textures/gui/sprites/hud/heart/container.png");

    private static final int PINK      = 0xC2187A;
    private static final int GREEN     = 0x1E7A1E;
    private static final int DARK_GRAY = 0x404040;
    private static final int GRAY      = 0x707070;
    private static final int RED       = 0xB01010;

    private final SocialInfoPayload info;

    public SocialWindowCitizen(final ICitizenDataView citizen, final SocialInfoPayload info)
    {
        super(citizen, ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, "gui/citizen/social.xml"));
        this.info = info;
    }

    @Override
    public void onOpened()
    {
        super.onOpened();
        showMood();
        showAffinity();
        showPartner();
        showFamily();
        showBonds();
    }

    /** Pregnant: "Grávida · faltam 0h 47min (dia 2 de 3)"; baby: "Bebê · vira criança em 3h 12min"; and a bar. */
    private void showFamily()
    {
        final SocialInfoPayload.Family family = info.family();
        final Text text = findPaneOfTypeByID("familyText", Text.class);
        final Gradient fill = findPaneOfTypeByID("familyFill", Gradient.class);
        final boolean shown = family.stage() != com.aiko.coloniesalive.network.FamilyPayload.NONE && family.total() > 0;
        text.setVisible(shown);
        fill.setVisible(shown);
        findPaneByID("familyBox").setVisible(shown);
        if (!shown)
        {
            return;
        }
        final float progress = Mth.clamp(family.elapsed() / (float) family.total(), 0f, 1f);
        final String left = ClientFamily.formatTicks(family.total() - (long) family.elapsed());
        if (family.stage() == com.aiko.coloniesalive.network.FamilyPayload.PREGNANT)
        {
            final int day = Math.min(3, 1 + family.elapsed() / 24000);
            text.setText(Component.translatable("coloniesalive.gui.social.pregnant", left, day));
        }
        else
        {
            text.setText(Component.translatable("coloniesalive.gui.social.baby", left));
        }
        text.setColors(PINK);
        fill.setSize(Math.max(1, Math.round(progress * 100)), 4);
        fill.setGradientStart(236, 120, 180, 255);
        fill.setGradientEnd(236, 120, 180, 255);
    }

    private void showMood()
    {
        final float mood = Mth.clamp(info.mood(), -10f, 10f);
        final Text text = findPaneOfTypeByID("moodText", Text.class);
        text.setText(Component.translatable("coloniesalive.gui.social.mood", Component.translatable(moodKey(mood))));
        text.setColors(DARK_GRAY);

        // bar from the middle: red when sad, green when happy
        final Gradient fill = findPaneOfTypeByID("moodFill", Gradient.class);
        final int full = 100;
        final int width = Math.max(1, Math.round((mood + 10f) / 20f * full));
        fill.setSize(width, 6);
        final float t = (mood + 10f) / 20f;
        final int red = (int) (t < 0.5f ? 200 : 200 * (1 - t) * 2);
        final int green = (int) (t < 0.5f ? 200 * t * 2 : 170);
        fill.setGradientStart(red, green, 40, 255);
        fill.setGradientEnd(red, green, 40, 255);
    }

    private void showAffinity()
    {
        final int affinity = info.affinity();
        // ten hearts, every half heart is 5 points; dislike shows as empty hearts
        final int halves = Math.max(0, Math.round(affinity / 5f));
        for (int i = 0; i < 10; i++)
        {
            final Image heart = findPaneOfTypeByID("heart" + i, Image.class);
            final int filled = halves - i * 2;
            heart.setImage(filled >= 2 ? HEART_FULL : filled == 1 ? HEART_HALF : HEART_CONTAINER, false);
        }

        final Text text = findPaneOfTypeByID("affinityText", Text.class);
        text.setText(Component.translatable(affinityKey(affinity)));
        text.setColors(affinity <= -20 ? RED : affinity >= 50 ? PINK : DARK_GRAY);
    }

    private void showPartner()
    {
        final Text text = findPaneOfTypeByID("partnerText", Text.class);
        if (info.partner().isEmpty())
        {
            text.setText(Component.translatable("coloniesalive.gui.social.partner", Component.translatable(citizen.isChild()
                                                                                                                ? "coloniesalive.gui.social.partner.child"
                                                                                                                : "coloniesalive.gui.social.partner.none")));
            text.setColors(GRAY);
        }
        else
        {
            text.setText(Component.translatable("coloniesalive.gui.social.partner", "♥ " + info.partner()));
            text.setColors(PINK);
        }
    }

    private void showBonds()
    {
        findPaneByID("noBonds").setVisible(info.bonds().isEmpty());
        final ScrollingList list = findPaneOfTypeByID("bonds", ScrollingList.class);
        list.setDataProvider(new ScrollingList.DataProvider()
        {
            @Override
            public int getElementCount()
            {
                return info.bonds().size();
            }

            @Override
            public void updateElement(final int index, @NotNull final Pane row)
            {
                final SocialInfoPayload.Bond bond = info.bonds().get(index);
                row.findPaneOfTypeByID("name", Text.class).setText(Component.literal(bond.name()));
                final Text status = row.findPaneOfTypeByID("status", Text.class);
                status.setText(Component.translatable(statusKey(bond)));
                status.setColors(statusColor(bond));
            }
        });
    }

    private static String statusKey(final SocialInfoPayload.Bond bond)
    {
        if (bond.grudge())
        {
            return "coloniesalive.gui.social.bond.grudge";
        }
        if (bond.partner())
        {
            return "coloniesalive.gui.social.bond.partner";
        }
        if (bond.romance() >= Social.IN_LOVE)
        {
            return "coloniesalive.gui.social.bond.love";
        }
        if (bond.romance() >= Social.CRUSH)
        {
            return "coloniesalive.gui.social.bond.crush";
        }
        if (bond.friendship() >= Social.BEST_FRIEND)
        {
            return "coloniesalive.gui.social.bond.best";
        }
        if (bond.friendship() >= Social.FRIEND)
        {
            return "coloniesalive.gui.social.bond.friend";
        }
        if (bond.friendship() <= Social.RIVAL)
        {
            return "coloniesalive.gui.social.bond.rival";
        }
        if (bond.friendship() < 0)
        {
            return "coloniesalive.gui.social.bond.dislike";
        }
        return "coloniesalive.gui.social.bond.known";
    }

    private static int statusColor(final SocialInfoPayload.Bond bond)
    {
        if (bond.grudge() || bond.friendship() <= Social.RIVAL)
        {
            return RED;
        }
        if (bond.partner() || bond.romance() >= Social.CRUSH)
        {
            return PINK;
        }
        if (bond.friendship() >= Social.FRIEND)
        {
            return GREEN;
        }
        return GRAY;
    }

    private static String moodKey(final float mood)
    {
        if (mood >= 5)
        {
            return "coloniesalive.mood.great";
        }
        if (mood >= 1.5)
        {
            return "coloniesalive.mood.good";
        }
        if (mood > -1.5)
        {
            return "coloniesalive.mood.ok";
        }
        if (mood > -5)
        {
            return "coloniesalive.mood.down";
        }
        return "coloniesalive.mood.bad";
    }

    private static String affinityKey(final int affinity)
    {
        if (affinity >= 80)
        {
            return "coloniesalive.gui.social.affinity.adores";
        }
        if (affinity >= 50)
        {
            return "coloniesalive.gui.social.affinity.likes_lot";
        }
        if (affinity >= 20)
        {
            return "coloniesalive.gui.social.affinity.likes";
        }
        if (affinity > -20)
        {
            return "coloniesalive.gui.social.affinity.neutral";
        }
        if (affinity > -50)
        {
            return "coloniesalive.gui.social.affinity.wary";
        }
        return "coloniesalive.gui.social.affinity.dislikes";
    }
}
