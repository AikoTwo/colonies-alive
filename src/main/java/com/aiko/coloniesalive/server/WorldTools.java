package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.network.ClearPathDebugPayload;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.colonyEvents.EventStatus;
import com.minecolonies.api.colony.colonyEvents.IColonyEvent;
import com.minecolonies.api.colony.colonyEvents.IColonyRaidEvent;
import com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider;
import com.minecolonies.core.entity.pathfinding.PathfindingUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Helpers for the test world:
 * <ul>
 *     <li>{@link #calmColonies}: ends stuck raids properly, keeps ten iron golems, turns day/night and weather back on</li>
 *     <li>{@link #clearPathDebug}: turns off MineColonies' path tracking display for a player</li>
 * </ul>
 * Raiders must not just be removed while their raid is running: MineColonies respawns raiders that vanish without dying,
 * so the raid never ends and the citizens stay locked in their homes. The raid is marked as finished first.
 */
public final class WorldTools
{
    private static final int RADIUS = 96;
    private static final int GOLEMS = 10;

    private WorldTools() {}

    /** @return a short report */
    public static String calmColonies(final ServerLevel level)
    {
        // day/night and weather run normally (an earlier test build had locked them)
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(true, level.getServer());

        int raids = 0;
        int monsters = 0;
        int golemsAdded = 0;
        for (final IColony colony : IColonyManager.getInstance().getColonies(level))
        {
            ColoniesAlive.LOGGER.info("[world] colony {} raided: {}", colony.getName(), colony.getRaiderManager().isRaided());
            // 1. finish the raids, so nothing respawns
            for (final IColonyEvent event : colony.getEventManager().getEvents().values())
            {
                if (event instanceof IColonyRaidEvent && event.getStatus() != EventStatus.DONE)
                {
                    event.setStatus(EventStatus.DONE);
                    raids++;
                }
            }

            // 2. then the raiders of those raids; ordinary monsters are left alone
            final BlockPos center = colony.getCenter();
            final AABB area = new AABB(center.getX() - RADIUS, level.getMinBuildHeight(), center.getZ() - RADIUS,
              center.getX() + RADIUS, level.getMaxBuildHeight(), center.getZ() + RADIUS);
            for (final AbstractEntityMinecoloniesRaider raider : level.getEntitiesOfClass(AbstractEntityMinecoloniesRaider.class, area, Entity::isAlive))
            {
                raider.kill();
                monsters++;
            }

            // 3. guards: iron golems around the town hall, topped up to ten
            final int present = level.getEntitiesOfClass(IronGolem.class, area).size();
            for (int i = present; i < GOLEMS; i++)
            {
                final double angle = Math.PI * 2 * i / GOLEMS;
                final int x = (int) Math.floor(center.getX() + Math.cos(angle) * 14);
                final int z = (int) Math.floor(center.getZ() + Math.sin(angle) * 14);
                final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                final IronGolem golem = EntityType.IRON_GOLEM.create(level);
                if (golem != null)
                {
                    golem.setPlayerCreated(true);
                    golem.moveTo(x + 0.5, y, z + 0.5, 0, 0);
                    level.addFreshEntity(golem);
                    golemsAdded++;
                }
            }
        }
        final String report = raids + " raid(s) ended, " + monsters + " raider(s) removed, " + golemsAdded + " iron golem(s) added";
        ColoniesAlive.LOGGER.info("[world] {}", report);
        return report;
    }

    /** Stops the path tracking of MineColonies' debug mode for this player and wipes what is drawn on screen. */
    public static void clearPathDebug(final ServerPlayer player)
    {
        PathfindingUtils.trackingMap.remove(player.getUUID());
        PacketDistributor.sendToPlayer(player, new ClearPathDebugPayload());
    }
}
