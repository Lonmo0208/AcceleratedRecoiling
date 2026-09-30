package com.wiyuka.acceleratedrecoiling.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.engine.EcoFrame;
import com.wiyuka.acceleratedrecoiling.engine.TickStats;
import com.wiyuka.acceleratedrecoiling.engine.VanillaPushDetector;
import com.wiyuka.acceleratedrecoiling.kernel.KernelMode;
import com.wiyuka.acceleratedrecoiling.kernel.KernelPath;
import com.wiyuka.acceleratedrecoiling.kernel.ParityQuery;
import com.wiyuka.acceleratedrecoiling.natives.CollisionMapData;
import com.wiyuka.acceleratedrecoiling.natives.TempID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

@Mixin(value = LivingEntity.class, priority = 1100)
public abstract class LivingEntityMixin {

    @Shadow
    protected abstract void doPush(Entity entity);

    @Unique
    private int lastClimbableCheckTick = -1;
    @Unique
    private boolean cachedClimbableResult = false;

    /**
     * Unified pushEntities entrance. The whole server branch is owned here so the
     * engine path never relies on the replaced Level.getEntities invocation arc:
     * VANILLA / AUTO-low-density / players replays vanilla exactly, PARITY replays
     * the vanilla semantics over ParityQuery candidates, and NATIVE / AUTO-dense
     * pushes through the native ECO frame when available.
     */
    @WrapMethod(method = "pushEntities")
    private void merged$pushEntities(Operation<Void> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        Level level = self.level();
        if (level.isClientSide()) {
            original.call();
            return;
        }

        KernelMode kernel = KernelMode.fromName(FoldConfig.kernelMode);
        if (kernel == KernelMode.PARITY) {
            parityServerPush(self, level);
            return;
        }
        // 与 tick HEAD 判定同源：显式 GPU 档，或 AUTO 且本机有 OpenCL。
        // 本机没有 OpenCL 时返回 false 落到引擎/原版，不会出现「候选表为空却一个都不推」。
        if (KernelPath.useGpu(kernel)) {
            gpuServerPush(self, level);
            return;
        }
        boolean engaged = FoldConfig.enableEntityCollision
                && !(self instanceof Player)
                && kernel != KernelMode.VANILLA;
        if (!engaged) {
            vanillaServerPush(self, level);
            return;
        }

        // NATIVE, or AUTO above the density gate: engine frame.
        ServerLevel serverLevel = (ServerLevel) level;
        EcoFrame frame = EcoFrame.of(serverLevel);
        if (frame != null && frame.usable()) {
            engineServerPush(self, serverLevel, frame);
            return;
        }
        // Engine unavailable (or AUTO below the sparse gate): fall back.
        if (kernel == KernelMode.NATIVE) {
            parityServerPush(self, level);
        } else {
            vanillaServerPush(self, level);
        }
    }

    /** 逐字复刻 1.21.1 原版 pushEntities 服务端分支。 */
    @Unique
    private void vanillaServerPush(LivingEntity self, Level level) {
        List<Entity> list = level.getEntities(self, self.getBoundingBox(), EntitySelector.pushableBy(self));
        if (list.isEmpty()) {
            return;
        }
        int max = level.getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
        if (max > 0 && list.size() > max - 1 && self.getRandom().nextInt(4) == 0) {
            int nonPassengers = 0;
            for (Entity entity : list) {
                if (!entity.isPassenger()) {
                    nonPassengers++;
                }
            }
            if (nonPassengers > max - 1) {
                self.hurt(self.damageSources().cramming(), 6.0F);
            }
        }
        for (Entity entity : list) {
            self.doPush(entity);
        }
    }

    /**
     * GPU 对比档：候选来自 {@code CollisionMapData}——每 tick 由 AcceleratedRecoiling 的
     * OpenCL 后端（{@code ParallelAABB.handleEntityPush}）枚举并填入，Java 侧照常逐对 {@code doPush}。
     *
     * <p>与原版的两处差异必须记住：候选集由 GPU 侧 2.5 格的立方哈希网格决定，不保证与原版
     * {@code getEntities} 一致（可能多推或少推）；且候选表是每 tick 一份快照，实体在 tick 内
     * 移动后不会更新。所以这是**对比模式**，不是等价模式。
     */
    @Unique
    private void gpuServerPush(LivingEntity self, Level level) {
        List<Entity> list = CollisionMapData.getCollisionList(self, level);
        if (list.isEmpty()) {
            return;
        }
        int max = level.getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
        if (max > 0 && list.size() > max - 1 && self.getRandom().nextInt(4) == 0) {
            int nonPassengers = 0;
            for (Entity entity : list) {
                if (!entity.isPassenger()) {
                    nonPassengers++;
                }
            }
            if (nonPassengers > max - 1) {
                self.hurt(self.damageSources().cramming(), 6.0F);
            }
        }
        for (Entity entity : list) {
            // 候选表用 TempID 槽位索引；查不到实体时视图会把 source 自己返回，必须跳过
            if (entity != self) {
                self.doPush(entity);
            }
        }
    }

