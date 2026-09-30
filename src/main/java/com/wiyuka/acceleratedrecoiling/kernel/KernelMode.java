package com.wiyuka.acceleratedrecoiling.kernel;

/**
 * Kernel selection for the merged collision optimizer.
 *
 * <ul>
 *   <li>{@link #VANILLA} - below the density threshold, the vanilla collision path is used unchanged.</li>
 *   <li>{@link #PARITY} - a pure Java engine that reproduces vanilla candidate set and order exactly
 *       (ported from Entity Collision Optimizer semantics).</li>
 *   <li>{@link #NATIVE} - 与 AUTO 同路：ECO 帧引擎的原生全量推，一个不漏，推挤异步计算。</li>
 *   <li>{@link #GPU} - AcceleratedRecoiling 的 OpenCL 后端在 GPU 上枚举候选对，再由 Java 逐对
 *       {@code doPush}。<b>这是对比模式，不是等价模式</b>：候选集由 GPU 侧 2.5 格的立方哈希网格决定，
 *       与原版 {@code getEntities} 的候选集不保证一致（可能多推或少推）。只用来横向比较速度。</li>
 *   <li>{@link #SPARSE} - <b>实验档</b>：ECO 引擎 + AR 那种「漏」——每个实体最多保留
 *       {@code maxCollision} 个对手，超出的丢弃。丢推挤换速度，生电量级的堆积下才明显。</li>
 *   <li>{@link #AUTO} - <b>默认档</b>：走 ECO 帧引擎（同 NATIVE）。它一度走 GPU 候选枚举，
 *       那是因为当时引擎更慢；引擎修好并改成异步之后结论反过来，详见 {@code FoldConfig} 的注释。</li>
 * </ul>
 */
public enum KernelMode {
    AUTO,
    PARITY,
    NATIVE,
    VANILLA,
    GPU,
    SPARSE;

    public static KernelMode fromName(String name) {
        if (name == null || name.isBlank()) return AUTO;
        // 缓存：这个方法在热路径上被每实体每 tick 调一次（pushEntities），而 trim+toUpperCase
        // 每次会分配两个字符串——2000 实体就是每 tick 四千次小对象分配，纯属白烧。
        // 缓存放一个不可变持有对象里用 volatile 引用整体替换，避免「新名字配旧模式」的撕裂读。
        ModeRef ref = CACHED;
        if (ref != null && ref.name.equals(name)) {
            return ref.mode;
        }
        KernelMode parsed;
        try {
            parsed = valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            parsed = AUTO;
        }
        CACHED = new ModeRef(name, parsed);
        return parsed;
    }

    /** 名字 → 模式的缓存条目；整体替换，不做字段级更新。 */
    private record ModeRef(String name, KernelMode mode) {
    }

    private static volatile ModeRef CACHED;

    /**
     * 严格解析：名字不合法时返回 null。
     *
     * <p>命令路径必须用这个而不是 {@link #fromName}——后者对无法识别的名字会**静默回退 AUTO**，
     * 打错一个字母不会得到任何提示，只会让人以为「改了没效果」。
     * 运行时读配置仍用 {@code fromName}（配置坏了也要能起服）。
     */
    public static KernelMode parseOrNull(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 合法取值列表，用于命令补全与报错提示：{@code AUTO|PARITY|NATIVE|VANILLA|GPU}。 */
    public static String names() {
        StringBuilder builder = new StringBuilder();
        for (KernelMode mode : values()) {
            if (builder.length() > 0) builder.append('|');
            builder.append(mode.name());
        }
        return builder.toString();
    }
}