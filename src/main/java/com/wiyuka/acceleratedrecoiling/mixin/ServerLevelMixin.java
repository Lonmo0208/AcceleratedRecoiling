package com.wiyuka.acceleratedrecoiling.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.engine.AbTest;
import com.wiyuka.acceleratedrecoiling.engine.CollidableEntities;
import com.wiyuka.acceleratedrecoiling.engine.EcoEngine;
import com.wiyuka.acceleratedrecoiling.engine.EcoFrame;
import com.wiyuka.acceleratedrecoiling.engine.TickStats;
import com.wiyuka.acceleratedrecoiling.kernel.KernelMode;
import com.wiyuka.acceleratedrecoiling.kernel.KernelPath;
import com.wiyuka.acceleratedrecoiling.natives.CollisionMapData;
import com.wiyuka.acceleratedrecoiling.natives.JavaVanillaBackend;
import com.wiyuka.acceleratedrecoiling.natives.NativeInterface;
import com.wiyuka.acceleratedrecoiling.natives.ParallelAABB;
import com.wiyuka.acceleratedrecoiling.natives.TempID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Shadow
    @Final
    private EntityTickList entityTickList;

    /**
     * Tick HEAD: assigns frame-wide TempID slots (same iteration order that will
     * later feed the native collision frame) and rebuilds the engine's spatial
     * index when the engine path is engaged and the density gate passes.
     */
    @Inject(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At("HEAD")
    )
    private void merged$tickHead(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        TickStats.levelTickBegin((ServerLevel) (Object) this);
        TempID.tickStart();
        if (JavaVanillaBackend.isSelected()) {
            JavaVanillaBackend.tick(this.entityTickList);
        }

        KernelMode kernel = KernelMode.fromName(FoldConfig.kernelMode);
        // GPU 路径：显式 GPU 档，或 AUTO 且本机有 OpenCL——由 AcceleratedRecoiling 的 OpenCL
        // 后端枚举候选对，Java 侧逐对 doPush。专用服务端通常没有 OpenCL，AUTO 会自动落引擎。
        boolean gpuPath = FoldConfig.enableEntityCollision && KernelPath.useGpu(kernel);
        boolean enginePath = FoldConfig.enableEntityCollision
                && EcoEngine.isAvailable()
                && !gpuPath
                && KernelPath.useEngine(kernel);

        List<Entity> frameEntities = (enginePath || gpuPath) ? new ArrayList<>() : null;
        int[] livingCount = new int[1];
        boolean[] foreignPushable = new boolean[1];
        boolean[] collidable = new boolean[1];
        this.entityTickList.forEach(entity -> {
            if (!entity.isRemoved() && !(entity instanceof Player)) {
                livingCount[0]++;
                // 有「可推但不是 LivingEntity」的实体时整帧不接管。
                // 原因：原版的推挤只从 LivingEntity.pushEntities 发起，船/矿车这类
                // 根本没有这个方法——它们只会被别的实体推、且原版每次 push 都是
                // 「归一化方向 × 0.05」有界；而引擎的批量全量推是线性累加冲量，
                // 高密度下会把船甩出几百格的速度（实测查询盒涨到 820 格、collide 92 ms）。
                if (entity.isPushable() && !(entity instanceof LivingEntity)) {
                    foreignPushable[0] = true;
                }
            }
            // 顺带记录：全维度是否存在可被碰撞的实体（Boat/Shulker 那类）。
            // 没有的话 Entity.collide 里的 getEntityCollisions 结果可证明为空，
            // 那一步在密集场景每次要遍历整张区段实体表（实测 19.4 us），必须跳过。
            if (entity.canBeCollidedWith()) {
                collidable[0] = true;
            }
            TempID.addEntity(entity);
            if (frameEntities != null) {
                frameEntities.add(entity);
            }
        });
        CollidableEntities.update((ServerLevel) (Object) this, collidable[0]);

        if (enginePath && !foreignPushable[0] && livingCount[0] >= FoldConfig.densityThreshold) {
            // 第二个参数告诉帧「本帧要不要算推挤」：只有走引擎的档需要，AR 候选枚举那条路不用。
            // 帧会在 tick HEAD 就把推挤交给 worker，让这 4 ms 藏在 tick 自己的时间里跑完。
            EcoFrame.getOrCreate((ServerLevel) (Object) this).begin(frameEntities, true);
        } else if (gpuPath) {
            // 每 tick 一次：把临时实体列表的包围盒交给后端枚举候选对，结果写进 CollisionMapData，
            // 供 pushEntities 读取（索引 i 与 TempID 槽位 i 一一对应）。
            if (NativeInterface.forceBackend(NativeInterface.BackendType.GPU) && frameEntities != null) {
                long gpuStart = System.nanoTime();
                ParallelAABB.handleEntityPush(frameEntities, 0.0D);
                TickStats.gpuPush(System.nanoTime() - gpuStart);
            } else {
                CollisionMapData.clear();
            }
        } else if (FoldConfig.enableEntityCollision) {
            // 稀疏/引擎不可用/含非生物可推实体：清空上一帧碰撞表，防止陈旧配对跨 tick 误用。
            CollisionMapData.clear();
        }
    }

    /** Tick RETURN: closes the per-level native frame. */
    @Inject(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At("RETURN")
    )
    private void merged$tickTail(CallbackInfo ci) {
        EcoFrame frame = EcoFrame.of((ServerLevel) (Object) this);
        if (frame != null) {
            frame.end();
        }
        TickStats.levelTickEnd((ServerLevel) (Object) this);
        // 先把这一 tick 结束时的全场平均速度采下来（对照实验用它区分「动得更多」与「算得更贵」），
        // 再结算本段均值——顺序不能反，否则本 tick 的采样会落到下一段里去。
        AbTest.observeSpeed((ServerLevel) (Object) this);
        // 自动交替对照（/acceleratedrecoiling abtest）在此结算每段均值
        AbTest.onLevelTick((ServerLevel) (Object) this);
        // 配置了 autoAbTest 就自动开跑：不依赖存档里的数据包，任何世界都能复现基准。
        AbTest.autoStart((ServerLevel) (Object) this);
    }

    /**
     * 只测量、不改语义：累计全部实体的 tick 耗时。和维度 tick 总时长一比就知道
     * tick 时间有多少属于实体，剩下的才是方块/区块/其它 mod 的固定开销。
     */
    @WrapMethod(method = "tickNonPassenger")
    private void measureEntityTick(Entity entity, Operation<Void> original) {
        if (!TickStats.isActive()) {
            original.call(entity);
            return;
        }
        long start = System.nanoTime();
        try {
            original.call(entity);
        } finally {
            TickStats.entityTick(System.nanoTime() - start);
        }
    }
}