package com.wiyuka.acceleratedrecoiling.engine;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 方块侧移动求解的离线差分测试。
 *
 * <p>真值：原版 {@code Entity.collide} 的方块那一半——{@code collideWithShapes}
 * + {@code collectCandidateStepUpHeights} + 台阶循环，逐行转写。
 * 被测：原生 {@code prepareMovement} + {@code solveMovement}(phase 0/1)。
 *
 * <p>结构刻意与游戏一致，包括两处容易做错的地方：
 * <ul>
 *   <li><b>台阶阶段用的是为更大扫描盒重新收集的形状</b>（原版 {@code collectColliders(..., aabb2)}），
 *       不是首次裁剪那一份。原生第二阶段的形状由 {@code stepScanBox()} 给出的盒子重新收集；</li>
 *   <li><b>形状不止单位满方块</b>：实体碰撞盒（{@code Shapes.create(boundingBox)}，跨度是实体尺寸）
 *       同样是单格长方体，一并纳入——这正是 {@code Boat} / {@code Shulker} 走的那条路。</li>
 * </ul>
 * 比较用 {@code doubleToRawLongBits}，要求逐位相同。
 *
 * 用法：NativeMovementParityTest <dll路径> [种子] [轮数]
 */
public final class NativeMovementParityTest {

    private static final int REQUEST = 6, RESULT = 12, STEP_SCAN = 24, MAX_STEP = 30,
            GROUNDED = 31, NEEDS_STEP = 32, ENTITY_QUERY = 33, FIELDS = 34;
    private static final int FLAG_SINGLE_CELL = 4;
    private static final long GEOMETRY_BYTES = 72L, REF_BYTES = 32L;

    private static MethodHandle prepareMovement;
    private static MethodHandle solveMovement;

    private static Arena arena;
    private static MemorySegment refs;
    private static MemorySegment packet;
    private static MemorySegment boundsScratch;

    /** 跨度 -> 几何段，精确比对。 */
    private static final List<double[]> SIZE_KEYS = new ArrayList<>();
    private static final List<MemorySegment> SIZE_GEOMETRIES = new ArrayList<>();

    private static int checks;
    private static int failures;
    private static int stepRounds;

