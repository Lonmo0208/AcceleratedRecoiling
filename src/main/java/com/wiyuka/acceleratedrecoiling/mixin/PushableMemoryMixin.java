package com.wiyuka.acceleratedrecoiling.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wiyuka.acceleratedrecoiling.natives.realtime.BatchDiagnostics;
import com.wiyuka.acceleratedrecoiling.natives.realtime.BatchedRules;
import com.wiyuka.acceleratedrecoiling.natives.realtime.PushableCache;
import com.wiyuka.acceleratedrecoiling.natives.realtime.PushableMemoryEntity;
import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.config.NeoForgeServerConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class PushableMemoryMixin implements PushableMemoryEntity {
    @Unique
    private boolean ar$climbableValue;

    @Unique
    private boolean ar$pushableValid;

    @Unique
    private long ar$pushableEpoch;

    @Override
    public void ar$invalidatePushable() {
        ar$pushableValid = false;
    }

    @WrapOperation(method = "isPushable", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;onClimbable()Z"))
    private boolean ar$memoizedClimbable(LivingEntity entity, Operation<Boolean> original) {
        if (!RealtimeNative.isEnabled() || NeoForgeServerConfig.INSTANCE.fullBoundingBoxLadders.get()
                || !BatchedRules.plain(entity.getClass())) {
            return original.call(entity);
        }

        long epoch = BatchedRules.epoch();
        if (ar$pushableValid && ar$pushableEpoch == epoch) {
            if (BatchDiagnostics.ENABLED) PushableCache.holds++;
            return ar$climbableValue;
        }

        if (BatchDiagnostics.ENABLED) {
            if (ar$pushableValid) {
                PushableCache.epochMisses++;
            } else {
                PushableCache.misses++;
            }
        }

        ar$climbableValue = original.call(entity);
        ar$pushableValid = !ar$climbableValue && BatchedRules.classify(entity) == BatchedRules.PUSHABLE;
        ar$pushableEpoch = epoch;
        return ar$climbableValue;
    }
}
