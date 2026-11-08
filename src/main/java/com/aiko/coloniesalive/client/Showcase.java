package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.ChildAnimation;
import com.aiko.coloniesalive.ColoniesAlive;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Development aid, only active with {@code -Dcoloniesalive.showcase=true} (gradle task {@code runShowcase}):
 * joins the world, freezes the nearest child, puts it in every pose and takes a front and a side screenshot
 * of each into {@code run/screenshots/ca_*.png}, then closes the game.
 */
public final class Showcase
{
    public static final boolean ENABLED = Boolean.getBoolean("coloniesalive.showcase") || Integer.getInteger("coloniesalive.observe", 0) > 0;

    /** Observe mode: just join, let the children live for this many seconds, then quit. */
    private static final int OBSERVE_SECONDS = Integer.getInteger("coloniesalive.observe", 0);

    private static final int START_TICK = 120;
    private static final int SHOT_TICKS = 24;

    private record Shot(String name, ChildAnimation animation, boolean side, boolean eyesClosed, boolean walk) {}

    private static final List<Shot> SHOTS = new ArrayList<>();

    static
    {
        SHOTS.add(new Shot("idle", ChildAnimation.NONE, false, false, false));
        SHOTS.add(new Shot("eyes_closed", ChildAnimation.NONE, false, true, false));
        // walking on the spot, several moments of the step cycle
        for (int i = 1; i <= 3; i++)
        {
            SHOTS.add(new Shot("walk_" + i + "_front", ChildAnimation.NONE, false, false, true));
            SHOTS.add(new Shot("walk_" + i + "_34", ChildAnimation.NONE, true, false, true));
        }
        // the hunched walk of a citizen in a bad mood
        for (int i = 1; i <= 2; i++)
        {
            SHOTS.add(new Shot("gloomy_walk_" + i + "_front", ChildAnimation.NONE, false, false, true));
            SHOTS.add(new Shot("gloomy_walk_" + i + "_34", ChildAnimation.NONE, true, false, true));
        }
        SHOTS.add(new Shot("gloomy_idle_34", ChildAnimation.NONE, true, false, false));
        SHOTS.add(new Shot("hurt_front", ChildAnimation.NONE, false, false, false));
        SHOTS.add(new Shot("hurt_34", ChildAnimation.NONE, true, false, false));
        SHOTS.add(new Shot("hurt2_front", ChildAnimation.NONE, false, false, false));
        final java.util.Set<String> only = only();
        for (final ChildAnimation animation : ChildAnimation.values())
        {
            if (animation != ChildAnimation.NONE && (only.isEmpty() || only.contains(animation.name().toLowerCase())))
            {
                SHOTS.add(new Shot(animation.name().toLowerCase() + "_front", animation, false, false, false));
                SHOTS.add(new Shot(animation.name().toLowerCase() + "_34", animation, true, false, false));
            }
        }
    }

    /** Development: {@code -Pposes=peekaboo,baby_clap} shoots only these poses. */
    private static java.util.Set<String> only()
    {
        final String list = System.getProperty("coloniesalive.poses", "");
        return list.isBlank() ? java.util.Set.of() : java.util.Set.of(list.toLowerCase().split(","));
    }

    /** Read by the eyelid layer. */
    public static volatile boolean forceEyesClosed;

    /** Tests: Santa hats outside December. */
    public static volatile boolean forceSanta;

    /** Tests (fashion show): this entity wears only {@link #fashionItem}. */
    public static volatile int fashionSubject = -1;
    public static volatile Accessories.Accessory fashionItem;

    private static int          tick;
    private static volatile int targetId    = -1;
    private static volatile int testChildId = -1;
    private static GameType     originalGameMode;
    private static boolean      allowScreen;
    private static volatile int affinityBefore;

