package com.wiyuka.acceleratedrecoiling.engine;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * 方块侧移动求解的原生入口：把整段「逐轴裁剪 + 台阶候选」交给原生 {@code solveMovement}。
 *
 * <p>形状来源仍是原版的 {@code level.getBlockCollisions}——这一层先不动，
 * 原生只负责裁剪与台阶。这样风险被限制在裁剪算式上，而算式已经离线逐位对拍过：
 * 7 个种子共 42 万例、0 处不一致（其中约 5.1 万例走了台阶分支），
 * 见 {@code tools/test/.../NativeMovementParityTest.java}。
 *
 * <p>只在「形状全是单位满方块」时才接管。判据由 {@link VoxelShapeBridge#writeShapes} 给出：
 * 任一形状不是本地坐标系下的 1×1×1 唯一格子满填充（台阶、栅栏、地毯、世界边界……），
 * 就返回 null 让调用方走原版。猪圈的石头地面与围墙全是满方块，因此这类场景能覆盖到。
 *
 * <p>约束：{@link VoxelShapeBridge} 的缓冲是 off-heap 复用的，只能在服务端 tick 内单线程使用。
 */
public final class NativeMovement {

    /** 为某个盒子重新收集形状（原版台阶第二阶段用的是更大的扫描盒）。 */
    public interface StepShapeSource {
        /**
         * @return 该盒子对应的形状列表；返回 null 表示收集失败，调用方回退原版
         */
        List<VoxelShape> shapesFor(AABB box);
    }

    private NativeMovement() {
    }

    /**
     * @param shapes          首次裁剪用的形状（实体形状 + 世界边界 + 扫描盒内的方块）
     * @param stepShapeSource 台阶阶段按原生算出的扫描盒重新收集形状的来源
     * @return 裁剪后的位移；null 表示这次不适用（含多格形状、原生未就绪或调用失败），
     * 调用方必须回退到原版等价实现
     */
    public static Vec3 solve(AABB box, Vec3 request, List<VoxelShape> shapes,
                             float maxUpStep, boolean onGround, StepShapeSource stepShapeSource) {
        if (shapes.isEmpty()) {
            // 空形状表：原版 collideWithShapes 原样返回请求向量，Java 侧已经很快
            return null;
        }
        if (!EcoEngine.isAvailable()) {
            return null;
        }
        int count = VoxelShapeBridge.writeShapes(shapes);
        if (count < 0) {
            return null;
        }
        if (!VoxelShapeBridge.prepare(box, request.x, request.y, request.z, maxUpStep, onGround)) {
            return null;
        }
        try {
            if (EcoEngine.solveMovement(java.lang.foreign.MemorySegment.NULL, VoxelShapeBridge.packet(),
                    VoxelShapeBridge.refs(), count, 0) != 0) {
                return null;
            }
            if (VoxelShapeBridge.needsStep()) {
                // 第二阶段必须换成「按台阶扫描盒重新收集」的形状，否则与原版的 aabb2 列表不等价
                List<VoxelShape> stepShapes = stepShapeSource == null
                        ? null : stepShapeSource.shapesFor(VoxelShapeBridge.stepScanBox());
                if (stepShapes == null) {
                    return null;
                }
                int stepCount = VoxelShapeBridge.writeShapes(stepShapes);
                if (stepCount < 0) {
                    return null;
                }
                if (EcoEngine.solveMovement(java.lang.foreign.MemorySegment.NULL, VoxelShapeBridge.packet(),
                        VoxelShapeBridge.refs(), stepCount, 1) != 0) {
                    return null;
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return new Vec3(VoxelShapeBridge.result(0), VoxelShapeBridge.result(1), VoxelShapeBridge.result(2));
    }

    /** 逐位比较两个结果位移。 */
    public static boolean sameBits(Vec3 a, Vec3 b) {
        return Double.doubleToRawLongBits(a.x) == Double.doubleToRawLongBits(b.x)
                && Double.doubleToRawLongBits(a.y) == Double.doubleToRawLongBits(b.y)
                && Double.doubleToRawLongBits(a.z) == Double.doubleToRawLongBits(b.z);
    }
}
