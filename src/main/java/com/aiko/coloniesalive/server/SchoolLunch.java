package com.aiko.coloniesalive.server;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.FoodUtils;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.constant.CitizenConstants;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingCook;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import static com.minecolonies.core.colony.buildings.modules.BuildingModules.RESTAURANT_MENU;

/**
 * School lunch: pupils don't leave class to queue at the restaurant (see {@code CitizenAIMixin#shouldEat}); a hungry
 * pupil at school gets a snack instead, taken from the restaurant's storage like a normal meal (it follows the
 * restaurant menu, and the food is really used up). Without food in the restaurant it just stays hungry until
 * after school.
 */
public final class SchoolLunch
{
    private static final int INTERVAL = 20 * 10;

    private SchoolLunch() {}

    public static void tick(final EntityCitizen kid)
    {
        if ((kid.tickCount + kid.getId() * 3) % INTERVAL != 0 || !School.inClass(kid))
        {
            return;
        }
        final ICitizenData data = kid.getCitizenData();
        if (data.getSaturation() >= CitizenConstants.AVERAGE_SATURATION || data.getWorkBuilding() == null
              || !data.getWorkBuilding().isInBuilding(kid.blockPosition()) || SchoolEscort.fetcherOf(kid) != null)
        {
            return;
        }

        final BuildingCook restaurant = closestRestaurant(kid);
        if (restaurant == null)
        {
            return;
        }
        final ItemStorage food = FoodUtils.checkForFoodInBuilding(data, restaurant.getModule(RESTAURANT_MENU).getMenu(), restaurant);
        if (food == null)
        {
            return;
        }
        final double value = Math.max(1.0, FoodUtils.getFoodValue(food.getItemStack(), kid));
        final int qty = (int) Math.max(1, Math.ceil((CitizenConstants.FULL_SATURATION - data.getSaturation()) / value));
        if (!InventoryUtils.transferItemStackIntoNextBestSlotInItemHandler(restaurant, food, qty, kid.getInventoryCitizen()))
        {
            return;
        }

        // eat it right away, at the desk
        int eaten = 0;
        for (int slot = 0; slot < kid.getInventoryCitizen().getSlots() && data.getSaturation() < CitizenConstants.FULL_SATURATION; slot++)
        {
            final ItemStack stack = kid.getInventoryCitizen().getStackInSlot(slot);
            while (!stack.isEmpty() && ItemStack.isSameItem(stack, food.getItemStack()) && data.getSaturation() < CitizenConstants.FULL_SATURATION)
            {
                final ItemStack shown = stack.copy();
                ItemStackUtils.consumeFood(stack, kid, null);
                data.getCitizenFoodHandler().addLastEaten(shown.getItem());
                eaten++;
                crumbs(kid, shown);
            }
        }
        if (eaten > 0)
        {
            kid.playSound(SoundEvents.GENERIC_EAT, 0.6f, 1.2f + kid.getRandom().nextFloat() * 0.3f);
            if (ChildLifeAI.DEBUG)
            {
                com.aiko.coloniesalive.ColoniesAlive.LOGGER.info("[school] {} had school lunch: {}x {} (saturation now {})",
                  kid.getName().getString(), eaten, food.getItemStack().getHoverName().getString(), data.getSaturation());
            }
        }
    }

    private static void crumbs(final EntityCitizen kid, final ItemStack food)
    {
        if (kid.level() instanceof ServerLevel level)
        {
            level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, food), kid.getX(), kid.getEyeY() - 0.15, kid.getZ(), 5, 0.1, 0.05, 0.1, 0.03);
        }
    }

    private static BuildingCook closestRestaurant(final EntityCitizen kid)
    {
        BuildingCook best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final IBuilding building : kid.getCitizenData().getColony().getServerBuildingManager().getBuildings().values())
        {
            if (building instanceof BuildingCook cook && cook.getBuildingLevel() > 0)
            {
                final double distance = cook.getPosition().distSqr(kid.blockPosition());
                if (distance < bestDistance)
                {
                    bestDistance = distance;
                    best = cook;
                }
            }
        }
        return best;
    }
}
