package com.aiko.coloniesalive.social;

import com.minecolonies.api.colony.interactionhandling.registry.InteractionResponseHandlerEntry;
import com.minecolonies.apiimp.CommonMinecoloniesAPIImpl;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Hooks the social chat into MineColonies' interaction registry. The entry has to live in the "minecolonies"
 * namespace: MineColonies resolves saved interaction types with its own namespace.
 */
public final class SocialRegistration
{
    private static final DeferredRegister<InteractionResponseHandlerEntry> INTERACTIONS =
      DeferredRegister.create(CommonMinecoloniesAPIImpl.INTERACTION_RESPONSE_HANDLERS, "minecolonies");

    static
    {
        INTERACTIONS.register(SocialInteraction.TYPE, () -> new InteractionResponseHandlerEntry.Builder()
          .setResponseHandlerProducer(SocialInteraction::new)
          .setRegistryName(ResourceLocation.fromNamespaceAndPath("minecolonies", SocialInteraction.TYPE))
          .createEntry());
    }

    private SocialRegistration() {}

    public static void init(final IEventBus modBus)
    {
        INTERACTIONS.register(modBus);
        modBus.addListener(FMLCommonSetupEvent.class, event -> event.enqueueWork(SocialInteraction::registerValidator));
    }
}
