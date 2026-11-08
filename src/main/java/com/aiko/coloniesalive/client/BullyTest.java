package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.server.BabyCare;
import com.aiko.coloniesalive.server.Bully;
import com.aiko.coloniesalive.social.Family;
import com.aiko.coloniesalive.social.Social;
import com.aiko.coloniesalive.social.SocialCommand;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Observe run with -Pbullytest=true (development): a mercenary goes for a child (it should cry, run to tell the family,
 * the relative should fight it and hug the child), then for a baby (born for the test; it should wail and be picked
 * up and rocked). Screenshots ca_test_bully_*.png; the log tells the story ([bully], [babycare], [child]).
 */
final class BullyTest
{
    static final boolean ENABLED = Boolean.getBoolean("coloniesalive.bullytest");

    private static final int START = 100;
    private static final int BABY  = 1400;
    private static final int END   = 2700;

    private static volatile int kidId  = -1;
    private static volatile int babyId = -1;
    private static volatile int mumId  = -1;
    private static volatile int mobId  = -1;
    private static volatile int grownId = -1;

    private BullyTest() {}

    static void tick(final Minecraft mc, final IntegratedServer server, final int tick)
    {
        final java.util.UUID playerId = mc.player.getUUID();
        final int t = tick - START;
        if (t == 0)
        {
            server.execute(() -> {
                // a baby for the second part
                final List<EntityCitizen> women = all(server, c -> !c.isBaby() && !c.isSleeping() && c.getCitizenData() != null && c.getCitizenData().isFemale()
                                                                   && !Family.isPregnant(c));
                if (!women.isEmpty())
                {
                    final EntityCitizen mum = women.get(0);
                    mumId = mum.getId();
                    Family.startPregnancy(mum, 0.99f);
                }
            });
        }
        else if (t == 10)
        {
            server.execute(() -> {
                if (server.overworld().getEntity(mumId) instanceof EntityCitizen mum)
                {
                    ColoniesAlive.LOGGER.info("[bully] test: baby born {}", Family.birthNow(mum));
                }
            });
        }
        else if (t == 40)
        {
            // the first baby grows into a child (with a mum, to run and tell), and a second baby is born
            server.execute(() -> {
                final List<EntityCitizen> babies = all(server, Family::isInfant);
                if (!babies.isEmpty())
                {
                    final EntityCitizen first = babies.get(babies.size() - 1);
                    ColoniesAlive.LOGGER.info("[bully] test: {} grows up {}", first.getName().getString(), Family.growNow(first));
                    grownId = first.getId();
                }
                if (server.overworld().getEntity(mumId) instanceof EntityCitizen mum)
                {
                    Family.startPregnancy(mum, 0.99f);
                }
            });
        }
        else if (t == 50)
        {
            server.execute(() -> {
                if (server.overworld().getEntity(mumId) instanceof EntityCitizen mum)
                {
                    ColoniesAlive.LOGGER.info("[bully] test: second baby born {}", Family.birthNow(mum));
                }
            });
        }
        else if (t == 80)
        {
            server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                final List<EntityCitizen> kids = all(server, c -> c.isBaby() && !Family.isInfant(c) && !c.isSleeping() && BabyCare.closestFamily(c) != null);
                if (player == null || kids.isEmpty())
                {
                    ColoniesAlive.LOGGER.info("[bully] test: no child with family found");
                    return;
                }
                kids.sort(Comparator.comparingDouble(c -> c.distanceToSqr(player)));
                final EntityCitizen kid = kids.get(0);
                kidId = kid.getId();
                ColoniesAlive.LOGGER.info("[bully] test: child {} mood {} family {}", kid.getName().getString(), Social.mood(kid),
                  BabyCare.closestFamily(kid).getName().getString());
                final Mob mob = SocialCommand.bullyFor(kid);
                mobId = mob == null ? -1 : mob.getId();
                ColoniesAlive.LOGGER.info("[bully] test: bully {}", mob == null ? "none" : mob.getName().getString());
                watch(player, kid);
            });
        }
        else if (t > 80 && t < BABY && t % 100 == 0)
        {
            server.execute(() -> follow(server, playerId, kidId));
            if (t <= 900)
            {
                shot(mc, "ca_test_bully_kid_" + t + ".png");
            }
        }
        else if (t == BABY)
        {
            server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                logMood(server, kidId, "child after");
                final List<EntityCitizen> babies = all(server, c -> c.isAlive() && Family.isInfant(c));
                if (server.overworld().getEntity(mobId) instanceof Mob old)
                {
                    old.discard();
                }
                if (player == null || babies.isEmpty())
                {
                    ColoniesAlive.LOGGER.info("[bully] test: no baby for the baby test");
                    return;
                }
                final EntityCitizen baby = babies.get(babies.size() - 1);
                babyId = baby.getId();
                // this time mum is right there and sees it: a real fight, then she picks the baby up
                if (server.overworld().getEntity(mumId) instanceof EntityCitizen mum)
                {
                    mum.setHealth(mum.getMaxHealth());
                    mum.teleportTo(baby.getX() - 3, baby.getY(), baby.getZ());
                    mum.getNavigation().stop();
                }
                final Mob mob = SocialCommand.bullyFor(baby);
                mobId = mob == null ? -1 : mob.getId();
                ColoniesAlive.LOGGER.info("[bully] test: baby {} mood {} family {} -> bully {}", baby.getName().getString(), Social.mood(baby),
                  BabyCare.closestFamily(baby) == null ? "none" : BabyCare.closestFamily(baby).getName().getString(), mob != null);
                watch(player, baby);
            });
        }
        else if (t > BABY && t < END && t % 100 == 0)
        {
            server.execute(() -> follow(server, playerId, babyId));
            if (t <= BABY + 900)
            {
                shot(mc, "ca_test_bully_baby_" + (t - BABY) + ".png");
            }
        }
        else if (t == END)
        {
            server.execute(() -> {
                logMood(server, babyId, "baby after");
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null)
                {
                    player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                }
                if (server.overworld().getEntity(mobId) instanceof Mob mob)
                {
                    mob.discard();
                }
                // both test citizens leave again, so the test world doesn't fill up
                for (final int id : new int[] {babyId, grownId})
                {
                    if (server.overworld().getEntity(id) instanceof EntityCitizen test && test.getCitizenData() != null)
                    {
                        test.stopRiding();
                        test.getCitizenData().getColony().getCitizenManager().removeCivilian(test.getCitizenData());
                        test.discard();
                        ColoniesAlive.LOGGER.info("[bully] test: test citizen {} removed", test.getName().getString());
                    }
                }
            });
        }
    }

    private static List<EntityCitizen> all(final IntegratedServer server, final java.util.function.Predicate<EntityCitizen> filter)
    {
        final List<EntityCitizen> found = new ArrayList<>();
        server.overworld().getEntities(EntityTypeTest.forClass(EntityCitizen.class), c -> c.isAlive() && filter.test(c), found);
        return found;
    }

    private static void logMood(final IntegratedServer server, final int id, final String what)
    {
        if (server.overworld().getEntity(id) instanceof EntityCitizen c)
        {
            ColoniesAlive.LOGGER.info("[bully] test: {} {} mood {} health {}", what, c.getName().getString(), String.format("%.1f", Social.mood(c)),
              String.format("%.1f", c.getHealth()));
        }
    }

    /** The player hovers a few blocks away (spectator, so nobody reacts to it), looking at the scene. */
    private static void watch(final ServerPlayer player, final Entity target)
    {
        player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        follow(player, target);
    }

    private static void follow(final IntegratedServer server, final java.util.UUID playerId, final int id)
    {
        final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null && server.overworld().getEntity(id) instanceof Entity target)
        {
            follow(player, target);
        }
    }

    private static void follow(final ServerPlayer player, final Entity target)
    {
        final double x = target.getX() + 5;
        final double y = target.getY() + 4;
        final double z = target.getZ() + 5;
        final double dx = target.getX() - x;
        final double dz = target.getZ() - z;
        final float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        final float pitch = (float) Math.toDegrees(Math.atan2(4, Math.sqrt(dx * dx + dz * dz)));
        player.teleportTo(player.serverLevel(), x, y, z, yaw, pitch);
    }

    private static void shot(final Minecraft mc, final String name)
    {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {});
    }
}
