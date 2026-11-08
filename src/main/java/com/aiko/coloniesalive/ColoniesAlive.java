package com.aiko.coloniesalive;

import com.aiko.coloniesalive.client.ColoniesAliveClient;
import com.aiko.coloniesalive.network.ChildAnimPayload;
import com.aiko.coloniesalive.network.ClearPathDebugPayload;
import com.aiko.coloniesalive.network.GrabPayload;
import com.aiko.coloniesalive.server.ChildLifeEvents;
import com.aiko.coloniesalive.server.GrabCarry;
import com.aiko.coloniesalive.social.SocialCommand;
import com.aiko.coloniesalive.social.SocialRegistration;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

@Mod(ColoniesAlive.MODID)
public class ColoniesAlive
{
    public static final String MODID  = "coloniesalive";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ColoniesAlive(final IEventBus modBus)
    {
        ModSounds.SOUNDS.register(modBus);
        modBus.addListener(ColoniesAlive::registerPayloads);
        NeoForge.EVENT_BUS.register(ChildLifeEvents.class);
        NeoForge.EVENT_BUS.register(GrabCarry.class);
        NeoForge.EVENT_BUS.register(SocialCommand.class);
        SocialRegistration.init(modBus);

        // Development: -Dcoloniesalive.clearweather=true opens the test world with clear skies (the weather cycle stays on,
        // so rain comes back by itself after a few in-game days)
        if (Boolean.getBoolean("coloniesalive.clearweather"))
        {
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent event) -> {
                event.getServer().overworld().setWeatherParameters(24000 * 3, 0, false, false);
                LOGGER.info("[world] test: weather cleared");
            });
        }

        if (FMLEnvironment.dist == Dist.CLIENT)
        {
            ColoniesAliveClient.init(modBus);
        }
    }

    private static void registerPayloads(final RegisterPayloadHandlersEvent event)
    {
        event.registrar("1")
          .playToClient(ChildAnimPayload.TYPE, ChildAnimPayload.CODEC, ChildAnimPayload::handle)
          .playToClient(ClearPathDebugPayload.TYPE, ClearPathDebugPayload.CODEC, ClearPathDebugPayload::handle)
          .playToServer(GrabPayload.TYPE, GrabPayload.CODEC, GrabPayload::handle)
          .playToServer(com.aiko.coloniesalive.network.SocialRequestPayload.TYPE, com.aiko.coloniesalive.network.SocialRequestPayload.CODEC,
            com.aiko.coloniesalive.network.SocialRequestPayload::handle)
          .playToClient(com.aiko.coloniesalive.network.GloomPayload.TYPE, com.aiko.coloniesalive.network.GloomPayload.CODEC,
            com.aiko.coloniesalive.network.GloomPayload::handle)
          .playToClient(com.aiko.coloniesalive.network.FamilyPayload.TYPE, com.aiko.coloniesalive.network.FamilyPayload.CODEC,
            com.aiko.coloniesalive.network.FamilyPayload::handle)
          .playToClient(com.aiko.coloniesalive.network.SocialInfoPayload.TYPE, com.aiko.coloniesalive.network.SocialInfoPayload.CODEC,
            com.aiko.coloniesalive.network.SocialInfoPayload::handle);
    }
}
