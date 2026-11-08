package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.network.GrabPayload;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Client side of picking children up ({@link com.aiko.coloniesalive.server.GrabCarry}): hold the key (middle mouse
 * button by default, changeable in Controls) while looking at a child; release to put it down; the mouse wheel moves it
 * closer or farther. While holding, attacks and right clicks are blocked. The held child is turned to the camera every
 * frame here on the client, otherwise it lagged behind when turning the camera.
 */
public final class GrabClient
{
    public static final KeyMapping GRAB = new KeyMapping("key.coloniesalive.grab", KeyConflictContext.IN_GAME,
      InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, KeyMapping.CATEGORY_MISC);

    private static boolean holding;
    private static boolean wasDown;
    private static int     heldId = -1;

    private GrabClient() {}

    private static AbstractEntityCitizen target(final Minecraft mc)
    {
        if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof AbstractEntityCitizen citizen && citizen.isBaby())
        {
            return citizen;
        }
        return null;
    }

    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Post event)
    {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null)
        {
            holding = false;
            wasDown = false;
            heldId = -1;
            return;
        }
        final boolean down = GRAB.isDown() && mc.screen == null;
        if (down && !wasDown && !holding)
        {
            final AbstractEntityCitizen child = target(mc);
            if (child != null)
            {
                PacketDistributor.sendToServer(new GrabPayload(GrabPayload.START, child.getId(), 0.0f));
                holding = true;
                heldId = child.getId();
            }
        }
        else if (!down && holding)
        {
            PacketDistributor.sendToServer(new GrabPayload(GrabPayload.DROP, 0, 0.0f));
            holding = false;
            heldId = -1;
        }
        wasDown = down;
        faceCamera(mc);
    }

    @SubscribeEvent
    public static void onRenderFrame(final RenderFrameEvent.Pre event)
    {
        faceCamera(Minecraft.getInstance());
    }

    /** Body and head of the held child stay turned to the camera. */
    private static void faceCamera(final Minecraft mc)
    {
        if (!holding || mc.level == null || !(mc.level.getEntity(heldId) instanceof AbstractEntityCitizen child) || !child.isNoGravity())
        {
            // isNoGravity: the server accepted (only held children float)
            return;
        }
        final Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        final double dx = camera.x - child.getX();
        final double dz = camera.z - child.getZ();
        final double dy = camera.y - child.getEyeY();
        final float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f;
        final float pitch = Mth.clamp((float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG), -60.0f, 60.0f);
        child.setYRot(yaw);
        child.yRotO = yaw;
        child.yBodyRot = yaw;
        child.yBodyRotO = yaw;
        child.yHeadRot = yaw;
        child.yHeadRotO = yaw;
        child.setXRot(pitch);
        child.xRotO = pitch;
    }

    @SubscribeEvent
    public static void onInteraction(final InputEvent.InteractionKeyMappingTriggered event)
    {
        if (holding)
        {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
        else if (event.isPickBlock() && GRAB.isDown() && target(Minecraft.getInstance()) != null)
        {
            // the middle button is also "pick block"
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScroll(final InputEvent.MouseScrollingEvent event)
    {
        if (holding && event.getScrollDeltaY() != 0.0)
        {
            PacketDistributor.sendToServer(new GrabPayload(GrabPayload.DISTANCE, 0, (float) Math.signum(event.getScrollDeltaY())));
            event.setCanceled(true);
        }
    }
}
