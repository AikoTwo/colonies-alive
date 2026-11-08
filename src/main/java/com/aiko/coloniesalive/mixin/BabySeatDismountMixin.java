package com.aiko.coloniesalive.mixin;

import com.aiko.coloniesalive.social.Family;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.other.SittingEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Getting up from a seat, MineColonies looks for a "spawn point" around it (two free blocks with a free neighbour), which
 * can be a good few blocks away: fine for a chair, but a baby sitting on the floor seemed to teleport every time it got
 * up to crawl or walk. A baby gets up right where it sat (if it fits there).
 */
@Mixin(value = SittingEntity.class, remap = false)
public abstract class BabySeatDismountMixin
{
    @Shadow
    private BlockPos sittingpos;

    @Inject(method = "getDismountLocationForPassenger", at = @At("HEAD"), cancellable = true)
    private void coloniesalive$babyGetsUpInPlace(final LivingEntity passenger, final CallbackInfoReturnable<Vec3> cir)
    {
        if (!(passenger instanceof EntityCitizen citizen) || !citizen.isBaby() || !Family.isInfant(citizen))
        {
            return;
        }
        final SittingEntity seat = (SittingEntity) (Object) this;
        final BlockPos ground = sittingpos == null || sittingpos.equals(BlockPos.ZERO) ? seat.blockPosition() : sittingpos;
        // top of what it sat on (a slab, a carpet...), like the seat itself was placed
        double top = 1.0;
        final var shapes = seat.level().getBlockState(ground).getCollisionShape(seat.level(), ground).toAabbs();
        for (final AABB box : shapes)
        {
            top = Math.min(top, box.maxY);
        }
        if (shapes.isEmpty())
        {
            top = 0.0;
        }
        final Vec3 spot = new Vec3(seat.getX(), ground.getY() + top + 0.01, seat.getZ());
        final AABB body = passenger.getDimensions(passenger.getPose()).makeBoundingBox(spot);
        if (seat.level().noCollision(passenger, body))
        {
            cir.setReturnValue(spot);
        }
    }
}
