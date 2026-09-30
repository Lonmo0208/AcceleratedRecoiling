package com.wiyuka.acceleratedrecoiling.engine;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import com.wiyuka.acceleratedrecoiling.api.ICustomData;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.natives.GpuEnginePush;
import com.wiyuka.acceleratedrecoiling.natives.TempID;import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * One ServerLevel's native collision frame. Slot i == TempID frame index i, so
 * the spatial index rows, the {@link EcoBodies} rows and the TempID entity
 * snapshot all share the same numbering.
 *
 * <p>Lifecycle per tick: {@link #begin(List)} rebuilds the native spatial index
 * from the tick HEAD collection order, refreshes semantic metadata for every
 * entity and pre-fills the body rows. During the tick, {@code pushEntities} uses
 * {@link #queryPushable} and {@link #pushRun}. {@link #end()} releases the frame.
 */
public final class EcoFrame implements AutoCloseable {

    /** 引擎运行统计（累计，服务器主线程单线程访问）。 */
    public static long FRAMES_BUILT = 0;
    public static long QUERIES_RUN = 0;
    public static long PUSH_RUNS = 0;
    /** executeFullPushRun 执行次数（全量推：帧内一次遍历所有实体对）。 */
    public static long FULL_RUNS = 0;
    public static long PUSHED_ENTITIES = 0;
    /** 走了 Java doPush（未批推）的候选数。 */
    public static long DOPUSH_ENTITIES = 0;
    /** executePushRun 返回非 0 的次数（批推失败）。 */
    public static long RUN_FAILED = 0;
    /** 最近一帧的实体数（用于诊断 count 是否远大于场景实体）。 */
    public static long LAST_COUNT = 0;
    /** 每次查询返回候选数累计（用于诊断候选是否异常放大）。 */
    public static long CANDIDATES_SUM = 0;
    /** 最近一次查询返回的候选数。 */
    public static long LAST_CANDIDATES = 0;
    /** 引擎路径每帧耗时累计（begin+全量推+flush，纳秒）。 */
    public static long ENGINE_NANOS = 0;
    /** 其中 executeFullPushRun 原生调用耗时累计（纳秒）。 */
    public static long NATIVE_FULLPUSH_NANOS = 0;
    /** 全量推走 GPU 后端的次数（含建序打包、内核、回写三段的合计耗时）。 */
    public static long GPU_FULL_RUNS = 0;
    /** GPU 后端全量推耗时累计（纳秒）。 */
    public static long GPU_FULLPUSH_NANOS = 0;
    /** GPU 推挤三段耗时累计（纳秒）：建序打包 / 内核+传输 / 回写。拆开才知道慢在哪一段。 */
    public static long GPU_PREPARE_NANOS = 0;
    public static long GPU_KERNEL_NANOS = 0;
    public static long GPU_APPLY_NANOS = 0;
    /** GPU 推挤差分对拍次数（debugPushParity 打开时才有）。 */
    public static long GPU_PARITY_CHECKS = 0;
    /** 对拍累计不一致的字段数；为 0 才说明两个后端算的是同一件事。 */
    public static long GPU_PARITY_DIFFS = 0;

    private static final IdentityHashMap<ServerLevel, EcoFrame> REGISTRY = new IdentityHashMap<>();

    /** Returns the per-level frame for the current tick, or null when not built. */
    public static EcoFrame of(ServerLevel level) {
        return REGISTRY.get(level);
    }

    /** Gets or lazily creates the frame for a level. */
    public static EcoFrame getOrCreate(ServerLevel level) {
        return REGISTRY.computeIfAbsent(level, ignored -> new EcoFrame());
    }

    /** Removes and closes a level's frame (level unload / server stop). */
    public static void remove(ServerLevel level) {
        EcoFrame frame = REGISTRY.remove(level);
        if (frame != null) {
            frame.close();
        }
    }

    /**
     * 放掉所有维度的帧（服务器停机）。
     *
     * <p>不清的话，注册表会一直强引用维度对象，而每个帧还占着 Arena 与原生段——
     * 停机本来就是进程结束，但单机内置服务端退出后进程还活着（回到主菜单再进世界），
     * 那一轮的帧就白占了。
     */
    public static void removeAll() {
        for (EcoFrame frame : REGISTRY.values()) {
            try {
                frame.close();
            } catch (Throwable ignored) {
                // 收尾路径不许抛
            }
        }
        REGISTRY.clear();
    }

    /** True when a frame with entities is currently active for this level. */
    public static boolean isArmed(ServerLevel level) {
        EcoFrame frame = REGISTRY.get(level);
        return frame != null && frame.armed;
    }

    /** Result of one pushable query; arrays are pooled and exclusively owned by the caller. */
    public static final class PushQuery {
        public final int size;
        public final int pushableCount;
        public final int nonPassengerCount;
        public final int[] ids;
        final int[] flags;
        final EcoFrame owner;

        PushQuery(EcoFrame owner, int size, int pushableCount, int nonPassengerCount,
                  int[] ids, int[] flags) {
            this.owner = owner;
            this.size = size;
            this.pushableCount = pushableCount;
            this.nonPassengerCount = nonPassengerCount;
            this.ids = ids;
            this.flags = flags;
        }

        public int id(int index) {
            return ids[3 + index];
        }

        /** True when the candidate can be pushed by the native batch run. */
        public boolean nativePush(int index) {
            return flags[index] != 0;
        }
    }

    private final MemorySegment context;
    private final EcoBodies bodies = new EcoBodies();
    private final IdentityHashMap<PlayerTeam, Integer> teamIds = new IdentityHashMap<>();
    private final ArrayDeque<int[]> idPool = new ArrayDeque<>();
    private final ArrayDeque<int[]> flagPool = new ArrayDeque<>();

    /** Native (off-heap) scratch buffers; FFM downcalls reject heap segments. */
    private Arena arena = Arena.ofShared();
    private MemorySegment boundsSeg = MemorySegment.NULL;
    private MemorySegment sectionsSeg = MemorySegment.NULL;
    private MemorySegment querySeg = MemorySegment.NULL;
    private MemorySegment nativePushSeg = MemorySegment.NULL;
    private MemorySegment slotSeg = MemorySegment.NULL;
    private MemorySegment neighborSeg = MemorySegment.NULL;
    private MemorySegment bounds6Seg = MemorySegment.NULL;
    /** 整箱实体查询的输出段（传感器/目标选择器用），按帧内实体数按需扩容。 */
    private MemorySegment boxQuerySeg = MemorySegment.NULL;
    private int boxQueryCapacity;

    /** GPU 推挤后端的中转段：原生打包 → 显卡 → 原生回写，Java 不碰里面的元素。 */
    private MemorySegment gpuPackedSeg = MemorySegment.NULL;
    private MemorySegment gpuMetaSeg = MemorySegment.NULL;
    private MemorySegment gpuOrderSeg = MemorySegment.NULL;
    private MemorySegment gpuLiveSeg = MemorySegment.NULL;
    private MemorySegment gpuVelSeg = MemorySegment.NULL;
    private MemorySegment gpuAuxSeg = MemorySegment.NULL;
    private MemorySegment gpuDiffSeg = MemorySegment.NULL;
    /** GPU 推挤每实体可能相交的 order 区间 [lo, hi)，每槽 2 个 int。 */
    private MemorySegment gpuRangeSeg = MemorySegment.NULL;
    /** GPU 推挤后端是否已在本帧被判定不可用（失败过一次就整帧走 CPU，不再反复回退）。 */
    private boolean gpuPushFailed;

    private final double[] boundsScratch = new double[6];

    private double[] bounds = new double[0];
    private int[] sections = new int[0];
    private int[] queryIds = new int[0];
    private int[] nativePush = new int[0];
    private int count;
    private boolean frameOk;
    private boolean armed;
    /**
     * 原生区块索引是否已在本帧建立。整箱实体查询（传感器/目标选择器接管）依赖它：
     * 索引不存在时 {@code queryEntitiesInBox} 会安静地返回 0，和「盒里真的没有实体」
     * 无法区分——所以调用方必须靠这个标志判断能不能信，绝不允许把 0 当成空结果。
     */
    private boolean indexBuilt;
    /** 帧内是否已执行过一次全量推（每个实体 pushEntities 时首调用执行一次）。 */
    private boolean fullPushDone;
    /** 该槽位实体相交可推伙伴数（vanilla cramming 判定用）。 */
    private int[] neighborCounts = new int[0];
    /**
     * 上一帧的伙伴数快照，专供 tick 中途的 cramming 判定读取。
     *
     * <p>异步之后本帧的伙伴数要等 worker 算完才有，而 cramming 在实体自己的 tick 上就要问。
     * 所以这里读上一帧的值——两者错开一个数组、互不重叠，既避免了对正在被 worker 写的
     * 数组做竞争读，也不必加锁。cramming 是一次「以 1/4 概率扣伤害」的判定，晚一帧不可见。
     */
    private int[] lastNeighborCounts = new int[0];

    /**
     * 延迟回写缓冲：每实体每帧只 setDeltaMovement 一次，存的是**本帧的冲量**而不是最终速度。
     *
     * <p>这一点是必须的：冲量要在 tick 末尾加到「当时的实时速度」上，而不是加到帧首速度上。
     * 曾经的写法是在帧首读实体速度、算好 `帧首速度 + 冲量` 存起来、tick 末直接 set，结果把
     * 实体在自己 tick 里算出的摩擦与重力衰减整份丢掉——冲量每帧叠加且永不衰减，畜群速度
     * 会一路涨到 3~6 m/tick（原版安静态是 0.41），move/collide 因此贵出 2~4 倍。
     * 存冲量、写回时现读现加，才与原版 {@code Entity.push} 的语义一致。
     */
    private double[] pendingImpulseX = new double[0];
    private double[] pendingImpulseZ = new double[0];
    private boolean[] pendingDirty = new boolean[0];
    /** 帧首（writeRow 时）实体 x/z 速度；flush 用 当前速度 + (body速度 - 帧首速度) 保留摩擦。 */
    private double[] beginVx = new double[0];
    private double[] beginVz = new double[0];

    public EcoFrame() {
        context = EcoEngine.createContext();
    }

    public boolean usable() {
        return frameOk && context != null && context.isNative();
    }

    public int count() {
        return count;
    }

    /**
     * 传感器／目标选择器那条路径用的整箱查询。返回输出段（TempID 槽位，按原版
     * section + 插入序排列）并把个数写入 {@code countOut[0]}；返回 null 表示这次服务不了，
     * 调用方必须回退原版。容量按帧内实体数取，够放任意子集，取不满时原生会返回负值，
     * 这里同样按「服务不了」处理。
     *
     * <p>只有服务端 tick 内、帧处于 armed 状态时才可用：段是复用的，不能并发访问。
     */
    public MemorySegment queryBoxIds(double minX, double minY, double minZ,
                                     double maxX, double maxY, double maxZ, int[] countOut) {
        if (!armed || !indexBuilt || count <= 0 || !ensureBoxQuerySegment(count)) {
            return null;
        }
        int returned;
        try {
            returned = EcoEngine.queryEntitiesInBox(context, minX, minY, minZ, maxX, maxY, maxZ,
                    boxQuerySeg, count);
        } catch (Throwable t) {
            return null;
        }
        if (returned < 0) {
            return null;
        }
        countOut[0] = returned;
        return boxQuerySeg;
    }

    private boolean ensureBoxQuerySegment(int needed) {
        if (boxQueryCapacity >= needed && boxQuerySeg != MemorySegment.NULL) {
            return true;
        }
        if (arena == null || !arena.scope().isAlive()) {
            return false;
        }
        try {
            boxQuerySeg = arena.allocate((long) needed * Integer.BYTES, Integer.BYTES);
        } catch (Throwable t) {
            return false;
        }
        boxQueryCapacity = needed;
        return true;
    }

    /**
     * 索引自检：拿第 0 个实体自己的包围盒去查，必须能查回它自己。
     *
     * <p>没有这道闸就会重演一次事故：原生索引根本没建时 {@code queryEntitiesInBox} 会安静地
     * 返回 0，与「盒里确实没有实体」无法区分，于是接管把原版查询取消掉、输出空列表，
     * 传感器从此看不见任何实体——而读数是「单次 0.13us，变快了」，看起来像优化。
     * 宁可整帧退回原版，也不能让查询悄悄返回空。
     */
    private boolean probeIndex() {
        if (count <= 0 || !ensureBoxQuerySegment(count)) {
            return false;
        }
        int base = 0;
        int probe;
        try {
            probe = EcoEngine.queryEntitiesInBox(context,
                    bounds[base] - 0.001, bounds[base + 1] - 0.001, bounds[base + 2] - 0.001,
                    bounds[base + 3] + 0.001, bounds[base + 4] + 0.001, bounds[base + 5] + 0.001,
                    boxQuerySeg, count);
        } catch (Throwable t) {
            return false;
        }
        if (probe < 0) {
            return false;
        }
        for (int i = 0; i < probe; i++) {
            if (boxQuerySeg.get(JAVA_INT, (long) i * Integer.BYTES) == 0) {
                return true;
            }
        }
        return false;
    }

    public EcoBodies bodies() {
        return bodies;
    }

    private static int gridSize() {
        int size = FoldConfig.gridSize > 0 ? FoldConfig.gridSize : 8;
        return size;
    }

    /** Rebuilds the native spatial index from the tick HEAD collection order. */
    public void begin(List<Entity> entities, boolean enginePush) {
        long t0 = System.nanoTime();
        count = entities.size();
        LAST_COUNT = count;
        armed = true;
        fullPushDone = false;
        gpuPushFailed = false;
        if (count > 0) {
            ensureCapacity(count);
            // 必须在本帧任何 body 行写入之前扩容（writeExtents/writeRow 直接访问段）。
            bodies.ensureCapacity(count);
            fillArrays(entities);
            // 从 extractionBoundingBox 结果 [minX,minY,minZ,maxX,maxY,maxZ] 计算半尺寸。
            for (int i = 0; i < count; i++) {
                int b = i * 6;
                bodies.writeExtents(i, (bounds[b + 3] - bounds[b]) * 0.5,
                        (bounds[b + 5] - bounds[b + 2]) * 0.5,
                        (bounds[b + 4] - bounds[b + 1]) * 0.5);
            }
            if (pendingImpulseX.length < count) {
                pendingImpulseX = new double[count];
                pendingImpulseZ = new double[count];
                pendingDirty = new boolean[count];
            }
            if (beginVx.length < count) {
                beginVx = new double[count];
                beginVz = new double[count];
            }
            java.util.Arrays.fill(pendingDirty, 0, count, false);
        } else {
            bounds = new double[0];
            sections = new int[0];
        }
        // 全量推只用 body 行（位置/速度/半尺寸/state/reserved），本身不需要空间索引。
        // 但整箱实体查询（传感器/目标选择器接管）需要，所以只在那个开关打开时建一次索引：
        // 重建是每 tick 一遍 O(实体数)，没人用就是白付。
        // sections 里已经是每个实体所在的区块坐标，顺序由原生按
        // (sectionX, sectionZ, sectionY, sectionOrder, id) 排列，与原版一致。
        indexBuilt = false;
        if (count > 0 && FoldConfig.enableEntityGetterOptimization) {
            copyToNative();
            indexBuilt = EcoEngine.beginFrame(context, boundsSeg, sectionsSeg, count, gridSize()) == 0
                    && probeIndex();
        }
        frameOk = true;
        FRAMES_BUILT++;
        for (int slot = 0; slot < count; slot++) {
            refreshMetadata(entities.get(slot), slot);
        }
        ENGINE_NANOS += System.nanoTime() - t0;
        // 帧首状态齐了就把推挤丢给 worker：这 4 ms 不再占服务器线程，藏在 tick 自己的时间里跑。
        submitPush(enginePush);
    }

    /**
     * 提交本帧的推挤任务。
     *
     * <p>{@code enginePush} 由维度 tick 那边按档位判定后传进来——只有走引擎的档才需要算，
     * AR 候选枚举那条路（gpuPath）不经过这里。
     */
    private void submitPush(boolean enginePush) {
        pushFailed = false;
        pushTask = null;
        pushPending = false;
        pushAsync = false;
        // workerBusy：上一帧的 worker 还没退出（通常是超时了）。它在写 body 行，这一帧必须让开；
        // 它一退出这个标志就清掉，之后自动恢复提交。
        if (!enginePush || count <= 0 || workerBusy) {
            return;
        }
        // worker 只读 boundsSeg，所以先在服务器线程把帧首包围盒写完，再交给它。
        copyBoundsToNative();
        if (!FoldConfig.asyncPush) {
            // 同步档（兼容性排查用）：就在 tick HEAD 算完。输入同样是帧首快照，结果与异步
            // 完全一致，只是这约 4 ms 占着服务器线程。
            pushPending = computePush() == 0;
            if (pushPending) {
                extractImpulses();
            } else {
                pushFailed = true;
            }
            return;
        }
        pushAsync = true;
        pushPending = true;
        workerBusy = true;
        pushTask = PUSH_POOL.submit(this::runPushTask);
    }

    /** worker 的入口：跑完（无论成败）都要清掉 workerBusy，否则服务器线程再也不会提交。 */
    private int runPushTask() {
        try {
            return computePush();
        } finally {
            workerBusy = false;
        }
    }

    /**
     * worker 侧：只跑 native（或 GPU）推挤本身。
     *
     * <p>**不碰任何 Java 侧数组**——邻居计数与 pendingImpulse* 都由服务器线程在 join 之后
     * 提取（见 {@link #extractImpulses}）。worker 只写 native 内存，而那块内存的读者只有
     * 服务器线程、且只在 join 之后读。
     *
     * @return 0 表示成功，非 0 交给 {@link #finishPush} 判定失败
     */
    private int computePush() {
        if (runGpuFullPush()) {
            return 0;
        }
        long t0 = System.nanoTime();
        int status = EcoEngine.executeFullPushRun(bodies.memory(), count, neighborSeg, boundsSeg,
                partnerCap());
        NATIVE_FULLPUSH_NANOS += System.nanoTime() - t0;
        if (status != 0) {
            RUN_FAILED++;
            return status;
        }
        FULL_RUNS++;
        return 0;
    }

    /**
     * 服务器线程侧：join 之后把推挤结果抽出来。
     *
     * <p>只取「被推过」的行：body 相对帧首的增量就是本帧冲量。冲量存下来、落到实体时再加到
     * 它**当时**的速度上——不能在这里就定死「帧首速度 + 冲量」，那会丢掉实体本 tick 的摩擦
     * 与重力衰减，冲量永不衰减就会越推越快（§40 那个 bug）。
     */
    private void extractImpulses() {
        long impulses = 0;
        for (int i = 0; i < count; i++) {
            int n = neighborSeg.get(JAVA_INT, (long) i * Integer.BYTES);
            neighborCounts[i] = n;
            impulses += n;
        }
        PUSHED_ENTITIES += impulses;
        for (int slot = 0; slot < count; slot++) {
            double pushedX = bodies.velocityX(slot);
            double pushedZ = bodies.velocityZ(slot);
            if (pushedX != beginVx[slot] || pushedZ != beginVz[slot]) {
                pendingImpulseX[slot] = pushedX - beginVx[slot];
                pendingImpulseZ[slot] = pushedZ - beginVz[slot];
                pendingDirty[slot] = true;
            }
        }
    }

    /** 等 worker 交出结果；tick 末尾调用。返回 false 表示本帧没有可用的推挤结果。 */
    private boolean finishPush() {
        if (!pushPending) {
            return false;
        }
        java.util.concurrent.Future<Integer> task = pushTask;
        pushTask = null;
        if (!pushAsync || task == null) {
            // 同步档：submitPush 里已经算完、提取完并判定过了。
            return !pushFailed;
        }
        try {
            // 有界等待：native 若真的卡住，宁可不推这一帧，也不能把服务器线程挂死。
            if (task.get(250, java.util.concurrent.TimeUnit.MILLISECONDS) != 0) {
                pushFailed = true;
                return false;
            }
            // join 之后、在服务器线程上提取：此刻 worker 已退出，数组没有第二个写者。
            extractImpulses();
            return true;
        } catch (java.util.concurrent.TimeoutException timeout) {
            // 不置永久标志：worker 退出时自己清 workerBusy，之后自动恢复提交。
            // 这一帧的结果一律丢弃（extractImpulses 没跑过，pendingDirty 全 false）。
            pushFailed = true;
            AcceleratedRecoiling.LOGGER.error("[EcoEngine] 异步推挤超过 250 ms 未完成，本帧不推挤；"
                    + "worker 退出后会自动恢复（期间该维度退回原版推挤）", timeout);
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pushFailed = true;
            return false;
        } catch (java.util.concurrent.ExecutionException failure) {
            pushFailed = true;
            AcceleratedRecoiling.LOGGER.warn("[EcoEngine] 异步推挤任务失败，本帧退回原版推挤：{}",
                    failure.getCause() == null ? failure.toString() : failure.getCause().toString());
            return false;
        }
    }

    /** Runs once at tick RETURN; frees the frame references. */
    public void end() {
        long t0 = System.nanoTime();
        // 先收 worker 的结果，再回写速度。join 之后才碰 body 行与伙伴数，单写者关系成立。
        boolean computed = finishPush();
        if (computed) {
            // 本帧伙伴数成为下一帧 cramming 的读数；两个数组错开，worker 不会再碰被读的那个。
            int[] swap = lastNeighborCounts;
            lastNeighborCounts = neighborCounts;
            neighborCounts = swap;
        }
        flushPending();
        ENGINE_NANOS += System.nanoTime() - t0;
        armed = false;
        count = 0;
        frameOk = false;
        indexBuilt = false;
    }

    /**
     * 每实体每帧一次 setDeltaMovement：把本帧冲量加到**当时的实时速度**上。
     *
     * <p>必须在 tick 末尾现读现加，不能在帧首就把结果定死——实体在自己 tick 里对速度做了
     * 摩擦与重力衰减，冲量要叠在衰减之后的速度上。语义与原版 {@code Entity.push} 一致：
     * 逐轴相加、和为非有限则整次不落盘。
     */
    private void flushPending() {
        if (pendingDirty == null || count <= 0) {
            return;
        }
        for (int slot = 0; slot < count; slot++) {
            if (pendingDirty[slot]) {
                applyPending(slot);
                pendingDirty[slot] = false;
            }
        }
    }

    /**
     * 逐实体落盘：把本帧冲量加到该实体**当前**的速度上。返回是否真的落了。
     *
     * <p>只在同步档（{@code asyncPush=false}）生效——那时冲量在 tick HEAD 就算完了，可以在实体
     * 自己的 {@code pushEntities} 上落盘，落点与原版「轮到谁推谁」一致。异步档要等 worker，
     * 来不及在第 1 个实体之前算出结果，落点只能推到 tick 末尾，所以这里是空操作、
     * 交给 {@link #flushPending()}。
     *
     * <p>两种落点的**冲量值完全相同**，差别只在时刻：批量末尾是「这一帧的推挤在帧末一起生效」，
     * 逐实体是「轮到谁就生效」。
     */
    public boolean publishImpulse(int slot) {
        // 异步档一律不在这里落：worker 可能在前半程就返回了，那时 pendingDirty 已置位，
        // 若不挡住就会出现「一部分实体逐实体落、另一部分攒到帧末」的混合状态。
        if (pushAsync || slot < 0 || slot >= count || !pendingDirty[slot]) {
            return false;
        }
        applyPending(slot);
        pendingDirty[slot] = false;
        return true;
    }

    /** 把 slot 的冲量加到它此刻的速度上。语义与原版 {@code Entity.push} 一致。 */
    private void applyPending(int slot) {
        Entity entity = TempID.getEntity(slot);
        if (entity == null) {
            return;
        }
        Vec3 cur = entity.getDeltaMovement();
        double nx = cur.x + pendingImpulseX[slot];
        double ny = cur.y + 0.0;
        double nz = cur.z + pendingImpulseZ[slot];
        if (Double.isFinite(nx) && Double.isFinite(ny) && Double.isFinite(nz)) {
            entity.setDeltaMovement(nx, ny, nz);
        }
    }

    private void ensureCapacity(int entityCount) {
        int idsNeeded = 3 + 2 * entityCount;
        if (bounds.length < (long) entityCount * 6) {
            bounds = new double[(int) (entityCount * 6)];
        }
        if (sections.length < (long) entityCount * 3) {
            sections = new int[(int) (entityCount * 3)];
        }
        if (queryIds.length < idsNeeded) {
            queryIds = new int[idsNeeded];
        }
        if (nativePush.length < entityCount) {
            nativePush = new int[entityCount];
        }
        if (neighborCounts.length < entityCount) {
            neighborCounts = new int[entityCount];
        }
        // 两个伙伴数数组每帧互换，所以两个都必须够大——只长一个会让 worker 越界写。
        if (lastNeighborCounts.length < entityCount) {
            lastNeighborCounts = new int[entityCount];
        }
        growNative("bounds", entityCount * 6 * Double.BYTES);
        growNative("sections", entityCount * 3 * Integer.BYTES);
        growNative("query", idsNeeded * Integer.BYTES);
        growNative("nativePush", entityCount * Integer.BYTES);
        growNative("slot", entityCount * Integer.BYTES);
        growNative("neighbor", entityCount * Integer.BYTES);
        growNative("gpuPacked", entityCount * 12L * Double.BYTES);
        growNative("gpuMeta", entityCount * 4L * Integer.BYTES);
        growNative("gpuOrder", entityCount * Integer.BYTES);
        growNative("gpuVel", entityCount * 3L * Double.BYTES);
        growNative("gpuAux", entityCount * 3L * Integer.BYTES);
        growNative("gpuRange", entityCount * 2L * Integer.BYTES);
        if (gpuLiveSeg.byteSize() < Integer.BYTES) {
            gpuLiveSeg = arena.allocate(Integer.BYTES, 8);
        }
        if (gpuDiffSeg.byteSize() < Integer.BYTES) {
            gpuDiffSeg = arena.allocate(Integer.BYTES, 8);
        }
        if (bounds6Seg.byteSize() < 6L * Double.BYTES) {
            bounds6Seg = arena.allocate(6L * Double.BYTES, Double.BYTES);
        }
    }

    private void growNative(String what, long needed) {
        MemorySegment current = switch (what) {
            case "bounds" -> boundsSeg;
            case "sections" -> sectionsSeg;
            case "query" -> querySeg;
            case "nativePush" -> nativePushSeg;
            case "neighbor" -> neighborSeg;
            case "gpuPacked" -> gpuPackedSeg;
            case "gpuMeta" -> gpuMetaSeg;
            case "gpuOrder" -> gpuOrderSeg;
            case "gpuVel" -> gpuVelSeg;
            case "gpuAux" -> gpuAuxSeg;
            case "gpuRange" -> gpuRangeSeg;
            default -> slotSeg;
        };
        if (current.byteSize() >= needed) {
            return;
        }
        MemorySegment next = arena.allocate(needed, 8);
        switch (what) {
            case "bounds" -> boundsSeg = next;
            case "sections" -> sectionsSeg = next;
            case "query" -> querySeg = next;
            case "nativePush" -> nativePushSeg = next;
            case "neighbor" -> neighborSeg = next;
            case "gpuPacked" -> gpuPackedSeg = next;
            case "gpuMeta" -> gpuMetaSeg = next;
            case "gpuOrder" -> gpuOrderSeg = next;
            case "gpuVel" -> gpuVelSeg = next;
            case "gpuAux" -> gpuAuxSeg = next;
            case "gpuRange" -> gpuRangeSeg = next;
            default -> slotSeg = next;
        }
    }

    /** Copies the Java frame arrays into the native scratch buffers. */
    private void copyToNative() {
        for (int i = 0; i < bounds.length; i++) {
            boundsSeg.set(JAVA_DOUBLE, (long) i * Double.BYTES, bounds[i]);
        }
        for (int i = 0; i < sections.length; i++) {
            sectionsSeg.set(JAVA_INT, (long) i * Integer.BYTES, sections[i]);
        }
    }

    /**
     * 把本帧的实体包围盒（每实体 6 个 double：minX,minY,minZ,maxX,maxY,maxZ）拷进原生缓冲。
     * 全量推用它与原版 getEntities 的包围盒过滤保持一致，并据 X 跨度剪掉不可能相交的候选。
     */
    private void copyBoundsToNative() {
        MemorySegment.copy(bounds, 0, boundsSeg, JAVA_DOUBLE, 0L, count * 6);
    }

    private void fillArrays(List<Entity> entities) {
        for (int i = 0; i < count; i++) {
            Entity entity = entities.get(i);
            ((ICustomData) entity).extractionBoundingBox(bounds, i * 6, 0.0D);
            int bx = (int) Math.floor(entity.getX());
            int by = (int) Math.floor(entity.getY());
            int bz = (int) Math.floor(entity.getZ());
            sections[i * 3] = SectionPos.blockToSectionCoord(bx);
            sections[i * 3 + 1] = SectionPos.blockToSectionCoord(by);
            sections[i * 3 + 2] = SectionPos.blockToSectionCoord(bz);
        }
    }

    /**
     * Semantic + body-row refresh for one entity. Passes the entity's current
     * bounding box so the native index keeps the source and candidate memberships
     * live across mid-tick movement.
     */
    public void refreshMetadata(Entity entity, int slot) {
        int state = 0;
        boolean pushable = entity.isPushable();
        if (pushable) {
            state |= EcoBodies.PUSHABLE;
        }
        if (entity.isVehicle()) {
            state |= EcoBodies.VEHICLE;
        }
        if (entity.isPassenger()) {
            state |= EcoBodies.PASSENGER;
        }
        if (entity instanceof LivingEntity living && living.isSleeping()) {
            state |= EcoBodies.SLEEPING;
        }
        if (entity.noPhysics) {
            state |= EcoBodies.NO_PHYSICS;
        }
        int root = -1;
        if (entity.isPassenger()) {
            root = TempID.getId(entity.getRootVehicle());
        }
        bodies.writeRow(slot, entity, state, root);
        if (slot < beginVx.length) {
            Vec3 v = entity.getDeltaMovement();
            beginVx[slot] = v.x;
            beginVz[slot] = v.z;
        }

        PlayerTeam team = entity.getTeam();
        int teamId = teamIdOf(team);
        int rule = team == null ? Team.CollisionRule.ALWAYS.ordinal() : team.getCollisionRule().ordinal();
        bodies.writeReserved(slot, teamId, rule);

        // 语义元数据上传。其中 sectionOrder 是查询顺序的关键：原生按
        // (sectionX, sectionZ, sectionY, sectionOrder, id) 排列，缺了它并列项会退化成按槽位 id 排，
        // 与原版的段内插入序不一定相同。selectable 传 1 与「未上传」时的默认（视为可查询）一致，
        // 因此这一调用不改变现有可见性行为，只是把顺序来源补齐。
        if (indexBuilt) {
            long sectionOrder = ((ICustomData) entity).getEcoSectionOrder();
            EcoEngine.updateEntity(context, slot,
                    boundsSeg.asSlice((long) slot * 6 * Double.BYTES, 6L * Double.BYTES),
                    1,
                    (state & EcoBodies.PASSENGER) != 0 ? 1 : 0,
                    (state & EcoBodies.VEHICLE) != 0 ? 1 : 0,
                    (state & EcoBodies.NO_PHYSICS) != 0 ? 1 : 0,
                    VanillaPushDetector.usesVanillaEntityPush(entity) ? 1 : 0,
                    VanillaPushDetector.usesVanillaVectorPush(entity) ? 1 : 0,
                    teamId, rule, slot,
                    entity.canBeCollidedWith() ? 1 : 0,
                    sectionOrder);
        }
    }

    private int teamIdOf(PlayerTeam team) {
        if (team == null) {
            return -1;
        }
        Integer existing = teamIds.get(team);
        if (existing != null) {
            return existing;
        }
        int id = teamIds.size();
        teamIds.put(team, id);
        return id;
    }

    /**
     * Query pushable candidates for a source. Returns null when the frame cannot
     * serve (unusable frame, source outside the frame, or metadata convergence
     * failure) so the caller can fall back to the vanilla path.
     */
    public PushQuery queryPushable(Entity source, int sourceSlot, PlayerTeam team,
                                   Team.CollisionRule rule, boolean usesVanillaPush) {
        if (sourceSlot < 0 || sourceSlot >= count) {
            return null;
        }
        int teamId = teamIdOf(team);
        int ruleId = rule == null ? Team.CollisionRule.ALWAYS.ordinal() : rule.ordinal();
        int vanillaPush = usesVanillaPush ? 1 : 0;

        int passes = 0;
        int returned;
        while (true) {
            returned = EcoEngine.queryPushable(context, sourceSlot, teamId, ruleId,
                    vanillaPush, querySeg, nativePushSeg, count);
            if (returned < 0) {
                return null;
            }
            readQueryBack(returned);
            if (queryIds[0] == 0) {
                break;
            }
            // Metadata misses: refresh the flagged ids and re-query.
            if (++passes > 2) {
                return null;
            }
            for (int i = 0; i < returned; i++) {
                int missId = queryIds[3 + i];
                Entity candidate = TempID.getEntity(missId);
                if (candidate != null) {
                    refreshMetadata(candidate, missId);
                }
            }
        }

        int pushableCount = queryIds[1];
        int nonPassengerCount = queryIds[2];
        int[] ids = acquireIds(returned);
        int[] flags = acquireFlags(returned);
        for (int i = 0; i < returned; i++) {
            ids[3 + i] = queryIds[3 + i];
            flags[i] = nativePush[i];
        }
        QUERIES_RUN++;
        CANDIDATES_SUM += returned;
        LAST_CANDIDATES = returned;
        return new PushQuery(this, returned, pushableCount, nonPassengerCount, ids, flags);
    }

    /** 只拷贝本次返回的长度，避免每次查询全量读回大数组。 */
    private void readQueryBack(int returned) {
        int header = 3 + returned;
        for (int i = 0; i < header; i++) {
            queryIds[i] = querySeg.get(JAVA_INT, (long) i * Integer.BYTES);
        }
        for (int i = 0; i < returned; i++) {
            nativePush[i] = nativePushSeg.get(JAVA_INT, (long) i * Integer.BYTES);
        }
    }

    /**
     * Refreshes the involved rows, runs the native batch push over the candidate
     * slice, then immediately publishes the resulting velocities back to the
     * entities so the movement in the same tick sees the impulses.
     *
     * <p><b>当前没有任何调用者</b>——引擎走的是 {@link #fullPushRun}（整帧批量推挤）。
     * 这条逐 source 的路与批量版**并不等价**：离线测试（tools/test 的
     * {@code PushRunEquivalenceTest}）实测逐 source 的冲量强度是批量版的约 1.89 倍，
     * 而且两版的受理规则本就不同（批量版多一道队伍过滤、且只检查扫描序靠后那个的休眠状态）。
     * 谁要重新启用它，先看那份测试，别假设两者等价。
     */
    public void pushRun(int sourceSlot, int[] targetSlots, int offset, int count) {
        if (count <= 0) {
            return;
        }
        // 行值始终等于实体速度：begin() 写入当前速度，每次 run 后立即 applyVelocity 回写，
        // 原生在行上累计的冲量与实体视角一致，无需 refreshVelocity 防脏。
        for (int i = 0; i < count; i++) {
            slotSeg.set(JAVA_INT, (long) i * Integer.BYTES, targetSlots[offset + i]);
        }
        int status = EcoEngine.executePushRun(bodies.memory(), bodies.capacity(), sourceSlot, slotSeg, count);
        if (status != 0) {
            RUN_FAILED++;
            return;
        }
        PUSH_RUNS++;
        PUSHED_ENTITIES += count;
        // 延迟回写：存本帧冲量（不是最终速度），tick 末加到当时的实时速度上。
        if (sourceSlot < pendingImpulseX.length) {
            pendingImpulseX[sourceSlot] = bodies.velocityX(sourceSlot) - beginVx[sourceSlot];
            pendingImpulseZ[sourceSlot] = bodies.velocityZ(sourceSlot) - beginVz[sourceSlot];
            pendingDirty[sourceSlot] = true;
        }
        for (int i = 0; i < count; i++) {
            int slot = targetSlots[offset + i];
            if (slot < pendingImpulseX.length) {
                pendingImpulseX[slot] = bodies.velocityX(slot) - beginVx[slot];
                pendingImpulseZ[slot] = bodies.velocityZ(slot) - beginVz[slot];
                pendingDirty[slot] = true;
            }
        }
    }

    /**
     * 每 200 帧落一行 GPU 推挤读数（含对拍）。做这个后端最难自证的是「显卡上算的到底是不是
     * 同一个引擎」，所以对拍结果必须能不进游戏就读到——只看日志就能确认，不必手输 /check。
     */
    private static void logGpuPush() {
        if (GPU_FULL_RUNS % 200 != 0) {
            return;
        }
        long runs = Math.max(1, GPU_FULL_RUNS);
        String average = String.format(java.util.Locale.ROOT, "%.3f",
                GPU_FULLPUSH_NANOS / 1e6 / runs);
        String phases = String.format(java.util.Locale.ROOT,
                "建序打包 %.2f + 内核传输 %.2f + 回写 %.2f ms/帧；其中提交 %.2f / 等结果 %.2f ms",
                GPU_PREPARE_NANOS / 1e6 / runs,
                GPU_KERNEL_NANOS / 1e6 / runs,
                GPU_APPLY_NANOS / 1e6 / runs,
                GpuEnginePush.submitNanos() / 1e6,
                GpuEnginePush.waitNanos() / 1e6);
        AcceleratedRecoiling.LOGGER.info(
                "[ECO-GPU] 显卡推挤 {} 帧 / 均 {} ms/帧 ({}) / 与 CPU 原生核对拍 {} 次 / 不一致 {} 项 / 实体 {}",
                GPU_FULL_RUNS, average, phases, GPU_PARITY_CHECKS, GPU_PARITY_DIFFS, LAST_COUNT);
    }

    /**
     * 帧内第一次 {@code pushEntities} 调用的落点：**只登记「本帧推挤已接管」，不在这里等结果**。
     *
     * <p>推挤已经在 {@link #begin} 时交给 worker 跑了，结果由 {@link #end} 在 tick 末尾取。
     * 中途唯一要用到推挤结论的是 cramming 的伙伴数，它读上一帧快照（见 {@code lastNeighborCounts}）。
     * 返回 false 表示**上一帧**的异步推挤失败过，调用方应当逐实体退回原版推挤。
     */
    public boolean fullPushRun() {
        if (!frameOk || count <= 0) {
            return false;
        }
        if (!pushPending || pushFailed) {
            // 失败或本帧没提交（停用中）：不置 fullPushDone，让每个实体各自走原版推挤。
            return false;
        }
        fullPushDone = true;
        return true;
    }

    /**
     * 本帧的对手上限。{@code SPARSE} 实验档用 {@code maxCollision} 当上限（与 AR 同义——它也是
     * 用这个值卡每个实体的候选数），其余档返回 0 表示不限。
     */
    private static int partnerCap() {
        return com.wiyuka.acceleratedrecoiling.kernel.KernelMode.fromName(FoldConfig.kernelMode)
                == com.wiyuka.acceleratedrecoiling.kernel.KernelMode.SPARSE
                ? Math.max(1, FoldConfig.maxCollision) : 0;
    }

    /**
     * 引擎的推挤算力放显卡还是 CPU，由 {@code gpuEnginePush} 单独决定——{@code SPARSE} 档不再强制开启。
     *
     * <p>实测（2088 实体，核显与渲染争用）：显卡推挤 4.9 ms/帧 对 CPU 原生 4.2 ms/帧，
     * 显卡反而贵 0.7~1.0 ms。所以「稀疏 + CPU 推挤」是最快组合，「稀疏 + 显卡推挤」是
     * 用户要的那版；两个组合都用同一个开关表达，不额外加配置项。
     */
    private static boolean gpuPushWanted() {
        return FoldConfig.gpuEnginePush;
    }

    private boolean runGpuFullPush() {
        if (gpuPushFailed || !gpuPushWanted() || !GpuEnginePush.available()) {
            return false;
        }
        try {
            long t0 = System.nanoTime();
            int cap = partnerCap();
            if (EcoEngine.prepareGpuPush(bodies.memory(), count, boundsSeg, gpuOrderSeg,
                    gpuLiveSeg, gpuPackedSeg, gpuMetaSeg, gpuRangeSeg, cap) != 0) {
                gpuPushFailed = true;
                return false;
            }
            int live = gpuLiveSeg.get(JAVA_INT, 0L);
            long t1 = System.nanoTime();
            GPU_PREPARE_NANOS += t1 - t0;
            if (!GpuEnginePush.push(gpuPackedSeg, gpuMetaSeg, gpuOrderSeg, gpuRangeSeg,
                    gpuVelSeg, gpuAuxSeg, count, live, cap)) {
                gpuPushFailed = true;
                return false;
            }
            long t2 = System.nanoTime();
            GPU_KERNEL_NANOS += t2 - t1;
            if (FoldConfig.debugPushParity) {
                // 必须在 applyGpuPush 之前核对：参考跑的是「帧首速度 + 冲量」，一旦先回写，
                // body 行已经是 GPU 的结果，参考就变成在 GPU 结果上再推一次，必然处处不一致。
                // 对拍自己会还原快照，所以放在这里既不影响下面的回写，也不改行为。
                if (EcoEngine.verifyGpuPush(bodies.memory(), count, boundsSeg, neighborSeg,
                        gpuVelSeg, gpuAuxSeg, gpuDiffSeg, cap) == 0) {
                    GPU_PARITY_CHECKS++;
                    GPU_PARITY_DIFFS += gpuDiffSeg.get(JAVA_INT, 0L);
                }
            }
            if (EcoEngine.applyGpuPush(bodies.memory(), count, gpuVelSeg, gpuAuxSeg, neighborSeg) != 0) {
                gpuPushFailed = true;
                return false;
            }
            GPU_APPLY_NANOS += System.nanoTime() - t2;
            GPU_FULLPUSH_NANOS += System.nanoTime() - t0;
            GPU_FULL_RUNS++;
            logGpuPush();
            return true;
        } catch (Throwable t) {
            gpuPushFailed = true;
            return false;
        }
    }

    /** 帧内全量推是否已执行（后续实体 pushEntities 直接短路）。 */
    public boolean fullPushDone() {
        return fullPushDone;
    }

    /** 该槽位实体相交可推伙伴数（vanilla cramming 判定用）。读上一帧的快照，见字段注释。 */
    public int neighborCount(int slot) {
        return slot >= 0 && slot < count && slot < lastNeighborCounts.length
                ? lastNeighborCounts[slot] : 0;
    }

    // ------------------------------------------------------------------
    // 异步推挤：worker 只跑 native 推挤本身，冲量提取与落盘都在服务器线程上。
    //
    // 为什么这样是安全的：body 行与 aabb 都是 tick HEAD 建好的帧首快照，此后到 tick 末尾
    // 之间没有任何人再碰它们（逐 source 那条 pushRun 已无调用者；引擎路径在 fullPushDone
    // 之后全部短接）。所以这是标准的单写者：worker 写 native 内存、服务器线程只在 join
    // 之后读它。
    //
    // **Java 侧数组一律不由 worker 写**（邻居计数、pendingImpulse*）：否则 worker 若超时、
    // 却在之后才结束，它写的 pendingDirty 会被下一帧的 flush 当成本帧冲量应用掉——
    // 跨帧注入陈旧冲量。把提取挪到 join 之后的服务器线程，这条路径就不存在了。
    //
    // 收益：这 4 ms 不再占服务器线程的 tick，藏在 tick 自己的时间里跑完。
    // ------------------------------------------------------------------

    /** 专门跑推挤的 worker。单线程即可：每个维度串行 tick，一帧一个任务。 */
    private static final java.util.concurrent.ExecutorService PUSH_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "eco-push");
                thread.setDaemon(true);
                return thread;
            });

    /** 本帧的推挤任务；null 表示本帧没提交（非引擎路径、或 worker 还没退出）。 */
    private java.util.concurrent.Future<Integer> pushTask;
    /** 本帧是否有可用的推挤结果（同步已算完，或异步的在算）。 */
    private boolean pushPending;
    /** 本帧是否走的异步。false 时结果已在 {@link #submitPush} 里同步算完。 */
    private boolean pushAsync;
    /** 上一次推挤是否失败（原生返回非 0、异常或超时）。失败的那一帧整体退回原版推挤。 */
    private boolean pushFailed;
    /**
     * worker 是否仍在运行。**这是跨帧状态，不是本帧状态。**
     *
     * <p>worker 一旦超时未完成，它仍可能在写 body 行，此时绝不能提交下一帧（两个写者）。
     * 所以超时不置任何永久标志，只等 worker 自己在退出时清掉它——极端情况下等价于停用，
     * 但 worker 真结束后**会自动恢复**，不像以前那样「一次超时、永久停用直到重启」。
     * 它是 volatile：worker 写、服务器线程读。
     */
    private volatile boolean workerBusy;

    private int[] acquireIds(int min) {
        int[] array = idPool.pollFirst();
        if (array == null || array.length < 3 + min) {
            int capsized = Math.max(min + 3, array == null ? 16 : (array.length << 1) + 3);
            array = new int[capsized];
        }
        return array;
    }

    private int[] acquireFlags(int min) {
        int[] array = flagPool.pollFirst();
        if (array == null || array.length < min) {
            int capsized = Math.max(min, array == null ? 16 : (array.length << 1));
            array = new int[capsized];
        }
        return array;
    }

    /** Returns pooled arrays of a query the caller no longer needs. */
    public void release(PushQuery query) {
        if (query == null || query.owner != this) {
            return;
        }
        idPool.addFirst(query.ids);
        flagPool.addFirst(query.flags);
    }

    @Override
    public void close() {
        end();
        bodies.close();
        teamIds.clear();
        if (arena != null) {
            arena.close();
            arena = null;
        }
        EcoEngine.destroyContext(context);
    }
}