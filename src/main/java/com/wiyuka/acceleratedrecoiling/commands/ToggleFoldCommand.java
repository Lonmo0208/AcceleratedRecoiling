package com.wiyuka.acceleratedrecoiling.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.kernel.KernelMode;
import com.wiyuka.acceleratedrecoiling.engine.AbTest;
import com.wiyuka.acceleratedrecoiling.engine.EcoEngine;
import com.wiyuka.acceleratedrecoiling.engine.EcoFrame;
import com.wiyuka.acceleratedrecoiling.engine.TickStats;
import com.wiyuka.acceleratedrecoiling.natives.NativeInterface;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.annotation.Native;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public class ToggleFoldCommand {
    private static final String[] COMMAND_ALIAS = {"acceleratedrecoiling", "togglefold"};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String commandAlias : COMMAND_ALIAS) {
            registerCommand(dispatcher, commandAlias);
        }
    }

    private static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        LiteralArgumentBuilder<CommandSourceStack> baseCommand = Commands.literal(name)
                .requires(source -> source.hasPermission(2));

        baseCommand.then(Commands.literal("check").executes(ToggleFoldCommand::checkConfig));
        baseCommand.then(Commands.literal("save").executes(ToggleFoldCommand::save));
        baseCommand.then(Commands.literal("updateConfig").executes(ToggleFoldCommand::updateConfig));

        // 自动交替对照：默认 AUTO 与 GPU 各跑 3 段、每段 100 tick，共 600 tick（约 30 秒）。
        // 手动「切档→等→读」会被场景漂移骗，这个命令把切换和统计都交给程序。
        baseCommand.then(Commands.literal("abtest")
                .executes(ctx -> runAbTest(ctx, "AUTO", "GPU", 100, 6))
                .then(Commands.argument("modeA", StringArgumentType.word())
                        .suggests(KERNEL_MODE_SUGGESTIONS)
                        .then(Commands.argument("modeB", StringArgumentType.word())
                                .suggests(KERNEL_MODE_SUGGESTIONS)
                                .executes(ctx -> runAbTest(ctx,
                                        StringArgumentType.getString(ctx, "modeA"),
                                        StringArgumentType.getString(ctx, "modeB"), 100, 6))
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 2000))
                                        .executes(ctx -> runAbTest(ctx,
                                                StringArgumentType.getString(ctx, "modeA"),
                                                StringArgumentType.getString(ctx, "modeB"),
                                                IntegerArgumentType.getInteger(ctx, "ticks"), 6))
                                        .then(Commands.argument("phases", IntegerArgumentType.integer(2, 20))
                                                .executes(ctx -> runAbTest(ctx,
                                                        StringArgumentType.getString(ctx, "modeA"),
                                                        StringArgumentType.getString(ctx, "modeB"),
                                                        IntegerArgumentType.getInteger(ctx, "ticks"),
                                                        IntegerArgumentType.getInteger(ctx, "phases"))))))));

        // 一次改多对参数：/acceleratedrecoiling set kernelMode SPARSE asyncPush false maxCollision 24
        //
        // 为什么不用「值后面续接下一对字段」那种写法：Brigadier 只执行解析到的**最终节点**的
        // executes，中间节点的动作不会跑——那样写只有最后一对生效（踩过一次，日志里只剩最后一对）。
        // 所以这里收一整条贪心尾串自己解析，成对地应用。
        baseCommand.then(Commands.literal("set")
                .then(Commands.argument("pairs", StringArgumentType.greedyString())
                        .suggests(PAIR_SUGGESTIONS)
                        .executes(ToggleFoldCommand::applyPairs)));
        // 单对参数的快捷写法照旧：/acceleratedrecoiling kernelMode AUTO（布尔不给值即取反）。
        attachFieldNodes(baseCommand);

        dispatcher.register(baseCommand);
    }

    /** 把 {@code FoldConfig} 里所有可写字段挂成子节点（单对参数的快捷写法）。 */
    private static void attachFieldNodes(ArgumentBuilder<CommandSourceStack, ?> parent) {
        for (Field field : FoldConfig.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) continue;
            field.setAccessible(true);
            parent.then(buildFieldNode(field));
        }
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildFieldNode(Field field) {
        String fieldName = field.getName();
        Class<?> type = field.getType();
        LiteralArgumentBuilder<CommandSourceStack> fieldNode = Commands.literal(fieldName);

        if (type == boolean.class) {
            fieldNode.executes(ctx -> {
                try {
                    boolean currentValue = field.getBoolean(null);
                    return setFieldValue(ctx, field, !currentValue);
                } catch (IllegalAccessException e) {
                    return 0;
                }
            });
            fieldNode.then(Commands.argument("value", BoolArgumentType.bool())
                    .executes(ctx -> setFieldValue(ctx, field, BoolArgumentType.getBool(ctx, "value"))));
        } else if (type == int.class) {
            fieldNode.then(Commands.argument("value", IntegerArgumentType.integer())
                    .executes(ctx -> setFieldValue(ctx, field, IntegerArgumentType.getInteger(ctx, "value"))));
        } else if (type == float.class) {
            fieldNode.then(Commands.argument("value", FloatArgumentType.floatArg())
                    .executes(ctx -> setFieldValue(ctx, field, FloatArgumentType.getFloat(ctx, "value"))));
        } else if (type == double.class) {
            fieldNode.then(Commands.argument("value", DoubleArgumentType.doubleArg())
                    .executes(ctx -> setFieldValue(ctx, field, DoubleArgumentType.getDouble(ctx, "value"))));
        } else if (type == String.class) {
            if ("kernelMode".equals(fieldName)) {
                // 枚举型字符串：给 tab 补全，并拒绝非法取值。
                // 不能直接用 setFieldValue —— KernelMode.fromName 会对打错的名字静默回退 AUTO。
                fieldNode.then(Commands.argument("value", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String prefix = builder.getRemainingLowerCase();
                            for (KernelMode mode : KernelMode.values()) {
                                String modeName = mode.name();
                                if (modeName.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                                    builder.suggest(modeName);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> setKernelMode(ctx, field,
                                StringArgumentType.getString(ctx, "value"))));
            } else {
                fieldNode.then(Commands.argument("value", StringArgumentType.string())
                        .executes(ctx -> setFieldValue(ctx, field, StringArgumentType.getString(ctx, "value"))));
            }
        }

        return fieldNode;
    }

    /**
     * 应用 {@code set} 后面的「字段 值」序列，成对出现、可写多对。
     *
     * <p>先全部校验通过再应用：有一对不合格就报错并停止，不留下改了一半的状态。
     */
    private static int applyPairs(CommandContext<CommandSourceStack> context) {
        String raw = StringArgumentType.getString(context, "pairs").trim();
        if (raw.isEmpty()) {
            context.getSource().sendFailure(Component.literal("用法: set <字段> <值> [<字段> <值> ...]"));
            return 0;
        }
        String[] tokens = raw.split("\\s+");
        if (tokens.length % 2 != 0) {
            context.getSource().sendFailure(Component.literal(
                    "参数必须成对：「字段 值」；末尾多了一个 \"" + tokens[tokens.length - 1] + "\""));
            return 0;
        }
        List<Field> fields = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < tokens.length; i += 2) {
            Field field = findWritableField(tokens[i]);
            if (field == null) {
                context.getSource().sendFailure(Component.literal("没有这个可写字段: \"" + tokens[i] + "\""));
                return 0;
            }
            Object value = parseValue(tokens[i], field, tokens[i + 1]);
            if (value == null) {
                context.getSource().sendFailure(Component.literal(
                        "字段 " + tokens[i] + " 不接受这个取值: \"" + tokens[i + 1] + "\""));
                return 0;
            }
            fields.add(field);
            values.add(value);
        }
        int applied = 0;
        for (int i = 0; i < fields.size(); i++) {
            applied += setFieldValue(context, fields.get(i), values.get(i));
        }
        return applied;
    }

    private static Field findWritableField(String name) {
        for (Field field : FoldConfig.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) continue;
            if (!field.getName().equals(name)) continue;
            field.setAccessible(true);
            return field;
        }
        return null;
    }

    /** 按字段类型解析取值；解析不了返回 null（调用方统一报错）。 */
    private static Object parseValue(String fieldName, Field field, String token) {
        Class<?> type = field.getType();
        try {
            if (type == boolean.class) {
                if ("toggle".equalsIgnoreCase(token)) return !field.getBoolean(null);
                if ("true".equalsIgnoreCase(token)) return Boolean.TRUE;
                if ("false".equalsIgnoreCase(token)) return Boolean.FALSE;
                return null;
            }
            if (type == int.class) return Integer.valueOf(token);
            if (type == float.class) return Float.valueOf(token);
            if (type == double.class) return Double.valueOf(token);
            if (type == String.class) {
                if ("kernelMode".equals(fieldName)) {
                    KernelMode parsed = KernelMode.parseOrNull(token);
                    return parsed == null ? null : parsed.name();
                }
                return token;
            }
        } catch (NumberFormatException | IllegalAccessException ignored) {
            // 落到统一的报错
        }
        return null;
    }

    /** {@code set} 的补全：偶数位补字段名，奇数位按前一个字段的类型补取值。 */
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> PAIR_SUGGESTIONS =
            (ctx, builder) -> {
                String remaining = builder.getRemaining();
                int cut = remaining.lastIndexOf(' ');
                String head = cut < 0 ? "" : remaining.substring(0, cut + 1);
                String token = (cut < 0 ? remaining : remaining.substring(cut + 1))
                        .toLowerCase(java.util.Locale.ROOT);
                String trimmed = head.trim();
                String[] done = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
                if (done.length % 2 == 0) {
                    for (Field field : FoldConfig.class.getDeclaredFields()) {
                        int modifiers = field.getModifiers();
                        if (!Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) continue;
                        if (field.getName().startsWith(token)) {
                            builder.suggest(head + field.getName());
                        }
                    }
                    return builder.buildFuture();
                }
                Field field = findWritableField(done[done.length - 1]);
                if (field == null) {
                    return builder.buildFuture();
                }
                if (field.getType() == boolean.class) {
                    for (String candidate : new String[]{"true", "false", "toggle"}) {
                        if (candidate.startsWith(token)) builder.suggest(head + candidate);
                    }
                } else if (field.getType() == String.class && "kernelMode".equals(field.getName())) {
                    for (KernelMode mode : KernelMode.values()) {
                        if (mode.name().toLowerCase(java.util.Locale.ROOT).startsWith(token)) {
                            builder.suggest(head + mode.name());
                        }
                    }
                }
                return builder.buildFuture();
            };

    /** GPU 推挤后端的现状：没开、本机没显卡、还是已经在用哪块显卡。 */
    private static String gpuPushDescription() {
        if (!FoldConfig.gpuEnginePush) {
            return "已关闭 (gpuEnginePush=false)";
        }
        if (!com.wiyuka.acceleratedrecoiling.natives.GpuEnginePush.available()) {
            String reason = com.wiyuka.acceleratedrecoiling.natives.GpuEnginePush.failureReason();
            return "推挤留在 CPU 原生"
                    + (reason.isEmpty() ? "" : "（" + reason + "）");
        }
        return "已启用，显卡 "
                + com.wiyuka.acceleratedrecoiling.natives.GpuEnginePush.deviceName();
    }

    private static int updateConfig(CommandContext<CommandSourceStack> context) {
        // 后端(含 GPU/jocl)初始化可能因缺依赖失败；命令路径一律容错，不影响主循环。
        try {
            NativeInterface.applyConfig();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int setFieldValue(CommandContext<CommandSourceStack> context, Field field, Object newValue) {
        try {
            field.set(null, newValue); // 静态字段对象传 null
            // 也落一条日志：函数来源的 sendSuccess 不进日志，而改配置最需要留痕——
            // 「到底改没改上、改成什么」应该能从 run\logs\latest.log 里查。
            AcceleratedRecoiling.LOGGER.info("[Config] {} = {}", field.getName(), newValue);
            sendSuccessMessage(context.getSource(), field.getName(), newValue);
            try {
                NativeInterface.applyConfig();
            } catch (Throwable ignored) {
            }
            return 1;
        } catch (IllegalAccessException e) {
            context.getSource().sendFailure(Component.literal("Failed to modify config: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    private static void sendSuccessMessage(CommandSourceStack source, String configName, Object newValue) {
        var message = Component.literal("Config ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(configName)
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" updated to ")
                        .withStyle(ChatFormatting.GRAY));

        if (newValue instanceof Boolean boolValue) {
            message.append(Component.literal(String.valueOf(boolValue))
                    .withStyle(boolValue ? ChatFormatting.GREEN : ChatFormatting.RED));
        } else {
            message.append(Component.literal(String.valueOf(newValue))
                    .withStyle(ChatFormatting.AQUA));
        }

        source.sendSuccess(() -> message, false);
    }

    /** kernelMode 的补全来源，set 与 abtest 共用。 */
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> KERNEL_MODE_SUGGESTIONS =
            (ctx, builder) -> {
                String prefix = builder.getRemainingLowerCase();
                for (KernelMode mode : KernelMode.values()) {
                    String modeName = mode.name();
                    if (modeName.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                        builder.suggest(modeName);
                    }
                }
                return builder.buildFuture();
            };

    /** 启动自动交替对照。 */
    private static int runAbTest(CommandContext<CommandSourceStack> context, String modeA, String modeB,
                                 int ticks, int phases) {
        KernelMode a = KernelMode.parseOrNull(modeA);
        KernelMode b = KernelMode.parseOrNull(modeB);
        if (a == null || b == null) {
            context.getSource().sendFailure(Component.literal(
                    "未知的 kernelMode；可选 " + KernelMode.names()));
            return 0;
        }
        if (a == b) {
            context.getSource().sendFailure(Component.literal("两个档位不能相同"));
            return 0;
        }
        if (AbTest.isRunning()) {
            context.getSource().sendFailure(Component.literal("已有一轮对照在进行中，等它报完再发起"));
            return 0;
        }
        if (AbTest.start(context.getSource(), a.name(), b.name(), ticks, phases) == 0) {
            context.getSource().sendFailure(Component.literal("参数不合法"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "开始交替对照：%s / %s 各 %d 段、每段 %d tick（共 %d tick，约 %.0f 秒）。"
                        + "期间请勿移动或转动视角，结束后会自动报各档均值。",
                a.name(), b.name(), phases / 2, ticks, ticks * phases,
                ticks * phases / 20.0D)), false);
        return 1;
    }

    /** kernelMode 专用：非法取值直接报错并列出可选项，不静默回退。 */
    private static int setKernelMode(CommandContext<CommandSourceStack> context, Field field, String raw) {
        KernelMode parsed = KernelMode.parseOrNull(raw);
        if (parsed == null) {
            context.getSource().sendFailure(Component.literal(
                    "未知的 kernelMode: \"" + raw + "\"；可选 " + KernelMode.names()));
            return 0;
        }
        return setFieldValue(context, field, parsed.name());
    }

    private static Component buildConfigLine(String configName, Object value) {
        var line = Component.literal("  " + configName + ": ")
                .withStyle(ChatFormatting.GRAY);

        if (value instanceof Boolean boolValue) {
            line.append(Component.literal(String.valueOf(boolValue))
                    .withStyle(boolValue ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD));
        } else {
            line.append(Component.literal(String.valueOf(value))
                    .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            // 枚举型字符串把合法值一并列出，省得记
            if ("kernelMode".equals(configName)) {
                line.append(Component.literal("  [" + KernelMode.names() + "]")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        line.append("\n");
        return line;
    }

    private static int checkConfig(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        var message = Component.literal("Accelerated Recoiling")
                .withStyle(ChatFormatting.AQUA);

        message.append(Component.literal("\n--------------------\n")
                .withStyle(ChatFormatting.DARK_GRAY));

        try {
            // 注意：这里显示的是「已加载的后端」，而它未必是本档在用的那个——
            // GPU 后端一旦加载就不会卸载，切回 AUTO 后依然挂着。所以必须把档位一起说清，
            // 否则会让人以为「正在跑 GPU」（这个坑已经造成一次误判）。
            String activeBackend = NativeInterface.activeBackendName();
            boolean inUse = activeBackend != null && "GPU".equalsIgnoreCase(FoldConfig.kernelMode);
            message.append(Component.literal("  Native(FFM/JNI): ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(activeBackend == null
                            ? Component.literal("未使用 (ECO 引擎已接管)")
                                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                            : (inUse
                                    ? Component.literal("已加载后端 " + activeBackend + "，本档正在使用")
                                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                                    : Component.literal("已加载后端 " + activeBackend
                                            + "，但本档 kernelMode=" + FoldConfig.kernelMode + " 不使用它")
                                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)))
                    .append("\n\n");
        } catch (Throwable e) {
            message.append(Component.literal("  Native(FFM/JNI): ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("未使用 (ECO 引擎已接管)")
                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append("\n\n");
        }

        if (EcoEngine.isAvailable()) {
            message.append(Component.literal("  ECO 原生引擎: ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("可用 (FFM 已绑定)")
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                    .append("\n");
            message.append(Component.literal("    帧构建: ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.valueOf(EcoFrame.FRAMES_BUILT))
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("  查询: ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.valueOf(EcoFrame.QUERIES_RUN))
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("  批推: ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(EcoFrame.PUSH_RUNS + " 次 / " + EcoFrame.PUSHED_ENTITIES + " 实体 / Java推 " + EcoFrame.DOPUSH_ENTITIES + " / 失败 " + EcoFrame.RUN_FAILED + " / 帧实体 " + EcoFrame.LAST_COUNT + " / 平均候选 " + (EcoFrame.QUERIES_RUN == 0 ? 0 : EcoFrame.CANDIDATES_SUM / EcoFrame.QUERIES_RUN) + " / 末候选 " + EcoFrame.LAST_CANDIDATES)
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" / 全量推 ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.valueOf(EcoFrame.FULL_RUNS))
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" / 引擎 ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format(java.util.Locale.ROOT, "%.2f ms/帧", EcoFrame.FRAMES_BUILT == 0 ? 0 : EcoFrame.ENGINE_NANOS / 1e6 / EcoFrame.FRAMES_BUILT))
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" / 原生 ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format(java.util.Locale.ROOT, "%.3f ms/帧", EcoFrame.FRAMES_BUILT == 0 ? 0 : EcoFrame.NATIVE_FULLPUSH_NANOS / 1e6 / EcoFrame.FRAMES_BUILT))
                            .withStyle(ChatFormatting.AQUA))
                    .append("\n\n");
            // GPU 推挤后端：这一行才是「引擎到底在显卡上还是在 CPU 上」的答案。
            message.append(Component.literal("  GPU 推挤: ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(gpuPushDescription())
                            .withStyle(com.wiyuka.acceleratedrecoiling.natives.GpuEnginePush.available()
                                    ? ChatFormatting.GREEN : ChatFormatting.YELLOW))
                    .append(Component.literal(" / 走显卡 ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(EcoFrame.GPU_FULL_RUNS + " 帧 " + String.format(
                                    java.util.Locale.ROOT, "%.3f ms/帧",
                                    EcoFrame.GPU_FULL_RUNS == 0 ? 0
                                            : EcoFrame.GPU_FULLPUSH_NANOS / 1e6 / EcoFrame.GPU_FULL_RUNS))
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" / 与原核对拍 ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(EcoFrame.GPU_PARITY_CHECKS + " 次 / 不一致 "
                                    + EcoFrame.GPU_PARITY_DIFFS + " 项")
                            .withStyle(EcoFrame.GPU_PARITY_DIFFS == 0
                                    ? ChatFormatting.GREEN : ChatFormatting.RED))
                    .append("\n\n");
        } else {
            message.append(Component.literal("  ECO 原生引擎: ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("不可用 (将自动使用 PARITY/Java 内核)")
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                    .append("\n\n");
        }

        TickStats.Snapshot serverStats = TickStats.server();
        TickStats.Snapshot levelStats = null;
        TickStats.Snapshot levelLast = null;
        String levelName = "?";
        try {
            net.minecraft.server.level.ServerLevel level = source.getLevel();
            levelStats = TickStats.level(level);
            levelLast = TickStats.levelLast(level);
            levelName = level.dimension().location().getPath();
        } catch (Throwable ignored) {
        }

        message.append(Component.literal("  Tick 拆解(近 " + serverStats.ticks + " tick 均值)  ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(java.util.Locale.ROOT, "服务器 tick %.2f ms", serverStats.tickMs))
                        .withStyle(ChatFormatting.GOLD))
                .append("\n");

        // 低 TPS 下 200 tick 窗口能跨上百秒，均值严重滞后；先看「上一 tick」才是真实开销
        if (levelLast != null && levelLast.ticks > 0) {
            message.append(Component.literal("    ★ 上一 tick " + levelName + ": ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format(java.util.Locale.ROOT,
                                    "tick %.2f = 实体 %.2f + 方块/其它 %.2f ms",
                                    levelLast.tickMs, levelLast.entityMs, levelLast.nonEntityMs()))
                            .withStyle(ChatFormatting.YELLOW))
                    .append("\n");
            message.append(Component.literal("      引擎 %.2f / move %.2f / collide %.2f ms (%.0f 次, 单次 %.2f us) / 其余 %.2f ms"
                            .formatted(levelLast.engineMs, levelLast.moveMs, levelLast.collideMs,
                                    levelLast.collideCalls,
                                    levelLast.collideCalls <= 0.0 ? 0.0
                                            : levelLast.collideMs * 1000.0 / levelLast.collideCalls,
                                    levelLast.otherEntityMs()))
                            .withStyle(ChatFormatting.YELLOW))
                    .append("\n");
            message.append(Component.literal("      move 调用 %.0f 次 / AABB 查询 %.0f 次共 %.2f ms / 接管 %.0f 次"
                            .formatted(levelLast.moveCalls, levelLast.aabbQueryCalls,
                                    levelLast.aabbQueryMs, levelLast.takeoverCalls))
                            .withStyle(ChatFormatting.YELLOW))
                    .append("\n");
            message.append(Component.literal("      原版 getEntityCollisions %.0f 次共 %.2f ms / 返回合计 %.0f 个 / 查询盒最大尺寸 %.4f"
                            .formatted(levelLast.entityCollisionCalls, levelLast.entityCollisionMs,
                                    levelLast.entityCollisionReturned, levelLast.entityCollisionMaxBoxSize))
                            .withStyle(ChatFormatting.YELLOW))
                    .append("\n");
            message.append(Component.literal("      传感器/目标选择器实体查询 %.0f 次 / 共 %.2f ms (单次 %.2f us) 每 tick"
                            .formatted(levelLast.typeQueryCalls, levelLast.typeQueryMs,
                                    levelLast.typeQueryCalls <= 0.0 ? 0.0
                                            : levelLast.typeQueryMs * 1000.0 / levelLast.typeQueryCalls))
                            .withStyle(ChatFormatting.YELLOW))
                    .append("\n");
            if (levelLast.parityChecks > 0.0) {
                boolean clean = levelLast.parityMismatches == 0.0;
                message.append(Component.literal("      查询影子对拍: 校验 %.0f 次 / 不一致 %.0f 次 / 原生 %.2f ms vs 原版 %.2f ms"
                                .formatted(levelLast.parityChecks, levelLast.parityMismatches,
                                        levelLast.parityNativeMs, levelLast.parityVanillaMs))
                                .withStyle(clean ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD))
                        .append("\n");
            }
            if (levelLast.moveParityChecks > 0.0) {
                boolean clean = levelLast.moveParityMismatches == 0.0;
                message.append(Component.literal("      移动求解对拍: 适用 %.0f 次 / 不一致 %.0f 次 / 原生合计 %.2f ms"
                                .formatted(levelLast.moveParityChecks, levelLast.moveParityMismatches,
                                        levelLast.moveParityNativeMs))
                                .withStyle(clean ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD))
                        .append("\n");
            }
            if (levelLast.gpuPushMs > 0.0) {
                message.append(Component.literal("      GPU 候选枚举(kernelMode=GPU): %.2f ms 每 tick"
                                .formatted(levelLast.gpuPushMs))
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                        .append("\n");
            }
            message.append("\n");
        }

        if (levelStats != null) {
            message.append(Component.literal("    维度 " + levelName + ": ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format(java.util.Locale.ROOT,
                                    "tick %.2f = 实体 %.2f + 方块/其它 %.2f ms",
                                    levelStats.tickMs, levelStats.entityMs, levelStats.nonEntityMs()))
                            .withStyle(ChatFormatting.AQUA))
                    .append("\n");
            message.append(Component.literal("    实体内部: ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format(java.util.Locale.ROOT,
                                    "引擎 %.2f / move %.2f / collide %.2f ms / move 调用 %.0f 次每 tick / 其余 %.2f ms",
                                    levelStats.engineMs, levelStats.moveMs, levelStats.collideMs,
                                    levelStats.moveCalls, levelStats.otherEntityMs()))
                            .withStyle(ChatFormatting.AQUA))
                    .append("\n");
            message.append(Component.literal("    碰撞次数: ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format(java.util.Locale.ROOT,
                                    "collide 被调用 %.0f 次每 tick，单次 %.2f us",
                                    levelStats.collideCalls,
                                    levelStats.collideCalls <= 0.0 ? 0.0
                                            : levelStats.collideMs * 1000.0 / levelStats.collideCalls))
                            .withStyle(ChatFormatting.AQUA))
                    .append("\n");
            message.append(Component.literal("    AABB 实体查询: ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format(java.util.Locale.ROOT,
                                    "%.0f 次 / 共 %.2f ms (单次 %.2f us) / 返回合计 %.0f 个",
                                    levelStats.aabbQueryCalls, levelStats.aabbQueryMs,
                                    levelStats.aabbQueryCalls <= 0.0 ? 0.0
                                            : levelStats.aabbQueryMs * 1000.0 / levelStats.aabbQueryCalls,
                                    levelStats.aabbQueryReturned))
                            .withStyle(ChatFormatting.AQUA))
                    .append("\n");
            // collide 是在 move 内部调用的，它的耗时已含在 move 里；子项只在移动接管生效时有值
            if (levelStats.takeoverCalls > 0.0) {
                message.append(Component.literal("    碰撞内部: ")
                                .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.format(java.util.Locale.ROOT,
                                        "接管 %.0f 次 / 实体查询 %.2f ms(每次返回 %.1f 个, 成盒 %.1f 个) / 方块收集 %.2f + 裁剪 %.2f ms",
                                        levelStats.takeoverCalls, levelStats.gatherMs,
                                        levelStats.returnedPerCall, levelStats.boxesPerCall,
                                        levelStats.blockMs, levelStats.clipMs))
                                .withStyle(ChatFormatting.AQUA))
                        .append("\n");
            }
            // 移动接管抛异常退回原版的累计次数：那条路是刻意静默的，没有这个数就完全没有痕迹。
            // 持续增长 = 原生移动求解坏了（表现只是「开了模组却没加速」）；首次异常的摘要一并显示。
            message.append(Component.literal("    移动接管退回原版累计: ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.valueOf(TickStats.movementFallbacks()))
                            .withStyle(TickStats.movementFallbacks() == 0
                                    ? ChatFormatting.GREEN : ChatFormatting.RED))
                    .append(Component.literal(" 次")
                            .withStyle(ChatFormatting.GRAY));
            String cause = TickStats.movementFallbackCause();
            if (!cause.isEmpty()) {
                message.append(Component.literal(" / 首次: " + cause)
                        .withStyle(ChatFormatting.RED));
            }
            message.append("\n");
            message.append("\n");
        } else {
            message.append("\n");
        }


        for (Field field : FoldConfig.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers)) continue;

            field.setAccessible(true);
            try {
                Object value = field.get(null);
                message.append(buildConfigLine(field.getName(), value));
            } catch (IllegalAccessException e) {
            }
        }

        message.append(Component.literal("--------------------")
                .withStyle(ChatFormatting.DARK_GRAY));

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static int save(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        File targetFile = new File("acceleratedRecoiling.json");

        JsonObject jsonObject = new JsonObject();

        for (Field field : FoldConfig.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) continue;

            field.setAccessible(true);
            try {
                Object value   = field.get(null);
                String jsonKey = field.getName();
                SerializedName serializedName = field.getAnnotation(SerializedName.class);
                if      (serializedName != null)         jsonKey = serializedName.value();
                if      (value instanceof Boolean bool)  jsonObject.addProperty(jsonKey, bool);
                else if (value instanceof Number number) jsonObject.addProperty(jsonKey, number);
                else if (value instanceof String string) jsonObject.addProperty(jsonKey, string);

            } catch (IllegalAccessException ignored) {
            }
        }

        try (FileWriter writer = new FileWriter(targetFile)) {
            GSON.toJson(jsonObject, writer);

            var message = Component.literal("Config saved ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(targetFile.getName())
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            source.sendSuccess(() -> message, false);
            return 1;

        } catch (IOException e) {
            var message = Component.literal("Failed to save config file: ")
                    .withStyle(ChatFormatting.RED)
                    .append(Component.literal(e.getMessage())
                            .withStyle(ChatFormatting.WHITE));
            source.sendFailure(message);
            e.printStackTrace();
            return 0;
        }
    }
}