package com.aiko.coloniesalive.social;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code /coloniesalive social}: shows the social life of the citizen you are looking at (or the closest one):
 * mood, how much it likes you, partner, friends, rivals and grudges.
 */
public final class SocialCommand
{
    private SocialCommand() {}

    @SubscribeEvent
    public static void register(final RegisterCommandsEvent event)
    {
        event.getDispatcher().register(Commands.literal("coloniesalive")
          .then(Commands.literal("social").executes(ctx -> show(ctx.getSource())))
          // ends raids properly, removes monsters, ten iron golems, day and clear weather
          .then(Commands.literal("consertar").requires(s -> s.hasPermission(2)).executes(ctx -> {
              final String report = com.aiko.coloniesalive.server.WorldTools.calmColonies(ctx.getSource().getLevel());
              ctx.getSource().sendSuccess(() -> Component.translatable("coloniesalive.calm.done", report), true);
              return 1;
          }))
          // family tests: the woman you look at gets pregnant (optionally already that far along, in %), gives birth now,
          // or the baby you look at becomes a child now
          .then(Commands.literal("gravidez").requires(s -> s.hasPermission(2))
                  .executes(ctx -> pregnancy(ctx.getSource(), 0))
                  .then(Commands.argument("porcentagem", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
                          .executes(ctx -> pregnancy(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "porcentagem")))))
          .then(Commands.literal("nascer").requires(s -> s.hasPermission(2)).executes(ctx -> {
              final EntityCitizen citizen = target(ctx.getSource().getPlayerOrException(), Family::isPregnant);
              final boolean done = citizen != null && Family.birthNow(citizen);
              ctx.getSource().sendSuccess(() -> Component.translatable(done ? "coloniesalive.family.cmd.born" : "coloniesalive.family.cmd.notpregnant"), false);
              return done ? 1 : 0;
          }))
          .then(Commands.literal("valentao").requires(s -> s.hasPermission(2)).executes(ctx -> {
              // test: a visitor or mercenary (one is spawned if there is none around) goes for the child you look at
              final EntityCitizen child = target(ctx.getSource().getPlayerOrException(), EntityCitizen::isBaby);
              final boolean done = child != null && bullyTest(child);
              ctx.getSource().sendSuccess(() -> Component.translatable(done ? "coloniesalive.bully.cmd.ok" : "coloniesalive.bully.cmd.none"), false);
              return done ? 1 : 0;
          }))
          .then(Commands.literal("crescer").requires(s -> s.hasPermission(2)).executes(ctx -> {
              final EntityCitizen citizen = target(ctx.getSource().getPlayerOrException(), Family::isInfant);
              final boolean done = citizen != null && Family.growNow(citizen);
              ctx.getSource().sendSuccess(() -> Component.translatable(done ? "coloniesalive.family.cmd.grown" : "coloniesalive.family.cmd.notbaby"), false);
              return done ? 1 : 0;
          }))
          // every child in the world grows up after that many seconds (default 30)
          .then(Commands.literal("adultos").requires(s -> s.hasPermission(2))
                  .executes(ctx -> growUp(ctx.getSource(), 30))
                  .then(Commands.argument("segundos", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 3600))
                          .executes(ctx -> growUp(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "segundos")))))
          // turns off MineColonies' path tracking display
          .then(Commands.literal("caminhos").executes(ctx -> {
              com.aiko.coloniesalive.server.WorldTools.clearPathDebug(ctx.getSource().getPlayerOrException());
              ctx.getSource().sendSuccess(() -> Component.translatable("coloniesalive.pathdebug.off"), false);
              return 1;
          })));
    }

    private static int growUp(final CommandSourceStack source, final int seconds)
    {
        final int count = com.aiko.coloniesalive.server.GrowUp.scheduleAll(source.getLevel(), seconds);
        source.sendSuccess(() -> Component.translatable("coloniesalive.growup.cmd", count, seconds), true);
        return count;
    }

    /** The closest grown woman in view gets pregnant, {@code percent} of the way along. */
    private static int pregnancy(final CommandSourceStack source, final int percent) throws CommandSyntaxException
    {
        final ServerPlayer player = source.getPlayerOrException();
        // a woman who is already expecting is left alone (asking again used to start her pregnancy over)
        final EntityCitizen citizen = target(player, c -> !c.isBaby() && c.getCitizenData() != null && c.getCitizenData().isFemale() && !Family.isPregnant(c));
        if (citizen == null || !Family.startPregnancy(citizen, percent / 100f))
        {
            source.sendFailure(Component.translatable("coloniesalive.family.cmd.nowoman"));
            return 0;
        }
        final long minutes = Family.GESTATION * (100 - percent) / 100 / 20 / 60;
        final String left = minutes >= 60 ? String.format("%dh %02dmin", minutes / 60, minutes % 60) : minutes + "min";
        source.sendSuccess(() -> Component.translatable("coloniesalive.family.cmd.pregnant", citizen.getCitizenData().getName(), percent, left), false);
        return 1;
    }

    private static int show(final CommandSourceStack source) throws CommandSyntaxException
    {
        final ServerPlayer player = source.getPlayerOrException();
        final EntityCitizen citizen = target(player);
        if (citizen == null || citizen.getCitizenData() == null)
        {
            source.sendFailure(Component.translatable("coloniesalive.social.none"));
            return 0;
        }

        final ICitizenData data = citizen.getCitizenData();
        final float mood = Social.mood(citizen);
        source.sendSystemMessage(Component.literal("— " + data.getName() + " —").withStyle(ChatFormatting.GOLD));
        source.sendSystemMessage(Component.translatable("coloniesalive.social.mood", Component.translatable(moodKey(mood)), String.format("%.1f", mood)));
        source.sendSystemMessage(Component.translatable("coloniesalive.social.affinity", Social.affinity(citizen, player)));
        if (data.getPartner() != null)
        {
            source.sendSystemMessage(Component.translatable("coloniesalive.social.partner", data.getPartner().getName()).withStyle(ChatFormatting.LIGHT_PURPLE));
        }

        final SocialData social = Social.data(player.getServer());
        final String self = Social.key(citizen);
        final IColony colony = data.getColony();
        final List<Map.Entry<String, SocialData.Bond>> bonds = new ArrayList<>();
        for (final Map.Entry<String, SocialData.Bond> entry : social.bonds.entrySet())
        {
            final String[] pair = entry.getKey().split("\\|");
            if (pair.length == 2 && (pair[0].equals(self) || pair[1].equals(self)))
            {
                bonds.add(entry);
            }
        }
        bonds.sort(Comparator.comparingInt(e -> -e.getValue().friendship));

        final MutableComponent friends = Component.translatable("coloniesalive.social.friends");
        final MutableComponent rivals = Component.translatable("coloniesalive.social.rivals");
        boolean anyFriend = false;
        boolean anyRival = false;
        final long now = citizen.level().getGameTime();
        for (final Map.Entry<String, SocialData.Bond> entry : bonds)
        {
            final String[] pair = entry.getKey().split("\\|");
            final String other = pair[0].equals(self) ? pair[1] : pair[0];
            final String name = nameOf(colony, other);
            if (name == null)
            {
                continue;
            }
            final SocialData.Bond bond = entry.getValue();
            String text = " " + name + " (" + bond.friendship + (bond.romance > 0 ? ", ♥" + bond.romance : "") + (bond.grudgeUntil > now ? ", ✗" : "") + ")";
            if (bond.friendship >= 0)
            {
                friends.append(Component.literal(text).withStyle(bond.friendship >= Social.BEST_FRIEND ? ChatFormatting.GREEN : ChatFormatting.WHITE));
                anyFriend = true;
            }
            else
            {
                rivals.append(Component.literal(text).withStyle(ChatFormatting.RED));
                anyRival = true;
            }
        }
        if (anyFriend)
        {
            source.sendSystemMessage(friends);
        }
        if (anyRival)
        {
            source.sendSystemMessage(rivals);
        }
        return 1;
    }

    private static String moodKey(final float mood)
    {
        if (mood >= 5)
        {
            return "coloniesalive.mood.great";
        }
        if (mood >= 1.5)
        {
            return "coloniesalive.mood.good";
        }
        if (mood > -1.5)
        {
            return "coloniesalive.mood.ok";
        }
        if (mood > -5)
        {
            return "coloniesalive.mood.down";
        }
        return "coloniesalive.mood.bad";
    }

    private static String nameOf(final IColony colony, final String key)
    {
        final String[] parts = key.split(":");
        if (colony == null || parts.length != 2 || !parts[0].equals(String.valueOf(colony.getID())))
        {
            return null;
        }
        final ICitizenData other = colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1]));
        return other == null ? null : other.getName();
    }

    /** The citizen in the middle of the screen, else the closest one. */
    private static boolean bullyTest(final EntityCitizen child)
    {
        return bullyFor(child) != null;
    }

    /** Tests: the closest visitor or mercenary (else a new mercenary) goes for {@code child}. @return the bully, or null */
    public static net.minecraft.world.entity.Mob bullyFor(final EntityCitizen child)
    {
        net.minecraft.world.entity.Mob bully = null;
        double best = 48 * 48;
        for (final net.minecraft.world.entity.Mob mob : child.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, child.getBoundingBox().inflate(48),
          com.aiko.coloniesalive.server.Bully::canBully))
        {
            if (mob.isAlive() && !com.aiko.coloniesalive.server.Bully.isBullying(mob) && mob.distanceToSqr(child) < best)
            {
                best = mob.distanceToSqr(child);
                bully = mob;
            }
        }
        if (bully == null && com.minecolonies.api.entity.ModEntities.MERCENARY.create(child.level()) instanceof com.minecolonies.core.entity.mobs.EntityMercenary merc)
        {
            merc.setPos(child.getX() + 6, child.getY(), child.getZ());
            final com.minecolonies.api.colony.IColony colony = child.getCitizenColonyHandler().getColony();
            child.level().addFreshEntity(merc);
            if (colony != null)
            {
                merc.setColony(colony);
            }
            bully = merc;
        }
        return bully != null && com.aiko.coloniesalive.server.Bully.start(bully, child) ? bully : null;
    }

    private static EntityCitizen target(final ServerPlayer player)
    {
        return target(player, c -> true);
    }

    /** Same, among the citizens that pass {@code filter}. */
    public static EntityCitizen target(final ServerPlayer player, final java.util.function.Predicate<EntityCitizen> filter)
    {
        final Vec3 eye = player.getEyePosition();
        final Vec3 look = player.getLookAngle();
        final Vec3 far = eye.add(look.scale(24));
        EntityCitizen best = null;
        double bestScore = Double.MAX_VALUE;
        for (final EntityCitizen citizen : player.level().getEntitiesOfClass(EntityCitizen.class, player.getBoundingBox().inflate(24), filter::test))
        {
            final Vec3 to = citizen.getBoundingBox().getCenter().subtract(eye);
            final double distance = to.length();
            // the one under the crosshair (the line of sight goes through it), else the closest one within 8 blocks
            final boolean aimedAt = citizen.getBoundingBox().inflate(0.35).clip(eye, far).isPresent();
            if (!aimedAt && distance > 8)
            {
                continue;
            }
            final double score = aimedAt ? distance : 100 + distance;
            if (score < bestScore)
            {
                bestScore = score;
                best = citizen;
            }
        }
        return best;
    }
}
