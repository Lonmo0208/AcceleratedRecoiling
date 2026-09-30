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
import java.nio.file.Path;

/**
 * 手写最小用例，逐步打印，用来定位「全量推一个配对都不受理」的原因。
 * 三个实体：0 与 1 的盒子真实相交，2 在远处。
 */
public final class PushProbe {

    private static final int STRIDE = 104;
    private static final int X = 0, Z = 8, VX = 16, VY = 24, VZ = 32, VERSION = 40,
            STATE = 48, ROOT = 52, SYNC = 56, RESERVED = 60, Y = 64;

    public static void main(String[] args) throws Throwable {
        Path dll = Path.of(args[0]).toAbsolutePath();
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.libraryLookup(dll, Arena.global());
        MethodHandle full = linker.downcallHandle(lookup.find("executeFullPushRun").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MethodHandle single = linker.downcallHandle(lookup.find("executePushRun").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        Arena arena = Arena.ofShared();
        ByteOrder byteOrder = ByteOrder.nativeOrder();
        int n = 3;

        // 0 与 1 完全叠在同一个位置（盒子肯定相交），2 放到 10 格外。
        double[][] pos = {{0.0, 0.0}, {0.0, 0.0}, {10.0, 10.0}};
        double half = 0.4;
        double[] box = new double[n * 6];
        ByteBuffer rows = ByteBuffer.allocate(STRIDE * n).order(byteOrder);
        for (int i = 0; i < n; i++) {
            box[i * 6] = pos[i][0] - half;
            box[i * 6 + 1] = 0.0;
            box[i * 6 + 2] = pos[i][1] - half;
            box[i * 6 + 3] = pos[i][0] + half;
            box[i * 6 + 4] = 1.8;
            box[i * 6 + 5] = pos[i][1] + half;
            int base = i * STRIDE;
            rows.putDouble(base + X, pos[i][0]);
            rows.putDouble(base + Z, pos[i][1]);
            rows.putDouble(base + VX, 0.0);
            rows.putDouble(base + VY, 0.0);
            rows.putDouble(base + VZ, 0.0);
            rows.putLong(base + VERSION, 0L);
            rows.putInt(base + STATE, 1);      // PUSHABLE
            rows.putInt(base + ROOT, i);
            rows.putInt(base + SYNC, 0);
            rows.putInt(base + RESERVED, ((-1) << 2) | 0);
            rows.putDouble(base + Y, 0.0);
        }
        System.out.println("盒子：0=[" + box[0] + "," + box[3] + "] 1=[" + box[6] + "," + box[9] + "]"
                + " 2=[" + box[12] + "," + box[15] + "]");
        System.out.println("相交(0,1)=" + intersects(box, 0, 1) + " 相交(0,2)=" + intersects(box, 0, 2));

        MemorySegment bodiesFull = arena.allocate(STRIDE * n, 8);
        MemorySegment bodiesSingle = arena.allocate(STRIDE * n, 8);
        MemorySegment aabbs = arena.allocate(6L * Double.BYTES * n, 8);
        MemorySegment neighbors = arena.allocate(4L * n, 4);
        MemorySegment targets = arena.allocate(4L * n, 4);
        MemorySegment outLive = arena.allocate(4, 4);
        MemorySegment packed = arena.allocate(12L * 8 * n, 8);
        MemorySegment meta = arena.allocate(4L * 4 * n, 4);
        MemorySegment range = arena.allocate(2L * 4 * n, 4);
        MemorySegment order = arena.allocate(4L * n, 4);

        MemorySegment.copy(rows.array(), 0, bodiesFull, ValueLayout.JAVA_BYTE, 0, STRIDE * n);
        MemorySegment.copy(rows.array(), 0, bodiesSingle, ValueLayout.JAVA_BYTE, 0, STRIDE * n);
        MemorySegment.copy(box, 0, aabbs, ValueLayout.JAVA_DOUBLE, 0, n * 6);

        int s1 = (int) full.invokeExact(bodiesFull, n, neighbors, aabbs, 0);
        System.out.println("\n[全量推] 返回=" + s1);
        for (int i = 0; i < n; i++) {
            System.out.println("  槽" + i + " 伙伴数=" + neighbors.get(ValueLayout.JAVA_INT, (long) i * 4));
        }
        dump(bodiesFull, "全量推");

        int s2 = (int) single.invokeExact(bodiesSingle, n, 0, targets, 1);
        targets.set(ValueLayout.JAVA_INT, 0, 1);
        s2 = (int) single.invokeExact(bodiesSingle, n, 0, targets, 1);
        System.out.println("\n[逐source] executePushRun(源=0, 目标=[1]) 返回=" + s2);
        dump(bodiesSingle, "逐source");

        // 顺便看 prepareGpuPush 认为哪些槽能进扫描序列
        MethodHandle prepare = linker.downcallHandle(lookup.find("prepareGpuPush").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        int s3 = (int) prepare.invokeExact(bodiesSingle, n, aabbs, order, outLive, packed, meta, range, 0);
        System.out.println("\n[prepareGpuPush] 返回=" + s3 + " live=" + outLive.get(ValueLayout.JAVA_INT, 0));
        for (int i = 0; i < n; i++) {
            System.out.println("  槽" + i + " sortedPos=" + meta.get(ValueLayout.JAVA_INT, (long) i * 16 + 12)
                    + " range=[" + range.get(ValueLayout.JAVA_INT, (long) i * 8)
                    + "," + range.get(ValueLayout.JAVA_INT, (long) i * 8 + 4) + ")");
        }
    }

    private static void dump(MemorySegment bodies, String label) {
        byte[] out = new byte[STRIDE * 3];
        MemorySegment.copy(bodies, ValueLayout.JAVA_BYTE, 0, out, 0, out.length);
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 3; i++) {
            int base = i * STRIDE;
            System.out.printf("  %s 槽%d vx=%.17g vz=%.17g version=%d sync=%d%n", label, i,
                    buf.getDouble(base + VX), buf.getDouble(base + VZ),
                    buf.getLong(base + VERSION), buf.getInt(base + SYNC));
        }
    }

    private static boolean intersects(double[] box, int i, int j) {
        int a = i * 6;
        int b = j * 6;
        return box[a] < box[b + 3] && box[a + 3] > box[b]
                && box[a + 1] < box[b + 4] && box[a + 4] > box[b + 1]
                && box[a + 2] < box[b + 5] && box[a + 5] > box[b + 2];
    }
}
