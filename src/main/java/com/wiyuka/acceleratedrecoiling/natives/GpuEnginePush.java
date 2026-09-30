package com.wiyuka.acceleratedrecoiling.natives;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import org.jocl.*;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.jocl.CL.*;

/**
 * ECO 原生推挤引擎的 OpenCL 后端：把全量推的计算整段搬上显卡。
 *
 * <p><b>为什么不是「枚举走 GPU、冲量走 CPU」</b>：那样每次推挤要按对做一次
 * Java→原生→Java 的往返，且枚举出来的候选对还要再按 body 行（104 字节）随机读一次。
 * 这里改成整段搬运：原生一次性把这一帧压成两个数组（{@code prepareGpuPush}），
 * 内核算完，再由原生一次性写回（{@code applyGpuPush}）。每 tick 只有 3 次上传、
 * 2 次下载，Java 侧一次逐元素循环都没有。
 *
 * <p><b>与 CPU 引擎逐位一致</b>：全量推对每一对只走一次，源是扫描序列里靠前的那个。
 * 因此单个实体的冲量累加次序是固定的——先（作为后项）按序吸收它前面的伙伴，再
 * （作为前项）吸收它后面的。内核让一个 work-item 负责一个实体、按 {@code order[]}
 * 指定的同一次序串行累加，浮点求和的每一步都与 CPU 相同。之所以能做到，是因为冲量
 * 只读推挤开始前的坐标（{@code push()} 只改速度、不改位置），它是帧首状态的纯函数。
 *
 * <p>编译内核时没有开 {@code -cl-fast-relaxed-math}：浮点重结合会立刻破坏上面这条
 * 逐位一致性，省下的那点算力不值得。
 */
public final class GpuEnginePush {

    private GpuEnginePush() {
    }

    /** 0 = 还没探测，1 = 可用，2 = 不可用（不再重试）。 */
    private static volatile int status = 0;

    private static cl_context context;
    private static cl_device_id device;
    private static cl_program program;
    private static cl_kernel kernel;
    private static cl_command_queue queue;
    private static String deviceName = "";

    private static cl_mem memPacked;
    private static cl_mem memMeta;
    private static cl_mem memOrder;
    private static cl_mem memRange;
    private static cl_mem memVel;
    private static cl_mem memAux;
    private static int allocated;

    /** 连续失败几次就永久回退 CPU：驱动一旦坏掉，不要每 tick 都去撞一次。 */
    private static int consecutiveFailures;
    private static final int FAILURE_LIMIT = 8;

    /** 不可用时的具体原因。必须区别于「本机没有显卡」——内核编译失败看起来是一样的。 */
    private static volatile String failureReason = "";

    /**
     * 上一次推挤的两段耗时（纳秒）：提交各条命令的 CPU 开销，以及最后一次阻塞读拿结果的等待。
     * 这两段差别很大：前者是驱动调用开销，后者才是显卡真正干活（含传输）的时间。
     */
    private static long submitNanos;
    private static long waitNanos;

    public static long submitNanos() {
        return submitNanos;
    }

    public static long waitNanos() {
        return waitNanos;
    }

