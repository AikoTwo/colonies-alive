package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.network.SocialInfoPayload;
import com.aiko.coloniesalive.network.SocialRequestPayload;
import com.minecolonies.api.colony.ICitizenDataView;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The "social life" tab of the MineColonies citizen window: clicking it asks the server for the data, the window
 * opens when the answer arrives.
 */
public final class SocialTab
{
    private static ICitizenDataView pending;

    private SocialTab() {}

    public static void request(final ICitizenDataView citizen)
    {
        pending = citizen;
        PacketDistributor.sendToServer(new SocialRequestPayload(citizen.getColony().getDimension(), citizen.getColony().getID(), citizen.getId()));
    }

    public static void onInfo(final SocialInfoPayload info)
    {
        final ICitizenDataView citizen = pending;
        pending = null;
        if (citizen != null && citizen.getId() == info.citizenId())
        {
            new SocialWindowCitizen(citizen, info).open();
        }
    }
}
