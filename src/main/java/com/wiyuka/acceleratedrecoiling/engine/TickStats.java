package com.wiyuka.acceleratedrecoiling.engine;

import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import net.minecraft.server.level.ServerLevel;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * tick 耗时拆解。此前只能看到「引擎 ms/帧」，无法判断 tick 里其余时间由谁消耗。
 *
 * <p>三个层次分开统计，避免互相污染：
 * <ul>
 *   <li>服务器 tick（{@code MinecraftServer.tickServer}）——与 F3 的 mspt 口径最接近；</li>
 *   <li>每个 {@link ServerLevel} 一个窗口——维度之间绝不可混平均，空心维度会把有实体
 *       的维度稀释成几分之一，读数会小得离谱；</li>
 *   <li>维度内的嵌套项：实体 tick 合计、其中的引擎全量推、{@code Entity.move}、
 *       {@code Entity.collide}。</li>
 * </ul>
 *
 * <p>热路径只做一次布尔判断加两次 nanoTime，可用配置项 tickProfiling 关闭。
 */
public final class TickStats {

    /** 滚动窗口长度：约 10 秒，足以滤掉抖动又跟得上场景切换。 */
    private static final int WINDOW = 200;

    /** 一个维度（或服务器）的耗时窗口。 */
    private static final class Window {
        private final long[] tick = new long[WINDOW];
        private final long[] entity = new long[WINDOW];
        private final long[] move = new long[WINDOW];
        private final long[] collide = new long[WINDOW];
        private final long[] engine = new long[WINDOW];
        private final long[] calls = new long[WINDOW];
        private final long[] gather = new long[WINDOW];
        private final long[] block = new long[WINDOW];
        private final long[] clip = new long[WINDOW];
        private final long[] takeover = new long[WINDOW];
        private final long[] returned = new long[WINDOW];
        private final long[] boxes = new long[WINDOW];
        private final long[] collideCallCounts = new long[WINDOW];
        private final long[] aabbNanos = new long[WINDOW];
        private final long[] aabbCallCounts = new long[WINDOW];
        private final long[] aabbReturned = new long[WINDOW];
        private int cursor = 0;
        private int filled = 0;

        private long sumTick = 0;
        private long sumEntity = 0;
        private long sumMove = 0;
        private long sumCollide = 0;
        private long sumEngine = 0;
        private long sumCalls = 0;
        private long sumGather = 0;
        private long sumBlock = 0;
        private long sumClip = 0;
        private long sumTakeover = 0;
        private long sumReturned = 0;
        private long sumBoxes = 0;
        private long sumCollideCalls = 0;
        private long sumAabbNanos = 0;
        private long sumAabbCalls = 0;
        private long sumAabbReturned = 0;

        void push(long tickNanos, long entityNanos, long moveNanos, long collideNanos,
                  long engineNanos, long moveCalls, long gatherNanos, long blockNanos, long clipNanos,
                  long takeoverCalls, long returnedEntities, long extractedBoxes,
                  long collideCallCount, long aabbNanosValue, long aabbCallCount, long aabbReturnedValue) {
            int slot = cursor;
            sumTick += tickNanos - this.tick[slot];
            sumEntity += entityNanos - this.entity[slot];
            sumMove += moveNanos - this.move[slot];
            sumCollide += collideNanos - this.collide[slot];
            sumEngine += engineNanos - this.engine[slot];
            sumCalls += moveCalls - this.calls[slot];
            sumGather += gatherNanos - this.gather[slot];
            sumBlock += blockNanos - this.block[slot];
            sumClip += clipNanos - this.clip[slot];
            sumTakeover += takeoverCalls - this.takeover[slot];
            sumReturned += returnedEntities - this.returned[slot];
            sumBoxes += extractedBoxes - this.boxes[slot];
            sumCollideCalls += collideCallCount - this.collideCallCounts[slot];
            sumAabbNanos += aabbNanosValue - this.aabbNanos[slot];
            sumAabbCalls += aabbCallCount - this.aabbCallCounts[slot];
            sumAabbReturned += aabbReturnedValue - this.aabbReturned[slot];
            this.tick[slot] = tickNanos;
            this.entity[slot] = entityNanos;
            this.move[slot] = moveNanos;
            this.collide[slot] = collideNanos;
            this.engine[slot] = engineNanos;
            this.calls[slot] = moveCalls;
            this.gather[slot] = gatherNanos;
            this.block[slot] = blockNanos;
            this.clip[slot] = clipNanos;
            this.takeover[slot] = takeoverCalls;
            this.returned[slot] = returnedEntities;
            this.boxes[slot] = extractedBoxes;
            this.collideCallCounts[slot] = collideCallCount;
            this.aabbNanos[slot] = aabbNanosValue;
            this.aabbCallCounts[slot] = aabbCallCount;
            this.aabbReturned[slot] = aabbReturnedValue;
            cursor = (slot + 1) % WINDOW;
            if (filled < WINDOW) {
                filled++;
            }
        }

