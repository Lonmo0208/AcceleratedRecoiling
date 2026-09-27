package com.wiyuka.acceleratedrecoiling.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityDoPushInvoker {

    @Invoker("doPush")
    void ar$doPush(Entity entity);
}
