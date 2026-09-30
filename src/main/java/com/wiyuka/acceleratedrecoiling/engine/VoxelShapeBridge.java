package com.wiyuka.acceleratedrecoiling.engine;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Java 与原生移动求解之间的桥：把一组形状写成原生 {@code VoxelRef} 数组，
 * 并按 {@code movement_solver.cpp} 的偏移约定填充数据包。
 *
 * <p>只处理「单个长方体格子」——三个轴各只有两个坐标值即可，跨度不受限制。这样一份形状
 * 就能用一个 {@code VoxelGeometry}（size 1×1×1、单满格、coords {0,span}）+ 世界坐标偏移量表示：
 * <ul>
 *   <li>方块：{@code Shapes.block().move(i,j,k)}，跨度 1，偏移是方块坐标；</li>
 *   <li>实体：{@code Shapes.create(boundingBox)}，跨度是实体尺寸，偏移是包围盒起点
 *       ——这正是 {@code Boat} / {@code Shulker} 这类可碰撞实体在 {@code move} 里走的那条路。</li>
 * </ul>
 * 台阶、栅栏、地毯这类多格形状（某轴多于两个坐标值）一律拒绝，交回原版。
 *
 * <p>几何按「三个跨度」精确比对后缓存：场景里尺寸种类很少（实体尺寸按类型固定、方块恒为 1），
 * 因此缓存必命中，每次调用只写 {@code VoxelRef}。缓冲是 off-heap 复用的（FFM 不收堆段），
 * 只能在服务端 tick 内单线程使用。
 */
public final class VoxelShapeBridge {

    /** 原生 {@code VoxelGeometry} 头部 4 个 int：size[3] + flags。 */
    private static final int HEADER_INT = 4;
    /** 单格几何：size = 1×1×1、coords 每轴 {0,span}、占用位 1 个，共 16 + 48 + 8 字节。 */
    private static final long GEOMETRY_BYTES = 72L;
    private static final int FLAG_SINGLE_CELL = 4;

    /** {@code VoxelRef} = geometryTag(uintptr) + offset 3 doubles。 */
    private static final long REF_BYTES = 32L;
    private static final int REF_COUNT_LIMIT = 512;

    /** 数据包偏移，逐条对应 movement_solver.cpp 顶部常量。 */
    public static final int BOX = 0;
    public static final int REQUEST = 6;
    public static final int POSITION = 9;
    public static final int RESULT = 12;
    public static final int TARGET = 15;
    public static final int STEP_BASE = 18;
    public static final int STEP_SCAN = 24;
    public static final int MAX_STEP = 30;
    public static final int GROUNDED = 31;
    public static final int NEEDS_STEP = 32;
    public static final int ENTITY_QUERY = 33;
    /** 数据包字段个数（double）。 */
    public static final int PACKET_FIELDS = 34;

    /** 缓存的几何：{w,h,d} 精确比对，配一段 off-heap 几何。 */
    private static final List<double[]> SIZE_KEYS = new ArrayList<>();
    private static final List<MemorySegment> SIZE_GEOMETRIES = new ArrayList<>();

    private static Arena arena;
    private static MemorySegment refs;
    private static MemorySegment packet;
    private static MemorySegment boxScratch;
    private static int refCapacity;

    private VoxelShapeBridge() {
    }

    private static boolean ensure(int needed) {
        if (arena != null && arena.scope().isAlive() && refCapacity >= needed) {
            return true;
        }
        try {
            if (arena != null) {
                arena.close();
            }
            int capacity = Math.max(Integer.highestOneBit(Math.max(needed, REF_COUNT_LIMIT) * 4), REF_COUNT_LIMIT);
            arena = Arena.ofShared();
            refs = arena.allocate(REF_BYTES * capacity, 8);
            packet = arena.allocate((long) PACKET_FIELDS * Double.BYTES, 8);
            boxScratch = arena.allocate(6L * Double.BYTES, 8);
            SIZE_KEYS.clear();
            SIZE_GEOMETRIES.clear();
            refCapacity = capacity;
            return true;
        } catch (Throwable t) {
            arena = null;
            refs = null;
            packet = null;
            boxScratch = null;
            refCapacity = 0;
            SIZE_KEYS.clear();
            SIZE_GEOMETRIES.clear();
            return false;
        }
    }

    /** 取（或建）跨度 {w,h,d} 的单格几何地址。 */
    private static long geometryFor(double w, double h, double d) {
        for (int i = 0, n = SIZE_KEYS.size(); i < n; i++) {
            double[] key = SIZE_KEYS.get(i);
            if (key[0] == w && key[1] == h && key[2] == d) {
                return SIZE_GEOMETRIES.get(i).address();
            }
        }
        MemorySegment segment = arena.allocate(GEOMETRY_BYTES, 8);
        segment.set(ValueLayout.JAVA_INT, 0L, 1);
        segment.set(ValueLayout.JAVA_INT, 4L, 1);
        segment.set(ValueLayout.JAVA_INT, 8L, 1);
        segment.set(ValueLayout.JAVA_INT, 12L, FLAG_SINGLE_CELL);
        long coord = HEADER_INT * Integer.BYTES;
        double[] spans = {w, h, d};
        for (int axis = 0; axis < 3; axis++) {
            segment.set(ValueLayout.JAVA_DOUBLE, coord, 0.0D);
            segment.set(ValueLayout.JAVA_DOUBLE, coord + Double.BYTES, spans[axis]);
            coord += 2L * Double.BYTES;
        }
        segment.set(ValueLayout.JAVA_LONG, coord, 1L);
        SIZE_KEYS.add(new double[]{w, h, d});
        SIZE_GEOMETRIES.add(segment);
        return segment.address();
    }

