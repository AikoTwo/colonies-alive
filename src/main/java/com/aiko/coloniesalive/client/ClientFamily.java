package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.network.FamilyPayload;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client side of the family (see {@code Family}): who is pregnant or a baby, how far along, and the time left, which
 * shows on the name tag ("grávida · 0h 47min", "bebê · 3h 12min"). The server sends the time left now and then; in
 * between the client counts down on its own.
 */
public final class ClientFamily
{
    private record Entry(int stage, float progress, int remaining, long receivedAt) {}

    private static final Map<Integer, Entry> ENTRIES = new ConcurrentHashMap<>();

    private ClientFamily() {}

    public static void set(final FamilyPayload payload)
    {
        if (payload.stage() == FamilyPayload.NONE)
        {
            ENTRIES.remove(payload.entityId());
        }
        else
        {
            ENTRIES.put(payload.entityId(), new Entry(payload.stage(), payload.progress(), payload.remaining(), gameTime()));
        }
    }

    private static long gameTime()
    {
        return Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
    }

    /** A baby (smaller, onesie skin, baby accessories). */
    public static boolean isInfant(final int entityId)
    {
        final Entry entry = ENTRIES.get(entityId);
        return entry != null && entry.stage() == FamilyPayload.INFANT;
    }

    /** 0..1 of the pregnancy, 0 if not pregnant. */
    public static float pregnancy(final int entityId)
    {
        final Entry entry = ENTRIES.get(entityId);
        if (entry == null || entry.stage() != FamilyPayload.PREGNANT)
        {
            return 0f;
        }
        final long elapsed = Math.max(0, gameTime() - entry.receivedAt());
        final float total = entry.progress() >= 1f || entry.remaining() <= 0 ? 1f : entry.remaining() / (1f - entry.progress());
        return Math.min(1f, entry.progress() + elapsed / total);
    }

    /** Ticks left until the birth / until it is a child, counting down since the last update. */
    private static int remaining(final Entry entry)
    {
        return (int) Math.max(0, entry.remaining() - (gameTime() - entry.receivedAt()));
    }

    /** "3h 05min" (or "12min" under an hour) from ticks, at 20 ticks per second. */
    public static String formatTicks(final long ticks)
    {
        final long minutes = Math.max(0, ticks) / 20 / 60;
        final long hours = minutes / 60;
        return hours > 0 ? String.format("%dh %02dmin", hours, minutes % 60) : String.format("%dmin", Math.max(1, minutes));
    }

    /** Adds the time left to the name tag of a pregnant citizen or a baby. */
    @SubscribeEvent
    public static void onNameTag(final RenderNameTagEvent event)
    {
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen))
        {
            return;
        }
        final Entry entry = ENTRIES.get(citizen.getId());
        if (entry == null)
        {
            return;
        }
        final String key = entry.stage() == FamilyPayload.PREGNANT ? "coloniesalive.family.tag.pregnant" : "coloniesalive.family.tag.baby";
        event.setContent(Component.empty()
                           .append(event.getContent())
                           .append(Component.literal(" "))
                           .append(Component.translatable(key, formatTicks(remaining(entry))).withStyle(ChatFormatting.LIGHT_PURPLE)));
    }

    public static void clear()
    {
        ENTRIES.clear();
    }
}
