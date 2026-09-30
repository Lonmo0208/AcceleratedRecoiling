package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.api.ICustomData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes the authoritative section insertion (adds, transfers and reentry) so
 * every entity keeps a per-section insertion ordinal. The native ordered build
 * uses it to reproduce vanilla's within-section candidate order.
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin {

    @Unique
    private long ecoNextSectionOrder;

    @Inject(method = "add", at = @At("RETURN"))
    private void ecoOnAdd(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof Entity real) {
            ((ICustomData) real).setEcoSectionOrder(++this.ecoNextSectionOrder);
        }
    }
}