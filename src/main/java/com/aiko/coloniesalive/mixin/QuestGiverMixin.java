package com.aiko.coloniesalive.mixin;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.managers.interfaces.ICitizenManager;
import com.minecolonies.core.quests.triggers.CitizenQuestTriggerTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * MineColonies picks a random citizen to hand out its colony quests ("build a mine", "place torches"...), children
 * included, which reads odd. Only grown-ups give quests here.
 */
@Mixin(value = CitizenQuestTriggerTemplate.class, remap = false)
public abstract class QuestGiverMixin
{
    @Redirect(method = "canTriggerQuest", at = @At(value = "INVOKE",
      target = "Lcom/minecolonies/api/colony/managers/interfaces/ICitizenManager;getCitizens()Ljava/util/List;"))
    private List<ICitizenData> coloniesalive$adultsOnly(final ICitizenManager manager)
    {
        final List<ICitizenData> adults = new ArrayList<>();
        for (final ICitizenData data : manager.getCitizens())
        {
            if (!data.isChild())
            {
                adults.add(data);
            }
        }
        return adults;
    }
}
