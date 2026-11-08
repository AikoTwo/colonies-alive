package com.aiko.coloniesalive.client;

import com.minecolonies.api.entity.ModEntities;
import com.minecolonies.api.client.render.modeltype.CitizenModel;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only setup. Only referenced when running on the client.
 */
public final class ColoniesAliveClient
{
    private ColoniesAliveClient() {}

    public static void init(final IEventBus modBus)
    {
        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(HalloweenOff::apply));
        modBus.addListener(EntityRenderersEvent.AddLayers.class, ColoniesAliveClient::addLayers);
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(GrabClient.GRAB));
        NeoForge.EVENT_BUS.register(GrabClient.class);
        NeoForge.EVENT_BUS.register(CitizenTurnFilter.class);
        NeoForge.EVENT_BUS.register(ClientFamily.class);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class, event -> ClientFamily.clear());
        modBus.addListener(RegisterClientReloadListenersEvent.class,
          event -> event.registerReloadListener((ResourceManagerReloadListener) manager -> {
                EyelidTextures.clear();
                MouthRows.clear();
            }));

        if (Showcase.ENABLED)
        {
            NeoForge.EVENT_BUS.register(Showcase.class);
        }
        if (JitterProbe.ENABLED)
        {
            NeoForge.EVENT_BUS.register(JitterProbe.class);
        }
    }

    private static void addLayers(final EntityRenderersEvent.AddLayers event)
    {
        addEyelids(event, ModEntities.CITIZEN);
        addEyelids(event, ModEntities.VISITOR);
    }

    @SuppressWarnings("unchecked")
    private static void addEyelids(final EntityRenderersEvent.AddLayers event, final EntityType<? extends AbstractEntityCitizen> type)
    {
        if (type == null)
        {
            return;
        }
        final EntityRenderer<?> renderer = event.getRenderer(type);
        if (renderer instanceof LivingEntityRenderer<?, ?> living)
        {
            final LivingEntityRenderer<AbstractEntityCitizen, CitizenModel<AbstractEntityCitizen>> citizenRenderer =
              (LivingEntityRenderer<AbstractEntityCitizen, CitizenModel<AbstractEntityCitizen>>) living;
            citizenRenderer.addLayer(new EyelidLayer(citizenRenderer));
            citizenRenderer.addLayer(new BellyLayer(citizenRenderer));
            citizenRenderer.addLayer(new AccessoryLayer(citizenRenderer));
        }
    }
}
