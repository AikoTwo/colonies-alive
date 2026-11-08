package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.ColoniesAlive;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Development aid (only with -Dcoloniesalive.debug=true): every client tick, looks at the head yaw (relative to the body),
 * the body yaw and the head pitch of each child and counts sharp back-and-forth changes - the "head shaking".
 * Logs children that shook during the last second.
 */
public final class JitterProbe
{
    public static final boolean ENABLED = Boolean.getBoolean("coloniesalive.debug");

    private static final float MIN_STEP = 1.0f;

    private static final class Channel
    {
        float   value;
        float   lastDelta;
        boolean init;

        /** @return true if this sample reversed a clear movement of the previous tick */
        boolean push(final float v)
        {
            if (!init)
            {
                init = true;
                value = v;
                return false;
            }
            final float delta = Mth.wrapDegrees(v - value);
            final boolean reversal = Math.abs(delta) > MIN_STEP && Math.abs(lastDelta) > MIN_STEP && Math.signum(delta) != Math.signum(lastDelta);
            lastDelta = delta;
            value = v;
            return reversal;
        }
    }

    private static final class Track
    {
        final Channel head  = new Channel();
        final Channel body  = new Channel();
        final Channel pitch = new Channel();
        final Channel serverHead = new Channel();
        final Channel serverBody = new Channel();
        int head_;
        int body_;
        int pitch_;
        int serverHead_;
        int serverBody_;
        final Map<String, Integer> states = new HashMap<>();
    }

    private static final Map<Integer, Track> TRACKS = new HashMap<>();
    public static int  totalReversals;
    public static long samples;
    public static int  adultReversals;
    public static long adultSamples;

    private JitterProbe() {}

    /** What is actually drawn (after the turn filter), per frame. */
    private static final class Drawn
    {
        float head;
        float body;
        float lastHeadDelta;
        float lastBodyDelta;
        boolean init;
        long lastNanos;
    }

    private static final Map<Integer, Drawn> DRAWN = new HashMap<>();
    public static long drawnFrames;
    public static int  drawnReversals;

    /** A visible twitch: the drawn angle moves at least half a degree one way, then at least half a degree back. */
    private static final float DRAWN_STEP = 0.5f;

    private static int reports;

    private static void report(final Entity entity, final String what, final float before, final float now)
    {
        if (reports++ < 60 && Minecraft.getInstance().level != null)
        {
            final ClientAnimations.Active active = ClientAnimations.get(entity.getId(), Minecraft.getInstance().level.getGameTime());
            final double speed = Math.sqrt(Math.pow(entity.getX() - entity.xo, 2) + Math.pow(entity.getZ() - entity.zo, 2));
            ColoniesAlive.LOGGER.info("[twitch] #{} {} {} -> {} pose {} speed {} onGround {}", entity.getId(), what,
              String.format("%.2f", before), String.format("%.2f", now), active == null ? "none" : active.animation(),
              String.format("%.3f", speed), entity.onGround());
        }
    }

    public static void drawn(final Entity entity, final float body, final float head, final float pitch)
    {
        if (!(entity instanceof AbstractEntityCitizen citizen) || !citizen.isBaby())
        {
            return;
        }
        drawnFrames++;
        final Drawn d = DRAWN.computeIfAbsent(entity.getId(), id -> new Drawn());
        final float relHead = Mth.wrapDegrees(head - body);
        // not drawn for a while (off screen): the filter starts over from the real angle, that is no twitch
        final long nowNanos = System.nanoTime();
        if (nowNanos - d.lastNanos > 500_000_000L)
        {
            d.init = false;
            d.lastHeadDelta = 0;
            d.lastBodyDelta = 0;
        }
        d.lastNanos = nowNanos;
        if (d.init)
        {
            final float headDelta = Mth.wrapDegrees(relHead - d.head);
            final float bodyDelta = Mth.wrapDegrees(body - d.body);
            if (Math.abs(headDelta) > DRAWN_STEP && Math.abs(d.lastHeadDelta) > DRAWN_STEP && Math.signum(headDelta) != Math.signum(d.lastHeadDelta))
            {
                drawnReversals++;
                report(entity, "head", d.lastHeadDelta, headDelta);
            }
            if (Math.abs(bodyDelta) > DRAWN_STEP && Math.abs(d.lastBodyDelta) > DRAWN_STEP && Math.signum(bodyDelta) != Math.signum(d.lastBodyDelta))
            {
                drawnReversals++;
                report(entity, "body", d.lastBodyDelta, bodyDelta);
            }
            if (Math.abs(headDelta) > 0.05f)
            {
                d.lastHeadDelta = headDelta;
            }
            if (Math.abs(bodyDelta) > 0.05f)
            {
                d.lastBodyDelta = bodyDelta;
            }
        }
        d.init = true;
        d.head = relHead;
        d.body = body;
    }

    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Post event)
    {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
        {
            return;
        }
        for (final Entity entity : mc.level.entitiesForRendering())
        {
            if (!(entity instanceof AbstractEntityCitizen citizen))
            {
                continue;
            }
            if (citizen.isBaby())
            {
                samples++;
            }
            else
            {
                adultSamples++;
            }
            final Track t = TRACKS.computeIfAbsent(citizen.getId(), id -> new Track());
            if (t.head.push(Mth.wrapDegrees(citizen.getYHeadRot() - citizen.yBodyRot)))
            {
                t.head_++;
            }
            if (t.body.push(citizen.yBodyRot))
            {
                t.body_++;
            }
            if (t.pitch.push(citizen.getXRot()))
            {
                t.pitch_++;
            }

            // same child on the integrated server (diagnostic read across threads)
            if (citizen.isBaby() && mc.getSingleplayerServer() != null
                  && mc.getSingleplayerServer().overworld().getEntity(citizen.getId()) instanceof com.minecolonies.core.entity.citizen.EntityCitizen server)
            {
                final boolean sh = t.serverHead.push(Mth.wrapDegrees(server.getYHeadRot() - server.yBodyRot));
                final boolean sb = t.serverBody.push(server.yBodyRot);
                if (sh)
                {
                    t.serverHead_++;
                }
                if (sb)
                {
                    t.serverBody_++;
                }
                if (sh || sb)
                {
                    final String state = server.getCitizenAI().getState() + (server.getNavigation().isDone() ? "/still" : "/moving");
                    t.states.merge(state, 1, Integer::sum);
                }
            }
        }

        if (mc.level.getGameTime() % 20 == 0)
        {
            final long now = mc.level.getGameTime();
            TRACKS.forEach((id, t) -> {
                final int sum = t.head_ + t.body_ + t.pitch_;
                final boolean baby = mc.level.getEntity(id) instanceof AbstractEntityCitizen c && c.isBaby();
                if (baby)
                {
                    totalReversals += sum;
                }
                else
                {
                    adultReversals += sum;
                }
                if ((sum >= 3 || t.serverHead_ + t.serverBody_ >= 3) && baby)
                {
                    final ClientAnimations.Active active = ClientAnimations.get(id, now);
                    ColoniesAlive.LOGGER.info("[jitter] child #{} head {} body {} pitch {} | server head {} body {} | states {} (pose {})",
                      id, t.head_, t.body_, t.pitch_, t.serverHead_, t.serverBody_, t.states, active == null ? "none" : active.animation());
                }
                t.head_ = 0;
                t.body_ = 0;
                t.pitch_ = 0;
                t.serverHead_ = 0;
                t.serverBody_ = 0;
                t.states.clear();
            });
        }
    }
}
