package com.aiko.coloniesalive.server;

import com.aiko.coloniesalive.ChildAnimation;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.other.SittingEntity;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Items;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The activities a child can pick on its own. Each factory returns null when the activity is not possible right now
 * (no animal around, no water, ...), so the caller can try something else.
 */
public final class Activities
{
    private static final int WALK_TIMEOUT = 20 * 30;

    private Activities() {}

    // ------------------------------------------------------------------------------------------------------------------
    // Solo poses
    // ------------------------------------------------------------------------------------------------------------------

    /** Holds a pose for a while, optionally doing something every tick. */
    private static final class TimedPose implements ChildActivity
    {
        private final ChildAnimation                 animation;
        private final int                            duration;
        private final BiConsumer<ChildLifeAI, Long>  effect;
        private long                                 start = -1;

        TimedPose(final ChildAnimation animation, final int duration, final BiConsumer<ChildLifeAI, Long> effect)
        {
            this.animation = animation;
            this.duration = duration;
            this.effect = effect;
        }

        @Override
        public boolean tick(final ChildLifeAI ai, final long time)
        {
            if (start < 0)
            {
                start = time;
                ai.broadcast(animation, duration);
            }
            final long elapsed = time - start;
            if (effect != null)
            {
                effect.accept(ai, elapsed);
            }
            return elapsed < duration;
        }
    }

    private static int between(final ChildLifeAI ai, final int min, final int max)
    {
        return min + ai.rand().nextInt(max - min + 1);
    }

    /** Small jump if standing on the ground, every {@code interval} ticks. */
    private static void hopEvery(final EntityCitizen child, final long elapsed, final int interval)
    {
        if (child.onGround() && elapsed % interval < 2)
        {
            child.setDeltaMovement(child.getDeltaMovement().x, 0.42, child.getDeltaMovement().z);
            child.hasImpulse = true;
        }
    }

    public static ChildActivity hop(final ChildLifeAI ai)
    {
        return new TimedPose(ChildAnimation.HOP_JOY, between(ai, 80, 160), (a, elapsed) -> {
            hopEvery(a.child(), elapsed, 12);
            if (elapsed == 20)
            {
                Fx.giggle(a.child());
            }
        });
    }

    public static ChildActivity spin(final ChildLifeAI ai)
    {
        return new TimedPose(ChildAnimation.SPIN, between(ai, 60, 120), (a, elapsed) -> {
            final EntityCitizen child = a.child();
            final float yaw = Mth.wrapDegrees(child.getYRot() + 36f);
            child.setYRot(yaw);
            child.yBodyRot = yaw;
            child.setYHeadRot(yaw);
            if (elapsed % 30 == 0)
            {
                Fx.giggle(child);
            }
        });
    }

    /** Crouches and draws in the dirt with a stick: bits of the ground fly up where it scratches. */
    public static ChildActivity crouchLook(final ChildLifeAI ai)
    {
        final EntityCitizen child = ai.child();
        final BlockPos groundPos = child.blockPosition().below();
        final BlockState ground = child.level().getBlockState(groundPos);
        if (ground.isAir() || !ground.getFluidState().isEmpty())
        {
            return null;
        }
        final int duration = between(ai, 100, 180);
        return new ChildActivity()
        {
            long start = -1;

            @Override
            public boolean tick(final ChildLifeAI a, final long time)
            {
                if (start < 0)
                {
                    start = time;
                    child.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
                    a.broadcast(ChildAnimation.CROUCH_LOOK, duration);
                }
                final long elapsed = time - start;
                if (elapsed % 6 == 0 && child.level() instanceof ServerLevel level)
                {
                    final double yaw = Math.toRadians(child.getYRot());
                    final double x = child.getX() - Math.sin(yaw) * 0.55;
                    final double z = child.getZ() + Math.cos(yaw) * 0.55;
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), x, groundPos.getY() + 1.02, z, 3, 0.12, 0.0, 0.12, 0.0);
                }
                if (elapsed == duration / 2)
                {
                    Fx.giggle(child);
                }
                return elapsed < duration;
            }

