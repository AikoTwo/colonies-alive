package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.social.Family;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Observe run (development): a woman gets pregnant at 90 %, belly screenshots from the front and the side, she gives
 * birth, the baby gets screenshotted next to her. Both are lifted up into the sky, like the test child, so nothing
 * blocks the view. Afterwards the test baby is taken out of the colony again, so the test world doesn't fill up.
 */
final class FamilyTest
{
    private static final int START = 1600;

    private static volatile int motherId = -1;
    private static volatile int babyId   = -1;
    private static volatile int kidId    = -1;
    private static volatile int laborId  = -1;
    private static Vec3         kidPos;
    private static Vec3         motherPos;
    private static net.minecraft.world.item.ItemStack chestBefore;

    private FamilyTest() {}

    static void tick(final Minecraft mc, final IntegratedServer server, final int tick)
    {
        final java.util.UUID playerId = mc.player.getUUID();
        final int t = tick - START;
        switch (t)
        {
            case 0 -> server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                final List<EntityCitizen> women = new ArrayList<>();
                server.overworld().getEntities(EntityTypeTest.forClass(EntityCitizen.class),
                  c -> c.isAlive() && !c.isBaby() && !c.isSleeping() && c.getCitizenData() != null && c.getCitizenData().isFemale(), women);
                if (player == null || women.isEmpty())
                {
                    ColoniesAlive.LOGGER.info("[family] test: no woman found");
                    return;
                }
                // a woman in a chestplate (a guard) first: the belly has to wear the armor, not poke through it
                women.sort(Comparator.comparing((EntityCitizen c) -> c.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty())
                             .thenComparingDouble(c -> c.distanceToSqr(player)));
                ColoniesAlive.LOGGER.info("[family] test: chest {}", women.get(0).getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
                final EntityCitizen mother = women.get(0);
                motherId = mother.getId();
                motherPos = mother.position();
                Family.startPregnancy(mother, 0.9f);
                lift(mother, 0);
                standInFront(player, mother, 2.4, 0);
                ColoniesAlive.LOGGER.info("[family] test: {} pregnant 90%, speed {}", mother.getName().getString(),
                  String.format("%.3f", mother.getAttributeValue(Attributes.MOVEMENT_SPEED)));
            });
            case 1 -> mc.options.hideGui = false;
            case 50 -> shot(mc, "ca_test_pregnant_front.png");
            case 60 -> server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && server.overworld().getEntity(motherId) instanceof EntityCitizen mother)
                {
                    standInFront(player, mother, 2.4, 50);
                }
            });
            case 90 -> shot(mc, "ca_test_pregnant_side.png");
            // in a chestplate, like a guard: the belly has to wear the armor, not poke through it
            case 92 -> server.execute(() -> {
                if (server.overworld().getEntity(motherId) instanceof EntityCitizen mother)
                {
                    chestBefore = mother.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).copy();
                    mother.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
                }
            });
            case 104 -> shot(mc, "ca_test_pregnant_armor.png");
            case 106 -> server.execute(() -> {
                if (server.overworld().getEntity(motherId) instanceof EntityCitizen mother && chestBefore != null)
                {
                    mother.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, chestBefore);
                }
            });
            case 110 -> server.execute(() -> {
                if (server.overworld().getEntity(motherId) instanceof EntityCitizen mother)
                {
                    ColoniesAlive.LOGGER.info("[family] test: birth {}", Family.birthNow(mother));
                }
            });
            case 150 -> server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                final List<EntityCitizen> babies = new ArrayList<>();
                server.overworld().getEntities(EntityTypeTest.forClass(EntityCitizen.class), c -> c.isAlive() && Family.isInfant(c), babies);
                if (player == null || babies.isEmpty() || !(server.overworld().getEntity(motherId) instanceof EntityCitizen mother))
                {
                    ColoniesAlive.LOGGER.info("[family] test: no baby found");
                    return;
                }
                final EntityCitizen baby = babies.get(babies.size() - 1);
                babyId = baby.getId();
                // mother back down; the baby lives on its own next to where she stood
                mother.setNoGravity(false);
                mother.setNoAi(false);
                mother.teleportTo(motherPos.x, motherPos.y, motherPos.z);
                // the baby stays at home, where it was born
                ColoniesAlive.LOGGER.info("[family] test: baby {} scale {} height {} state {}", baby.getName().getString(),
                  String.format("%.2f", baby.getScale()), String.format("%.2f", baby.getBbHeight()), baby.getCitizenAI().getState());
                final var babyAi = com.aiko.coloniesalive.server.ChildLifeEvents.get(baby);
                ColoniesAlive.LOGGER.info("[family] test: baby ai {}", babyAi == null ? "none" : babyAi.whyNotStarting());
            });
            // an older child comes to play peekaboo with the baby
            case 330 -> server.execute(() -> {
                if (!(server.overworld().getEntity(babyId) instanceof EntityCitizen baby))
                {
                    return;
                }
                final List<EntityCitizen> kids = new ArrayList<>();
                server.overworld().getEntities(EntityTypeTest.forClass(EntityCitizen.class),
                  c -> c.isAlive() && c.isBaby() && !Family.isInfant(c) && !c.isSleeping() && c.distanceToSqr(baby) < 60 * 60, kids);
                kids.sort(Comparator.comparingDouble(c -> c.distanceToSqr(baby)));
                if (kids.isEmpty())
                {
                    ColoniesAlive.LOGGER.info("[family] test: no child to play with the baby");
                    return;
                }
                final EntityCitizen kid = kids.get(0);
                kidId = kid.getId();
                kid.stopRiding();
                kid.teleportTo(baby.getX() + 3, baby.getY(), baby.getZ());
                final var kidAi = com.aiko.coloniesalive.server.ChildLifeEvents.get(kid);
                if (kidAi != null)
                {
                    kidAi.testNext(com.aiko.coloniesalive.server.Activities::visitBabyNow);
                }
                ColoniesAlive.LOGGER.info("[family] test: {} comes to play with the baby", kid.getName().getString());
            });
            // crawling (the name tag must stay put), then trying to walk
            case 620 -> babyNext(server, com.aiko.coloniesalive.server.Activities::babyCrawlTest, "crawls");
            case 760 -> babyNext(server, com.aiko.coloniesalive.server.Activities::babyWalkTest, "tries to walk");
            // picked up: sits in the arms
            case 900 -> server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && server.overworld().getEntity(babyId) instanceof EntityCitizen baby)
                {
                    watch(player, baby);
                    com.aiko.coloniesalive.server.GrabCarry.onPayload(player,
                      new com.aiko.coloniesalive.network.GrabPayload(com.aiko.coloniesalive.network.GrabPayload.START, babyId, 0));
                    ColoniesAlive.LOGGER.info("[family] test: baby picked up");
                }
            });
            case 940 -> shot(mc, "ca_test_baby_held.png");
            case 960 -> server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null)
                {
                    com.aiko.coloniesalive.server.GrabCarry.release(player);
                }
            });
            // hit: it must not run off, it sits and cries
            case 1000 -> server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && server.overworld().getEntity(babyId) instanceof EntityCitizen baby)
                {
                    final float health = baby.getHealth();
                    baby.hurt(player.damageSources().playerAttack(player), 0.5f);
                    baby.setHealth(health);
                    ColoniesAlive.LOGGER.info("[family] test: baby slapped");
                }
            });
            default ->
            {
                fashionShow(mc, server, playerId, t);
                if (t >= 170 && t <= 2390 && t % 20 == 0 && (t <= 1090 || t % 60 == 0))
                {
                    server.execute(() -> {
                        if (server.overworld().getEntity(babyId) instanceof EntityCitizen baby)
                        {
                            final var ai = com.aiko.coloniesalive.server.ChildLifeEvents.get(baby);
                            String kidInfo = "";
                            if (server.overworld().getEntity(kidId) instanceof EntityCitizen kid)
                            {
                                final var kidAi = com.aiko.coloniesalive.server.ChildLifeEvents.get(kid);
                                kidInfo = String.format(" | kid %s %s dist %.1f", kid.getCitizenAI().getState(), kidAi == null ? "?" : kidAi.currentAnimation(),
                                  Math.sqrt(kid.distanceToSqr(baby)));
                            }
                            if (t == 200 || t == 300)
                            {
                                ColoniesAlive.LOGGER.info("[family] test: baby ai {}", ai == null ? "none" : ai.whyNotStarting());
                            }
                            ColoniesAlive.LOGGER.info("[family] test: t{} baby state {} pose {} sitting {} moving {} job {}{}", t, baby.getCitizenAI().getState(),
                              ai == null ? "?" : ai.currentAnimation(), baby.getVehicle() != null, !baby.getNavigation().isDone(),
                              baby.getCitizenData() == null ? "?" : baby.getCitizenData().getJob(), kidInfo);
                            // keep the camera on it (not while it is held)
                            final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                            if (player != null && t % 60 == 40 && !com.aiko.coloniesalive.server.GrabCarry.isHeld(baby))
                            {
                                watch(player, baby);
                            }
                        }
                    });
                }
                if (t >= 200 && t <= 1090 && t % 60 == 50 && t != 890 && t != 950)
                {
                    shot(mc, "ca_test_baby_" + t + ".png");
                }
                // close-ups of the crawl and of getting up
                if (t == 650 || t == 690 || t == 780 || t == 800 || t == 830)
                {
                    shot(mc, "ca_test_baby_" + t + ".png");
                }
            }
            // badly hurt: no walking to the hospital, the healer comes
            case 1040 -> server.execute(() -> {
                if (server.overworld().getEntity(babyId) instanceof EntityCitizen baby)
                {
                    baby.setHealth(2f);
                    ColoniesAlive.LOGGER.info("[family] test: baby badly hurt (health 2)");
                }
            });
            // night: someone of the family puts it to bed (or it sleeps where it is)
            case 1700 -> server.execute(() -> {
                server.overworld().setDayTime(13000);
                ColoniesAlive.LOGGER.info("[family] test: night falls");
            });
            case 2000, 2200 -> shot(mc, "ca_test_baby_night_" + t + ".png");
            case 2300 -> server.execute(() -> {
                server.overworld().setDayTime(1000);
                ColoniesAlive.LOGGER.info("[family] test: morning");
            });
            // the test baby leaves the colony again, so the test world doesn't fill up
            case 3150 -> server.execute(() -> {
                Showcase.fashionSubject = -1;
                Showcase.fashionItem = null;
                if (server.overworld().getEntity(babyId) instanceof EntityCitizen baby && baby.getCitizenData() != null)
                {
                    baby.stopRiding();
                    baby.getCitizenData().getColony().getCitizenManager().removeCivilian(baby.getCitizenData());
                    baby.discard();
                    ColoniesAlive.LOGGER.info("[family] test: test baby removed");
                }
            });
            // labour: another woman due right now walks home first
            case 3200 -> server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                final List<EntityCitizen> women = new ArrayList<>();
                server.overworld().getEntities(EntityTypeTest.forClass(EntityCitizen.class),
                  c -> c.isAlive() && !c.isBaby() && !c.isSleeping() && c.getId() != motherId && c.getCitizenData() != null && c.getCitizenData().isFemale()
                         && c.getCitizenData().getHomeBuilding() != null && !c.getCitizenData().getHomeBuilding().isInBuilding(c.blockPosition()), women);
                if (player == null || women.isEmpty())
                {
                    ColoniesAlive.LOGGER.info("[family] test: no woman away from home for the labour test");
                    return;
                }
                final EntityCitizen mum = women.get(0);
                laborId = mum.getId();
                Family.startPregnancy(mum, 0.9999f);
                ColoniesAlive.LOGGER.info("[family] test: {} is due now, {} blocks from home", mum.getName().getString(),
                  (int) Math.sqrt(mum.blockPosition().distSqr(mum.getCitizenData().getHomeBuilding().getPosition())));
            });
            case 3260, 3400, 3600, 3800, 4000 -> server.execute(() -> {
                if (server.overworld().getEntity(laborId) instanceof EntityCitizen mum && mum.getCitizenData() != null)
                {
                    ColoniesAlive.LOGGER.info("[family] test: labour t{}: {} state {} pregnant {} {} blocks from home", t, mum.getName().getString(),
                      mum.getCitizenAI().getState(), Family.isPregnant(mum),
                      (int) Math.sqrt(mum.blockPosition().distSqr(mum.getCitizenData().getHomeBuilding().getPosition())));
                }
            });
            case 4100 -> server.execute(() -> {
                // the labour test's baby leaves again too
                if (server.overworld().getEntity(laborId) instanceof EntityCitizen mum && mum.getCitizenData() != null)
                {
                    for (final Integer childId : new ArrayList<>(mum.getCitizenData().getChildren()))
                    {
                        final var kid = mum.getCitizenData().getColony().getCitizenManager().getCivilian(childId);
                        if (kid != null && kid.getEntity().orElse(null) instanceof EntityCitizen baby && Family.isInfant(baby))
                        {
                            baby.stopRiding();
                            kid.getColony().getCitizenManager().removeCivilian(kid);
                            baby.discard();
                            ColoniesAlive.LOGGER.info("[family] test: labour test baby removed");
                        }
                    }
                }
            });
        }
    }

    /** Where the fashion show starts (relative to {@link #START}): every accessory once, a photo each. */
    private static final int FASHION = 2400;
    private static final int FASHION_STEP = 8;

    private static void fashionShow(final Minecraft mc, final IntegratedServer server, final java.util.UUID playerId, final int t)
    {
        final List<Accessories.Accessory> baby = new java.util.ArrayList<>(Accessories.BABY);
        baby.addAll(List.of(Accessories.BAND_AID_CHEEK, Accessories.BAND_AID_CHEEK_LEFT, Accessories.BAND_AID_ARM, Accessories.BAND_AID_KNEE,
          Accessories.HEAD_BANDAGE));
        final List<Accessories.Accessory> kid = Accessories.CHILD;
        final int babyEnd = FASHION + 10 + baby.size() * FASHION_STEP;
        final int kidStart = babyEnd + 40;
        final int kidEnd = kidStart + kid.size() * FASHION_STEP;
        if (t == FASHION)
        {
            server.execute(() -> {
                if (server.overworld().getEntity(babyId) instanceof EntityCitizen subject)
                {
                    final var ai = com.aiko.coloniesalive.server.ChildLifeEvents.get(subject);
                    if (ai != null)
                    {
                        ai.broadcast(com.aiko.coloniesalive.ChildAnimation.BABY_SIT, 2000);
                    }
                    subject.stopRiding();
                    lift(subject, 0);
                    Showcase.fashionSubject = subject.getId();
                }
            });
        }
        else if (t == babyEnd + 5)
        {
            // the baby becomes a child for the children's accessories
            server.execute(() -> {
                if (server.overworld().getEntity(babyId) instanceof EntityCitizen subject)
                {
                    Family.growNow(subject);
                    final var ai = com.aiko.coloniesalive.server.ChildLifeEvents.get(subject);
                    if (ai != null)
                    {
                        ai.broadcast(com.aiko.coloniesalive.ChildAnimation.NONE, 0);
                    }
                }
            });
        }
        final boolean babyPart = t >= FASHION + 10 && t < babyEnd;
        final boolean kidPart = t >= kidStart && t < kidEnd;
        if (!babyPart && !kidPart)
        {
            return;
        }
        final int step = babyPart ? (t - FASHION - 10) : (t - kidStart);
        final int index = step / FASHION_STEP;
        final Accessories.Accessory item = babyPart ? baby.get(index) : kid.get(index);
        if (step % FASHION_STEP == 0)
        {
            Showcase.fashionItem = item;
            server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && server.overworld().getEntity(babyId) instanceof EntityCitizen subject)
                {
                    final double angle = switch (item.slot())
                    {
                        case BACK -> 155;
                        case HAND, HANDS -> 35;
                        default -> 20;
                    };
                    standInFront(player, subject, item.slot() == Accessories.Slot.HAND && !babyPart ? 3.2 : babyPart ? 1.6 : 2.1, angle);
                }
            });
        }
        else if (step % FASHION_STEP == FASHION_STEP - 2)
        {
            shot(mc, "ca_fashion_" + (babyPart ? "baby_" : "kid_") + String.format("%02d", index) + ".png");
        }
    }

    private static void babyNext(final IntegratedServer server, final java.util.function.Function<com.aiko.coloniesalive.server.ChildLifeAI, com.aiko.coloniesalive.server.ChildActivity> next,
                                 final String what)
    {
        server.execute(() -> {
            if (server.overworld().getEntity(babyId) instanceof EntityCitizen baby)
            {
                final var ai = com.aiko.coloniesalive.server.ChildLifeEvents.get(baby);
                if (ai != null)
                {
                    ai.testNext(next);
                    ColoniesAlive.LOGGER.info("[family] test: baby {}", what);
                }
            }
        });
    }

    /** Camera 3 blocks off to the side of the baby, a bit above, looking down at it. */
    private static void watch(final ServerPlayer player, final EntityCitizen baby)
    {
        // the baby is usually indoors: the first free spot around it with a clear view (not inside a wall)
        final var level = player.serverLevel();
        final double[][] offsets = {{2.2, 2.2}, {-2.2, 2.2}, {2.2, -2.2}, {-2.2, -2.2}, {2.5, 0}, {-2.5, 0}, {0, 2.5}, {0, -2.5}, {1.6, 1.6}, {-1.6, 1.6}, {1.6, -1.6}, {-1.6, -1.6}};
        for (final double rise : new double[] {1.0, 0.4})
        {
            for (final double[] o : offsets)
            {
                final double x = baby.getX() + o[0];
                final double z = baby.getZ() + o[1];
                final net.minecraft.core.BlockPos feet = net.minecraft.core.BlockPos.containing(x, baby.getY() + rise, z);
                final Vec3 eye = new Vec3(x, baby.getY() + rise + 1.62, z);
                final boolean free = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                                       && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
                final boolean clear = level.clip(new net.minecraft.world.level.ClipContext(eye, baby.position().add(0, 0.3, 0),
                  net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player)).getType()
                                        == net.minecraft.world.phys.HitResult.Type.MISS;
                if (free && clear)
                {
                    final float lookYaw = (float) Math.toDegrees(Math.atan2(baby.getZ() - z, baby.getX() - x)) - 90f;
                    final double dy = eye.y - (baby.getY() + 0.3);
                    final float pitch = (float) Math.toDegrees(Math.atan2(dy, Math.sqrt(o[0] * o[0] + o[1] * o[1])));
                    player.teleportTo(level, x, baby.getY() + rise, z, lookYaw, pitch);
                    return;
                }
            }
        }
        ColoniesAlive.LOGGER.info("[family] test: no clear spot to watch the baby from");
    }

    /** Up into the sky, frozen like a statue (no AI: MineColonies would otherwise bring her back down at once). */
    private static void lift(final EntityCitizen citizen, final double dx)
    {
        citizen.getNavigation().stop();
        citizen.setNoAi(true);
        citizen.setNoGravity(true);
        citizen.setDeltaMovement(Vec3.ZERO);
        citizen.teleportTo(citizen.getX() + 6 + dx, 200, citizen.getZ());
        // body, head and facing all the same way, so "front" and "side" shots really are front and side
        citizen.setYRot(0f);
        citizen.setYBodyRot(0f);
        citizen.setYHeadRot(0f);
    }

    /** The player {@code distance} blocks away, {@code angle} degrees around from her front, looking at her. */
    private static void standInFront(final ServerPlayer player, final EntityCitizen target, final double distance, final double angle)
    {
        final double yaw = Math.toRadians(target.getYRot() + angle);
        final double x = target.getX() - Math.sin(yaw) * distance;
        final double z = target.getZ() + Math.cos(yaw) * distance;
        final float lookYaw = (float) Math.toDegrees(Math.atan2(target.getZ() - z, target.getX() - x)) - 90f;
        // a little above, looking down: small citizens fill the picture too
        player.teleportTo(player.serverLevel(), x, target.getY() + 0.2, z, lookYaw, target.isBaby() ? 28f : 12f);
    }

    private static void shot(final Minecraft mc, final String name)
    {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {});
    }
}