    private static final String KERNEL_SOURCE =
            """
                    #pragma OPENCL EXTENSION cl_khr_fp64 : enable

                    #define ECO_PUSHABLE 1
                    #define ECO_VEHICLE 2
                    #define ECO_PASSENGER 4
                    #define ECO_SLEEPING 8
                    #define ECO_NO_PHYSICS 16

                    // state/entity_metadata.h：ALWAYS=0, NEVER=1, PUSH_OWN_TEAM=2, PUSH_OTHER_TEAMS=3
                    #define ECO_RULE_ALWAYS 0
                    #define ECO_RULE_NEVER 1
                    #define ECO_RULE_OWN 2
                    #define ECO_RULE_OTHER 3

                    #define ECO_PUSH_EPSILON 0.009999999776482582
                    #define ECO_PUSH_SCALE 0.05000000074505806

                    inline int ecoAccepts(int state) {
                        return (state & (ECO_PUSHABLE | ECO_VEHICLE)) == ECO_PUSHABLE;
                    }

                    // 与 push_math.h 的 teamAccepts 同一套位运算，reserved 打包 (teamId<<2)|rule。
                    inline int ecoTeamAccepts(int sTeam, int sRule, int tTeam, int tRule) {
                        int allied = (sTeam >= 0 && sTeam == tTeam);
                        uint allowed;
                        if (allied) {
                            allowed = (sRule == ECO_RULE_NEVER || sRule == ECO_RULE_OWN)
                                    ? 0u : (1u << ECO_RULE_ALWAYS) | (1u << ECO_RULE_OTHER);
                        } else {
                            allowed = (sRule == ECO_RULE_NEVER || sRule == ECO_RULE_OTHER)
                                    ? 0u : (1u << ECO_RULE_ALWAYS) | (1u << ECO_RULE_OWN);
                        }
                        return (allowed & (1u << tRule)) != 0;
                    }

                    // Entity.push(Entity)：每一步除法/乘法保持原版顺序。
                    inline int ecoImpulse(double sx, double sz, double tx, double tz,
                                          double* ox, double* oz) {
                        double x = sx - tx;
                        double z = sz - tz;
                        double maximum = fmax(fabs(x), fabs(z));
                        if (!(maximum >= ECO_PUSH_EPSILON)) return 0;
                        double root = sqrt(maximum);
                        x /= root;
                        z /= root;
                        double inverse = fmin(1.0, 1.0 / root);
                        x *= inverse;
                        z *= inverse;
                        x *= ECO_PUSH_SCALE;
                        z *= ECO_PUSH_SCALE;
                        *ox = x;
                        *oz = z;
                        return 1;
                    }

                    // packed 每槽 12 个 double：x,z,vx,vy,vz,minX,minY,minZ,maxX,maxY,maxZ
                    // meta   每槽 4 个 int：state,root,reserved,sortedPos
                    // range  每槽 2 个 int：该实体可能相交的 order 区间 [lo, hi)
                    // outVel 每槽 3 个 double：vx,vy,vz
                    // outAux 每槽 3 个 int：伙伴数,被接受的推挤次数,needsSync
                    __kernel void engine_push(
                            __global const double* packed,
                            __global const int* meta,
                            __global const int* order,
                            __global const int* range,
                            __global double* outVel,
                            __global int* outAux,
                            const int count,
                            const int live,
                            const int partnerCap) {
                        const int i = get_global_id(0);
                        if (i >= count) return;

                        const int stateI = meta[i * 4];
                        const int rootI = meta[i * 4 + 1];
                        const int resI = meta[i * 4 + 2];
                        const int posI = meta[i * 4 + 3];
                        // 内核里的局部指针默认是 __private 地址空间，从 __global 取址必须显式标注，
                        // 否则编译直接失败（CL_BUILD_PROGRAM_FAILURE，且报错只在构建日志里）。
                        const __global double* row = packed + (size_t)i * 12;

                        double vx = row[2];
                        double vy = row[3];
                        double vz = row[4];
                        int neighbors = 0;
                        int pushes = 0;
                        int sync = 0;

                        if (posI >= 0) {
                            const int selfPush = ecoAccepts(stateI);
                            // 原生按 X 轴几何算好的候选窗口：相交必然落在这一段里，
                            // 所以窗口外的不必扫。剪掉的都是本来也不相交的对。
                            const int lo = range[i * 2];
                            const int hi = range[i * 2 + 1];
                            // pass 0：j 在 i 之前，i 是这一对的后项（原版 target）。
                            // pass 1：i 是前项（原版 source）。次序不能颠倒。
                            for (int pass = 0; pass < 2; ++pass) {
                                const int from = (pass == 0) ? lo : (posI + 1);
                                const int to = (pass == 0) ? posI : hi;
                                for (int a = from; a < to; ++a) {
                                    const int j = order[a];
                                    const int stateJ = meta[j * 4];
                                    const int rootJ = meta[j * 4 + 1];
                                    const int resJ = meta[j * 4 + 2];
                                    const __global double* other = packed + (size_t)j * 12;

                                    const int iIsLater = (pass == 0) ? 1 : 0;
                                    const int stateLater = iIsLater ? stateI : stateJ;
                                    if (stateLater & (ECO_NO_PHYSICS | ECO_SLEEPING)) continue;
                                    const int rootEarlier = iIsLater ? rootJ : rootI;
                                    const int rootLater = iIsLater ? rootI : rootJ;
                                    if (((stateI | stateJ) & ECO_PASSENGER) && rootEarlier == rootLater) continue;
                                    const int stateEarlier = iIsLater ? stateJ : stateI;
                                    const int pushEarlier = ecoAccepts(stateEarlier);
                                    const int pushLater = ecoAccepts(stateLater);
                                    if (pushEarlier == 0 && pushLater == 0) continue;

                                    const int resEarlier = iIsLater ? resJ : resI;
                                    const int resLater = iIsLater ? resI : resJ;
                                    if (ecoTeamAccepts(resEarlier >> 2, resEarlier & 3,
                                                       resLater >> 2, resLater & 3) == 0) continue;

                                    // 原版 AABB.intersects：严格不等、无 epsilon、无膨胀。
                                    const __global double* e = iIsLater ? other : row;
                                    const __global double* l = iIsLater ? row : other;                                    if (!(e[5] < l[8] && e[8] > l[5]
                                            && e[6] < l[9] && e[9] > l[6]
                                            && e[7] < l[10] && e[10] > l[7])) continue;

                                    double px, pz;
                                    if (ecoImpulse(e[0], e[1], l[0], l[1], &px, &pz) == 0) continue;
                                    // SPARSE 档的「漏」：本实体最多保留 partnerCap 个对手。
                                    // 判据只按 i 自己算——CPU 那侧也是每个实体各自判断，
                                    // 所以 work-item 不必知道对方满没满（对方那一半由对方的
                                    // work-item 自己算）。partnerCap <= 0 表示不限。
                                    if (partnerCap > 0 && neighbors >= partnerCap) continue;
                                    ++neighbors;

                                    // i 在这两段里承接的都是 push()：位置不变，只有速度按
                                    // 原版 setDeltaMovement 的语义累加（和为非有限则整次不落盘，
                                    // 但 needsSync 已经置位）。
                                    if (selfPush != 0) {
                                        const double dx = iIsLater ? -px : px;
                                        const double dz = iIsLater ? -pz : pz;
                                        const double nvx = vx + dx;
                                        const double nvy = vy + 0.0;
                                        const double nvz = vz + dz;
                                        sync = 1;
                                        if (isfinite(nvx) && isfinite(nvy) && isfinite(nvz)) {
                                            vx = nvx;
                                            vy = nvy;
                                            vz = nvz;
                                            ++pushes;
                                        }
                                    }
                                }
                            }
                        }

                        outVel[i * 3] = vx;
                        outVel[i * 3 + 1] = vy;
                        outVel[i * 3 + 2] = vz;
                        outAux[i * 3] = neighbors;
                        outAux[i * 3 + 1] = pushes;
                        outAux[i * 3 + 2] = sync;
                    }
                    """;

