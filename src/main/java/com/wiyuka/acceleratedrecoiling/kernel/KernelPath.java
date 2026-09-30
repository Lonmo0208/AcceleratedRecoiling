package com.wiyuka.acceleratedrecoiling.kernel;

import com.wiyuka.acceleratedrecoiling.natives.NativeInterface;

/**
 * 把「内核档位」翻译成「这台机器上实际该走哪条路」。
 *
 * <p>要解决的问题：GPU 只有客户端那台机器有（OpenCL），专用服务端通常没有。
 * 所以不该把默认值写死成 GPU 或写死成引擎，而应该让 AUTO 自己判断——
 * **能加载 OpenCL 就走 GPU 候选枚举，加载不了就走 ECO 帧引擎**。
 * 这样客户端（单机内置服务端）与专用服务端用的是同一份配置、同一个默认档。
 *
 * <p>判定结果缓存：探测本身要建 OpenCL 上下文并编译内核（一次性百毫秒级），
 * 而这条判断在热路径上每 tick 每实体都会被问到。用三态 int 缓存，失败也记住，
 * 不会反复重试。
 *
 * <p>显式指定 {@code kernelMode=GPU} 时同样做可用性确认：**加载不成功就让调用方
 * 落到引擎/原版路径**，绝不允许「档位说是 GPU、候选表却是空的、结果一个都不推」。
 */
public final class KernelPath {

    private static final int UNKNOWN = 0;
    private static final int AVAILABLE = 1;
    private static final int UNAVAILABLE = 2;

    private static volatile int gpuState = UNKNOWN;

    private KernelPath() {
    }

    /**
     * 本机能否真正跑 GPU 候选枚举（首次调用会做一次 OpenCL 探测）。
     *
     * <p><b>⚠ 这个「探测」有全局副作用</b>：它借 {@code NativeInterface.forceBackend(GPU)}
     * 来试，而那会**真的把全局后端切成 GPU**。也就是说一个本该只读的可用性判断会改状态。
     * 现在只有 {@code kernelMode=GPU} 会走到它（{@code AUTO} 已不走这条路），影响面被限制住了；
     * 但如果以后有人在这里加调用点，先想清楚「探测」会不会把后端改掉。
     * 结果本身是三态缓存，只探一次，失败也记住。
     */
    public static boolean gpuUsable() {
        int state = gpuState;
        if (state == UNKNOWN) {
            state = NativeInterface.forceBackend(NativeInterface.BackendType.GPU)
                    ? AVAILABLE : UNAVAILABLE;
            gpuState = state;
        }
        return state == AVAILABLE;
    }

    /**
     * 本档是否走 AR 的 GPU 候选枚举（枚举出来的对再交给原版 doPush）。
     *
     * <p><b>只有显式 {@code GPU} 档走这里。</b>候选集由 GPU 侧 2.5 格的立方哈希网格决定，
     * 会漏配，而且实测比引擎慢（2088 实体约 25.8 ms 对 20.9 ms），所以它只作对照档。
     *
     * <p>{@code AUTO} 曾经也走这条路（当时引擎要 45.8 ms、比它慢 20 ms）。引擎修好
     * （§40 那个丢摩擦的 bug）又改成异步之后，结论反过来了：又快又不漏，所以 AUTO 改走引擎。
     */
    public static boolean useGpu(KernelMode mode) {
        return mode == KernelMode.GPU && gpuUsable();
    }

    /**
     * 本档是否走 ECO 帧引擎（原生全量推，异步计算）。
     *
     * <p>{@code AUTO}（默认）、{@code NATIVE}、{@code SPARSE} 都走这里；
     * {@code VANILLA}/{@code PARITY} 有各自的路径，不归这里管。
     * 显式要求 GPU 但本机不可用时也会落到引擎，而不是什么都不推。
     *
     * <p>引擎内部还能再选推挤算力放显卡还是 CPU（{@code FoldConfig.gpuEnginePush}），
     * 那条只影响引擎自己，不改变这里的选路。
     */
    public static boolean useEngine(KernelMode mode) {
        if (mode == KernelMode.VANILLA || mode == KernelMode.PARITY) {
            return false;
        }
        return !useGpu(mode);
    }
}
