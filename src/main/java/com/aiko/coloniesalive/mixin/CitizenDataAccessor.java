package com.aiko.coloniesalive.mixin;

import com.minecolonies.api.colony.interactionhandling.IInteractionResponseHandler;
import com.minecolonies.core.colony.CitizenData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Access to the citizen's open chat options, so an answered topic can be removed at once (MineColonies would drop it
 * on its next check and only tell the client up to ten seconds later, so reopening the chat showed it again).
 */
@Mixin(value = CitizenData.class, remap = false)
public interface CitizenDataAccessor
{
    @Accessor("citizenChatOptions")
    Map<Component, IInteractionResponseHandler> coloniesalive$chatOptions();
}