    /** 一个「格子」：任意跨度的单格长方体。 */
    private record Cell(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        AABB box() {
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    public static void main(String[] args) throws Throwable {
        if (args.length < 1) {
            System.out.println("usage: NativeMovementParityTest <dll> [seed] [rounds]");
            System.exit(2);
        }
        Path dll = Path.of(args[0]);
        if (!Files.isRegularFile(dll)) {
            System.out.println("dll not found: " + dll);
            System.exit(2);
        }
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20260917L;
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 20000;

        Linker linker = Linker.nativeLinker();
        arena = Arena.ofShared();
        SymbolLookup lookup = SymbolLookup.libraryLookup(dll, arena);
        prepareMovement = linker.downcallHandle(lookup.find("prepareMovement").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        solveMovement = linker.downcallHandle(lookup.find("solveMovement").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        refs = arena.allocate(REF_BYTES * 512, 8);
        packet = arena.allocate((long) FIELDS * Double.BYTES, 8);
        boundsScratch = arena.allocate(6L * Double.BYTES, 8);

        Random random = new Random(seed);
        for (int round = 0; round < rounds; round++) {
            oneRound(random, round);
        }
        System.out.println("checks=" + checks + " failures=" + failures + " stepRounds=" + stepRounds);
        arena.close();
        if (failures != 0 || checks == 0) {
            System.exit(1);
        }
    }

    /** 每轮随机造一个「世界」：一批随机跨度的单格盒子。 */
    private static void oneRound(Random random, int round) {
        int cells = 1 + random.nextInt(24);
        List<Cell> world = new ArrayList<>(cells);
        for (int i = 0; i < cells; i++) {
            double sx = random.nextDouble() * 3.0 - 3.5;
            double sy = random.nextDouble() * 3.0 - 3.5;
            double sz = random.nextDouble() * 3.0 - 3.5;
            // 一半是单位方块（跨度 1），一半是实体尺寸那样的非单位跨度
            double w = random.nextBoolean() ? 1.0 : 0.3 + random.nextDouble() * 1.2;
            double h = random.nextBoolean() ? 1.0 : 0.3 + random.nextDouble() * 1.8;
            double d = random.nextBoolean() ? 1.0 : 0.3 + random.nextDouble() * 1.2;
            world.add(new Cell(sx, sy, sz, sx + w, sy + h, sz + d));
        }

        double x = random.nextDouble() * 6.0 - 3.0;
        double y = random.nextDouble() * 4.0 - 3.0;
        double z = random.nextDouble() * 6.0 - 3.0;
        AABB box = new AABB(x, y, z, x + 0.6, y + 1.8, z + 0.6);

        // 请求位移里刻意混入 ±0.0 分量：原版对「值为 -0.0」的轴会跳过裁剪并原样返回 -0.0，
        // 而把结果初始化为 +0.0 的实现会丢掉符号。这类偏差只在零的符号上，行为无害，
        // 但逐位对拍能抓出来，必须覆盖。
        double[] requestComponents = {
                (random.nextDouble() - 0.5) * 1.2,
                (random.nextDouble() - 0.5) * 1.2,
                (random.nextDouble() - 0.5) * 1.2};
        int zeroAxes = random.nextInt(3);
        for (int i = 0; i < zeroAxes; i++) {
            requestComponents[random.nextInt(3)] = random.nextBoolean() ? 0.0D : -0.0D;
        }
        Vec3 request = new Vec3(requestComponents[0], requestComponents[1], requestComponents[2]);
        float maxUpStep = random.nextBoolean() ? 0.6F : 0.0F;
        boolean onGround = random.nextBoolean();

        Vec3 expected = vanilla(request, box, world, maxUpStep, onGround);
        Vec3 actual = nativeSolve(request, box, world, maxUpStep, onGround);
        checks++;
        if (expected.x != request.x || expected.y != request.y || expected.z != request.z) {
            stepRounds++;
        }
        if (actual == null) {
            failures++;
            if (failures <= 10) {
                System.out.println("native refused: round=" + round + " cells=" + cells);
            }
            return;
        }
        if (Double.doubleToRawLongBits(expected.x) != Double.doubleToRawLongBits(actual.x)
                || Double.doubleToRawLongBits(expected.y) != Double.doubleToRawLongBits(actual.y)
                || Double.doubleToRawLongBits(expected.z) != Double.doubleToRawLongBits(actual.z)) {
            failures++;
            if (failures <= 10) {
                System.out.println("mismatch round=" + round
                        + " expected=" + expected + " actual=" + actual
                        + " box=" + box + " request=" + request
                        + " maxUpStep=" + maxUpStep + " onGround=" + onGround
                        + " cells=" + cells);
            }
        }
    }

    /** 「收集」规则：与查询盒相交的格子。近似原版 {@code BlockCollisions} 的过滤结果。 */
    private static List<VoxelShape> gather(List<Cell> world, AABB query) {
        List<VoxelShape> out = new ArrayList<>();
        for (Cell cell : world) {
            if (cell.box().intersects(query)) {
                out.add(Shapes.create(cell.box()));
            }
        }
        return out;
    }

    private static Vec3 nativeSolve(Vec3 request, AABB box, List<Cell> world,
                                    float maxUpStep, boolean onGround) {
        int count = writeShapes(gather(world, box.expandTowards(request)));
        if (count < 0) {
            return null;
        }
        if (!prepare(box, request, maxUpStep, onGround)) {
            return null;
        }
        try {
            if ((int) solveMovement.invokeExact(MemorySegment.NULL, packet, refs, count, 0) != 0) {
                return null;
            }
            if (get(NEEDS_STEP) != 0.0D) {
                // 第二阶段：按原生算出的扫描盒重新收集形状
                int stepCount = writeShapes(gather(world, stepScanBox()));
                if (stepCount < 0
                        || (int) solveMovement.invokeExact(MemorySegment.NULL, packet, refs, stepCount, 1) != 0) {
                    return null;
                }
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return new Vec3(get(RESULT), get(RESULT + 1), get(RESULT + 2));
    }

    private static boolean prepare(AABB box, Vec3 request, float maxUpStep, boolean onGround) {
        boundsScratch.set(ValueLayout.JAVA_DOUBLE, 0L, box.minX);
        boundsScratch.set(ValueLayout.JAVA_DOUBLE, 8L, box.minY);
        boundsScratch.set(ValueLayout.JAVA_DOUBLE, 16L, box.minZ);
        boundsScratch.set(ValueLayout.JAVA_DOUBLE, 24L, box.maxX);
        boundsScratch.set(ValueLayout.JAVA_DOUBLE, 32L, box.maxY);
        boundsScratch.set(ValueLayout.JAVA_DOUBLE, 40L, box.maxZ);
        set(REQUEST, request.x);
        set(REQUEST + 1, request.y);
        set(REQUEST + 2, request.z);
        set(MAX_STEP, maxUpStep);
        set(GROUNDED, onGround ? 1.0D : 0.0D);
        set(ENTITY_QUERY, 0.0D);
        set(NEEDS_STEP, 0.0D);
        try {
            return (int) prepareMovement.invokeExact(boundsScratch, packet) == 0;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static AABB stepScanBox() {
        return new AABB(get(STEP_SCAN), get(STEP_SCAN + 1), get(STEP_SCAN + 2),
                get(STEP_SCAN + 3), get(STEP_SCAN + 4), get(STEP_SCAN + 5));
    }

    private static void set(int field, double value) {
        packet.set(ValueLayout.JAVA_DOUBLE, (long) field * Double.BYTES, value);
    }

    private static double get(int field) {
        return packet.get(ValueLayout.JAVA_DOUBLE, (long) field * Double.BYTES);
    }

    /** 只接受单格长方体（每轴恰两个坐标值），跨度不受限制。 */
    private static int writeShapes(List<VoxelShape> shapes) {
        for (int i = 0; i < shapes.size(); i++) {
            VoxelShape shape = shapes.get(i);
            if (shape.isEmpty()) {
                return -1;
            }
            double[] cell = new double[6];
            for (int axis = 0; axis < 3; axis++) {
                var coords = shape.getCoords(Direction.Axis.values()[axis]);
                if (coords.size() != 2) {
                    return -1;
                }
                double low = coords.getDouble(0);
                double span = coords.getDouble(1) - low;
                if (!(span > 0.0D)) {
                    return -1;
                }
                cell[axis] = low;
                cell[axis + 3] = span;
            }
            long base = (long) i * REF_BYTES;
            refs.set(ValueLayout.JAVA_LONG, base, geometryFor(cell[3], cell[4], cell[5]) | 1L);
            refs.set(ValueLayout.JAVA_DOUBLE, base + 8L, cell[0]);
            refs.set(ValueLayout.JAVA_DOUBLE, base + 16L, cell[1]);
            refs.set(ValueLayout.JAVA_DOUBLE, base + 24L, cell[2]);
        }
        return shapes.size();
    }

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
        long coord = 16L;
        double[] spans = {w, h, d};
        for (int axis = 0; axis < 3; axis++) {
            segment.set(ValueLayout.JAVA_DOUBLE, coord, 0.0D);
            segment.set(ValueLayout.JAVA_DOUBLE, coord + 8L, spans[axis]);
            coord += 16L;
        }
        segment.set(ValueLayout.JAVA_LONG, coord, 1L);
        SIZE_KEYS.add(new double[]{w, h, d});
        SIZE_GEOMETRIES.add(segment);
        return segment.address();
    }

    // ---------------------------------------------------------------- 原版真值

    private static Vec3 vanilla(Vec3 request, AABB box, List<Cell> world,
                                float maxUpStep, boolean onGround) {
        List<VoxelShape> list = gather(world, box.expandTowards(request));
        Vec3 clipped = collideWithShapes(request, box, list);
        boolean flag = request.x != clipped.x;
        boolean flag2 = request.z != clipped.z;
        boolean flag1 = request.y != clipped.y;
        boolean flag3 = flag1 && request.y < 0.0;
        if (maxUpStep > 0.0F && (flag3 || onGround) && (flag || flag2)) {
            AABB aabb1 = flag3 ? box.move(0.0, clipped.y, 0.0) : box;
            AABB aabb2 = aabb1.expandTowards(request.x, maxUpStep, request.z);
            if (!flag3) {
                aabb2 = aabb2.expandTowards(0.0, -9.999999747378752E-6, 0.0);
            }
            List<VoxelShape> list1 = gather(world, aabb2);
            float[] heights = candidateHeights(aabb1, list1, maxUpStep, (float) clipped.y);
            for (float height : heights) {
                Vec3 lifted = collideWithShapes(new Vec3(request.x, height, request.z), aabb1, list1);
                if (lifted.horizontalDistanceSqr() > clipped.horizontalDistanceSqr()) {
                    double d0 = box.minY - aabb1.minY;
                    return lifted.add(0.0, -d0, 0.0);
                }
            }
        }
        return clipped;
    }

    private static Vec3 collideWithShapes(Vec3 requested, AABB box, List<VoxelShape> shapes) {
        if (shapes.isEmpty()) {
            return requested;
        }
        double d0 = requested.x;
        double d1 = requested.y;
        double d2 = requested.z;
        if (d1 != 0.0) {
            d1 = Shapes.collide(Direction.Axis.Y, box, shapes, d1);
            if (d1 != 0.0) {
                box = box.move(0.0, d1, 0.0);
            }
        }
        boolean flag = Math.abs(d0) < Math.abs(d2);
        if (flag && d2 != 0.0) {
            d2 = Shapes.collide(Direction.Axis.Z, box, shapes, d2);
            if (d2 != 0.0) {
                box = box.move(0.0, 0.0, d2);
            }
        }
        if (d0 != 0.0) {
            d0 = Shapes.collide(Direction.Axis.X, box, shapes, d0);
            if (!flag && d0 != 0.0) {
                box = box.move(d0, 0.0, 0.0);
            }
        }
        if (!flag && d2 != 0.0) {
            d2 = Shapes.collide(Direction.Axis.Z, box, shapes, d2);
        }
        return new Vec3(d0, d1, d2);
    }

    private static float[] candidateHeights(AABB box, List<VoxelShape> shapes,
                                            float maxUpStep, float clippedY) {
        List<Float> raw = new ArrayList<>();
        for (VoxelShape shape : shapes) {
            for (double coordinate : shape.getCoords(Direction.Axis.Y)) {
                float height = (float) (coordinate - box.minY);
                if (!(height < 0.0F) && height != clippedY) {
                    if (height > maxUpStep) {
                        break;
                    }
                    raw.add(height);
                }
            }
        }
        if (raw.isEmpty()) {
            return new float[0];
        }
        float[] sorted = new float[raw.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = raw.get(i);
        }
        Arrays.sort(sorted);
        int unique = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] != sorted[unique - 1]) {
                sorted[unique++] = sorted[i];
            }
        }
        return Arrays.copyOf(sorted, unique);
    }

    private NativeMovementParityTest() {
    }
}
