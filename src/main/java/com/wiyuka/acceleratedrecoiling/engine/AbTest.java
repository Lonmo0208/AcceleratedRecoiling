package com.wiyuka.acceleratedrecoiling.engine;

import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动交替对照：每隔固定 tick 数在两种 kernelMode 之间来回切，逐段记录维度 tick 耗时，
 * 最后报各档均值与逐段明细。
 *
 * <p>为什么需要它：手动「切档 → 等 → 读」会被场景漂移骗——切档的同一时刻猪群可能正在
 * 沉降或区块正在加载，于是「换档变快了」可能只是场景变了。实测已出现同一档位内部
 * {@code collide 单次} 摆动 10 倍的情况。交替对照让场景漂移对两档的影响均摊，
 * 而且逐段明细能看出是单调改善还是来回摆动。
 *
 * <p>只统计命令发起时所在的那个维度；每段记录该段内每次 tick 的均值。
 */
public final class AbTest {

    private static boolean running;
    /** 自动开跑只触发一次，避免每个维度/每次加载都重开一轮。 */
    private static boolean autoStarted;
    private static ServerLevel target;
    private static CommandSourceStack reporter;
    private static String modeA = "AUTO";
    private static String modeB = "GPU";
    private static int ticksPerPhase = 100;
    private static int phaseCount = 6;
    private static int phaseIndex;
    private static int ticksInPhase;
    private static double phaseSum;
    private static double phaseEntity, phaseEngine, phaseMove, phaseCollide;
    /** 本段平均速度与平均每 tick 加起来的「速度总和」，用来判断畜群是不是动得更多。 */
    private static double phaseSpeedSum;
    private static double lastAvgSpeed;
    private static final List<Double> seriesA = new ArrayList<>();
    private static final List<Double> seriesB = new ArrayList<>();

    private AbTest() {
    }