    private Showcase() {}

    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Post event)
    {
        final Minecraft mc = Minecraft.getInstance();
        final IntegratedServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null)
        {
            tick = 0;
            return;
        }

        tick++;
        mc.options.pauseOnLostFocus = false;
        if (mc.screen != null && !allowScreen)
        {
            mc.setScreen(null);
        }

        if (OBSERVE_SECONDS > 0)
        {
            if (tick == 40)
            {
                server.execute(() -> overworld(server).setDayTime(SCHOOL_WATCH ? 0 : 1000));
            }
            if (SCHOOL_WATCH)
            {
                // natural school morning, no scripted tests: where is every pupil?
                if (tick % 300 == 100)
                {
                    server.execute(() -> watchSchool(server));
                }
                if (tick > 200 && tick % 60 == 0)
                {
                    server.execute(() -> followTruant(server));
                }
                if (tick > 200 && tick % 60 == 10)
                {
                    Screenshot.grab(mc.gameDirectory, "ca_test_fetch_" + tick + ".png", mc.getMainRenderTarget(), message -> {});
                }
                if (tick == 120)
                {
                    // one pupil skips school: someone should come and get it
                    server.execute(() -> {
                        final List<EntityCitizen> pupils = new ArrayList<>();
                        overworld(server).getEntities(EntityTypeTest.forClass(EntityCitizen.class),
                          c -> com.aiko.coloniesalive.server.School.isPupil(c) && !c.getCitizenData().getCitizenMournHandler().isMourning(), pupils);
                        if (!pupils.isEmpty())
                        {
                            final var ai = com.aiko.coloniesalive.server.ChildLifeEvents.get(pupils.get(0));
                            if (ai != null)
                            {
                                ai.forceTruantToday();
                                truantId = pupils.get(0).getId();
                                ColoniesAlive.LOGGER.info("[school] test: {} skips school (parents: {})", pupils.get(0).getName().getString(),
                                  pupils.get(0).getCitizenData().getParents());
                            }
                        }
                    });
                }
            }
            else
            {
                scriptedTests(mc, server);
            }
            if (tick >= OBSERVE_SECONDS * 20)
            {
                ColoniesAlive.LOGGER.info("Observe finished");
                mc.stop();
            }
            return;
        }

        if (tick == START_TICK - 60)
        {
            mc.options.hideGui = true;
            server.execute(() -> setup(server, mc.player.getUUID()));
            return;
        }
        if (tick == START_TICK - 30)
        {
            // first camera move ahead of time, so the chunks are there when the shots start
            server.execute(() -> placeCamera(server, mc.player.getUUID(), false));
            return;
        }
        if (tick < START_TICK)
        {
            return;
        }

        final int index = (tick - START_TICK) / SHOT_TICKS;
        final int inShot = (tick - START_TICK) % SHOT_TICKS;

        if (index >= SHOTS.size() || targetId < 0)
        {
            if (inShot == 0 || targetId < 0)
            {
                ColoniesAlive.LOGGER.info("Showcase finished ({} shots, target {})", index, targetId);
                server.execute(() -> release(server));
                mc.stop();
            }
            return;
        }

        // keep the citizen facing south on the client too, its body yaw otherwise drifts while frozen
        final Entity shown = mc.level.getEntity(targetId);
        if (shown instanceof net.minecraft.world.entity.LivingEntity living)
        {
            living.setYRot(0);
            living.yRotO = 0;
            living.yBodyRot = 0;
            living.yBodyRotO = 0;
            living.yHeadRot = 0;
            living.yHeadRotO = 0;
        }

        final Shot shot = SHOTS.get(index);
        if (shot.walk() && shown instanceof net.minecraft.world.entity.LivingEntity walker)
        {
            // feed the step cycle as if walking (the citizen itself stays put)
            walker.walkAnimation.update(0.75f, 0.4f);
        }
        if (inShot == 0)
        {
            forceEyesClosed = shot.eyesClosed();
            ClientGloom.set(targetId, shot.name().startsWith("gloomy"));
            ClientAnimations.start(targetId, shot.animation(), mc.level.getGameTime(), 100000);
            // the band-aids of a badly hurt child
            fashionSubject = shot.name().startsWith("hurt") ? targetId : -1;
            fashionItem = shot.name().startsWith("hurt2") ? Accessories.BAND_AID_FOREHEAD
                            : shot.name().startsWith("hurt") ? Accessories.HEAD_BANDAGE : null;
            server.execute(() -> placeCamera(server, mc.player.getUUID(), shot.side()));
        }
        else if (inShot == SHOT_TICKS - 4)
        {
            Screenshot.grab(mc.gameDirectory, "ca_" + shot.name() + ".png", mc.getMainRenderTarget(), message -> {});
        }
    }

    /**
     * Observe mode extras: picks a child up (through the same code path as the network packet), puts it down,
     * then hurts another child a tiny bit, with screenshots of both.
     */
    private static void scriptedTests(final Minecraft mc, final IntegratedServer server)
    {
        final java.util.UUID playerId = mc.player.getUUID();
        if (BullyTest.ENABLED)
        {
            // only the bully scene: the other scripted tests would move the same citizens around
            BullyTest.tick(mc, server, tick);
            return;
        }
        FamilyTest.tick(mc, server, tick);
        switch (tick)
        {
            case 41 -> server.execute(() -> {
                // an earlier showcase could have left the player in spectator mode
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && player.isSpectator())
                {
                    player.setGameMode(GameType.CREATIVE);
                }
                // stand among the children: their "look at the player" goal is what used to fight with the games
                final List<EntityCitizen> kids = new ArrayList<>();
                overworld(server).getEntities(EntityTypeTest.forClass(EntityCitizen.class), c -> c.isAlive() && c.isBaby(), kids);
                if (player != null && !kids.isEmpty())
                {
                    final double x = kids.stream().mapToDouble(Entity::getX).average().orElse(player.getX());
                    final double z = kids.stream().mapToDouble(Entity::getZ).average().orElse(player.getZ());
                    final EntityCitizen closest = kids.stream().min(Comparator.comparingDouble(k -> k.distanceToSqr(x, k.getY(), z))).get();
                    player.teleportTo(overworld(server), closest.getX() + 1.5, closest.getY(), closest.getZ(), 0f, 10f);
                }
            });
            case 380, 3500 -> ColoniesAlive.LOGGER.info("[jitter] drawn twitches: {} in {} frames; raw: children {} in {} samples, adults {} in {} samples", JitterProbe.drawnReversals, JitterProbe.drawnFrames,
              JitterProbe.totalReversals, JitterProbe.samples, JitterProbe.adultReversals, JitterProbe.adultSamples);
            case 400 -> server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                final EntityCitizen child = nearestChild(server, player);
                if (player == null || child == null)
                {
                    return;
                }
                // a stage up in the sky: the child can't wander off and nothing blocks the view
                testChildId = child.getId();
                originalPos = child.position();
                child.setNoGravity(true);
                child.teleportTo(child.getX(), 200, child.getZ());
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                standInFront(server, player, child);
                com.aiko.coloniesalive.server.GrabCarry.onPayload(player,
                  new com.aiko.coloniesalive.network.GrabPayload(com.aiko.coloniesalive.network.GrabPayload.START, child.getId(), 0));
            });
            case 470 ->
            {
                mc.options.hideGui = true;
                Screenshot.grab(mc.gameDirectory, "ca_test_held.png", mc.getMainRenderTarget(), message -> {});
            }
            case 520 -> server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null)
                {
                    com.aiko.coloniesalive.server.GrabCarry.onPayload(player,
                      new com.aiko.coloniesalive.network.GrabPayload(com.aiko.coloniesalive.network.GrabPayload.DROP, 0, 0));
                }
                // keep it floating on the stage instead of falling
                if (overworld(server).getEntity(testChildId) instanceof EntityCitizen child)
                {
                    child.setNoGravity(true);
                    child.setDeltaMovement(0, 0, 0);
                }
            });
            case 600 -> server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && overworld(server).getEntity(testChildId) instanceof EntityCitizen child)
                {
                    standInFront(server, player, child);
                    child.hurt(overworld(server).damageSources().playerAttack(player), 1.0f);
                }
            });
            case 650 -> Screenshot.grab(mc.gameDirectory, "ca_test_cry.png", mc.getMainRenderTarget(), message -> {});
            case 700 -> server.execute(() -> {
                mc.options.hideGui = false;
                if (overworld(server).getEntity(testChildId) instanceof EntityCitizen child && originalPos != null)
                {
                    child.setNoGravity(false);
                    child.teleportTo(originalPos.x, originalPos.y, originalPos.z);
                }
            });
            // --- social chat: the child brings up a topic, the MineColonies chat window shows it, the player answers
            case 720 -> server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && overworld(server).getEntity(testChildId) instanceof EntityCitizen child)
                {
                    standInFront(server, player, child);
                    com.aiko.coloniesalive.social.Topics.raiseNow(child, com.aiko.coloniesalive.social.Topics.Topic.PLAYED,
                      "Teste", net.minecraft.network.chat.Component.translatable("coloniesalive.game.ciranda"));
                    affinityBefore = com.aiko.coloniesalive.social.Social.affinity(child, player);
                }
            });
            case 800 ->
            {
                allowScreen = true;
                if (mc.level.getEntity(testChildId) instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen child
                      && child.getCitizenDataView() != null)
                {
                    // by reflection: BlockUI (MineColonies' GUI library) is not on our compile classpath
                    try
                    {
                        final Object window = Class.forName("com.minecolonies.core.client.gui.WindowInteraction")
                          .getConstructor(com.minecolonies.api.colony.ICitizenDataView.class).newInstance(child.getCitizenDataView());
                        window.getClass().getMethod("open").invoke(window);
                    }
                    catch (final ReflectiveOperationException e)
                    {
                        ColoniesAlive.LOGGER.warn("Could not open the chat window", e);
                    }
                }
            }
            case 840 -> Screenshot.grab(mc.gameDirectory, "ca_test_chat.png", mc.getMainRenderTarget(), message -> {});
            case 60 -> server.execute(() -> prepareTestWorld(server, playerId));
            // --- school: after noon pupils stay in class; one skips school and gets punished after school
            case 900 -> server.execute(() -> {
                final List<EntityCitizen> pupils = new ArrayList<>();
                overworld(server).getEntities(EntityTypeTest.forClass(EntityCitizen.class), com.aiko.coloniesalive.server.School::isPupil, pupils);
                if (!pupils.isEmpty())
                {
                    final com.aiko.coloniesalive.server.ChildLifeAI ai = com.aiko.coloniesalive.server.ChildLifeEvents.get(pupils.get(0));
                    if (ai != null)
                    {
                        ai.forceTruantToday();
                        ColoniesAlive.LOGGER.info("[school] test: {} will skip school", pupils.get(0).getName().getString());
                    }
                }
                overworld(server).setDayTime(7500);
                ColoniesAlive.LOGGER.info("[school] test: clock set to 13:30");
            });
            case 1151 -> server.execute(() -> logPupils(server, "13:30"));
            case 1161 -> server.execute(() -> overworld(server).setDayTime(9200));
            case 1450 -> server.execute(() -> logPupils(server, "after school"));
            case 1200 ->
            {
                com.aiko.coloniesalive.server.AdultSocialAI.everyoneOffWork = true;
                ColoniesAlive.LOGGER.info("[social] test: all adults have free time now");
            }
            case 1300 -> server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                final List<EntityCitizen> adults = new ArrayList<>();
                overworld(server).getEntities(EntityTypeTest.forClass(EntityCitizen.class), c -> c.isAlive() && !c.isBaby(), adults);
                ColoniesAlive.LOGGER.info("[social] test: {} adults loaded", adults.size());
                final List<EntityCitizen> kids = new ArrayList<>();
                overworld(server).getEntities(EntityTypeTest.forClass(EntityCitizen.class), c -> c.isAlive() && c.isBaby(), kids);
                kids.forEach(k -> ColoniesAlive.LOGGER.info("[school] {} pupil {} inClass {} state {} school AI {} seated {} time {}",
                  k.getName().getString(), com.aiko.coloniesalive.server.School.isPupil(k), com.aiko.coloniesalive.server.School.inClass(k),
                  k.getCitizenAI().getState(), com.aiko.coloniesalive.server.School.pupilState(k), k.getVehicle() != null,
                  k.level().getDayTime() % 24000));
                adults.stream().limit(12).forEach(a -> ColoniesAlive.LOGGER.info("[social] adult {} state {} job {} dist {}",
                  a.getName().getString(), a.getCitizenAI().getState(), a.getCitizenData() == null ? "?" : a.getCitizenData().getJob(),
                  player == null ? -1 : (int) Math.sqrt(a.distanceToSqr(player))));
            });
            case 850 ->
            {
                allowScreen = false;
                mc.setScreen(null);
                server.execute(() -> {
                    // answer the test child's topic with the RUDE option: affinity and mood drop, it replies and pouts or cries
                    final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    // another child than the test one, which is busy getting back at the player (see the cry test)
                    final EntityCitizen citizen = player == null ? null : overworld(server).getEntitiesOfClass(EntityCitizen.class, player.getBoundingBox().inflate(48),
                      c -> c.isBaby() && c.getId() != testChildId && c.getCitizenData() != null).stream().findFirst().orElse(null);
                    if (citizen == null || !(citizen.getCitizenData() instanceof com.aiko.coloniesalive.mixin.CitizenDataAccessor accessor))
                    {
                        return;
                    }
                    com.aiko.coloniesalive.social.Topics.raiseNow(citizen, com.aiko.coloniesalive.social.Topics.Topic.CHATTER);
                    final var open = accessor.coloniesalive$chatOptions().values().stream()
                      .filter(h -> h instanceof com.aiko.coloniesalive.social.SocialInteraction).findFirst();
                    if (open.isPresent())
                    {
                        final var interaction = (com.aiko.coloniesalive.social.SocialInteraction) open.get();
                        final int rude = interaction.indexOf(com.aiko.coloniesalive.social.SocialInteraction.Answer.RUDE);
                        final int before = com.aiko.coloniesalive.social.Social.affinity(citizen, player);
                        final float moodBefore = com.aiko.coloniesalive.social.Social.mood(citizen);
                        ColoniesAlive.LOGGER.info("[social] test answers {}: '{}' with {} options, rude #{} '{}'", citizen.getName().getString(),
                          interaction.getInquiry().getString(), interaction.getPossibleResponses().size(), rude,
                          rude < 0 ? "-" : interaction.getPossibleResponses().get(rude).getString());
                        citizen.getCitizenData().onResponseTriggered(interaction.getId(), rude, player);
                        ColoniesAlive.LOGGER.info("[social] after the rude answer: affinity {} -> {}, mood {} -> {}", before,
                          com.aiko.coloniesalive.social.Social.affinity(citizen, player), moodBefore, com.aiko.coloniesalive.social.Social.mood(citizen));
                    }
                });
            }
            // --- revenge: slap the test child once it is back on the ground; after crying it comes after the player
            case 740 -> server.execute(() -> {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && overworld(server).getEntity(testChildId) instanceof EntityCitizen child)
                {
                    // the hit of the crying test (tick 600) left a revenge of its own pending: start clean
                    final com.aiko.coloniesalive.server.ChildLifeAI ai = com.aiko.coloniesalive.server.ChildLifeEvents.get(child);
                    if (ai != null)
                    {
                        ai.forgetRevenge();
                    }
                    final float health = child.getHealth();
                    child.hurt(player.damageSources().playerAttack(player), 0.5f);
                    child.setHealth(health);
                    ColoniesAlive.LOGGER.info("[revenge] test: player slapped {}", child.getName().getString());
                }
            });
            case 862, 880, 898, 916, 934, 952, 1000 ->
            {
                final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && mc.level.getEntity(testChildId) instanceof net.minecraft.world.entity.LivingEntity child)
                {
                    final var anim = ClientAnimations.get(testChildId, mc.level.getGameTime());
                    ColoniesAlive.LOGGER.info("[revenge] tick {}: distance {} anim {}", tick, String.format("%.1f", child.distanceTo(mc.player)),
                      anim == null ? "none" : anim.animation());
                }
                Screenshot.grab(mc.gameDirectory, "ca_test_revenge_" + tick + ".png", mc.getMainRenderTarget(), message -> {});
            }
            default ->
            {
            }
        }
    }

    /**
     * Test world: normal day/night, weather and monsters; stuck raids ended properly and ten iron golems guarding the
     * colony (topped up to ten, never more).
     */
    private static void prepareTestWorld(final IntegratedServer server, final java.util.UUID playerId)
    {
        // Same as /coloniesalive consertar. Never just discard raiders: MineColonies respawns them and the raid never ends.
        com.aiko.coloniesalive.server.WorldTools.calmColonies(overworld(server));
    }

    /** Development: -Dcoloniesalive.schoolwatch=true makes the observe run only watch the pupils during a school morning. */
    private static final boolean SCHOOL_WATCH = Boolean.getBoolean("coloniesalive.schoolwatch");

    private static volatile int truantId = -1;

    /** Puts the camera (the player) a few blocks from the truant and its fetcher. */
    private static void followTruant(final IntegratedServer server)
    {
        final ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
        if (player != null && truantId >= 0 && overworld(server).getEntity(truantId) instanceof EntityCitizen kid)
        {
            player.setGameMode(GameType.SPECTATOR);
            player.teleportTo(overworld(server), kid.getX() + 3.5, kid.getY() + 1.6, kid.getZ() + 3.5, 135f, 20f);
        }
    }

    private static void watchSchool(final IntegratedServer server)
    {
        if (tick == 100)
        {
            // which MineColonies chats the citizens have open, children and adults apart
            final List<EntityCitizen> all = new ArrayList<>();
            overworld(server).getEntities(EntityTypeTest.forClass(EntityCitizen.class), c -> c.getCitizenData() != null, all);
            final java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
            for (final EntityCitizen c : all)
            {
                if (c.getCitizenData() instanceof com.aiko.coloniesalive.mixin.CitizenDataAccessor accessor)
                {
                    for (final var handler : accessor.coloniesalive$chatOptions().values())
                    {
                        final var contents = handler.getInquiry().getContents();
                        final String key = contents instanceof net.minecraft.network.chat.contents.TranslatableContents t ? t.getKey() : handler.getInquiry().getString();
                        counts.merge((c.isBaby() ? "child " : (c.getCitizenData().getJob() == null ? "adult(no job) " : "adult ")) + key + " :: "
                          + handler.getInquiry().getString(), 1, Integer::sum);
                    }
                }
            }
            counts.forEach((k, n) -> ColoniesAlive.LOGGER.info("[chats] {}x {}", n, k));
        }
        final List<EntityCitizen> kids = new ArrayList<>();
        overworld(server).getEntities(EntityTypeTest.forClass(EntityCitizen.class), c -> c.isAlive() && c.isBaby(), kids);
        int atSchool = 0;
        int pupils = 0;
        final StringBuilder away = new StringBuilder();
        for (final EntityCitizen kid : kids)
        {
            if (!com.aiko.coloniesalive.server.School.isPupil(kid))
            {
                continue;
            }
            pupils++;
            final var school = kid.getCitizenData().getWorkBuilding();
            final boolean inside = school != null && school.isInBuilding(kid.blockPosition());
            if (inside)
            {
                atSchool++;
            }
            else
            {
                away.append(String.format(" %s[%s/%s %.0fm sat=%.0f]", kid.getName().getString(), kid.getCitizenAI().getState(),
                  com.aiko.coloniesalive.server.School.pupilState(kid), school == null ? -1 : Math.sqrt(school.getPosition().distSqr(kid.blockPosition())),
                  kid.getCitizenData().getSaturation()));
            }
        }
        ColoniesAlive.LOGGER.info("[school] {} ({}): {}/{} pupils in the school building, {} children; away:{}", overworld(server).getDayTime() % 24000,
          kids.size() - pupils, atSchool, pupils, kids.size(), away);
    }

    private static void logPupils(final IntegratedServer server, final String when)
    {
        final List<EntityCitizen> pupils = new ArrayList<>();
        overworld(server).getEntities(EntityTypeTest.forClass(EntityCitizen.class), com.aiko.coloniesalive.server.School::isPupil, pupils);
        final java.util.Map<String, Integer> states = new java.util.TreeMap<>();
        pupils.forEach(p -> states.merge(String.valueOf(p.getCitizenAI().getState()), 1, Integer::sum));
        ColoniesAlive.LOGGER.info("[school] {} ({}): {} pupils, states {}", when, overworld(server).getDayTime() % 24000, pupils.size(), states);
    }

    private static EntityCitizen nearestChild(final IntegratedServer server, final ServerPlayer player)
    {
        if (player == null)
        {
            return null;
        }
        final List<EntityCitizen> citizens = new ArrayList<>();
        overworld(server).getEntities(EntityTypeTest.forClass(EntityCitizen.class), c -> c.isAlive() && c.isBaby() && !c.isSleeping(), citizens);
        citizens.sort(Comparator.comparingDouble(c -> c.distanceToSqr(player)));
        return citizens.isEmpty() ? null : citizens.get(0);
    }

    /** Puts the player two blocks in front of the child, looking at it. */
    private static void standInFront(final IntegratedServer server, final ServerPlayer player, final EntityCitizen child)
    {
        final double yaw = Math.toRadians(child.getYRot());
        final double x = child.getX() - Math.sin(yaw) * 2.0;
        final double z = child.getZ() + Math.cos(yaw) * 2.0;
        final float lookYaw = (float) Math.toDegrees(Math.atan2(child.getZ() - z, child.getX() - x)) - 90f;
        player.teleportTo(overworld(server), x, child.getY(), z, lookYaw, 15f);
    }

    private static ServerLevel overworld(final IntegratedServer server)
    {
        return server.overworld();
    }

    private static void setup(final IntegratedServer server, final java.util.UUID playerId)
    {
        final ServerLevel level = overworld(server);
        final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null)
        {
            return;
        }
        com.aiko.coloniesalive.server.ChildLifeAI.frozen = true;
        level.setDayTime(6000);
        level.setWeatherParameters(6000, 0, false, false);
        originalGameMode = player.gameMode.getGameModeForPlayer();
        player.setGameMode(GameType.SPECTATOR);

        final List<EntityCitizen> citizens = new ArrayList<>();
        level.getEntities(EntityTypeTest.forClass(EntityCitizen.class), c -> c.isAlive() && !c.isSleeping() && c.getVehicle() == null, citizens);
        citizens.sort(Comparator.<EntityCitizen>comparingInt(c -> c.isBaby() ? 0 : 1).thenComparingDouble(c -> c.distanceToSqr(player)));
        if (citizens.isEmpty())
        {
            ColoniesAlive.LOGGER.warn("Showcase: no citizen loaded");
            targetId = -1;
            return;
        }
        final EntityCitizen target = citizens.get(0);
        target.setNoAi(true);
        target.getNavigation().stop();
        // up in the sky: nothing in the way of the camera and a plain background
        originalPos = target.position();
        target.setNoGravity(true);
        target.teleportTo(target.getX(), Math.max(target.getY() + 40, 200), target.getZ());
        targetId = target.getId();
        ColoniesAlive.LOGGER.info("Showcase target {} baby={} at {}", target.getName().getString(), target.isBaby(), target.blockPosition());
    }

    private static void placeCamera(final IntegratedServer server, final java.util.UUID playerId, final boolean side)
    {
        final ServerLevel level = overworld(server);
        final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        final Entity target = level.getEntity(targetId);
        if (player == null || target == null)
        {
            return;
        }
        target.setYRot(0);
        target.setYHeadRot(0);
        if (target instanceof EntityCitizen citizen)
        {
            citizen.yBodyRot = 0;
            citizen.setXRot(0);
        }
        target.setDeltaMovement(0, 0, 0);
        final double distance = 1.9;
        final double eyeY = target.getY() + target.getBbHeight() * 0.55 - player.getEyeHeight();
        // citizen faces south (+z); camera in front looking north, or three-quarter view from its left
        final double angle = side ? Math.toRadians(45) : 0;
        final double camX = target.getX() + Math.sin(angle) * distance;
        final double camZ = target.getZ() + Math.cos(angle) * distance;
        // Minecraft yaw: 0 looks towards +z, 90 towards -x, 180 towards -z
        player.teleportTo(level, camX, eyeY, camZ, 180f - (float) Math.toDegrees(angle), 4f);
    }

    private static net.minecraft.world.phys.Vec3 originalPos;

    private static void release(final IntegratedServer server)
    {
        final Entity target = overworld(server).getEntity(targetId);
        com.aiko.coloniesalive.server.ChildLifeAI.frozen = false;
        if (originalGameMode != null)
        {
            server.getPlayerList().getPlayers().forEach(p -> p.setGameMode(originalGameMode == GameType.SPECTATOR ? GameType.CREATIVE : originalGameMode));
        }
        if (target instanceof EntityCitizen citizen)
        {
            citizen.setNoAi(false);
            citizen.setNoGravity(false);
            if (originalPos != null)
            {
                citizen.teleportTo(originalPos.x, originalPos.y, originalPos.z);
            }
        }
    }
}
