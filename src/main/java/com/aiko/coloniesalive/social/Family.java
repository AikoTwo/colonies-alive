package com.aiko.coloniesalive.social;

import com.aiko.coloniesalive.ColoniesAlive;
import com.aiko.coloniesalive.network.FamilyPayload;
import com.aiko.coloniesalive.server.Fx;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.CitizenData;
import com.minecolonies.core.colony.buildings.modules.LivingBuildingModule;
import com.minecolonies.core.colony.eventhooks.citizenEvents.CitizenBornEvent;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

import static com.minecolonies.api.util.constant.StatisticsConstants.BIRTH;

/**
 * Babies the addon's way, instead of MineColonies' children that appear out of nowhere (its random births are switched
 * off, see {@code ReproductionManagerMixin}; the first settlers of a new colony still arrive as always):
 * <ol>
 *     <li><b>Conception</b>: a woman and her partner (MineColonies partner, which our dating creates) get a chance once
 *     a day, or the player encourages them when one of them brings it up in the chat ({@link Topics.Topic#BABY_WISH}).
 *     Only if the colony has room for one more citizen and she has no baby yet.</li>
 *     <li><b>Pregnancy</b>: {@link #GESTATION} (3 Minecraft days). The belly grows with it (client, {@code BellyLayer}),
 *     she walks a bit slower and rests a hand on it.</li>
 *     <li><b>Birth</b>: the baby is created like MineColonies does it (stats from the parents, family name, siblings,
 *     skin), gets a bed at home and appears there.</li>
 *     <li><b>Baby</b>: for {@link #INFANCY} (about 4 hours) it is a much smaller child that sits around and toddles near
 *     home ({@code InfantLife}); MineColonies doesn't let it grow up meanwhile ({@code ChildGrowUpMixin}). Then it is an
 *     ordinary child.</li>
 * </ol>
 * The time left shows on the name tag and in the "social life" tab.
 */
public final class Family
{
    /** 3 Minecraft days. */
    public static final long GESTATION = 24000L * 3;
    /** About 4 hours of play. */
    public static final long INFANCY   = 20L * 60 * 60 * 4;

    /** Chance per day for a couple that can have a baby. */
    private static final float DAILY_CHANCE = 0.25f;
    /** A baby that was born from our own pregnancy is this much smaller than a child. */
    private static final double INFANT_SCALE = 0.6;

    private static final int TICK_INTERVAL = 20;
    /** Clients get the time left again every so often (they count down themselves in between). */
    private static final int SYNC_INTERVAL = 20 * 15;

    private static final ResourceLocation PREGNANT_SLOW = id("pregnant_slow");
    private static final ResourceLocation INFANT_SMALL  = id("infant_scale");
    private static final ResourceLocation INFANT_SLOW   = id("infant_slow");

    private static final Random RAND = new Random();

    /** Last stage sent to the trackers and when. */
    private static final Map<EntityCitizen, long[]> SENT = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    /** Mothers in labour (on their way home), since when. */
    private static final Map<EntityCitizen, Long> LABOR = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    /** Players who started seeing a citizen whose data wasn't loaded yet: they get the family state as soon as it is. */
    private static final Map<EntityCitizen, java.util.Set<ServerPlayer>> PENDING_SYNC = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    private Family() {}

