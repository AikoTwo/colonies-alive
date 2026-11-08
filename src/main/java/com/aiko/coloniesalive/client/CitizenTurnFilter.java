package com.aiko.coloniesalive.client;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Citizens' heads (and bodies) used to twitch: small back-and-forth turns coming from path following and from
 * several systems aiming the head. This filter decides what is drawn, so it can't happen anymore, whatever the cause:
 * right before a citizen is rendered, its body yaw, head yaw and head pitch are replaced by filtered values, and put
 * back right after. Game logic never sees the filtered values.
 *
 * <p>Each angle has hysteresis: it only starts turning once the real angle is more than {@link #START} degrees away,
 * then follows smoothly until it is within {@link #STOP} degrees. A small, quick wobble never gets past {@link #START},
 * so it never moves the head at all.
 */
public final class CitizenTurnFilter
{
    private static final float START = 4.5f;
    private static final float STOP  = 0.8f;

    /** Time constants (seconds) of the smooth follow. */
    private static final float BODY_TAU  = 0.16f;
    private static final float HEAD_TAU  = 0.12f;
    private static final float PITCH_TAU = 0.12f;

    private static final class Angle
    {
        float   value;
        boolean moving;
        /** Direction of the current turn: +1 or -1. */
        float   direction;

        float follow(final float target, final float dt, final float tau)
        {
            final float delta = Mth.wrapDegrees(target - value);
            if (!moving)
            {
                if (Math.abs(delta) <= START)
                {
                    return value;
                }
                moving = true;
                direction = Math.signum(delta);
            }
            else if (Math.signum(delta) != direction)
            {
                // Turning back: only for a real change of mind, not for a wobble on top of a turn
                if (Math.abs(delta) <= START)
                {
                    moving = false;
                    return value;
                }
                direction = Math.signum(delta);
            }
            value = Mth.wrapDegrees(value + delta * (1.0f - (float) Math.exp(-dt / tau)));
            if (Math.abs(Mth.wrapDegrees(target - value)) < STOP)
            {
                moving = false;
            }
            return value;
        }
    }

    private static final class State
    {
        final Angle body  = new Angle();
        final Angle head  = new Angle();
        final Angle pitch = new Angle();
        long        lastNanos;

        // the real values, put back after rendering
        float yBodyRot;
        float yBodyRotO;
        float yHeadRot;
        float yHeadRotO;
        float xRot;
        float xRotO;
        boolean swapped;
    }

    private static final Map<Integer, State> STATES = new HashMap<>();

    private CitizenTurnFilter() {}

    private static boolean applies(final LivingEntity entity)
    {
        // held children are turned to the camera by GrabClient; sleepers and riders have fixed poses
        return entity instanceof AbstractEntityCitizen && !entity.isNoGravity() && entity.getVehicle() == null && entity.getPose() != Pose.SLEEPING;
    }

    @SubscribeEvent
    public static void onRenderPre(final RenderLivingEvent.Pre<?, ?> event)
    {
        final LivingEntity entity = event.getEntity();
        if (!applies(entity))
        {
            STATES.remove(entity.getId());
            return;
        }
        if (STATES.size() > 1024)
        {
            STATES.clear();
        }

        final float partial = event.getPartialTick();
        final float body = Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot);
        final float head = Mth.rotLerp(partial, entity.yHeadRotO, entity.yHeadRot);
        final float pitch = Mth.lerp(partial, entity.xRotO, entity.getXRot());

        final long now = System.nanoTime();
        State state = STATES.get(entity.getId());
        if (state == null || now - state.lastNanos > 500_000_000L)
        {
            // new, or not drawn for a while: start from the real values
            state = new State();
            state.body.value = body;
            state.head.value = head;
            state.pitch.value = pitch;
            STATES.put(entity.getId(), state);
        }
        final float dt = state.lastNanos == 0L ? 0.016f : Mth.clamp((now - state.lastNanos) / 1.0e9f, 0.0f, 0.1f);
        state.lastNanos = now;

        final float drawnBody = state.body.follow(body, dt, BODY_TAU);
        final float drawnHead = state.head.follow(head, dt, HEAD_TAU);
        final float drawnPitch = state.pitch.follow(pitch, dt, PITCH_TAU);

        state.yBodyRot = entity.yBodyRot;
        state.yBodyRotO = entity.yBodyRotO;
        state.yHeadRot = entity.yHeadRot;
        state.yHeadRotO = entity.yHeadRotO;
        state.xRot = entity.getXRot();
        state.xRotO = entity.xRotO;
        state.swapped = true;

        // old and new equal: the renderer's interpolation then gives exactly the filtered value
        entity.yBodyRot = drawnBody;
        entity.yBodyRotO = drawnBody;
        entity.yHeadRot = drawnHead;
        entity.yHeadRotO = drawnHead;
        entity.setXRot(drawnPitch);
        entity.xRotO = drawnPitch;

        if (JitterProbe.ENABLED)
        {
            JitterProbe.drawn(entity, drawnBody, drawnHead, drawnPitch);
        }
    }

    @SubscribeEvent
    public static void onRenderPost(final RenderLivingEvent.Post<?, ?> event)
    {
        final LivingEntity entity = event.getEntity();
        final State state = STATES.get(entity.getId());
        if (state == null || !state.swapped)
        {
            return;
        }
        state.swapped = false;
        entity.yBodyRot = state.yBodyRot;
        entity.yBodyRotO = state.yBodyRotO;
        entity.yHeadRot = state.yHeadRot;
        entity.yHeadRotO = state.yHeadRotO;
        entity.setXRot(state.xRot);
        entity.xRotO = state.xRotO;
    }
}