    public static MemorySegment packet() {
        return packet;
    }

    public static MemorySegment refs() {
        return refs;
    }

    /**
     * 把一组形状写成 VoxelRef 数组。只接受单格长方体（每轴恰两个坐标值）。
     *
     * @return 写入的个数；遇到多格形状返回 -1，调用方必须回退原版
     */
    public static int writeShapes(List<VoxelShape> shapes) {
        int count = shapes.size();
        if (count == 0) {
            return 0;
        }
        if (!ensure(count)) {
            return -1;
        }
        for (int i = 0; i < count; i++) {
            VoxelShape shape = shapes.get(i);
            if (shape == null || shape.isEmpty()) {
                return -1;
            }
            double[] cell = singleCell(shape);
            if (cell == null) {
                return -1;
            }
            long base = (long) i * REF_BYTES;
            // 低位标 1 表示带显式平移：坐标按 offset 相加，与 OffsetDoubleList 的语义一致。
            refs.set(ValueLayout.JAVA_LONG, base, geometryFor(cell[3], cell[4], cell[5]) | 1L);
            refs.set(ValueLayout.JAVA_DOUBLE, base + 8L, cell[0]);
            refs.set(ValueLayout.JAVA_DOUBLE, base + 16L, cell[1]);
            refs.set(ValueLayout.JAVA_DOUBLE, base + 24L, cell[2]);
        }
        return count;
    }

    /**
     * 把包围盒与请求位移写进数据包，并算出首次扫描盒（原生 {@code prepareMovement}）。
     *
     * <p>{@code ENTITY_QUERY} 置 0：本次的实体形状已经作为普通形状写进 refs，
     * 由 {@code clipMovement} 统一裁剪，不需要走那条「只查实体」的分支。
     * POSITION/TARGET 不写——它们只服务于把结果回写给 CollisionBody 的用法，这里只取 RESULT。
     *
     * @return false 表示原生准备失败，调用方必须回退原版
     */
    public static boolean prepare(AABB box, double requestX, double requestY, double requestZ,
                                  float maxUpStep, boolean onGround) {
        if (!ensure(0)) {
            return false;
        }
        boxScratch.set(ValueLayout.JAVA_DOUBLE, 0L, box.minX);
        boxScratch.set(ValueLayout.JAVA_DOUBLE, 8L, box.minY);
        boxScratch.set(ValueLayout.JAVA_DOUBLE, 16L, box.minZ);
        boxScratch.set(ValueLayout.JAVA_DOUBLE, 24L, box.maxX);
        boxScratch.set(ValueLayout.JAVA_DOUBLE, 32L, box.maxY);
        boxScratch.set(ValueLayout.JAVA_DOUBLE, 40L, box.maxZ);
        set(REQUEST, requestX);
        set(REQUEST + 1, requestY);
        set(REQUEST + 2, requestZ);
        set(MAX_STEP, maxUpStep);
        set(GROUNDED, onGround ? 1.0D : 0.0D);
        set(ENTITY_QUERY, 0.0D);
        set(NEEDS_STEP, 0.0D);
        return EcoEngine.prepareMovement(boxScratch, packet) == 0;
    }

    private static void set(int field, double value) {
        packet.set(ValueLayout.JAVA_DOUBLE, (long) field * Double.BYTES, value);
    }

    /** 读出原生写回的结果位移。 */
    public static double result(int index) {
        return packet.get(ValueLayout.JAVA_DOUBLE, (long) (RESULT + index) * Double.BYTES);
    }

    /** 原生判定是否需要走台阶候选。 */
    public static boolean needsStep() {
        return packet.get(ValueLayout.JAVA_DOUBLE, (long) NEEDS_STEP * Double.BYTES) != 0.0D;
    }

    /**
     * 读出原生算好的台阶扫描盒（{@code STEP_SCAN}）。
     *
     * <p>第二阶段的形状必须为**这个盒子**重新收集：原版台阶用的是
     * {@code collectColliders(..., aabb2)}，即同一个更大的盒子，而不是首次裁剪那个盒子。
     */
    public static AABB stepScanBox() {
        return new AABB(
                packet.get(ValueLayout.JAVA_DOUBLE, (long) STEP_SCAN * Double.BYTES),
                packet.get(ValueLayout.JAVA_DOUBLE, (long) (STEP_SCAN + 1) * Double.BYTES),
                packet.get(ValueLayout.JAVA_DOUBLE, (long) (STEP_SCAN + 2) * Double.BYTES),
                packet.get(ValueLayout.JAVA_DOUBLE, (long) (STEP_SCAN + 3) * Double.BYTES),
                packet.get(ValueLayout.JAVA_DOUBLE, (long) (STEP_SCAN + 4) * Double.BYTES),
                packet.get(ValueLayout.JAVA_DOUBLE, (long) (STEP_SCAN + 5) * Double.BYTES));
    }

    /**
     * 形状是否是单个长方体格子。
     *
     * @return {minX, minY, minZ, spanX, spanY, spanZ}；不是则返回 null
     */
    private static double[] singleCell(VoxelShape shape) {
        double[] cell = new double[6];
        for (int axis = 0; axis < 3; axis++) {
            var coords = shape.getCoords(AXES[axis]);
            if (coords.size() != 2) {
                return null;
            }
            double low = coords.getDouble(0);
            double span = coords.getDouble(1) - low;
            if (!(span > 0.0D)) {
                return null;
            }
            cell[axis] = low;
            cell[axis + 3] = span;
        }
        return cell;
    }

    /** 缓存下来：{@code Direction.Axis.values()} 每次调用都会克隆数组，热路径上不能反复调。 */
    private static final net.minecraft.core.Direction.Axis[] AXES =
            net.minecraft.core.Direction.Axis.values();
}
