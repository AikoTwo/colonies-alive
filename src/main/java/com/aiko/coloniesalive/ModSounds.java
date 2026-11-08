package com.aiko.coloniesalive;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Giggles and cries are based on the voices of MCA Reborn (GPL-3.0), pitched up to sound like children.
 */
public final class ModSounds
{
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, ColoniesAlive.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> GIGGLE_GIRL = register("child_giggle_girl");
    public static final DeferredHolder<SoundEvent, SoundEvent> GIGGLE_BOY  = register("child_giggle_boy");
    public static final DeferredHolder<SoundEvent, SoundEvent> CRY_GIRL    = register("child_cry_girl");
    public static final DeferredHolder<SoundEvent, SoundEvent> CRY_BOY     = register("child_cry_boy");
    public static final DeferredHolder<SoundEvent, SoundEvent> ANGRY_GIRL  = register("child_angry_girl");
    public static final DeferredHolder<SoundEvent, SoundEvent> ANGRY_BOY   = register("child_angry_boy");
    /** Babies: MCA Reborn's baby laughs, and its baby "ah?" (surprise) at a few pitches as babbling. No words: babies don't talk. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BABY_LAUGH  = register("baby_laugh");
    public static final DeferredHolder<SoundEvent, SoundEvent> BABY_COO    = register("baby_coo");

    private ModSounds() {}

    private static DeferredHolder<SoundEvent, SoundEvent> register(final String name)
    {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, name)));
    }
}