        double ms(long sum) {
            return filled == 0 ? 0.0 : sum / 1e6 / filled;
        }

        double perTick(long sum) {
            return filled == 0 ? 0.0 : (double) sum / filled;
        }
    }

    /** 一个维度的累计器（每 tick 清零）。 */
    private static final class Acc {
        long tickStart;
        long entity, move, collide, engine, calls;
        long gather, block, clip, takeover, returned, boxes;
        long collideCalls, aabbNanos, aabbCalls, aabbReturned;
        long ecNanos, ecCalls, ecReturned;
        double ecMaxBoxSize;
        long typeQueryCalls;
        long typeQueryNanos;
        long parityChecks, parityMismatches, parityNativeNanos, parityVanillaNanos;
        long moveParityChecks, moveParityMismatches, moveParityNativeNanos;
        long gpuPushNanos;
    }

    private static final Window SERVER = new Window();
    private static final Map<ServerLevel, Window> LEVEL_WINDOWS = new IdentityHashMap<>();
    private static final Map<ServerLevel, Acc> LEVEL_ACC = new IdentityHashMap<>();
    /** 最近一个完整 tick 的原始值，用于低 TPS 下读瞬时值——滑动平均在低 TPS 时严重滞后。 */
    private static final Map<ServerLevel, long[]> LEVEL_LAST = new IdentityHashMap<>();

    /** 服务端 tick 是否进行中；热路径先查它，客户端线程的调用不会被算进来。 */
    private static volatile boolean active = false;
    /**
     * 正在 tick 的线程。客户端线程也会调用 {@code Entity.move}，而 {@code active} 是
     * 服务端线程写的静态标志，客户端线程可能读到 true，从而把客户端的工作量记进服务端的账。
     * 只在 owner 线程上记账即可杜绝这种污染；客户端线程读到的 owner 与自己不等就直接不算。
     */
    private static Thread owner = null;
    /** 当前正在 tick 的维度，供不知道维度的热路径（move/collide）归集。 */
    private static ServerLevel current = null;
    private static long serverTickStart = 0;

    private TickStats() {
    }

    public static boolean isActive() {
        return active && FoldConfig.tickProfiling && Thread.currentThread() == owner;
    }

    // ---- 服务器层 ----

    public static void serverTickBegin() {
        if (!FoldConfig.tickProfiling) {
            return;
        }
        serverTickStart = System.nanoTime();
    }

