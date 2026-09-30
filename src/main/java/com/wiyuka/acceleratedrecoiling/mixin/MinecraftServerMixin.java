package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.engine.TickStats;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 只测量、不改语义：整个服务器 tick 的耗时，口径最接近 F3 的 mspt。
 *
 * <p>加上它才能发现「维度 tick 之外的每 tick 开销」——200 个 mod 的网络、存档、
 * 事件回调未必落在 {@code ServerLevel.tick} 里面，只看维度 tick 会低估总数。
 * {@code IntegratedServer} 覆写了该方法并调用 {@code super}，因此单机同样覆盖。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(
            method = "tickServer(Ljava/util/function/BooleanSupplier;)V",
            at = @At("HEAD")
    )
    private void merged$serverTickHead(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        TickStats.serverTickBegin();
    }

    @Inject(
            method = "tickServer(Ljava/util/function/BooleanSupplier;)V",
            at = @At("RETURN")
    )
    private void merged$serverTickTail(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        TickStats.serverTickEnd();
    }
}