    /** 原版语义 + ParityQuery 候选（原版等价候选枚举）。 */
    @Unique
    private void parityServerPush(LivingEntity self, Level level) {        Predicate<Entity> filter = candidate -> candidate != self && EntitySelector.pushableBy(self).test(candidate);
        List<Entity> list = ParityQuery.pushable(self, self.getBoundingBox(), filter);
        if (list.isEmpty()) {
            return;
        }
        int max = level.getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
        if (max > 0 && list.size() > max - 1 && self.getRandom().nextInt(4) == 0) {
            int nonPassengers = 0;
            for (Entity entity : list) {
                if (!entity.isPassenger()) {
                    nonPassengers++;
                }
            }
            if (nonPassengers > max - 1) {
                self.hurt(self.damageSources().cramming(), 6.0F);
            }
        }
        for (Entity entity : list) {
            self.doPush(entity);
        }
    }

    /** 原生引擎路径：帧内首调用执行一次全量推（C++ 一次遍历所有实体对），其余实体直接短路；cramming 用 neighborCounts。 */
    @Unique
    private void engineServerPush(LivingEntity self, ServerLevel level, EcoFrame frame) {
        PlayerTeam sourceTeam = self.getTeam();
        Team.CollisionRule sourceRule = sourceTeam == null
                ? Team.CollisionRule.ALWAYS : sourceTeam.getCollisionRule();
        if (sourceRule == Team.CollisionRule.NEVER) {
            return;
        }
        try {
            int sourceSlot = TempID.getId(self);
            if (sourceSlot < 0 || sourceSlot >= frame.count()) {
                // 实体不在帧内（tick 中途加入）：退回原版语义，避免本次漏推。
                vanillaServerPush(self, level);
                return;
            }
            if (!frame.fullPushDone()) {
                long engineStart = System.nanoTime();
                boolean pushed;
                try {
                    pushed = frame.fullPushRun();
                } finally {
                    TickStats.engine(System.nanoTime() - engineStart);
                }
                if (!pushed) {
                    // 引擎无法服务（原生失败）：退回原版语义。
                    vanillaServerPush(self, level);
                    return;
                }
            }
            // 同步档：就在实体自己的 pushEntities 上落盘它的冲量，落点与原版「轮到谁推谁」一致。
            // 异步档这里是空操作，由帧在 tick 末尾统一落（见 EcoFrame.publishImpulse）。
            frame.publishImpulse(sourceSlot);
            int max = level.getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
            if (max > 0 && frame.neighborCount(sourceSlot) > max - 1 && self.getRandom().nextInt(4) == 0) {
                self.hurt(self.damageSources().cramming(), 6.0F);
            }
        } catch (Throwable failure) {
            // 防御：原生路径任何异常都不得中断实体循环，退回原版并记录。
            AcceleratedRecoiling.LOGGER.warn("[EcoEngine] engine push failed for {}, falling back to vanilla: {}",
                    self.getType(), failure.toString());
            vanillaServerPush(self, level);
        }
    }

    @WrapOperation(
            method = "pushEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;doPush(Lnet/minecraft/world/entity/Entity;)V"
            )
    )
    private void doPushVerify(LivingEntity instance, Entity entity, Operation<Void> original) {
        if (instance.getBoundingBox().intersects(entity.getBoundingBox())) {
            original.call(instance, entity);
        }
    }

    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    private void injectOnClimbableHead(CallbackInfoReturnable<Boolean> cir) {
        if (((LivingEntity) (Object) this).tickCount == this.lastClimbableCheckTick) {
            cir.setReturnValue(this.cachedClimbableResult);
        }
    }

    @Inject(method = "onClimbable", at = @At("RETURN"))
    private void injectOnClimbableReturn(CallbackInfoReturnable<Boolean> cir) {
        this.lastClimbableCheckTick = ((LivingEntity) (Object) this).tickCount;
        this.cachedClimbableResult = cir.getReturnValueZ();
    }
}