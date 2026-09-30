package com.wiyuka.acceleratedrecoiling.engine;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * 全量推「一对走一次」施加的冲量，是否等于「逐 source 各跑一次」。
 *
 * <p>{@code executeFullPushRun} 曾声称两者等价。但逐 source 时，无序对 {A,B} 会在 A 的
 * 轮次与 B 的轮次各施加一次（原版 {@code pushEntities} 就是每个实体各推对方一次，同一对
 * 一帧吃两遍冲量）；全量推若只施加一次，强度就只有一半。
 *
 * <p>初速度置零，于是速度就等于冲量本身，比值可直接判读。报告分两类：
 * <b>量级差</b>（相对偏差 &gt; 1e-9，属于真的算错）与 <b>末位差</b>（仅求和次序不同导致的舍入）。
 *
 * <p>用法：{@code PushRunEquivalenceTest <dll路径> [种子] [轮数]}
 */
public final class PushRunEquivalenceTest {

    private static final int STRIDE = 104;
    private static final int X = 0, Z = 8, VX = 16, VY = 24, VZ = 32, VERSION = 40,
            STATE = 48, ROOT = 52, SYNC = 56, RESERVED = 60, Y = 64;
    private static final int PUSHABLE = 1, VEHICLE = 2, PASSENGER = 4, SLEEPING = 8, NO_PHYSICS = 16;
    private static final double MATERIAL = 1e-9;

    private static MethodHandle executePushRun;
    private static MethodHandle executeFullPushRun;