    public static void serverTickEnd() {
        if (!FoldConfig.tickProfiling) {
            return;
        }
        SERVER.push(System.nanoTime() - serverTickStart, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    // ---- 维度层 ----

    public static void levelTickBegin(ServerLevel level) {
        if (!FoldConfig.tickProfiling) {
            return;
        }
        owner = Thread.currentThread();
        current = level;
        active = true;
        Acc acc = new Acc();
        acc.tickStart = System.nanoTime();
        LEVEL_ACC.put(level, acc);
    }

    public static void levelTickEnd(ServerLevel level) {
        if (!FoldConfig.tickProfiling) {
            return;
        }
        active = false;
        Acc acc = LEVEL_ACC.get(level);
        current = null;
        if (acc == null) {
            return;
        }
        Window window = LEVEL_WINDOWS.computeIfAbsent(level, ignored -> new Window());
        window.push(System.nanoTime() - acc.tickStart, acc.entity, acc.move, acc.collide,
                acc.engine, acc.calls, acc.gather, acc.block, acc.clip, acc.takeover,
                acc.returned, acc.boxes, acc.collideCalls, acc.aabbNanos, acc.aabbCalls, acc.aabbReturned);
        LEVEL_LAST.put(level, new long[]{
                System.nanoTime() - acc.tickStart, acc.entity, acc.move, acc.collide, acc.engine,
                acc.calls, acc.gather, acc.block, acc.clip, acc.takeover, acc.returned, acc.boxes,
                acc.collideCalls, acc.aabbNanos, acc.aabbCalls, acc.aabbReturned,
                acc.ecNanos, acc.ecCalls, acc.ecReturned, Double.doubleToRawLongBits(acc.ecMaxBoxSize),
                acc.typeQueryCalls, acc.typeQueryNanos,
                acc.parityChecks, acc.parityMismatches, acc.parityNativeNanos, acc.parityVanillaNanos,
                acc.moveParityChecks, acc.moveParityMismatches, acc.moveParityNativeNanos,
                acc.gpuPushNanos});
    }

    // ---- 实体层（归到当前维度）----

    public static void entityTick(long nanos) {
        Acc acc = currentAcc();
        if (acc != null) {
            acc.entity += nanos;
        }
    }

    public static void move(long nanos) {
        Acc acc = currentAcc();
        if (acc != null) {
            acc.move += nanos;
            acc.calls++;
        }
    }

    public static void collide(long nanos) {
        Acc acc = currentAcc();
        if (acc != null) {
            acc.collide += nanos;
            acc.collideCalls++;
        }
    }

    /**
     * GPU 对比档：每 tick 一次的候选枚举耗时（发生于维度 tick HEAD，不在实体 tick 内）。
     * 放在这里是为了让「ECO 引擎 vs GPU 后端」的对比有直接数字，而不是只看维度 tick 总数。
     */
    public static void gpuPush(long nanos) {
        Acc acc = currentAcc();
        if (acc != null) {
            acc.gpuPushNanos += nanos;
        }
    }

    /**
     * 移动接管抛异常、静默退回原版的累计次数。
     *
     * <p>那条路是刻意静默的（失败即改用原版，行为不变），但**静默就没人知道它一直在失败**：
     * 原生求解若持续抛异常，表现只是「开了模组却没加速」。所以这里留一个计数，/check 会显示——
     * 只要它在持续增长，就说明原生移动求解坏了。
     * 用 AtomicLong：客户端线程也会走到那段代码（{@code Entity.collide} 两端都会调）。
     */
    private static final java.util.concurrent.atomic.AtomicLong MOVEMENT_FALLBACKS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 首次退回时的异常摘要。**只记第一条**：持续失败时后面几千条都是同一根因，全记只会刷屏；
     * 只记第一条就能在 /check 里直接看到「为什么退回」，不必再开 debug 猜。
     * 借鉴自 ECO 上游的「保留原生失败细节」：静默路径必须留痕，否则没人知道它在失败。
     * volatile：写发生在计数递增之前（happens-before 由 AtomicLong 的写保证），读侧直接读。
     */
    private static volatile String movementFallbackCause = "";

    /** 移动接管抛异常、退回原版时调用。首个异常的摘要被保留。 */
    public static void movementFallback(Throwable cause) {
        MOVEMENT_FALLBACKS.incrementAndGet();
        if (movementFallbackCause.isEmpty() && cause != null) {
            movementFallbackCause = cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : (": " + cause.getMessage()));
        }
    }

    /** 累计退回次数；持续增长即原生移动求解有问题。 */
    public static long movementFallbacks() {
        return MOVEMENT_FALLBACKS.get();
    }

    /** 首次退回的异常摘要；从未退回过则为空串。 */
    public static String movementFallbackCause() {
        return movementFallbackCause;
    }

    /**
     * 移动求解影子对拍：适用次数（原生拒绝的不计）、原生耗时、不一致次数。
     * 不一致次数必须为 0 才允许把方块侧结果换成原生的。
     */
    public static void movementParity(boolean applied, long nativeNanos, boolean mismatch) {        Acc acc = currentAcc();
        if (acc != null && applied) {
            acc.moveParityChecks++;
            acc.moveParityNativeNanos += nativeNanos;
            if (mismatch) {
                acc.moveParityMismatches++;
            }
        }
    }

    /**
     * 查询影子对拍：调用次数、不一致次数、原生耗时、原版耗时。
     * 不一致次数是判断原生查询是否真的与原版等价的硬指标，必须为 0 才允许接管常开。
     */
    public static void queryParity(long nativeNanos, long vanillaNanos, boolean mismatch) {
        Acc acc = currentAcc();
        if (acc != null) {
            acc.parityChecks++;
            if (mismatch) {
                acc.parityMismatches++;
            }
            acc.parityNativeNanos += nativeNanos;
            acc.parityVanillaNanos += vanillaNanos;
        }
    }

    /**
     * 传感器／目标选择器那条实体查询路径（{@code getEntities(EntityTypeTest, AABB, Predicate)}）的调用次数。
     * 「其余」那部分 AI 与感知开销若来自实体查询，一定记在这里。
     */
    public static void typeQuery() {
        Acc acc = currentAcc();
        if (acc != null) {
            acc.typeQueryCalls++;
        }
    }

    /** 同上，带耗时。 */
    public static void typeQuery(long nanos) {
        Acc acc = currentAcc();
        if (acc != null) {
            acc.typeQueryCalls++;
            acc.typeQueryNanos += nanos;
        }
    }

    /**
     * 原版 {@code Entity.collide} 自己那次 {@code getEntityCollisions} 的总账。
     * 若调用次数接近 0，说明它一直在 {@code getSize() < 1e-7} 处提前返回——
     * 那么这条路径对这些实体本来就是空操作，任何「接管它」的优化都没有收益。
     */
    public static void entityCollisions(long nanos, int returned, double boxSize) {
        Acc acc = currentAcc();
        if (acc != null) {
            acc.ecNanos += nanos;
            acc.ecCalls++;
            acc.ecReturned += returned;
            if (boxSize > acc.ecMaxBoxSize) {
                acc.ecMaxBoxSize = boxSize;
            }
        }
    }

    /**
     * {@code Level.getEntities(Entity, AABB, Predicate)} 的总账：调用次数、返回个数、耗时。
     * 这条路径不止 collide 在用，单独看 collide 会漏掉别处的开销。
     */
    public static void aabbQuery(long nanos, int returned) {
        Acc acc = currentAcc();
        if (acc != null) {
            acc.aabbNanos += nanos;
            acc.aabbCalls++;
            acc.aabbReturned += returned;
        }
    }

    /** 移动接管内部：实体碰撞盒收集（含实体查询）耗时。每次接管调用只上报一次。 */
    public static void collideGather(long nanos, long returned, long boxes) {
        Acc acc = currentAcc();
        if (acc != null) {
            acc.gather += nanos;
            acc.takeover++;
            acc.returned += returned;
            acc.boxes += boxes;
        }
    }

    /** 移动接管内部：方块碰撞形状收集耗时（方块那一半仍走原版）。 */
    public static void collideBlock(long nanos) {
        Acc acc = currentAcc();
        if (acc != null) {
            acc.block += nanos;
        }
    }

    /** 移动接管内部：裁剪与台阶求解耗时。 */
    public static void collideClip(long nanos) {
        Acc acc = currentAcc();
        if (acc != null) {
            acc.clip += nanos;
        }
    }

    /** 引擎全量推一次调用（含包围盒拷贝与原生调用）的耗时。 */
    public static void engine(long nanos) {
        Acc acc = currentAcc();
        if (acc != null) {
            acc.engine += nanos;
        }
    }

    private static Acc currentAcc() {
        return current == null ? null : LEVEL_ACC.get(current);
    }

    // ---- 读数 ----

    /** 一次读数快照，单位均为毫秒（除 calls/ticks）。 */
    public static final class Snapshot {
        public final int ticks;
        public final double tickMs;
        public final double entityMs;
        public final double moveMs;
        public final double collideMs;
        public final double engineMs;
        public final double moveCalls;
        public final double gatherMs;
        public final double blockMs;
        public final double clipMs;
        public final double takeoverCalls;
        /** 接管时实体查询每次平均返回多少个实体。 */
        public final double returnedPerCall;
        /** 接管时实际用于裁剪的碰撞盒个数（实体盒 + 空形状占位）。 */
        public final double boxesPerCall;
        /** collide 被调用的次数，用来判断它与 move 次数是否一致。 */
        public final double collideCalls;
        /** {@code Level.getEntities(Entity, AABB, Predicate)} 的总耗时与总账。 */
        public final double aabbQueryMs;
        public final double aabbQueryCalls;
        public final double aabbQueryReturned;
        /** 原版 collide 自己那次 getEntityCollisions 的调用次数、耗时、返回个数与查询盒最大尺寸（仅上一 tick 有值）。 */
        public final double entityCollisionCalls;
        public final double entityCollisionMs;
        public final double entityCollisionReturned;
        public final double entityCollisionMaxBoxSize;
        /** 传感器／目标选择器那条实体查询路径（EntityTypeTest 变体）每 tick 的调用次数与耗时。 */
        public final double typeQueryCalls;
        public final double typeQueryMs;
        /** 影子对拍：校验次数、不一致次数、原生耗时、原版耗时。 */
        public final double parityChecks;
        public final double parityMismatches;
        public final double parityNativeMs;
        public final double parityVanillaMs;
        /** 移动求解影子对拍：适用次数、不一致次数、原生耗时。 */
        public final double moveParityChecks;
        public final double moveParityMismatches;
        public final double moveParityNativeMs;
        /** GPU 对比档的候选枚举耗时（维度 tick HEAD，不在实体 tick 内）。 */
        public final double gpuPushMs;

        Snapshot(Window window) {
            this.ticks = window.filled;
            this.tickMs = window.ms(window.sumTick);
            this.entityMs = window.ms(window.sumEntity);
            this.moveMs = window.ms(window.sumMove);
            this.collideMs = window.ms(window.sumCollide);
            this.engineMs = window.ms(window.sumEngine);
            this.moveCalls = window.perTick(window.sumCalls);
            this.gatherMs = window.ms(window.sumGather);
            this.blockMs = window.ms(window.sumBlock);
            this.clipMs = window.ms(window.sumClip);
            this.takeoverCalls = window.perTick(window.sumTakeover);
            double calls = window.perTick(window.sumTakeover);
            this.returnedPerCall = calls <= 0.0 ? 0.0 : window.perTick(window.sumReturned) / calls;
            this.boxesPerCall = calls <= 0.0 ? 0.0 : window.perTick(window.sumBoxes) / calls;
            this.collideCalls = window.perTick(window.sumCollideCalls);
            this.aabbQueryMs = window.ms(window.sumAabbNanos);
            this.aabbQueryCalls = window.perTick(window.sumAabbCalls);
            this.aabbQueryReturned = window.perTick(window.sumAabbReturned);
            this.entityCollisionCalls = 0.0;
            this.entityCollisionMs = 0.0;
            this.entityCollisionReturned = 0.0;
            this.entityCollisionMaxBoxSize = 0.0;
            this.typeQueryCalls = 0.0;
            this.typeQueryMs = 0.0;
            this.parityChecks = 0.0;
            this.parityMismatches = 0.0;
            this.parityNativeMs = 0.0;
            this.parityVanillaMs = 0.0;
            this.moveParityChecks = 0.0;
            this.moveParityMismatches = 0.0;
            this.moveParityNativeMs = 0.0;
            this.gpuPushMs = 0.0;
        }

        /** 单个 tick 的原始值：{{tickNanos, entity, move, collide, engine, calls, gather, block, clip, takeover, returned, boxes, collideCalls, aabbNanos, aabbCalls, aabbReturned}}。 */
        Snapshot(long[] values) {
            this.ticks = 1;
            this.tickMs = values[0] / 1e6;
            this.entityMs = values[1] / 1e6;
            this.moveMs = values[2] / 1e6;
            this.collideMs = values[3] / 1e6;
            this.engineMs = values[4] / 1e6;
            this.moveCalls = values[5];
            this.gatherMs = values[6] / 1e6;
            this.blockMs = values[7] / 1e6;
            this.clipMs = values[8] / 1e6;
            this.takeoverCalls = values[9];
            this.returnedPerCall = values[9] <= 0 ? 0.0 : (double) values[10] / values[9];
            this.boxesPerCall = values[9] <= 0 ? 0.0 : (double) values[11] / values[9];
            this.collideCalls = values[12];
            this.aabbQueryMs = values[13] / 1e6;
            this.aabbQueryCalls = values[14];
            this.aabbQueryReturned = values[15];
            this.entityCollisionCalls = values[17];
            this.entityCollisionMs = values[16] / 1e6;
            this.entityCollisionReturned = values[18];
            this.entityCollisionMaxBoxSize = Double.longBitsToDouble(values[19]);
            this.typeQueryCalls = values[20];
            this.typeQueryMs = values[21] / 1e6;
            this.parityChecks = values[22];
            this.parityMismatches = values[23];
            this.parityNativeMs = values[24] / 1e6;
            this.parityVanillaMs = values[25] / 1e6;
            this.moveParityChecks = values[26];
            this.moveParityMismatches = values[27];
            this.moveParityNativeMs = values[28] / 1e6;
            this.gpuPushMs = values[29] / 1e6;
        }

        /**
         * 维度 tick 里实体之外的部分：方块、方块实体、区块、其它 mod。
         *
         * <p>注意 {@code collide} 是在 {@code move} 内部调用的，它的耗时已经包含在 moveMs 里，
         * 不能再从实体总时间里扣一次，否则「其余」会被少算一个 collide。
         */
        public double nonEntityMs() {
            return Math.max(0.0, tickMs - entityMs);
        }

        /** 实体 tick 里除去位移与推挤之后剩余的部分：AI、感知、目标选择等。 */
        public double otherEntityMs() {
            return Math.max(0.0, entityMs - engineMs - moveMs);
        }
    }

    private static final Snapshot EMPTY = new Snapshot(new Window());

    public static Snapshot server() {
        return SERVER.filled == 0 ? EMPTY : new Snapshot(SERVER);
    }

    public static Snapshot level(ServerLevel level) {
        Window window = LEVEL_WINDOWS.get(level);
        return window == null || window.filled == 0 ? EMPTY : new Snapshot(window);
    }

    /**
     * 最近一个完整 tick 的值。滑动平均在低 TPS 下会严重滞后——TPS 2 时 200 tick 窗口跨 100 秒，
     * 读数里绝大部分是更早的便宜 tick，看不出真实开销。诊断性能问题必须先看这一份。
     */
    public static Snapshot levelLast(ServerLevel level) {
        long[] values = LEVEL_LAST.get(level);
        return values == null ? EMPTY : new Snapshot(values);
    }

    /**
     * 维度卸载时清掉它的三张表。
     *
     * <p>这三张都是 {@code IdentityHashMap<ServerLevel, ...>}，强引用维度对象。原版只加载三个
     * 维度且永不卸载，所以平时没有影响；动态增删维度的场景下不清就是泄漏。
     */
    public static void removeLevel(ServerLevel level) {
        LEVEL_WINDOWS.remove(level);
        LEVEL_ACC.remove(level);
        LEVEL_LAST.remove(level);
    }

    /** 服务器停机时清空全部按维度的表。 */
    public static void removeAllLevels() {
        LEVEL_WINDOWS.clear();
        LEVEL_ACC.clear();
        LEVEL_LAST.clear();
    }
}