    /** 显卡上的推挤是否可用（首次调用探测，之后缓存）。 */
    public static boolean available() {
        if (status == 0) {
            initialize();
        }
        return status == 1;
    }

    /** 已选中的显卡名，供 /check 展示；未初始化时返回空串。 */
    public static String deviceName() {
        return deviceName;
    }

    /** 不可用的具体原因（供 /check 展示）；可用时为空串。 */
    public static String failureReason() {
        return failureReason;
    }

    private static synchronized void initialize() {
        if (status != 0) {
            return;
        }
        try {
            setExceptionsEnabled(true);
            cl_platform_id targetPlatform = null;
            cl_device_id targetDevice = null;
            int[] platformCount = new int[1];
            clGetPlatformIDs(0, null, platformCount);
            if (platformCount[0] > 0) {
                cl_platform_id[] platforms = new cl_platform_id[platformCount[0]];
                clGetPlatformIDs(platforms.length, platforms, null);
                for (cl_platform_id platform : platforms) {
                    try {
                        int[] deviceCount = new int[1];
                        clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 0, null, deviceCount);
                        if (deviceCount[0] > 0) {
                            cl_device_id[] devices = new cl_device_id[deviceCount[0]];
                            clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devices.length, devices, null);
                            targetPlatform = platform;
                            targetDevice = devices[0];
                            break;
                        }
                    } catch (CLException ignored) {
                        // 这个平台没有 GPU，换下一个。
                    }
                }
            }
            if (targetPlatform == null || targetDevice == null) {
                status = 2;
                failureReason = "本机没有 OpenCL GPU";
                AcceleratedRecoiling.LOGGER.info("[ECO-GPU] 没有可用的 OpenCL GPU，推挤继续走 CPU 原生。");
                return;
            }
            long[] nameSize = new long[1];
            clGetDeviceInfo(targetDevice, CL_DEVICE_NAME, 0, null, nameSize);
            byte[] nameBuffer = new byte[(int) nameSize[0]];
            clGetDeviceInfo(targetDevice, CL_DEVICE_NAME, nameBuffer.length, Pointer.to(nameBuffer), null);
            deviceName = new String(nameBuffer, 0, Math.max(0, nameBuffer.length - 1)).trim();

