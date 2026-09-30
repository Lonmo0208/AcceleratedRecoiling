package com.wiyuka.acceleratedrecoiling.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wiyuka.acceleratedrecoiling.api.ICustomData;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.engine.MovementCollision;
import com.wiyuka.acceleratedrecoiling.engine.TickStats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(Entity.class)
public abstract class EntityMixin implements ICustomData {
    private double bbMinX = 0.0;
    private double bbMinY = 0.0;
    private double bbMinZ = 0.0;
    private double bbMaxX = 0.0;
    private double bbMaxY = 0.0;
    private double bbMaxZ = 0.0;
    private float density = 0.0f;

    @Unique
    private int nativeId = -1;

    @Unique
    private int ecoVelVersion = 0;

    @Unique
    private long ecoSectionOrder = 0L;

    @Override
    public int getNativeId() {
        return nativeId;
    }

    @Override
    public void setNativeId(int nativeId) {
        this.nativeId = nativeId;
    }

    @Override
    public int getEcoVelVersion() {
        return ecoVelVersion;
    }

    @Override
    public void setEcoVelVersion(int version) {
        this.ecoVelVersion = version;
    }

    @Override
    public long getEcoSectionOrder() {
        return ecoSectionOrder;
    }

    @Override
    public void setEcoSectionOrder(long order) {
        this.ecoSectionOrder = order;
    }

    @Inject(
            method = "setBoundingBox(Lnet/minecraft/world/phys/AABB;)V",
            at = @At("RETURN")
    )
    private void onSetBoundingBox(AABB bb, CallbackInfo ci) {
        this.bbMinX = bb.minX;
        this.bbMinY = bb.minY;
        this.bbMinZ = bb.minZ;
        this.bbMaxX = bb.maxX;
        this.bbMaxY = bb.maxY;
        this.bbMaxZ = bb.maxZ;
    }

    @Inject(
            method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("RETURN")
    )
    private void onSetDeltaMovement(Vec3 velocity, CallbackInfo ci) {
        this.ecoVelVersion++;
    }

    @Override
    public void setDensity(float density) {
        this.density = density;
    }

    @Override
    public float getDensity() {
        return density;
    }

    @Override
    public final void extractionBoundingBox(double[] doubleArray, int offset, double inflate) {
        doubleArray[offset + 0] = (double) this.bbMinX - inflate;
        doubleArray[offset + 1] = (double) this.bbMinY - inflate;
        doubleArray[offset + 2] = (double) this.bbMinZ - inflate;
        doubleArray[offset + 3] = (double) this.bbMaxX + inflate;
        doubleArray[offset + 4] = (double) this.bbMaxY + inflate;
        doubleArray[offset + 5] = (double) this.bbMaxZ + inflate;
    }

    @Shadow
    private Vec3 position;

    @Shadow
    public abstract boolean isRemoved();

    /**
     * 只测量、不改语义：累计 {@code Entity.move} 的耗时，用来判断实体位移这一块占多少 tick。
     * 非服务端 tick 期间直接放行，客户端线程调用不会被算进来。
     */
    @WrapMethod(method = "move")
    private void measureMove(MoverType type, Vec3 movement, Operation<Void> original) {
        if (!TickStats.isActive()) {
            original.call(type, movement);
            return;
        }
        long start = System.nanoTime();
        try {
            original.call(type, movement);
        } finally {
            TickStats.move(System.nanoTime() - start);
        }
    }

    /**
     * 只测量、不改语义：量原版 {@code Entity.collide} 自己那次 {@code getEntityCollisions}。
     *
     * <p>这条读了才能回答一个必须回答的问题：原版到底有没有真的去查实体？
     * {@code getEntityCollisions} 唯一的提前返回是查询盒 {@code getSize() < 1e-7}，
     * 所以同时记录盒尺寸——若它一直在提前返回，说明这条路径对这批实体本来就是空操作。
     * 移动接管开启时不会走到这里（接管直接换了整个 collide），所以这个数只在接管关闭时有值。
     */
    @WrapOperation(
            method = "collide",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;")
    )
    private List<VoxelShape> ecoMeasureCollideQuery(Level level, Entity entity, AABB box,
                                                    Operation<List<VoxelShape>> original) {
        if (!TickStats.isActive()) {
            return original.call(level, entity, box);
        }
        long start = System.nanoTime();
        List<VoxelShape> result = original.call(level, entity, box);
        TickStats.entityCollisions(System.nanoTime() - start, result.size(), box.getSize());
        return result;
    }

    /**
     * {@code Entity.collide} 的入口。开启移动碰撞接管时走等价实现，接管内部会把
     * 实体碰撞盒那一半换成闭式裁剪；方块那一半始终是原版。实现返回 null（遇到无法判定的
     * 形状）或抛出任何异常时立刻改用原版，行为不会因此改变。
     */
    @WrapMethod(method = "collide")
    private Vec3 ecoCollide(Vec3 movement, Operation<Vec3> original) {
        boolean active = TickStats.isActive();
        if (FoldConfig.enableMovementTakeover) {
            long start = active ? System.nanoTime() : 0L;
            Vec3 taken = null;
            try {
                taken = MovementCollision.collide((Entity) (Object) this, movement);
            } catch (Throwable failure) {
                // 静默降级：本帧这次调用改用原版路径。必须计数——否则「开了模组却没加速」
                // 会完全没有痕迹（这个数会出现在 /check 里，持续增长即原生移动求解坏了）。
                // 首个异常的摘要被保留，/check 直接显示根因，不必再开 debug 猜。
                TickStats.movementFallback(failure);
            }
            if (taken != null) {
                if (active) {
                    TickStats.collide(System.nanoTime() - start);
                }
                return taken;
            }
        }
        if (!active) {
            return original.call(movement);
        }
        long start = System.nanoTime();
        try {
            return original.call(movement);
        } finally {
            TickStats.collide(System.nanoTime() - start);
        }
    }

    @Override
    public final void extractionPosition(double[] doubleArray, int offset) {
        doubleArray[offset + 0] = (double) this.position.x;
        doubleArray[offset + 1] = (double) this.position.y;
        doubleArray[offset + 2] = (double) this.position.z;
    }
}