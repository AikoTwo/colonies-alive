package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ChildAnimation;
import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.network.GrabPayload;
import com.aiko.coloniesalive.social.Social;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Picking a child up with the mouse (ported from our MCA addon): hold the grab key (middle mouse button by default)
 * while looking at a child and it floats in front of you, following where you look; the mouse wheel brings it closer
 * or farther; releasing the key puts it down where it is. The child always faces the player.
 *
 * <p>Every child reacts in its own way. MineColonies has no personalities, so the temperament comes from the citizen's
 * id (stable for each child) and its mood: unhappy or just hurt children get scared or grumpy.
 * <ul>
 *     <li>{@link Reaction#WHEE}: laughs, arms up, legs kicking; cheers when put down</li>
 *     <li>{@link Reaction#CALM}: legs dangling, looking around</li>
 *     <li>{@link Reaction#SHY}: hands on the face, peeking</li>
 *     <li>{@link Reaction#GRUMPY}: arms crossed, pouts when put down</li>
 *     <li>{@link Reaction#SCARED}: flails; held longer than 6 s it cries when put down</li>
 * </ul>
 */
public final class GrabCarry
{
    enum Reaction
    {
        WHEE(ChildAnimation.GRAB_WHEE),
        CALM(ChildAnimation.GRAB_CALM),
        SHY(ChildAnimation.GRAB_SHY),
        GRUMPY(ChildAnimation.GRAB_GRUMPY),
        SCARED(ChildAnimation.GRAB_SCARED);

        final ChildAnimation animation;

        Reaction(final ChildAnimation animation)
        {
            this.animation = animation;
        }
    }

    private static final class Hold
    {
        final EntityCitizen child;
        final Reaction      reaction;
        final long          start;
        double distance;
        long   nextSound;

        Hold(final EntityCitizen child, final Reaction reaction, final long start, final double distance)
        {
            this.child = child;
            this.reaction = reaction;
            this.start = start;
            this.distance = distance;
        }
    }

    private static final double MIN_DISTANCE      = 1.2;
    private static final double MAX_DISTANCE      = 3.5;
    private static final double SCROLL_STEP       = 0.25;
    private static final double REACH_EXTRA       = 1.0;
    private static final int    SCARED_CRY_TICKS  = 120;

    private static final Map<UUID, Hold> HOLDS     = new HashMap<>();
    private static final Map<UUID, UUID> HOLDER_OF = new HashMap<>();

    private GrabCarry() {}

    public static boolean isHeld(final EntityCitizen child)
    {
        return HOLDER_OF.containsKey(child.getUUID());
    }

    public static void onPayload(final ServerPlayer player, final GrabPayload payload)
    {
        switch (payload.action())
        {
            case GrabPayload.START -> start(player, payload.entityId());
            case GrabPayload.DROP -> release(player);
            case GrabPayload.DISTANCE ->
            {
                final Hold hold = HOLDS.get(player.getUUID());
                if (hold != null)
                {
                    hold.distance = Mth.clamp(hold.distance + payload.value() * SCROLL_STEP, MIN_DISTANCE, MAX_DISTANCE);
                }
            }
            default ->
            {
            }
        }
    }

    private static void start(final ServerPlayer player, final int entityId)
    {
        if (HOLDS.containsKey(player.getUUID()) || player.isSpectator() || !player.isAlive()
              || !(player.level().getEntity(entityId) instanceof EntityCitizen child) || !child.isAlive() || !child.isBaby())
        {
            return;
        }
        final double reach = player.entityInteractionRange() + REACH_EXTRA;
        if (child.distanceToSqr(player.getEyePosition()) > reach * reach || isHeld(child))
        {
            return;
        }
        if (child.isSleeping())
        {
            child.stopSleeping();
        }
        if (child.isPassenger())
        {
            child.stopRiding();
        }

        final ChildLifeAI ai = ChildLifeEvents.get(child);
        final Reaction reaction = react(child, ai, player);
        final long now = child.level().getGameTime();
        final Hold hold = new Hold(child, reaction, now,
          Mth.clamp(player.getEyePosition().distanceTo(child.getBoundingBox().getCenter()), MIN_DISTANCE, 2.2));
        hold.nextSound = now + 10;
        HOLDS.put(player.getUUID(), hold);
        HOLDER_OF.put(child.getUUID(), player.getUUID());

        child.setNoGravity(true);
        child.getNavigation().stop();
        if (ai != null)
        {
            ai.onGrabbed();
            ai.play(pose(child, reaction));
        }
        if (reaction == Reaction.WHEE)
        {
            Fx.giggle(child);
        }
        else if (reaction == Reaction.SCARED)
        {
            Fx.cry(child);
        }
        else if (reaction == Reaction.GRUMPY)
        {
            Fx.grumble(child);
            Fx.angry(child);
        }
        player.displayClientMessage(Component.translatable("coloniesalive.grab.hint", child.getDisplayName()), true);
        if (ai != null)
        {
            ai.debug("picked up by " + player.getName().getString() + " (" + reaction + ")");
        }
    }

    /** The pose for this hold: a baby sits in the arms (reaching out, or crying), a child shows its reaction. */
    private static ChildAnimation pose(final EntityCitizen child, final Reaction reaction)
    {
        if (com.aiko.coloniesalive.social.Family.isInfant(child))
        {
            return reaction == Reaction.SCARED ? ChildAnimation.BABY_HELD_CRY : ChildAnimation.BABY_HELD;
        }
        return reaction.animation;
    }

    private static Reaction react(final EntityCitizen child, final ChildLifeAI ai, final ServerPlayer player)
    {
        if (ai != null && ai.isCrying())
        {
            return Reaction.SCARED;
        }
        // babies love being held, unless they can't stand this player
        if (com.aiko.coloniesalive.social.Family.isInfant(child))
        {
            return Social.affinity(child, player) <= -30 ? Reaction.SCARED : Math.floorMod(child.getUUID().hashCode(), 3) == 0 ? Reaction.CALM : Reaction.WHEE;
        }

        // how the child feels about this player matters most
        final int affinity = Social.affinity(child, player);
        if (affinity <= -30)
        {
            return (child.getId() & 1) == 0 ? Reaction.GRUMPY : Reaction.SCARED;
        }
        if (affinity >= 40)
        {
            return Math.floorMod(child.getUUID().hashCode(), 3) == 0 ? Reaction.CALM : Reaction.WHEE;
        }
        if (Social.mood(child) < -4)
        {
            return Reaction.GRUMPY;
        }

        final ICitizenData data = child.getCitizenData();
        final IColony colony = child.getCitizenColonyHandler().getColony();
        if (data != null && colony != null)
        {
            final double happiness = data.getCitizenHappinessHandler().getHappiness(colony, data);
            if (happiness < 4)
            {
                return (child.getId() & 1) == 0 ? Reaction.GRUMPY : Reaction.SCARED;
            }
        }

        // a stable temperament per child
        final int roll = Math.floorMod(child.getUUID().hashCode(), 100);
        if (roll < 35)
        {
            return Reaction.WHEE;
        }
        if (roll < 60)
        {
            return Reaction.CALM;
        }
        if (roll < 75)
        {
            return Reaction.SHY;
        }
        if (roll < 87)
        {
            return Reaction.SCARED;
        }
        return Reaction.GRUMPY;
    }

    @SubscribeEvent
    public static void onPlayerTick(final PlayerTickEvent.Post event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
        {
            return;
        }
        final Hold hold = HOLDS.get(player.getUUID());
        if (hold == null)
        {
            return;
        }
        final EntityCitizen child = hold.child;
        if (!child.isAlive() || child.isRemoved() || child.level() != player.level() || !player.isAlive() || player.isSpectator()
              || child.isPassenger() || child.distanceToSqr(player) > 64 || !child.isBaby())
        {
            release(player);
            return;
        }
        final ServerLevel level = (ServerLevel) player.level();
        final long now = level.getGameTime();

        // In front of the eyes at the chosen distance, without going through walls or into the ground
        final Vec3 eye = player.getEyePosition();
        final Vec3 look = player.getLookAngle();
        double d = hold.distance;
        final BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(d + 0.4)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.MISS)
        {
            d = Math.max(0.8, eye.distanceTo(hit.getLocation()) - 0.4);
        }
        Vec3 feet = eye.add(look.scale(d)).subtract(0.0, child.getBbHeight() * 0.55, 0.0);
        AABB box = child.getDimensions(child.getPose()).makeBoundingBox(feet);
        for (int i = 0; i < 16 && !level.noCollision(child, box); i++)
        {
            feet = feet.add(0.0, 1.0 / 16.0, 0.0);
            box = box.move(0.0, 1.0 / 16.0, 0.0);
        }
        final Vec3 current = child.position();
        child.setPos(current.distanceToSqr(feet) > 9.0 ? feet : current.lerp(feet, 0.55));
        child.setDeltaMovement(Vec3.ZERO);
        child.resetFallDistance();
        child.getNavigation().stop();

        final float yaw = (float) (Mth.atan2(player.getZ() - child.getZ(), player.getX() - child.getX()) * Mth.RAD_TO_DEG) - 90.0f;
        child.setYRot(yaw);
        child.yBodyRot = yaw;
        child.yHeadRot = yaw;

        final ChildLifeAI ai = ChildLifeEvents.get(child);
        final ChildAnimation holdPose = pose(child, hold.reaction);
        if (ai != null && ai.currentAnimation() != holdPose)
        {
            ai.play(holdPose);
        }
        tickSounds(level, hold, now);
    }

    private static void tickSounds(final ServerLevel level, final Hold hold, final long now)
    {
        if (now < hold.nextSound)
        {
            return;
        }
        final EntityCitizen child = hold.child;
        hold.nextSound = now + 40 + child.getRandom().nextInt(30);
        switch (hold.reaction)
        {
            case WHEE ->
            {
                Fx.giggle(child);
                Fx.hearts(child, 1);
            }
            case CALM ->
            {
                if (child.getRandom().nextInt(3) == 0)
                {
                    Fx.happy(child, 2);
                }
            }
            case SHY -> Fx.hearts(child, 1);
            case GRUMPY ->
            {
                Fx.angry(child);
                if (child.getRandom().nextInt(3) != 0)
                {
                    Fx.grumble(child);
                }
            }
            case SCARED ->
            {
                Fx.tears(child);
                if (child.getRandom().nextBoolean())
                {
                    Fx.cry(child);
                }
            }
        }
    }

    /** Puts the child down where it is. */
    public static void release(final ServerPlayer player)
    {
        final Hold hold = HOLDS.remove(player.getUUID());
        if (hold == null)
        {
            return;
        }
        final EntityCitizen child = hold.child;
        HOLDER_OF.remove(child.getUUID());
        child.setNoGravity(false);
        if (!child.isAlive() || child.isRemoved())
        {
            return;
        }
        child.setDeltaMovement(0.0, -0.05, 0.0);

        final ChildLifeAI ai = ChildLifeEvents.get(child);
        if (ai == null)
        {
            return;
        }
        final long held = child.level().getGameTime() - hold.start;
        if (com.aiko.coloniesalive.social.Family.isInfant(child))
        {
            // a baby is put down on its bottom: sitting again right away (crying on, if it was scared)
            ai.babyPutDown();
            if (hold.reaction == Reaction.SCARED && held >= SCARED_CRY_TICKS)
            {
                ai.startCryingFor(100);
                Social.addAffinity(child, player, -5);
            }
            else if (hold.reaction != Reaction.SCARED)
            {
                Fx.giggle(child);
                Fx.hearts(child, 3);
                Social.addAffinity(child, player, 3);
                Social.addMood(child, 1.5f);
            }
            ai.debug("baby put down after " + held + " ticks");
            return;
        }
        switch (hold.reaction)
        {
            case WHEE ->
            {
                ai.broadcast(ChildAnimation.HOP_JOY, 50);
                Fx.giggle(child);
                Fx.hearts(child, 3);
                Social.addAffinity(child, player, 3);
                Social.addMood(child, 1.5f);
            }
            case CALM ->
            {
                ai.broadcast(ChildAnimation.NONE, 0);
                Fx.happy(child, 4);
                Social.addAffinity(child, player, 2);
                Social.addMood(child, 1f);
            }
            case SHY ->
            {
                ai.broadcast(ChildAnimation.GRAB_SHY, 40);
                Social.addAffinity(child, player, 2);
            }
            case GRUMPY ->
            {
                ai.broadcast(ChildAnimation.POUT, 70);
                Fx.angry(child);
                Fx.grumble(child);
                Social.addAffinity(child, player, -1);
                Social.addMood(child, -0.5f);
            }
            case SCARED ->
            {
                if (held >= SCARED_CRY_TICKS)
                {
                    ai.startCryingFor(100);
                    Social.addAffinity(child, player, -5);
                    Social.addMood(child, -2f);
                }
                else
                {
                    ai.broadcast(ChildAnimation.NONE, 0);
                }
            }
        }
        ai.debug("put down after " + held + " ticks");
    }

    @SubscribeEvent
    public static void onLogout(final PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            release(player);
        }
    }

    @SubscribeEvent
    public static void onChangeDimension(final PlayerEvent.PlayerChangedDimensionEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            release(player);
        }
    }

    /** Safety net: a child saved while floating would stay without gravity forever. */
    public static void fixOrphan(final EntityCitizen child)
    {
        if (child.isNoGravity() && !isHeld(child))
        {
            child.setNoGravity(false);
            ColoniesAlive.LOGGER.debug("Restored gravity of {}", child.getName().getString());
        }
    }
}