            cl_context_properties props = new cl_context_properties();
            props.addProperty(CL_CONTEXT_PLATFORM, targetPlatform);
            context = clCreateContext(props, 1, new cl_device_id[]{targetDevice}, null, null, null);
            program = clCreateProgramWithSource(context, 1, new String[]{KERNEL_SOURCE}, null, null);
            // 不开 fast-relaxed-math：浮点重结合会破坏与 CPU 的逐位一致性。
            // 但 -cl-fp32-correctly-rounded-divide-sqrt 属于「设备可以不支持」的可选构建选项，
            // 不被支持时整个构建会以 CL_BUILD_PROGRAM_FAILURE 收场（这里踩过一次：内核没编出来，
            // 推挤静默退回 CPU，而 /check 上只看到「没有可用显卡」）。所以带选项失败就退回默认重编。
            try {
                clBuildProgram(program, 0, null, "-cl-fp32-correctly-rounded-divide-sqrt", null, null);
            } catch (CLException first) {
                AcceleratedRecoiling.LOGGER.warn("[ECO-GPU] 带正确舍入选项构建失败，改用默认选项重编：{}",
                        first.getMessage());
                clReleaseProgram(program);
                program = clCreateProgramWithSource(context, 1, new String[]{KERNEL_SOURCE}, null, null);
                clBuildProgram(program, 0, null, null, null, null);
            }
            kernel = clCreateKernel(program, "engine_push", null);
            queue = clCreateCommandQueueWithProperties(context, targetDevice, null, null);
            device = targetDevice;
            status = 1;
            AcceleratedRecoiling.LOGGER.info("[ECO-GPU] 推挤引擎已放到显卡上：{}", deviceName);
        } catch (Throwable t) {
            status = 2;
            String log = programBuildLog();
            failureReason = t.getClass().getSimpleName()
                    + (log.isEmpty() ? "" : "：" + log.split("\n")[0]);
            AcceleratedRecoiling.LOGGER.warn("[ECO-GPU] OpenCL 推挤后端初始化失败，继续走 CPU 原生：{}",
                    t.toString());
            // 没有编译器日志就只能猜；CL_BUILD_PROGRAM_FAILURE 的正文全在这里。
            for (String line : log.split("\n")) {
                AcceleratedRecoiling.LOGGER.warn("[ECO-GPU] 构建日志: {}", line);
            }
        }
    }

    /** 取 OpenCL 编译器针对当前程序给出的日志（失败时才有内容）。 */
    private static String programBuildLog() {
        try {
            if (program == null || device == null) {
                return "";
            }
            long[] size = new long[1];
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, 0, null, size);
            if (size[0] <= 1) {
                return "";
            }
            byte[] buffer = new byte[(int) size[0]];
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, buffer.length,
                    Pointer.to(buffer), null);
            return new String(buffer, 0, Math.max(0, buffer.length - 1)).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 执行一次 GPU 全量推。输入段由 {@code EcoEngine.prepareGpuPush} 填好，结果段由
     * {@code EcoEngine.applyGpuPush} 搬回 body 行。返回 false 表示这一帧没做成（调用方回退 CPU）。
     */
    public static synchronized boolean push(MemorySegment packed, MemorySegment meta,
                                            MemorySegment order, MemorySegment range,
                                            MemorySegment outVel, MemorySegment outAux,
                                            int count, int live, int partnerCap) {
        if (status != 1 || count <= 0) {
            return false;
        }
        try {
            ensureBuffers(count);
            long submitStart = System.nanoTime();
            ByteBuffer packedBuf = packed.asByteBuffer().order(ByteOrder.nativeOrder());
            ByteBuffer metaBuf = meta.asByteBuffer().order(ByteOrder.nativeOrder());
            ByteBuffer orderBuf = order.asByteBuffer().order(ByteOrder.nativeOrder());
            ByteBuffer rangeBuf = range.asByteBuffer().order(ByteOrder.nativeOrder());
            ByteBuffer velBuf = outVel.asByteBuffer().order(ByteOrder.nativeOrder());
            ByteBuffer auxBuf = outAux.asByteBuffer().order(ByteOrder.nativeOrder());
            // 同一条 in-order 队列：上传不阻塞，直到最后一次下载才真正等结果。
            clEnqueueWriteBuffer(queue, memPacked, CL_FALSE, 0, (long) count * 12 * Sizeof.cl_double,
                    Pointer.to(packedBuf), 0, null, null);
            clEnqueueWriteBuffer(queue, memMeta, CL_FALSE, 0, (long) count * 4 * Sizeof.cl_int,
                    Pointer.to(metaBuf), 0, null, null);
            clEnqueueWriteBuffer(queue, memOrder, CL_FALSE, 0, (long) live * Sizeof.cl_int,
                    Pointer.to(orderBuf), 0, null, null);
            clEnqueueWriteBuffer(queue, memRange, CL_FALSE, 0, (long) count * 2 * Sizeof.cl_int,
                    Pointer.to(rangeBuf), 0, null, null);

            clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(memPacked));
            clSetKernelArg(kernel, 1, Sizeof.cl_mem, Pointer.to(memMeta));
            clSetKernelArg(kernel, 2, Sizeof.cl_mem, Pointer.to(memOrder));
            clSetKernelArg(kernel, 3, Sizeof.cl_mem, Pointer.to(memRange));
            clSetKernelArg(kernel, 4, Sizeof.cl_mem, Pointer.to(memVel));
            clSetKernelArg(kernel, 5, Sizeof.cl_mem, Pointer.to(memAux));
            clSetKernelArg(kernel, 6, Sizeof.cl_int, Pointer.to(new int[]{count}));
            clSetKernelArg(kernel, 7, Sizeof.cl_int, Pointer.to(new int[]{live}));
            clSetKernelArg(kernel, 8, Sizeof.cl_int, Pointer.to(new int[]{partnerCap}));
            clEnqueueNDRangeKernel(queue, kernel, 1, null, new long[]{count}, null, 0, null, null);

            clEnqueueReadBuffer(queue, memVel, CL_FALSE, 0, (long) count * 3 * Sizeof.cl_double,
                    Pointer.to(velBuf), 0, null, null);
            long submitEnd = System.nanoTime();
            submitNanos = submitEnd - submitStart;
            // 前面几条都是非阻塞提交，真正的等待全落在最后一次阻塞读上。
            clEnqueueReadBuffer(queue, memAux, CL_TRUE, 0, (long) count * 3 * Sizeof.cl_int,
                    Pointer.to(auxBuf), 0, null, null);
            waitNanos = System.nanoTime() - submitEnd;
            consecutiveFailures = 0;
            return true;
        } catch (Throwable t) {
            if (++consecutiveFailures >= FAILURE_LIMIT) {
                status = 2;
                failureReason = "连续 " + consecutiveFailures + " 帧推挤失败：" + t;
                AcceleratedRecoiling.LOGGER.warn("[ECO-GPU] 连续 {} 帧推挤失败，已停用 GPU 推挤：{}",
                        consecutiveFailures, t.toString());
            }
            return false;
        }
    }

    private static void ensureBuffers(int count) {
        if (allocated >= count) {
            return;
        }
        releaseBuffers();
        int next = Math.max(64, Integer.highestOneBit(count) << 1);
        memPacked = clCreateBuffer(context, CL_MEM_READ_ONLY, (long) next * 12 * Sizeof.cl_double, null, null);
        memMeta = clCreateBuffer(context, CL_MEM_READ_ONLY, (long) next * 4 * Sizeof.cl_int, null, null);
        memOrder = clCreateBuffer(context, CL_MEM_READ_ONLY, (long) next * Sizeof.cl_int, null, null);
        memRange = clCreateBuffer(context, CL_MEM_READ_ONLY, (long) next * 2 * Sizeof.cl_int, null, null);
        memVel = clCreateBuffer(context, CL_MEM_WRITE_ONLY, (long) next * 3 * Sizeof.cl_double, null, null);
        memAux = clCreateBuffer(context, CL_MEM_WRITE_ONLY, (long) next * 3 * Sizeof.cl_int, null, null);
        allocated = next;
    }

    private static void releaseBuffers() {
        if (memPacked != null) clReleaseMemObject(memPacked);
        if (memMeta != null) clReleaseMemObject(memMeta);
        if (memOrder != null) clReleaseMemObject(memOrder);
        if (memRange != null) clReleaseMemObject(memRange);
        if (memVel != null) clReleaseMemObject(memVel);
        if (memAux != null) clReleaseMemObject(memAux);
        memPacked = null;
        memMeta = null;
        memOrder = null;
        memRange = null;
        memVel = null;
        memAux = null;
        allocated = 0;
    }
}
