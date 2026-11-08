package com.aiko.coloniesalive.social;

import com.aiko.coloniesalive.network.SocialInfoPayload;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Collects what the "social life" tab of the citizen window shows. Works also when the citizen is not loaded (window
 * opened from the town hall), then the mood is the last one known.
 */
public final class SocialSnapshot
{
    private SocialSnapshot() {}

    public static SocialInfoPayload build(final ServerPlayer player, final ResourceKey<Level> dimension, final int colonyId, final int citizenId)
    {
        final IColony colony = IColonyManager.getInstance().getColonyByDimension(colonyId, dimension);
        if (colony == null || player.getServer() == null)
        {
            return null;
        }
        final ICitizenData data = colony.getCitizenManager().getCivilian(citizenId);
        if (data == null)
        {
            return null;
        }

        final SocialData social = SocialData.get(player.getServer());
        final String self = SocialData.personKey(colonyId, citizenId);
        final float mood = data.getEntity().orElse(null) instanceof EntityCitizen entity ? Social.mood(entity) : social.person(self).mood;
        final SocialData.Person person = social.people.get(self);
        final int affinity = person == null ? 0 : person.affinity.getOrDefault(player.getUUID(), 0);
        final ICitizenData partner = data.getPartner();

        final long now = player.serverLevel().getGameTime();
        final String prefix = colonyId + ":";
        final List<SocialInfoPayload.Bond> bonds = new ArrayList<>();
        boolean partnerListed = false;
        for (final Map.Entry<String, SocialData.Bond> entry : social.bonds.entrySet())
        {
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
            final ICitizenData otherData;
            try
            {
                otherData = colony.getCitizenManager().getCivilian(Integer.parseInt(other.substring(prefix.length())));
            }
            catch (final NumberFormatException e)
            {
                continue;
            }
            if (otherData == null)
            {
                continue;
            }
            final SocialData.Bond bond = entry.getValue();
            final boolean isPartner = partner != null && partner.getId() == otherData.getId();
            partnerListed |= isPartner;
            if (bond.friendship == 0 && bond.romance == 0 && !isPartner)
            {
                continue;
            }
            bonds.add(new SocialInfoPayload.Bond(otherData.getName(), bond.friendship, bond.romance, bond.grudgeUntil > now, isPartner));
        }
        if (partner != null && !partnerListed)
        {
            bonds.add(new SocialInfoPayload.Bond(partner.getName(), 0, 0, false, true));
        }
        // partner first, then from best friend down to worst rival
        bonds.sort(Comparator.comparing((SocialInfoPayload.Bond b) -> !b.partner()).thenComparingInt(b -> -b.friendship()));

        final long[] family = Family.status(player.getServer(), data);
        return new SocialInfoPayload(citizenId, mood, affinity, partner == null ? "" : partner.getName(), bonds,
          new SocialInfoPayload.Family((int) family[0], (int) Math.min(Integer.MAX_VALUE, Math.max(0, family[1])), (int) family[2]));
    }
}