    public static void main(String[] args) throws Throwable {
        if (args.length < 1) {
            System.out.println("用法: PushRunEquivalenceTest <dll路径> [种子] [轮数]");
            return;
        }
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20260917L;
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 300;

        Path dll = Path.of(args[0]).toAbsolutePath();
        if (!Files.exists(dll)) {
            System.out.println("[FAIL] 找不到原生库: " + dll);
            return;
        }
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.libraryLookup(dll, Arena.global());
        executePushRun = linker.downcallHandle(lookup.find("executePushRun").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        executeFullPushRun = linker.downcallHandle(lookup.find("executeFullPushRun").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        Arena arena = Arena.ofShared();
        Random random = new Random(seed);

        int materialDiffs = 0;
        int ulpDiffs = 0;
        int compared = 0;
        int roundsWithPairs = 0;
        long totalPairs = 0;
        long totalBlockNeighbors = 0;
        double worstRelative = 0.0;
        double ratioSum = 0.0;
        int ratioCount = 0;
        String worstCase = "";

        for (int round = 0; round < rounds; round++) {
            int n = 2 + random.nextInt(14);
            double[] box = new double[n * 6];
            ByteBuffer rows = ByteBuffer.allocate(STRIDE * n).order(ByteOrder.nativeOrder());
            for (int i = 0; i < n; i++) {
                // 连续坐标：同位置的实体会被距离守卫拒绝（原版同样如此），那样根本测不到冲量。
                double x = random.nextDouble() * 3.0;
                double z = random.nextDouble() * 3.0;
                double half = 0.3 + random.nextDouble() * 0.2;
                box[i * 6] = x - half;
                box[i * 6 + 1] = 0.0;
                box[i * 6 + 2] = z - half;
                box[i * 6 + 3] = x + half;
                box[i * 6 + 4] = 1.8;
                box[i * 6 + 5] = z + half;

                int state = PUSHABLE;
                int roll = random.nextInt(10);
                if (roll == 0) state = NO_PHYSICS;
                else if (roll == 1) state = SLEEPING;
                else if (roll == 2) state = PUSHABLE | VEHICLE;
                else if (roll == 3) state = PUSHABLE | PASSENGER;

                int base = i * STRIDE;
                rows.putDouble(base + X, x);
                rows.putDouble(base + Z, z);
                rows.putDouble(base + VX, 0.0);
                rows.putDouble(base + VY, 0.0);
                rows.putDouble(base + VZ, 0.0);
                rows.putLong(base + VERSION, 7L);
                rows.putInt(base + STATE, state);
                rows.putInt(base + ROOT, i);
                rows.putInt(base + SYNC, 0);
                // reserved = (teamId<<2)|rule，rule 0 = COLLISION_ALWAYS
                rows.putInt(base + RESERVED, ((random.nextBoolean() ? 0 : -1) << 2) | 0);
                rows.putDouble(base + Y, 0.0);
            }

            MemorySegment bodiesA = arena.allocate(STRIDE * n, 8);
            MemorySegment bodiesB = arena.allocate(STRIDE * n, 8);
            MemorySegment aabbs = arena.allocate(6L * Double.BYTES * n, 8);
            MemorySegment neighbors = arena.allocate(4L * n, 4);
            MemorySegment targets = arena.allocate(4L * n, 4);

            MemorySegment.copy(rows.array(), 0, bodiesA, ValueLayout.JAVA_BYTE, 0, STRIDE * n);
            MemorySegment.copy(rows.array(), 0, bodiesB, ValueLayout.JAVA_BYTE, 0, STRIDE * n);
            MemorySegment.copy(box, 0, aabbs, ValueLayout.JAVA_DOUBLE, 0, n * 6);

            // 甲：全量推
            int statusA = (int) executeFullPushRun.invokeExact(bodiesA, n, neighbors, aabbs, 0);
            if (statusA != 0) {
                System.out.println("[FAIL] executeFullPushRun 返回 " + statusA);
                return;
            }
            long blockNeighbors = 0;
            for (int i = 0; i < n; i++) {
                blockNeighbors += neighbors.get(ValueLayout.JAVA_INT, (long) i * 4);
            }

            // 乙：逐 source 各跑一次，只传真正相交的候选（与原版 getEntities 一致）
            long pairCount = 0;
            for (int i = 0; i < n; i++) {
                int count = 0;
                for (int j = 0; j < n; j++) {
                    if (j != i && intersects(box, i, j)) {
                        targets.set(ValueLayout.JAVA_INT, (long) count * 4, j);
                        count++;
                    }
                }
                if (count == 0) continue;
                pairCount += count;
                int status = (int) executePushRun.invokeExact(bodiesB, n, i, targets, count);
                if (status != 0) {
                    System.out.println("[FAIL] executePushRun 返回 " + status);
                    return;
                }
            }
            totalPairs += pairCount;
            totalBlockNeighbors += blockNeighbors;
            if (pairCount == 0) continue;
            roundsWithPairs++;

            byte[] outA = new byte[STRIDE * n];
            byte[] outB = new byte[STRIDE * n];
            MemorySegment.copy(bodiesA, ValueLayout.JAVA_BYTE, 0, outA, 0, STRIDE * n);
            MemorySegment.copy(bodiesB, ValueLayout.JAVA_BYTE, 0, outB, 0, STRIDE * n);
            ByteBuffer bufA = ByteBuffer.wrap(outA).order(ByteOrder.nativeOrder());
            ByteBuffer bufB = ByteBuffer.wrap(outB).order(ByteOrder.nativeOrder());

            for (int i = 0; i < n; i++) {
                int base = i * STRIDE;
                int[] axes = {VX, VZ};
                for (int k = 0; k < 2; k++) {
                    double a = bufA.getDouble(base + axes[k]);
                    double b = bufB.getDouble(base + axes[k]);
                    if (a == 0.0 && b == 0.0) continue;
                    compared++;
                    if (Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b)) continue;
                    double denom = Math.max(Math.abs(a), Math.abs(b));
                    double relative = denom == 0.0 ? 0.0 : Math.abs(a - b) / denom;
                    if (relative > worstRelative) {
                        worstRelative = relative;
                        worstCase = "轮" + round + " 槽" + i + " off=" + axes[k]
                                + " 全量推=" + a + " 逐source=" + b;
                    }
                    if (a != 0.0 && b != 0.0) {
                        ratioSum += b / a;
                        ratioCount++;
                    }
                    if (relative > MATERIAL) {
                        materialDiffs++;
                        if (materialDiffs <= 4) {
                            System.out.printf("  [量级差] 轮%d 槽%d off=%d 全量推=%.17g 逐source=%.17g (比 %.3f)%n",
                                    round, i, axes[k], a, b, b / a);
                        }
                    } else {
                        ulpDiffs++;
                    }
                }
            }
        }

        System.out.println();
        System.out.println("轮数=" + rounds + "（有相交的 " + roundsWithPairs + "）"
                + " 逐source候选对=" + totalPairs + " 全量推伙伴数合计=" + totalBlockNeighbors);
        System.out.println("比较字段 " + compared + "：量级差 " + materialDiffs + " 个，仅末位差 " + ulpDiffs + " 个");
        if (ratioCount > 0) {
            System.out.printf("逐source / 全量推 平均比 = %.4f（1.0 = 强度相同；2.0 = 全量推只有一半）%n",
                    ratioSum / ratioCount);
        }
        if (!worstCase.isEmpty()) {
            System.out.printf("最大相对偏差 %.3g（%s）%n", worstRelative, worstCase);
        }
        System.out.println(materialDiffs == 0
                ? "结论：量级一致 —— 两个版本施加的冲量强度相同"
                : "结论：存在量级差 —— 两个版本施加的冲量强度不同");
    }

    private static boolean intersects(double[] box, int i, int j) {
        int a = i * 6;
        int b = j * 6;
        // 严格不等、无 epsilon，与原版 AABB.intersects 一致。
        return box[a] < box[b + 3] && box[a + 3] > box[b]
                && box[a + 1] < box[b + 4] && box[a + 4] > box[b + 1]
                && box[a + 2] < box[b + 5] && box[a + 5] > box[b + 2];
    }
}
