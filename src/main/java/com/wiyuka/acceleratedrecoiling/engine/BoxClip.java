package com.wiyuka.acceleratedrecoiling.engine;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * 实体包围盒的闭式裁剪。
 *
 * <p>原版 {@code Entity.collide} 会把每个可碰撞实体的包围盒包成
 * {@code Shapes.create(boundingBox)} 再逐个裁剪。世界坐标下的实体包围盒至少有一个轴落在
 * [0,1] 之外，{@code Shapes.create} 于是走 {@code ArrayVoxelShape} 分支：一个 1×1×1、
 * 唯一格子满填充的形状，坐标就是包围盒自身。这种形状的裁剪结果可以闭式写出，不必构造形状对象。
 *
 * <p>下面每条分支都对应 {@code VoxelShape#collideX} 在「单满格子」下的展开，包括 1e-7
 * 容差出现的位置。任何一条对不上时返回原位移量，与原版一致。
 */
public final class BoxClip {

    /** 与原版 {@code Shapes.EPSILON} 同一个常量。 */
    private static final double EPS = 1.0E-7;

    private static final Direction.Axis[] AXES = Direction.Axis.values();

    /** 可以走闭式裁剪。 */
    public static final int FAST = 1;
    /** 形状为空：{@code Shapes.create} 会得到空形状，裁剪时原样返回位移量。 */
    public static final int EMPTY = 0;
    /** 无法断定形状结构，调用方应回退原版。 */
    public static final int UNSUPPORTED = -1;

    private BoxClip() {
    }

    /**
     * 判断一个包围盒能否走闭式裁剪。条件刻意取得比实际所需更严：只要不能确定是单满格子，
     * 就交回原版处理，绝不自作主张。
     *
     * @param box    六个 double：minX/minY/minZ/maxX/maxY/maxZ
     * @param offset 该组数据在数组中的起点
     */
    public static int classify(double[] box, int offset) {
        for (int axis = 0; axis < 3; axis++) {
            if (!(box[offset + 3 + axis] - box[offset + axis] >= EPS)) {
                return EMPTY;
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            // Shapes.create 的 findBits 只在区间被 [0,1] 包住时才可能给出有效位宽，
            // 因此任一轴出界就一定落进「原始坐标 + 单格」那条分支。
            if (box[offset + axis] < -EPS || box[offset + 3 + axis] > 1.0000001) {
                return FAST;
            }
        }
        return UNSUPPORTED;
    }

    /** 等价于 {@code Shapes.create(box).collide(axis, movingBox, desiredOffset)}。 */
    public static double clip(Direction.Axis axis, AABB movingBox, double[] box, int offset, double desiredOffset) {
        if (Math.abs(desiredOffset) < EPS) {
            return 0.0;
        }
        int index = axis.ordinal();
        if (desiredOffset > 0.0) {
            double low = box[offset + index];
            double far = movingBox.max(axis);
            // 沿运动轴只有格 0 一个候选，移动盒必须整体位于形状低面之下才会被取到。
            if (!(far < low + EPS)) {
                return desiredOffset;
            }
            if (!overlaps(movingBox, box, offset, (index + 1) % 3)
                    || !overlaps(movingBox, box, offset, (index + 2) % 3)) {
                return desiredOffset;
            }
            double gap = low - far;
            return gap >= -EPS ? Math.min(desiredOffset, gap) : desiredOffset;
        }
        if (desiredOffset < 0.0) {
            double high = box[offset + 3 + index];
            double near = movingBox.min(axis);
            if (!(near + EPS >= high)) {
                return desiredOffset;
            }
            if (!overlaps(movingBox, box, offset, (index + 1) % 3)
                    || !overlaps(movingBox, box, offset, (index + 2) % 3)) {
                return desiredOffset;
            }
            double gap = high - near;
            return gap <= EPS ? Math.max(desiredOffset, gap) : desiredOffset;
        }
        return desiredOffset;
    }

    /**
     * 另外两个轴上若有任一轴不重叠，这次裁剪就不发生。原版用格号区间表达同一件事：
     * 移动盒两侧各收缩 1e-7 后覆盖到的格号区间必须包含格 0。
     */
    private static boolean overlaps(AABB movingBox, double[] box, int offset, int axis) {
        Direction.Axis named = AXES[axis];
        return movingBox.min(named) + EPS < box[offset + 3 + axis]
                && movingBox.max(named) - EPS >= box[offset + axis];
    }
}