            @Override
            public void stop(final ChildLifeAI a)
            {
                if (child.getMainHandItem().is(Items.STICK))
                {
                    child.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                }
            }
        };
    }

    /** Moping: head hanging, standing still for a while. */
    public static ChildActivity sad(final ChildLifeAI ai)
    {
        // a real slump (15-30 s), standing still; the really unhappy ones shed a tear now and then
        final boolean miserable = com.aiko.coloniesalive.social.Social.mood(ai.child()) <= -5;
        return new TimedPose(ChildAnimation.SAD, between(ai, 300, 600), (a, elapsed) -> {
            a.child().getNavigation().stop();
            if (miserable && elapsed % 70 == 35)
            {
                Fx.tears(a.child());
            }
        });
    }

    public static ChildActivity soloDance(final ChildLifeAI ai)
    {
        return new TimedPose(ChildAnimation.DANCE, between(ai, 100, 200), (a, elapsed) -> {
            if (elapsed % 12 == 0)
            {
                Fx.note(a.child());
            }
            swayYaw(a.child(), elapsed);
        });
    }

    private static void swayYaw(final EntityCitizen child, final long elapsed)
    {
        if (elapsed % 20 == 0)
        {
            // only the body: the head follows on its own (setting it here made it shake)
            final float yaw = Mth.wrapDegrees(child.getYRot() + ((elapsed / 20) % 2 == 0 ? 25f : -25f));
            child.setYRot(yaw);
            child.yBodyRot = yaw;
        }
    }

    public static ChildActivity daydream(final ChildLifeAI ai)
    {
        final EntityCitizen child = ai.child();
        final int duration = between(ai, 200, 400);
        final BlockPos ground = child.blockPosition().below();
        if (child.level().getBlockState(ground).getCollisionShape(child.level(), ground).isEmpty() || !SittingEntity.sitDown(ground, child, duration))
        {
            return null;
        }
        return new TimedPose(ChildAnimation.DAYDREAM, duration, null);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Moving around the colony
    // ------------------------------------------------------------------------------------------------------------------

    /** Runs off to some building of the colony and cheers on arrival. */
    public static ChildActivity explore(final ChildLifeAI ai)
    {
        final IBuilding building = randomBuilding(ai);
        if (building == null)
        {
            return null;
        }

        return new ChildActivity()
        {
            long start = -1;
            long arrivedAt = -1;

            @Override
            public boolean tick(final ChildLifeAI a, final long time)
            {
                if (start < 0)
                {
                    start = time;
                }
                if (arrivedAt < 0)
                {
                    if (time - start > 20 * 60)
                    {
                        return false;
                    }
                    if (EntityNavigationUtils.walkToBuilding(a.child(), building))
                    {
                        arrivedAt = time;
                        a.broadcast(ChildAnimation.HOP_JOY, 50);
                    }
                    return true;
                }
                hopEvery(a.child(), time - arrivedAt, 14);
                return time - arrivedAt < 50;
            }
        };
    }

    /**
     * Nobody to play with nearby: walk over to the closest other child of the colony. On arrival the next activity is a
     * game together (see {@link ChildLifeAI#wantCompany}).
     */
    public static ChildActivity seekFriend(final ChildLifeAI ai)
    {
        final EntityCitizen friend = ChildLifeEvents.closestOtherChild(ai, 64);
        if (friend == null || friend.distanceToSqr(ai.child()) < 6 * 6)
        {
            return null;
        }
        return new ChildActivity()
        {
            long start = -1;

            @Override
            public boolean tick(final ChildLifeAI a, final long time)
            {
                if (start < 0)
                {
                    start = time;
                }
                if (!friend.isAlive() || friend.level() != a.child().level() || time - start > 20 * 30)
                {
                    return false;
                }
                if (a.child().distanceToSqr(friend) < 5 * 5)
                {
                    a.child().getNavigation().stop();
                    a.wantCompany();
                    return false;
                }
                Moves.follow(a.child(), friend, 1.2);
                return true;
            }
        };
    }

    public static IBuilding randomBuilding(final ChildLifeAI ai)
    {
        final IColony colony = ai.child().getCitizenColonyHandler().getColony();
        if (colony == null)
        {
            return null;
        }
        final List<IBuilding> buildings = new ArrayList<>(colony.getServerBuildingManager().getBuildings().values());
        return buildings.isEmpty() ? null : buildings.get(ai.rand().nextInt(buildings.size()));
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The world around
    // ------------------------------------------------------------------------------------------------------------------

    /** Pets calm animals, chases the others around. */
    public static ChildActivity animals(final ChildLifeAI ai)
    {
        final EntityCitizen child = ai.child();
        final List<Animal> animals = child.level().getEntitiesOfClass(Animal.class, child.getBoundingBox().inflate(14), Animal::isAlive);
        if (animals.isEmpty())
        {
            return null;
        }
        final Animal animal = animals.get(ai.rand().nextInt(animals.size()));
        final boolean pet = animal instanceof TamableAnimal || animal instanceof Sheep || animal instanceof Cow || animal.isBaby();
        return pet ? petAnimal(ai, animal) : chaseAnimal(ai, animal);
    }

    private static ChildActivity petAnimal(final ChildLifeAI ai, final Animal animal)
    {
        final int petTime = between(ai, 80, 140);
        return new ChildActivity()
        {
            long start = -1;
            long petStart = -1;

            @Override
            public boolean tick(final ChildLifeAI a, final long time)
            {
                final EntityCitizen child = a.child();
                if (start < 0)
                {
                    start = time;
                }
                if (!animal.isAlive())
                {
                    return false;
                }
                if (petStart < 0)
                {
                    if (time - start > WALK_TIMEOUT)
                    {
                        return false;
                    }
                    if (child.distanceToSqr(animal) > 1.8 * 1.8)
                    {
                        if (child.distanceToSqr(animal) < 5 * 5)
                        {
                            animal.getNavigation().stop();
                        }
                        Moves.follow(child, animal, 1.0);
                        return true;
                    }
                    petStart = time;
                    child.getNavigation().stop();
                    // the animal holds still while being petted (otherwise its wander goal walks it away next tick)
                    animal.getNavigation().stop();
                    animal.goalSelector.disableControlFlag(Goal.Flag.MOVE);
                    animal.goalSelector.disableControlFlag(Goal.Flag.LOOK);
                    frozen = true;
                    a.broadcast(ChildAnimation.PET_ANIMAL, petTime);
                    Fx.giggle(child);
                }

                // it got pushed or led away: stop petting the air
                if (child.distanceToSqr(animal) > 2.6 * 2.6)
                {
                    return false;
                }
                animal.getNavigation().stop();
                animal.getLookControl().setLookAt(child);
                Fx.face(child, animal);
                if ((time - petStart) % 25 == 0)
                {
                    Fx.hearts(animal, 1);
                }
                return time - petStart < petTime;
            }

            boolean frozen;

            @Override
            public void stop(final ChildLifeAI a)
            {
                if (frozen)
                {
                    animal.goalSelector.enableControlFlag(Goal.Flag.MOVE);
                    animal.goalSelector.enableControlFlag(Goal.Flag.LOOK);
                }
            }
        };
    }

    private static ChildActivity chaseAnimal(final ChildLifeAI ai, final Animal animal)
    {
        final int duration = between(ai, 200, 320);
        return new ChildActivity()
        {
            long start = -1;
            long lastScare;

            @Override
            public boolean tick(final ChildLifeAI a, final long time)
            {
                final EntityCitizen child = a.child();
                if (start < 0)
                {
                    start = time;
                    a.broadcast(ChildAnimation.CHASE_ANIMAL, duration);
                }
                if (!animal.isAlive() || child.distanceToSqr(animal) > 24 * 24)
                {
                    return false;
                }

                if ((time - start) % 12 == 0)
                {
                    Moves.follow(child, animal, 1.3);
                }

                if (child.distanceToSqr(animal) < 2.5 * 2.5 && time - lastScare > 40)
                {
                    lastScare = time;
                    final Vec3 away = DefaultRandomPos.getPosAway(animal, 8, 4, child.position());
                    if (away != null)
                    {
                        animal.getNavigation().moveTo(away.x, away.y, away.z, 1.4);
                    }
                    Fx.giggle(child);
                    Fx.happy(child, 3);
                }
                return time - start < duration;
            }
        };
    }

    /** Picks a flower (without breaking it) and brings it to an adult or a player. */
    public static ChildActivity flowers(final ChildLifeAI ai)
    {
        final EntityCitizen child = ai.child();
        final List<BlockPos> found = new ArrayList<>();
        final BlockPos center = child.blockPosition();
        for (final BlockPos pos : BlockPos.betweenClosed(center.offset(-10, -3, -10), center.offset(10, 3, 10)))
        {
            if (child.level().getBlockState(pos).is(BlockTags.SMALL_FLOWERS))
            {
                found.add(pos.immutable());
                if (found.size() >= 24)
                {
                    break;
                }
            }
        }
        if (found.isEmpty())
        {
            return null;
        }
        final BlockPos flower = found.get(ai.rand().nextInt(found.size()));
        final ItemStack flowerItem = new ItemStack(child.level().getBlockState(flower).getBlock().asItem());

        return new ChildActivity()
        {
            int phase = 0; // 0 walk, 1 pick, 2 bring, 3 give
            long phaseStart = -1;
            LivingEntity recipient;
            boolean holding;

            @Override
            public boolean tick(final ChildLifeAI a, final long time)
            {
                final EntityCitizen child = a.child();
                if (phaseStart < 0)
                {
                    phaseStart = time;
                }
                final long inPhase = time - phaseStart;

                switch (phase)
                {
                    case 0 ->
                    {
                        if (inPhase > WALK_TIMEOUT)
                        {
                            return false;
                        }
                        if (EntityNavigationUtils.walkToPos(child, flower, 1, false, 1.0) || child.blockPosition().distSqr(flower) <= 2)
                        {
                            child.getNavigation().stop();
                            a.broadcast(ChildAnimation.PICK_FLOWER, 60);
                            next(time, 1);
                        }
                    }
                    case 1 ->
                    {
                        Fx.face(child, flower.getX() + 0.5, flower.getZ() + 0.5);
                        if (inPhase >= 60)
                        {
                            child.setItemSlot(EquipmentSlot.MAINHAND, flowerItem.copy());
                            holding = true;
                            Fx.happy(child, 3);
                            recipient = findRecipient(child);
                            if (recipient == null)
                            {
                                a.broadcast(ChildAnimation.HOP_JOY, 50);
                                next(time, 4);
                            }
                            else
                            {
                                a.broadcast(ChildAnimation.NONE, 0);
                                next(time, 2);
                            }
                        }
                    }
                    case 2 ->
                    {
                        if (inPhase > WALK_TIMEOUT || !recipient.isAlive())
                        {
                            return false;
                        }
                        if (child.distanceToSqr(recipient) > 2.0 * 2.0)
                        {
                            if (inPhase % 10 == 0)
                            {
                                Moves.follow(child, recipient, 1.1);
                            }
                        }
                        else
                        {
                            child.getNavigation().stop();
                            if (recipient instanceof EntityCitizen citizen)
                            {
                                citizen.getNavigation().stop();
                            }
                            a.broadcast(ChildAnimation.GIVE_FLOWER, 70);
                            Fx.giggle(child);
                            next(time, 3);
                        }
                    }
                    case 3 ->
                    {
                        Fx.face(child, recipient);
                        if (!(recipient instanceof Player))
                        {
                            Fx.face(recipient, child);
                        }
                        if (inPhase % 20 == 0)
                        {
                            Fx.hearts(recipient, 2);
                        }
                        if (inPhase >= 70)
                        {
                            return false;
                        }
                    }
                    default ->
                    {
                        hopEvery(child, inPhase, 14);
                        if (inPhase >= 50)
                        {
                            return false;
                        }
                    }
                }
                return true;
            }

            private void next(final long time, final int nextPhase)
            {
                phase = nextPhase;
                phaseStart = time;
            }

            @Override
            public void stop(final ChildLifeAI a)
            {
                if (holding)
                {
                    a.child().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                }
            }
        };
    }

    private static LivingEntity findRecipient(final EntityCitizen child)
    {
        final Player player = child.level().getNearestPlayer(child, 12);
        if (player != null && !player.isSpectator() && child.getRandom().nextBoolean())
        {
            return player;
        }
        final List<EntityCitizen> adults = child.level().getEntitiesOfClass(EntityCitizen.class, child.getBoundingBox().inflate(16),
          c -> c != child && c.isAlive() && !c.isBaby());
        if (!adults.isEmpty())
        {
            return adults.get(child.getRandom().nextInt(adults.size()));
        }
        return player != null && !player.isSpectator() ? player : null;
    }

    /** Splashes around at the edge of a pond. */
    public static ChildActivity water(final ChildLifeAI ai)
    {
        final EntityCitizen child = ai.child();
        final Level level = child.level();
        final BlockPos center = child.blockPosition();
        BlockPos water = null;
        BlockPos stand = null;

        search:
        for (final BlockPos pos : BlockPos.betweenClosed(center.offset(-12, -3, -12), center.offset(12, 2, 12)))
        {
            if (!level.getFluidState(pos).is(FluidTags.WATER) || !level.getBlockState(pos.above()).isAir())
            {
                continue;
            }
            for (final Direction dir : Direction.Plane.HORIZONTAL)
            {
                final BlockPos ground = pos.relative(dir);
                final BlockPos feet = ground.above();
                if (level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)
                      && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                      && level.getFluidState(feet).isEmpty())
                {
                    water = pos.immutable();
                    stand = feet.immutable();
                    break search;
                }
            }
        }
        if (water == null)
        {
            return null;
        }

        final BlockPos waterPos = water;
        final BlockPos standPos = stand;
        final int duration = between(ai, 140, 220);
        return new ChildActivity()
        {
            long start = -1;
            long splashStart = -1;

            @Override
            public boolean tick(final ChildLifeAI a, final long time)
            {
                final EntityCitizen child = a.child();
                if (start < 0)
                {
                    start = time;
                }
                if (splashStart < 0)
                {
                    if (time - start > WALK_TIMEOUT)
                    {
                        return false;
                    }
                    if (EntityNavigationUtils.walkToPos(child, standPos, 1, false, 1.1) || child.blockPosition().distSqr(standPos) <= 2)
                    {
                        child.getNavigation().stop();
                        splashStart = time;
                        a.broadcast(ChildAnimation.SPLASH, duration);
                        Fx.giggle(child);
                    }
                    return true;
                }

                final long elapsed = time - splashStart;
                Fx.face(child, waterPos.getX() + 0.5, waterPos.getZ() + 0.5);
                if (elapsed % 8 == 0)
                {
                    Fx.splash(child, waterPos);
                }
                hopEvery(child, elapsed, 30);
                if (elapsed % 70 == 35)
                {
                    Fx.giggle(child);
                }
                return elapsed < duration;
            }
        };
    }

    /** Dances next to a jukebox that is playing music. */
    public static ChildActivity jukeboxDance(final ChildLifeAI ai)
    {
        final JukeboxBlockEntity jukebox = findPlayingJukebox(ai.child());
        if (jukebox == null)
        {
            return null;
        }
        final BlockPos jukeboxPos = jukebox.getBlockPos();
        final BlockPos spot = jukeboxPos.offset(ai.rand().nextInt(7) - 3, 0, ai.rand().nextInt(7) - 3);
        final int maxDance = between(ai, 400, 800);

        return new ChildActivity()
        {
            long start = -1;
            long danceStart = -1;

            @Override
            public boolean tick(final ChildLifeAI a, final long time)
            {
                final EntityCitizen child = a.child();
                if (start < 0)
                {
                    start = time;
                }
                if (jukebox.isRemoved() || !jukebox.getSongPlayer().isPlaying())
                {
                    return false;
                }
                if (danceStart < 0)
                {
                    if (time - start > WALK_TIMEOUT)
                    {
                        return false;
                    }
                    if (EntityNavigationUtils.walkToPos(child, spot, 2, false, 1.1))
                    {
                        child.getNavigation().stop();
                        danceStart = time;
                        a.play(ChildAnimation.DANCE);
                    }
                    return true;
                }

                final long elapsed = time - danceStart;
                if (elapsed % 10 == 0)
                {
                    Fx.note(child);
                }
                swayYaw(child, elapsed);
                hopEvery(child, elapsed, 40);
                if (elapsed % 160 == 80)
                {
                    Fx.giggle(child);
                }
                return elapsed < maxDance;
            }
        };
    }

    private static JukeboxBlockEntity findPlayingJukebox(final EntityCitizen child)
    {
        final Level level = child.level();
        final ChunkPos center = child.chunkPosition();
        JukeboxBlockEntity best = null;
        double bestDistance = 16 * 16;
        for (int cx = center.x - 1; cx <= center.x + 1; cx++)
        {
            for (int cz = center.z - 1; cz <= center.z + 1; cz++)
            {
                if (!level.hasChunk(cx, cz))
                {
                    continue;
                }
                for (final BlockEntity blockEntity : level.getChunk(cx, cz).getBlockEntities().values())
                {
                    if (blockEntity instanceof JukeboxBlockEntity jukebox && jukebox.getSongPlayer().isPlaying())
                    {
                        final double distance = jukebox.getBlockPos().distToCenterSqr(child.position());
                        if (distance < bestDistance)
                        {
                            bestDistance = distance;
                            best = jukebox;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Waves at a player nearby. */
    public static ChildActivity wave(final ChildLifeAI ai)
    {
        final EntityCitizen child = ai.child();
        final Player player = child.level().getNearestPlayer(child, 10);
        if (player == null || player.isSpectator())
        {
            return null;
        }
        return new TimedPose(ChildAnimation.WAVE, between(ai, 50, 80), (a, elapsed) -> {
            Fx.face(a.child(), player);
            if (elapsed == 4)
            {
                Fx.giggle(a.child());
            }
        });
    }

    /**
     * Goes over to a baby nearby and plays peekaboo with it ({@link ChildAnimation#PEEKABOO}); the baby claps and laughs
     * ({@link ChildLifeAI#cheerBaby}). Both end up a bit happier and closer.
     */
    public static ChildActivity visitBaby(final ChildLifeAI ai)
    {
        return ai.rand().nextInt(2) == 0 ? null : visitBabyNow(ai);
    }

    /** Tests: a baby crawls a few blocks / tries to walk. */
    public static ChildActivity babyCrawlTest(final ChildLifeAI ai)
    {
        return InfantLife.testCrawl(ai);
    }

    public static ChildActivity babyWalkTest(final ChildLifeAI ai)
    {
        return InfantLife.testWalk(ai);
    }

    /** Same, without the coin toss (tests). */
    public static ChildActivity visitBabyNow(final ChildLifeAI ai)
    {
        if (ai.isInfant())
        {
            return null;
        }
        final ChildLifeAI babyAi = ChildLifeEvents.closestBaby(ai, 24);
        if (babyAi == null)
        {
            return null;
        }
        final EntityCitizen kid = ai.child();
        final EntityCitizen baby = babyAi.child();
        final int playTicks = between(ai, 140, 220);
        return new ChildActivity()
        {
            long start = -1;
            long playing = -1;

            @Override
            public boolean tick(final ChildLifeAI ai, final long time)
            {
                if (start < 0)
                {
                    start = time;
                }
                if (!baby.isAlive() || !babyAi.isInfant() || baby.level() != kid.level())
                {
                    return false;
                }
                if (playing < 0)
                {
                    // on the way
                    // stops a little way off, in front of the baby, not on top of it
                    if (kid.distanceToSqr(baby) <= 2.4 * 2.4)
                    {
                        kid.getNavigation().stop();
                        if (!babyAi.cheerBaby(kid, playTicks + 20))
                        {
                            return false;
                        }
                        playing = time;
                        ai.broadcast(ChildAnimation.PEEKABOO, playTicks);
                        Fx.giggle(kid);
                        return true;
                    }
                    Moves.follow(kid, baby, 1.0);
                    return time - start < 20 * 15;
                }
                kid.getNavigation().stop();
                Fx.face(kid, baby);
                final long t = time - playing;
                // "boo!" every 45 ticks (see the pose): a giggle from the big one too, now and then
                if (t % 45 == 28 && ai.rand().nextInt(2) == 0)
                {
                    Fx.giggle(kid);
                }
                if (t >= playTicks)
                {
                    com.aiko.coloniesalive.social.Social.addFriendship(kid, baby, 4);
                    com.aiko.coloniesalive.social.Social.addMood(kid, 1f);
                    com.aiko.coloniesalive.social.Social.addMood(baby, 1.5f);
                    Fx.hearts(baby, 3);
                    return false;
                }
                return true;
            }

            @Override
            public void stop(final ChildLifeAI ai)
            {
                Moves.reset(kid);
            }
        };
    }

    /** True if the entity is still around and close enough to keep playing with. */
    static boolean near(final Entity a, final Entity b, final double distance)
    {
        return a.isAlive() && b.isAlive() && a.level() == b.level() && a.distanceToSqr(b) < distance * distance;
    }
}