    private static ResourceLocation id(final String path)
    {
        return ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, path);
    }

    private static long now(final MinecraftServer server)
    {
        return server.overworld().getGameTime();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------------------------------------------

    private static SocialData.Person peek(final EntityCitizen citizen)
    {
        if (citizen.getServer() == null || citizen.getCitizenData() == null || citizen.getCitizenData().getColony() == null)
        {
            return null;
        }
        return SocialData.get(citizen.getServer()).people.get(Social.key(citizen));
    }

    public static boolean isPregnant(final EntityCitizen citizen)
    {
        final SocialData.Person person = peek(citizen);
        return person != null && person.pregnantSince != SocialData.NONE;
    }

    /** A baby (smaller than a child, doesn't play or go to school, doesn't grow up yet). */
    public static boolean isInfant(final EntityCitizen citizen)
    {
        if (!citizen.isBaby())
        {
            return false;
        }
        final SocialData.Person person = peek(citizen);
        return person != null && person.infantSince != SocialData.NONE;
    }

    /** Same, from the citizen data (the citizen may not be loaded). */
    public static boolean isInfant(final ICitizenData data)
    {
        if (data == null || !data.isChild() || data.getColony() == null || data.getColony().getWorld() == null || data.getColony().getWorld().getServer() == null)
        {
            return false;
        }
        return isInfant(SocialData.get(data.getColony().getWorld().getServer()), data);
    }

    private static boolean isInfant(final SocialData social, final ICitizenData data)
    {
        final SocialData.Person person = social.people.get(Social.key(data));
        return data.isChild() && person != null && person.infantSince != SocialData.NONE;
    }

    /**
     * What the "social life" tab shows: stage ({@link FamilyPayload}), progress and ticks left.
     */
    public static long[] status(final MinecraftServer server, final ICitizenData data)
    {
        final SocialData.Person person = SocialData.get(server).people.get(Social.key(data));
        final long now = now(server);
        if (person != null && person.pregnantSince != SocialData.NONE)
        {
            return new long[] {FamilyPayload.PREGNANT, now - person.pregnantSince, GESTATION};
        }
        if (person != null && person.infantSince != SocialData.NONE && data.isChild())
        {
            return new long[] {FamilyPayload.INFANT, now - person.infantSince, INFANCY};
        }
        return new long[] {FamilyPayload.NONE, 0, 1};
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Every citizen, every second
    // ------------------------------------------------------------------------------------------------------------------

    public static void tick(final EntityCitizen citizen)
    {
        // players who began seeing this citizen while it was still loading
        if (!PENDING_SYNC.isEmpty() && citizen.getCitizenData() != null && citizen.getCitizenData().getColony() != null)
        {
            final java.util.Set<ServerPlayer> waiting = PENDING_SYNC.remove(citizen);
            if (waiting != null)
            {
                waiting.forEach(player -> syncTo(citizen, player));
                SENT.remove(citizen);
            }
        }
        if ((citizen.tickCount + citizen.getId()) % TICK_INTERVAL != 0 || citizen.getServer() == null)
        {
            return;
        }
        final ICitizenData data = citizen.getCitizenData();
        if (data == null || data.getColony() == null || !citizen.isAlive())
        {
            return;
        }
        final MinecraftServer server = citizen.getServer();
        final SocialData social = SocialData.get(server);
        final String key = Social.key(citizen);
        final long now = now(server);
        SocialData.Person person = social.people.get(key);

        // baby: grows into a child after INFANCY (or MineColonies made it an adult some other way)
        boolean infant = person != null && person.infantSince != SocialData.NONE;
        if (infant && (!citizen.isBaby() || now - person.infantSince >= INFANCY))
        {
            person.infantSince = SocialData.NONE;
            social.setDirty();
            infant = false;
            debug(data.getName() + " is not a baby anymore");
        }
        modifier(citizen, Attributes.SCALE, INFANT_SMALL, INFANT_SCALE - 1.0, infant);
        if (infant && person.openTopic != null)
        {
            Topics.clear(social, key, data);
        }
        modifier(citizen, Attributes.MOVEMENT_SPEED, INFANT_SLOW, -0.45, infant);

        // pregnancy: the baby comes after GESTATION
        boolean pregnant = person != null && person.pregnantSince != SocialData.NONE;
        if (pregnant && citizen.isBaby())
        {
            person.pregnantSince = SocialData.NONE; // can't happen in normal play, but never leave a child pregnant
            pregnant = false;
            social.setDirty();
        }
        if (pregnant && now - person.pregnantSince >= GESTATION && !LABOR.containsKey(citizen))
        {
            // labour: she goes home first (the baby gets a bed there), then the baby comes
            final SocialData.Person mum = person;
            LABOR.put(citizen, now);
            if (!com.aiko.coloniesalive.server.BabyCare.goHomeToGiveBirth(citizen, () -> giveBirth(citizen, social, mum, citizen.level().getGameTime())))
            {
                giveBirth(citizen, social, person, now);
                pregnant = false;
            }
        }
        modifier(citizen, Attributes.MOVEMENT_SPEED, PREGNANT_SLOW, -0.15, pregnant);

        // once a day, a couple may have a baby on its own
        if (!pregnant && !citizen.isBaby() && data.isFemale())
        {
            person = social.person(key);
            if (person.nextConceptionTick == 0)
            {
                person.nextConceptionTick = now + RAND.nextInt(24000);
                social.setDirty();
            }
            else if (now >= person.nextConceptionTick)
            {
                person.nextConceptionTick = now + 24000;
                social.setDirty();
                if (canConceive(social, data) && RAND.nextFloat() < DAILY_CHANCE)
                {
                    conceive(citizen, data.getPartner(), now);
                }
            }
        }

        sync(citizen, social, key, now);
    }

    private static void modifier(final EntityCitizen citizen, final Holder<Attribute> attribute, final ResourceLocation id, final double amount, final boolean on)
    {
        final AttributeInstance instance = citizen.getAttribute(attribute);
        if (instance == null)
        {
            return;
        }
        if (on && !instance.hasModifier(id))
        {
            instance.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        else if (!on && instance.hasModifier(id))
        {
            instance.removeModifier(id);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Conception
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Can she have a baby now? A grown woman with a (male) partner, not pregnant, without a baby yet, not sick, and the
     * colony has room for one more citizen (counting the babies already on the way).
     */
    public static boolean canConceive(final SocialData social, final ICitizenData mother)
    {
        if (mother == null || mother.isChild() || !mother.isFemale() || mother.getColony() == null)
        {
            return false;
        }
        final ICitizenData father = mother.getPartner();
        if (father == null || father.isChild() || father.isFemale())
        {
            return false;
        }
        final SocialData.Person person = social.people.get(Social.key(mother));
        if (person != null && person.pregnantSince != SocialData.NONE)
        {
            return false;
        }
        if (mother.getCitizenDiseaseHandler() != null && mother.getCitizenDiseaseHandler().isSick())
        {
            return false;
        }
        final IColony colony = mother.getColony();
        for (final Integer childId : mother.getChildren())
        {
            final ICitizenData child = colony.getCitizenManager().getCivilian(childId);
            if (child != null && isInfant(social, child))
            {
                return false;
            }
        }
        return hasRoom(social, colony);
    }

    /** One more citizen fits (free bed, below the colony's maximum), babies on the way included. */
    private static boolean hasRoom(final SocialData social, final IColony colony)
    {
        int onTheWay = 0;
        final String prefix = colony.getID() + ":";
        for (final Map.Entry<String, SocialData.Person> entry : social.people.entrySet())
        {
            if (entry.getValue().pregnantSince != SocialData.NONE && entry.getKey().startsWith(prefix))
            {
                onTheWay++;
            }
        }
        return colony.getCitizenManager().getCurrentCitizenCount() + onTheWay < colony.getCitizenManager().getMaxCitizens()
                 && colony.getServerBuildingManager().getHouseWithSpareBed() != null;
    }

    /** The couple this citizen belongs to could have a baby (whichever of the two asks). */
    public static boolean coupleCanHaveBaby(final EntityCitizen citizen)
    {
        final ICitizenData data = citizen.getCitizenData();
        if (data == null || citizen.getServer() == null || data.getPartner() == null)
        {
            return false;
        }
        final ICitizenData mother = data.isFemale() ? data : data.getPartner();
        return canConceive(SocialData.get(citizen.getServer()), mother);
    }

    /** The player cheered the couple on (kind answer to {@link Topics.Topic#BABY_WISH}): the baby is on its way. */
    static void encouraged(final ICitizenData asker)
    {
        if (asker == null || asker.getColony() == null || asker.getColony().getWorld() == null)
        {
            return;
        }
        final MinecraftServer server = asker.getColony().getWorld().getServer();
        if (server == null)
        {
            return;
        }
        final ICitizenData mother = asker.isFemale() ? asker : asker.getPartner();
        if (!canConceive(SocialData.get(server), mother))
        {
            return;
        }
        final ICitizenData father = mother.getPartner();
        if (mother.getEntity().orElse(null) instanceof EntityCitizen entity)
        {
            conceive(entity, father, now(server));
        }
        else
        {
            start(server, mother, father, now(server));
        }
    }

    /** Starts a pregnancy (development and tests too), {@code progress} 0..1 already gone by. */
    public static boolean startPregnancy(final EntityCitizen mother, final float progress)
    {
        final ICitizenData data = mother.getCitizenData();
        if (data == null || mother.getServer() == null || mother.isBaby() || !data.isFemale())
        {
            return false;
        }
        final long now = now(mother.getServer());
        conceive(mother, data.getPartner(), now - (long) (GESTATION * progress));
        return true;
    }

    private static void conceive(final EntityCitizen mother, final ICitizenData father, final long since)
    {
        final ICitizenData data = mother.getCitizenData();
        start(mother.getServer(), data, father, since);
        Fx.hearts(mother, 6);
        Topics.raiseNow(mother, Topics.Topic.PREGNANT, father == null ? "" : father.getName());
        if (father != null && father.getEntity().orElse(null) instanceof EntityCitizen dad)
        {
            Fx.hearts(dad, 6);
            Topics.raiseNow(dad, Topics.Topic.FATHER_TO_BE, data.getName());
        }
        SENT.remove(mother);
    }

    private static void start(final MinecraftServer server, final ICitizenData mother, final ICitizenData father, final long since)
    {
        final SocialData social = SocialData.get(server);
        final SocialData.Person person = social.person(Social.key(mother));
        person.pregnantSince = since;
        person.fatherId = father == null ? -1 : father.getId();
        social.setDirty();
        debug(mother.getName() + " is expecting a baby" + (father == null ? "" : " with " + father.getName()));
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Birth
    // ------------------------------------------------------------------------------------------------------------------

    /** Development/tests: the baby comes right now. */
    public static boolean birthNow(final EntityCitizen mother)
    {
        final SocialData.Person person = peek(mother);
        if (person == null || person.pregnantSince == SocialData.NONE)
        {
            return false;
        }
        giveBirth(mother, SocialData.get(mother.getServer()), person, now(mother.getServer()));
        return true;
    }

    /** Development/tests: this baby is a child from now on. */
    public static boolean growNow(final EntityCitizen baby)
    {
        final SocialData.Person person = peek(baby);
        if (person == null || person.infantSince == SocialData.NONE)
        {
            return false;
        }
        person.infantSince = now(baby.getServer()) - INFANCY;
        SocialData.get(baby.getServer()).setDirty();
        return true;
    }

    /**
     * The baby is born: created the way MineColonies' own ReproductionManager does it, but with the real parents, then
     * given a bed (her home if it has one free, else any house with a free bed) and spawned there.
     */
    private static void giveBirth(final EntityCitizen motherEntity, final SocialData social, final SocialData.Person person, final long now)
    {
        final ICitizenData mother = motherEntity.getCitizenData();
        final IColony colony = mother.getColony();
        final ICitizenData father = person.fatherId >= 0 ? colony.getCitizenManager().getCivilian(person.fatherId) : null;
        person.pregnantSince = SocialData.NONE;
        person.fatherId = -1;
        social.setDirty();
        // tell the clients right away: no more belly, no more "pregnant" on the name tag
        SENT.put(motherEntity, new long[] {FamilyPayload.NONE, now});
        PacketDistributor.sendToPlayersTrackingEntity(motherEntity, new FamilyPayload(motherEntity.getId(), FamilyPayload.NONE, 0, 0));
        LABOR.remove(motherEntity);

        final ICitizenData baby = colony.getCitizenManager().createAndRegisterCivilianData();
        baby.getCitizenSkillHandler().init(colony, mother, father, RAND);
        baby.setIsChild(true);

        final List<String> suffixes = new ArrayList<>();
        baby.addSiblings(mother.getChildren().toArray(new Integer[0]));
        mother.addChildren(baby.getId());
        suffixes.add(mother.getTextureSuffix());
        if (father != null)
        {
            baby.addSiblings(father.getChildren().toArray(new Integer[0]));
            father.addChildren(baby.getId());
            suffixes.add(father.getTextureSuffix());
        }
        baby.setParents(mother.getName(), father == null ? "" : father.getName());
        baby.generateName(RAND, mother.getName(), father == null ? "" : father.getName(), colony.getCitizenNameFile());
        for (final int sibling : baby.getSiblings())
        {
            final ICitizenData siblingData = colony.getCitizenManager().getCivilian(sibling);
            if (siblingData != null)
            {
                siblingData.addSiblings(baby.getId());
            }
        }
        if (suffixes.contains("_w") && suffixes.contains("_d"))
        {
            suffixes.add("_b");
        }
        if (suffixes.isEmpty())
        {
            suffixes.addAll(CitizenData.SUFFIXES);
        }
        baby.setSuffix(suffixes.get(RAND.nextInt(suffixes.size())));

        // a bed: at home with mum if there is one free, else in any house with a free bed
        // (her "home" can be a building without beds of that kind, e.g. a tavern: then any house with a free bed)
        IBuilding home = mother.getHomeBuilding();
        LivingBuildingModule module = livingModule(home);
        if (module == null || module.isFull())
        {
            home = colony.getServerBuildingManager().getHouseWithSpareBed();
            module = livingModule(home);
        }
        if (module != null && !module.isFull())
        {
            module.assignCitizen(baby);
        }

        // a fresh start: MineColonies reuses the ids of citizens that left, so old moods, grudges and bonds must go
        final String babyKey = Social.key(baby);
        social.people.remove(babyKey);
        social.bonds.keySet().removeIf(bond -> bond.startsWith(babyKey + "|") || bond.endsWith("|" + babyKey));
        // a baby from the first moment (the entity reads this on its first tick)
        social.person(babyKey).infantSince = now;
        social.setDirty();

        final BlockPos where = home != null ? home.getPosition() : motherEntity.blockPosition();
        colony.getCitizenManager().spawnOrCreateCitizen(baby, colony.getWorld(), where);
        colony.getEventDescriptionManager().addEventDescription(new CitizenBornEvent(where, baby.getName()));
        colony.getStatisticsManager().increment(BIRTH, colony.getDay());
        debug(mother.getName() + " had a baby: " + baby.getName());

        // the family is over the moon
        Fx.hearts(motherEntity, 10);
        Fx.happy(motherEntity, 8);
        Topics.raiseNow(motherEntity, Topics.Topic.BABY_BORN, baby.getName());
        if (father != null && father.getEntity().orElse(null) instanceof EntityCitizen dad)
        {
            Fx.hearts(dad, 8);
            Topics.raiseNow(dad, Topics.Topic.BABY_BORN, baby.getName());
        }
        for (final int sibling : baby.getSiblings())
        {
            final ICitizenData siblingData = colony.getCitizenManager().getCivilian(sibling);
            if (siblingData != null && siblingData.isChild() && siblingData.getEntity().orElse(null) instanceof EntityCitizen kid && !isInfant(kid))
            {
                Topics.raiseNow(kid, Topics.Topic.BIG_SIBLING, baby.getName());
            }
        }
    }

    /** The house part of a building, or null (asking a building without one throws). */
    private static LivingBuildingModule livingModule(final IBuilding building)
    {
        return building != null && building.hasModule(LivingBuildingModule.class) ? building.getFirstModuleOccurance(LivingBuildingModule.class) : null;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Clients: belly, pose, time left
    // ------------------------------------------------------------------------------------------------------------------

    private static FamilyPayload payload(final EntityCitizen citizen, final SocialData.Person person, final long now)
    {
        if (person != null && person.pregnantSince != SocialData.NONE)
        {
            final long gone = now - person.pregnantSince;
            return new FamilyPayload(citizen.getId(), FamilyPayload.PREGNANT, Math.min(1f, gone / (float) GESTATION), (int) Math.max(0, GESTATION - gone));
        }
        if (person != null && person.infantSince != SocialData.NONE && citizen.isBaby())
        {
            final long gone = now - person.infantSince;
            return new FamilyPayload(citizen.getId(), FamilyPayload.INFANT, Math.min(1f, gone / (float) INFANCY), (int) Math.max(0, INFANCY - gone));
        }
        return new FamilyPayload(citizen.getId(), FamilyPayload.NONE, 0, 0);
    }

    private static void sync(final EntityCitizen citizen, final SocialData social, final String key, final long now)
    {
        final FamilyPayload payload = payload(citizen, social.people.get(key), now);
        final long[] last = SENT.get(citizen);
        final boolean changed = last == null ? payload.stage() != FamilyPayload.NONE : last[0] != payload.stage();
        final boolean due = payload.stage() != FamilyPayload.NONE && last != null && now - last[1] >= SYNC_INTERVAL;
        if (changed || due)
        {
            SENT.put(citizen, new long[] {payload.stage(), now});
            PacketDistributor.sendToPlayersTrackingEntity(citizen, payload);
        }
    }

    /** A player who just started seeing this citizen. */
    public static void syncTo(final EntityCitizen citizen, final ServerPlayer player)
    {
        if (citizen.getServer() == null)
        {
            return;
        }
        if (citizen.getCitizenData() == null || citizen.getCitizenData().getColony() == null)
        {
            // still loading (joining the world): send it as soon as the citizen knows who it is
            PENDING_SYNC.computeIfAbsent(citizen, c -> new java.util.HashSet<>()).add(player);
            return;
        }
        final SocialData.Person person = peek(citizen);
        if (person == null)
        {
            return;
        }
        final FamilyPayload payload = payload(citizen, person, now(citizen.getServer()));
        if (payload.stage() != FamilyPayload.NONE)
        {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private static void debug(final String message)
    {
        ColoniesAlive.LOGGER.info("[family] {}", message);
    }
}
