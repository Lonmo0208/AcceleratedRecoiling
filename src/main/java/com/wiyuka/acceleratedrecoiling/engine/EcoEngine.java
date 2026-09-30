package com.wiyuka.acceleratedrecoiling.engine;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.UUID;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Java 21 (FFM preview) binding for the merged native engine: the original AcceleratedRecoiling
 * AVX2 batch push (symbols {@code push/createCtx/...}) and the ported Entity Collision Optimizer
 * engine (19 {@code eco/collision_api.h} symbols, {@code ECO_VANILLA_ORDER=1} ordered variant)
 * are compiled into one {@code AcceleratedRecoiling.dll} and loaded here.
 *
 * <p>Failure to load never throws into the game loop: {@link #isAvailable()} becomes false and the
 * kernel dispatch falls back to the Java PARITY path.
 */
public final class EcoEngine {

    private static boolean initialized = false;
    private static boolean available = false;

    private static Linker linker;
    private static Arena nativeArena;
    private static SymbolLookup library;

    // Downcall handles (ordered variant signatures; see eco/collision_api.h)
    private static MethodHandle createContext;
    private static MethodHandle destroyContext;
    private static MethodHandle setGridSize;
    private static MethodHandle beginFrame;
    private static MethodHandle putEntity;
    private static MethodHandle removeEntity;
    private static MethodHandle updateLocation;
    private static MethodHandle updateEntity;
    private static MethodHandle invalidateEntityPushabilityCache;
    private static MethodHandle invalidatePushEligibilityFields;
    private static MethodHandle query;
    private static MethodHandle queryHard;
    private static MethodHandle queryEntities;
    private static MethodHandle queryPushable;
    private static MethodHandle executeRun;
    private static MethodHandle fullPushRun;
    private static MethodHandle prepareGpuPush;
    private static MethodHandle applyGpuPush;
    private static MethodHandle verifyGpuPush;
    private static MethodHandle movement;
    private static MethodHandle prepareMovement;
    private static MethodHandle scanBlocks;

    private EcoEngine() {
    }

    public static boolean isAvailable() {
        if (!initialized) {
            initialize();
        }
        return available;
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            loadNativeLibraryAndBind();
            available = true;
            AcceleratedRecoiling.LOGGER.info("[EcoEngine] ECO native engine bound (20 symbols).");
        } catch (Throwable failure) {
            AcceleratedRecoiling.LOGGER.warn("[EcoEngine] Native engine unavailable, falling back to Java PARITY: {}",
                    failure.getMessage());
            available = false;
        }
    }

    private static void loadNativeLibraryAndBind() throws IOException {
        String nativeFileName = nativeFileName();
        String resourcePath = platformNativePath() + nativeFileName;
        try (InputStream dllStream = AcceleratedRecoiling.class.getResourceAsStream(resourcePath)) {
            if (dllStream == null) {
                throw new FileNotFoundException("Cannot find " + nativeFileName + " in resources at " + resourcePath);
            }
            File temp = File.createTempFile(UUID.randomUUID() + "_eco_engine_", "_" + nativeFileName);
            temp.deleteOnExit();
            try (OutputStream out = new FileOutputStream(temp)) {
                dllStream.transferTo(out);
            }
            linker = Linker.nativeLinker();
            nativeArena = Arena.global();
            library = SymbolLookup.libraryLookup(temp.getAbsolutePath(), nativeArena);
        }
        createContext = downcall("createCollisionContext", FunctionDescriptor.of(ADDRESS));
        destroyContext = downcall("destroyCollisionContext", FunctionDescriptor.ofVoid(ADDRESS));
        setGridSize = downcall("setCollisionGridSize", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        beginFrame = downcall("beginCollisionFrame",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        putEntity = downcall("putCollisionEntity",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
        removeEntity = downcall("removeCollisionEntity", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        updateLocation = downcall("updateCollisionLocation",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
        updateEntity = downcall("updateCollisionEntity",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS,
                        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_LONG));
        invalidateEntityPushabilityCache = downcall("invalidateEntityPushabilityCache",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        invalidatePushEligibilityFields = downcall("invalidatePushEligibilityFields",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        query = downcall("queryCollisionEntities",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        queryHard = downcall("queryHardCollisionEntities",
                FunctionDescriptor.of(JAVA_INT, ADDRESS,
                        JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
                        JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        queryEntities = downcall("queryEntitiesInBox",
                FunctionDescriptor.of(JAVA_INT, ADDRESS,
                        JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
                        ADDRESS, JAVA_INT));
        queryPushable = downcall("queryPushableEntities",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        executeRun = downcall("executePushRun",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        fullPushRun = downcall("executeFullPushRun",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        prepareGpuPush = downcall("prepareGpuPush",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS,
                        ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        applyGpuPush = downcall("applyGpuPush",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        verifyGpuPush = downcall("verifyGpuPush",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
                        ADDRESS, JAVA_INT));
        movement = downcall("solveMovement",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        prepareMovement = downcall("prepareMovement",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        scanBlocks = downcall("scanCollisionBlocks",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    }

    private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = library.find(name).orElseThrow(() -> new IllegalStateException("missing symbol " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    /** 平台目录: /natives/{os}-{arch}/ */
    private static String platformNativePath() {
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(java.util.Locale.ROOT);
        String osName;
        if (os.contains("win")) {
            osName = "windows";
        } else if (os.contains("mac")) {
            osName = "macos";
        } else {
            osName = "linux";
        }
        String archName;
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            archName = "x64";
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            archName = "arm64";
        } else {
            archName = "x64";
        }
        return "/natives/" + osName + "-" + archName + "/";
    }

    private static String nativeFileName() {
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return "AcceleratedRecoiling.dll";
        }
        if (os.contains("mac")) {
            return "AcceleratedRecoiling.dylib";
        }
        return "AcceleratedRecoiling.so";
    }

    // ---- lifecycle ----

    /** Creates a native collision context (one per ServerLevel). Null on failure. */
    public static MemorySegment createContext() {
        if (!isAvailable()) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) createContext.invokeExact();
        } catch (Throwable t) {
            AcceleratedRecoiling.LOGGER.error("[EcoEngine] createCollisionContext failed", t);
            return MemorySegment.NULL;
        }
    }

    public static void destroyContext(MemorySegment context) {
        if (context == null || !context.isNative()) {
            return;
        }
        try {
            destroyContext.invokeExact(context);
        } catch (Throwable t) {
            AcceleratedRecoiling.LOGGER.error("[EcoEngine] destroyCollisionContext failed", t);
        }
    }

    public static int setGridSize(MemorySegment context, int gridSize) {
        try {
            return (int) setGridSize.invokeExact(context, gridSize);
        } catch (Throwable t) {
            throw new IllegalStateException("setCollisionGridSize failed", t);
        }
    }

    public static int beginFrame(MemorySegment context, MemorySegment aabbs, MemorySegment sections, int count, int gridSize) {
        try {
            return (int) beginFrame.invokeExact(context, aabbs, sections, count, gridSize);
        } catch (Throwable t) {
            throw new IllegalStateException("beginCollisionFrame failed", t);
        }
    }

    /** Ordered metadata refresh; bounds segment must hold 6 doubles for the entity. */
    public static int updateEntity(MemorySegment context, int entityId, MemorySegment bounds,
                                   int selectable, int passenger, int vehicle, int noPhysics,
                                   int vanillaEntityPush, int vanillaVectorPush,
                                   int teamId, int collisionRule, int bodySlot, int hardCollidable,
                                   long sectionOrder) {
        try {
            return (int) updateEntity.invokeExact(context, entityId, bounds,
                    selectable, passenger, vehicle, noPhysics,
                    vanillaEntityPush, vanillaVectorPush,
                    teamId, collisionRule, bodySlot, hardCollidable, sectionOrder);
        } catch (Throwable t) {
            throw new IllegalStateException("updateCollisionEntity failed", t);
        }
    }

    /**
     * Native query for pushable candidates (vanilla order).
     *
     * <p>{@code output} layout (see collision_api.h): header[0]=metadataRequired, header[1]=pushableCount,
     * header[2]=nonPassengerCount, then ids[capacity] and bodySlots[capacity].
     * In the merged frame model ids == body slots == TempID indices.
     */
    public static int queryPushable(MemorySegment context, int sourceId, int sourceTeamId, int sourceCollisionRule,
                                    int sourceUsesVanillaPush, MemorySegment output, MemorySegment nativePushOutput,
                                    int capacity) {
        try {
            return (int) queryPushable.invokeExact(context, sourceId, sourceTeamId, sourceCollisionRule,
                    sourceUsesVanillaPush, output, nativePushOutput, capacity);
        } catch (Throwable t) {
            throw new IllegalStateException("queryPushableEntities failed", t);
        }
    }

    /**
     * 移动求解：按原版 {@code collideWithShapes} 的轴序与轴间包围盒推移，对一组体素形状裁剪。
     *
     * <p>{@code phase=0} 做首次裁剪并算出是否需要台阶候选；{@code phase=1} 在同一次调用的
     * {@code data} 上继续走台阶扫描。{@code data} 的布局见 {@code movement_solver.cpp} 里的
     * BOX/REQUEST/POSITION/RESULT/... 偏移常量，Java 侧由 {@code VoxelShapeBridge} 统一写入。
     */
    public static int solveMovement(MemorySegment body, MemorySegment data, MemorySegment shapes,
                                    int count, int phase) {
        try {
            return (int) movement.invokeExact(body, data, shapes, count, phase);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 移动求解的准备阶段：把包围盒与请求位移写进原生包，并算出首次扫描盒。 */
    public static int prepareMovement(MemorySegment bounds, MemorySegment data) {
        try {
            return (int) prepareMovement.invokeExact(bounds, data);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 整箱实体查询，按原版的 section + 插入序返回。
     *
     * <p>{@code output} 接收 {@code capacity} 个 int（TempID 槽位）。返回个数；返回负值表示
     * 这次查询服务不了（例如输出容量不足）——调用方必须回退原版，不能当成空结果。
     * 与其它导出不同，这里不抛异常：原生出问题时静默降级，绝不把异常带进实体 tick。
     */
    public static int queryEntitiesInBox(MemorySegment context,
                                         double minX, double minY, double minZ,
                                         double maxX, double maxY, double maxZ,
                                         MemorySegment output, int capacity) {
        try {
            return (int) queryEntities.invokeExact(context, minX, minY, minZ, maxX, maxY, maxZ, output, capacity);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Executes the native push run over the given body array; velocities are modified in place. */
    public static int executePushRun(MemorySegment bodies, int capacity, int sourceSlot,
                                     MemorySegment targetSlots, int count) {
        try {
            return (int) executeRun.invokeExact(bodies, capacity, sourceSlot, targetSlots, count);
        } catch (Throwable t) {
            throw new IllegalStateException("executePushRun failed", t);
        }
    }

    /**
     * Whole-level batch push over every candidate pair; velocities and velocityVersion are
     * modified in place. {@code neighborCounts} receives count ints: intersecting pushable
     * partners per slot (for vanilla cramming).
     *
     * <p>{@code aabbs} holds 6 doubles per slot (minX, minY, minZ, maxX, maxY, maxZ) taken from
     * {@code Entity#getBoundingBox()}. The native side admits a pair only when those boxes
     * intersect, matching the vanilla {@code getEntities} filter, and uses the X extents to
     * skip the pairs that cannot reach each other.
     *
     * <p>Pass the LIVE entity count, not the buffer capacity, so stale tail rows never participate.
     */
    public static int executeFullPushRun(MemorySegment bodies, int liveCount, MemorySegment neighborCounts,
                                         MemorySegment aabbs, int partnerCap) {
        try {
            return (int) fullPushRun.invokeExact(bodies, liveCount, neighborCounts, aabbs, partnerCap);
        } catch (Throwable t) {
            throw new IllegalStateException("executeFullPushRun failed", t);
        }
    }

    /**
     * GPU 推挤后端的第一端：原生按与 {@code executeFullPushRun} 完全相同的构造器建扫描序列，
     * 并把这一帧压成两个可直接上传的数组（{@code packed} 每槽 12 个 double，
     * {@code meta} 每槽 4 个 int，其中第 4 个是该槽在 {@code order} 中的位置，-1 表示不进序列）。
     * {@code outLive} 收扫描序列长度。
     *
     * <p>{@code order} 必须至少能装下 liveCount 个 int，{@code packed} / {@code meta} 至少
     * {@code liveCount*12} 个 double 与 {@code liveCount*4} 个 int。
     */
    public static int prepareGpuPush(MemorySegment bodies, int liveCount, MemorySegment aabbs,
                                     MemorySegment order, MemorySegment outLive,
                                     MemorySegment packed, MemorySegment meta, MemorySegment range,
                                     int partnerCap) {
        try {
            return (int) prepareGpuPush.invokeExact(bodies, liveCount, aabbs, order, outLive,
                    packed, meta, range, partnerCap);
        } catch (Throwable t) {
            throw new IllegalStateException("prepareGpuPush failed", t);
        }
    }

    /**
     * GPU 推挤后端的第二端：把内核结果搬回 body 行，顺带写出每槽的相交伙伴数
     * （{@code outAux} 每槽 3 个 int：伙伴数、被接受的推挤次数、needsSync）。
     */
    public static int applyGpuPush(MemorySegment bodies, int liveCount, MemorySegment outVel,
                                   MemorySegment outAux, MemorySegment neighborCounts) {
        try {
            return (int) applyGpuPush.invokeExact(bodies, liveCount, outVel, outAux, neighborCounts);
        } catch (Throwable t) {
            throw new IllegalStateException("applyGpuPush failed", t);
        }
    }

    /**
     * GPU 推挤的差分对拍（调试用）：原生把 body 行先存一份，就用同一批行跑一遍 CPU 全量推
     * 当参考，逐位比对内核结果，再把快照还原（帧里留下的仍是 GPU 的结果）。
     * {@code outDiff} 收不一致的字段数，一致时为 0。
     *
     * <p>比对用原始位（memcmp），{@code -0.0/+0.0} 与 NaN 的差别也算不一致；比较字段覆盖
     * vx/vy/vz、伙伴数、velocityVersion 增量与 needsSync。
     */
    public static int verifyGpuPush(MemorySegment bodies, int liveCount, MemorySegment aabbs,
                                    MemorySegment neighborCounts, MemorySegment outVel,
                                    MemorySegment outAux, MemorySegment outDiff, int partnerCap) {
        try {
            return (int) verifyGpuPush.invokeExact(bodies, liveCount, aabbs, neighborCounts,
                    outVel, outAux, outDiff, partnerCap);
        } catch (Throwable t) {
            throw new IllegalStateException("verifyGpuPush failed", t);
        }
    }

    public static void destroy() {
        library = null;
        nativeArena = null;
        linker = null;
        initialized = false;
        available = false;
    }
}