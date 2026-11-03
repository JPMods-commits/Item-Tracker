/* PotionTracker health-data access, 2026-10-30. GPL-3.0-or-later. */
package net.jpmods.itemtracker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor
{
    @Accessor("DATA_HEALTH_ID")
    static EntityDataAccessor<Float> getHealthDataAccessor()
    {
        throw new AssertionError("Mixin accessor was not applied");
    }
}