    /**
     * 每 tick 采样一次全场活体实体的平均速度（只在 {@code tickProfiling} 打开时调用）。
     *
     * <p>为什么需要这个读数：引擎与保真参照的 {@code move/collide} 差了 2 倍以上，而这两个
     * 数字只说明「碰撞解算贵」，说不清是「实体动得更多」还是「同样的运动被算得更贵」。
     * 平均速度是区分这两者的直接证据——畜群被推得越凶，这个数越大。
     */
    public static void observeSpeed(ServerLevel level) {
        if (!FoldConfig.tickProfiling || level == null) {
            return;
        }
        double sum = 0.0D;
        int counted = 0;
        for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
            if (entity instanceof net.minecraft.world.entity.LivingEntity) {
                sum += entity.getDeltaMovement().length();
                counted++;
            }
        }
        lastAvgSpeed = counted == 0 ? 0.0D : sum / counted;
        if (running && level == target) {
            phaseSpeedSum += lastAvgSpeed;
        }
    }

    /** 最近一次采样的全场平均速度（m/tick）。 */
    public static double lastAvgSpeed() {
        return lastAvgSpeed;
    }

    public static boolean isRunning() {
        return running;
    }

    /** @return 0 表示参数不合法，1 表示已开始 */
    public static int start(CommandSourceStack source, String a, String b, int ticks, int phases) {
        return start(source == null ? null : source.getLevel(), source, a, b, ticks, phases);
    }

    /**
     * 自动开跑：配置了 {@code autoAbTest="<A> <B> <每段tick> <段数>"} 时，世界加载后自己跑一轮。
     *
     * <p>为什么不让数据包来驱动：存档里的数据包状态、加载函数是否触发都不受模组控制，
     * 曾经因此出现过「以为在跑基准、其实一条都没执行」。走配置就与存档无关，任何世界都能复现。
     * 结果不需要命令来源，全在日志里（{@code [AB]} 前缀）。
     */
    public static void autoStart(ServerLevel level) {
        if (running || autoStarted) {
            return;
        }
        String spec = FoldConfig.autoAbTest;
        if (spec == null || spec.isBlank()) {
            return;
        }
        String[] parts = spec.trim().split("\\s+");
        if (parts.length < 4) {
            return;
        }
        try {
            autoStarted = true;
            int ticks = Integer.parseInt(parts[2]);
            int phases = Integer.parseInt(parts[3]);
            if (start(level, null, parts[0], parts[1], ticks, phases) == 0) {
                com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling.LOGGER.warn(
                        "[AB] autoAbTest 参数不合法：{}", spec);
            } else {
                com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling.LOGGER.info(
                        "[AB] 自动开跑：{} vs {}，每段 {} tick × {} 段", parts[0], parts[1], ticks, phases);
            }
        } catch (NumberFormatException ignored) {
            // 参数写错就当没配置，绝不影响主循环。
        }
    }

    /** @return 0 表示参数不合法，1 表示已开始 */
    private static int start(ServerLevel level, CommandSourceStack source,
                             String a, String b, int ticks, int phases) {
        if (a == null || b == null || a.equals(b) || ticks <= 0 || phases < 2 || level == null) {
            return 0;
        }
        modeA = a;
        modeB = b;
        ticksPerPhase = ticks;
        phaseCount = phases;
        phaseIndex = 0;
        ticksInPhase = 0;
        phaseSum = 0.0D;
        seriesA.clear();
        seriesB.clear();
        reporter = source;
        target = level;
        running = true;
        applyMode(a);
        return 1;
    }

    /**
     * 把对照档名落到配置上。
     *
     * <p>{@code ENGINE_GPU} / {@code ENGINE_CPU} 是同一个引擎的两种推挤后端（原生 vs 显卡）。
     *
     * <p>{@code ENGINE_ASYNC} / {@code ENGINE_SYNC} 是同一个引擎的两种**发布时刻**：
     * 前者异步算、tick 末尾批量落盘；后者同步算、在实体自己的 {@code pushEntities} 上逐实体落盘
     * （落点与原版「轮到谁推谁」一致）。要量「时序保真值不值那几毫秒」只能这样切。
     */
    private static void applyMode(String mode) {
        if ("ENGINE_GPU".equalsIgnoreCase(mode)) {
            FoldConfig.kernelMode = "NATIVE";
            FoldConfig.gpuEnginePush = true;
        } else if ("ENGINE_CPU".equalsIgnoreCase(mode)) {
            FoldConfig.kernelMode = "NATIVE";
            FoldConfig.gpuEnginePush = false;
        } else if ("ENGINE_ASYNC".equalsIgnoreCase(mode)) {
            FoldConfig.kernelMode = "NATIVE";
            FoldConfig.asyncPush = true;
        } else if ("ENGINE_SYNC".equalsIgnoreCase(mode)) {
            FoldConfig.kernelMode = "NATIVE";
            FoldConfig.asyncPush = false;
        } else {
            FoldConfig.kernelMode = mode;
        }
    }

    /** 在维度 tick 结束时调用（维度层数据已结算）。 */
    public static void onLevelTick(ServerLevel level) {
        if (!running || level != target) {
            return;
        }
        TickStats.Snapshot last = TickStats.levelLast(level);
        phaseSum += last.tickMs;
        // 一并累计拆解：光知道哪个档快不够，还要知道差额落在哪个环节
        phaseEntity += last.entityMs;
        phaseEngine += last.engineMs;
        phaseMove += last.moveMs;
        phaseCollide += last.collideMs;
        ticksInPhase++;
        if (ticksInPhase < ticksPerPhase) {
            return;
        }
        double mean = phaseSum / ticksInPhase;
        boolean isA = (phaseIndex % 2) == 0;
        (isA ? seriesA : seriesB).add(mean);
        // 每段结束就打一条日志：这样即便命令来源是函数（sendSuccess 不会进日志），
        // 也能从 run\logs\latest.log 里读到逐段结果。
        com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling.LOGGER.info(
                "[AB] phase {} mode={} tick={} entity={} engine={} move={} collide={} speed={}",
                phaseIndex + 1, isA ? modeA : modeB,
                f(mean), f(phaseEntity / ticksInPhase), f(phaseEngine / ticksInPhase),
                f(phaseMove / ticksInPhase), f(phaseCollide / ticksInPhase),
                f(phaseSpeedSum / ticksInPhase));
        phaseIndex++;
        ticksInPhase = 0;
        phaseSum = 0.0D;
        phaseEntity = 0.0D;
        phaseEngine = 0.0D;
        phaseMove = 0.0D;
        phaseCollide = 0.0D;
        phaseSpeedSum = 0.0D;
        if (phaseIndex >= phaseCount) {
            finish();
            return;
        }
        applyMode(((phaseIndex % 2) == 0) ? modeA : modeB);
    }

    private static void finish() {
        running = false;
        StringBuilder text = new StringBuilder();
        text.append("AB 对照完成：每段 ").append(ticksPerPhase).append(" tick × ")
                .append(phaseCount).append(" 段\n");
        appendSeries(text, modeA, seriesA);
        appendSeries(text, modeB, seriesB);
        double meanA = mean(seriesA);
        double meanB = mean(seriesB);
        text.append(String.format(java.util.Locale.ROOT, "  ⇒ %s %.2f ms  vs  %s %.2f ms",
                modeA, meanA, modeB, meanB));
        if (meanA > 0.0 && meanB > 0.0) {
            String lower = meanA < meanB ? modeA : modeB;
            text.append(String.format(java.util.Locale.ROOT, "（%s 低 %.2f ms）",
                    lower, Math.abs(meanA - meanB)));
        }
        try {
            if (reporter != null) {
                String message = text.toString();
                reporter.sendSuccess(() -> Component.literal(message), false);
            }
        } catch (Throwable ignored) {
            // 命令来源可能已失效，忽略
        }
        com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling.LOGGER.info(
                "[AB] finished:\n{}", text.toString().replace("\n", "\n[AB] "));
        reporter = null;
        target = null;
    }

    private static void appendSeries(StringBuilder text, String mode, List<Double> series) {
        text.append("  ").append(mode).append("：均值 ");
        text.append(String.format(java.util.Locale.ROOT, "%.2f ms", mean(series)));
        text.append("（").append(series.size()).append(" 段: ");
        for (int i = 0; i < series.size(); i++) {
            if (i > 0) text.append(" / ");
            text.append(String.format(java.util.Locale.ROOT, "%.1f", series.get(i)));
        }
        text.append("）\n");
    }

    private static double mean(List<Double> series) {
        if (series.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (double value : series) {
            sum += value;
        }
        return sum / series.size();
    }

    private static String f(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
