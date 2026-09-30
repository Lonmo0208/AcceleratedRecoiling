package com.wiyuka.acceleratedrecoiling.engine;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * 段索引整箱查询的最小复现：把 5 个实体塞进同一个 16³ 段，然后走一次 {@code queryEntitiesInBox}。
 *
 * <p>段成员表（{@code context.sections}）的 {@code bounds} 必须与 {@code ids} 同长同序——
 * 整箱查询按槽位下标做 4 路 SIMD 包围盒比较，直接读 {@code bounds}。少了它，
 * 段里凑够 4 个实体就会走进批量分支，读的是一段没分配过的内存（空 vector 的 data 是 nullptr），
 * 结果是 JVM 级 SIGSEGV，不是能被 catch 的 Java 异常。
 *
 * <p>用法：{@code java --enable-preview -cp <classes> com.wiyuka...SectionQueryProbe <dll 路径>}
 */
public final class SectionQueryProbe {

    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;

    public static void main(String[] args) throws Throwable {
        Path dll = Path.of(args[0]).toAbsolutePath();
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.libraryLookup(dll, Arena.global());

        MethodHandle create = linker.downcallHandle(
                lookup.find("createCollisionContext").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        MethodHandle add = linker.downcallHandle(
                lookup.find("addCollisionEntity").orElseThrow(),
                FunctionDescriptor.of(INT, ValueLayout.ADDRESS,
                        DOUBLE, DOUBLE, DOUBLE, DOUBLE, DOUBLE, DOUBLE, INT, INT, INT));
        MethodHandle query = linker.downcallHandle(
                lookup.find("queryEntitiesInBox").orElseThrow(),
                FunctionDescriptor.of(INT, ValueLayout.ADDRESS,
                        DOUBLE, DOUBLE, DOUBLE, DOUBLE, DOUBLE, DOUBLE,
                        ValueLayout.ADDRESS, INT));

        MemorySegment context = (MemorySegment) create.invokeExact();
        if (context.equals(MemorySegment.NULL)) {
            System.out.println("createCollisionContext 失败");
            return;
        }

        // 全部落在段 (0,0,0) 内，彼此不重叠；id 依次为 0..4。
        final int count = 5;
        for (int i = 0; i < count; i++) {
            double x = i * 1.0;
            int id = (int) add.invokeExact(context,
                    x, 0.0, 0.0, x + 0.8, 1.8, 0.8,
                    0, 0, 0);
            System.out.println("addCollisionEntity -> id=" + id);
        }

        // 段内 5 个实体 ≥ 4，命中批量分支。
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate((long) count * Integer.BYTES, Integer.BYTES);
            int returned = (int) query.invokeExact(context,
                    -1.0, -1.0, -1.0, 6.0, 2.0, 1.0,
                    out, count);
            System.out.println("queryEntitiesInBox -> " + returned);
            if (returned < 0) {
                System.out.println("（负值＝服务不了，不是崩溃）");
                return;
            }
            int hits = 0;
            for (int i = 0; i < returned; i++) {
                int id = out.get(INT, (long) i * Integer.BYTES);
                System.out.println("  hit id=" + id);
                if (id >= 0 && id < count) hits++;
            }
            System.out.println(hits == count
                    ? "OK：5 个实体全部查回，段 bounds 与 ids 一致"
                    : "不一致：查回 " + hits + " 个，期望 " + count + " 个");
        }

        // 删除会让段尾元素换到被删的槽位（swap-pop）：bounds 必须跟着换，
        // 否则剩下的实体会被拿去跟别人的盒子比较——那是不崩、但结果错的静默失配。
        MethodHandle remove = linker.downcallHandle(
                lookup.find("removeCollisionEntity").orElseThrow(),
                FunctionDescriptor.of(INT, ValueLayout.ADDRESS, INT));
        int removed = (int) remove.invokeExact(context, 1);
        System.out.println("removeCollisionEntity(1) -> " + removed);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate((long) count * Integer.BYTES, Integer.BYTES);
            // 只框住 x=4.0 那一格：期望且只期望实体 4（它此刻在段里的槽位是 1，正是换位后的位置）。
            int returned = (int) query.invokeExact(context,
                    3.9, -0.5, -0.5, 4.9, 2.0, 1.3,
                    out, count);
            System.out.println("删除后查询 x=4.0 那一格 -> " + returned);
            boolean ok = returned == 1 && out.get(INT, 0L) == 4;
            if (returned > 1) {
                for (int i = 0; i < returned; i++) {
                    System.out.println("  hit id=" + out.get(INT, (long) i * Integer.BYTES));
                }
            }
            System.out.println(ok
                    ? "OK：换位后 bounds 跟着实体走（只查回 id=4）"
                    : "不一致：期望只查回 id=4");
        }
    }
}
