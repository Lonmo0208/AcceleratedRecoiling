package com.wiyuka.acceleratedrecoiling.config;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import org.slf4j.Logger;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
public class FoldConfig {

    // 你的静态配置项
    public static final boolean debugDensity = false;
    public static boolean enableEntityCollision = true;

    /**
     * ECO 引擎的全量推是否交给显卡（OpenCL）算。**默认关闭，因为实测在这台机器上更慢。**
     *
     * <p>搬过去的是整段推挤计算，不只是候选枚举：原生只做建序打包与回写，中间每一对的相交
     * 判定、队伍过滤、冲量累加都在内核里。之所以能搬，是因为冲量只读推挤开始前的坐标
     * （{@code push()} 只改速度不改位置），是帧首状态的纯函数；内核让一个 work-item 负责
     * 一个实体、按原生的扫描次序串行累加，浮点求和每一步都与 CPU 相同——对拍 6000 帧
     * 逐位比对（{@link #debugPushParity}）不一致 0 项。
     *
     * <p><b>为什么默认关</b>：同一引擎只切推挤后端做交替对照
     * （{@code abtest ENGINE_GPU ENGINE_CPU 100 6}，2087 实体）实测
     * <b>ENGINE_GPU 48.03 ms 对 ENGINE_CPU 42.43 ms，显卡反而高 5.60 ms</b>。
     * 三段拆解：建序打包 0.09 + 内核传输 4.86 + 回写 0.02 ms/帧，其中「提交」只要 0.05 ms，
     * 4.8 ms 全花在等结果上。本机是核显（gfx1101），同一块 GPU 正在给游戏渲染，
     * 提交上去的计算要排在渲染之后；2087 个实体的算力本来就不足以盖过这份等待。
     * 换独显、或实体规模大得多时这条结论可能反转，所以留成开关而不是删掉。
     *
     * <p>没有 OpenCL 的机器（专用服务端）探测一次失败就不再重试，静默留在 CPU 原生，
     * 所以这一项开着也能安全部署到服务端。
     */
    public static boolean gpuEnginePush = false;

    /**
     * 实体查询走原生空间索引（含每帧索引构建）。**实测比原版慢，故默认关闭。**
     *
     * <p>影子对拍读数（2600 实体）：原版 {@code getEntities(EntityTypeTest, AABB, Predicate)}
     * 单次 0.37 us，原生 {@code queryEntitiesInBox} 单次 57 us——**慢 245 倍**。
     * 而原版这条路径全加起来才 3 ms/tick 量级（8700 次 × 0.37 us），
     * 也就是说「调用次数多」根本不等于「开销大」：这里本来就没有可省的东西。
     * 原生侧每帧还要为建索引多付约 2.6 ms。
     * 除非将来改成按需惰性维护并能实测更快，否则不要再打开。
     */
    public static boolean enableEntityGetterOptimization = false;
    public static int maxCollision = 32;
    public static int gridSize = 1;
    public static int densityWindow = 4;
    public static int densityThreshold = 16;
    public static int maxThreads = 1;

    /**
     * 合并内核模式：AUTO(默认) / NATIVE / SPARSE / PARITY / VANILLA / GPU。
     * AUTO：**默认档**——走 ECO 帧引擎（原生全量推，一个不漏），推挤在 worker 线程异步算。
     * NATIVE：与 AUTO 同路，显式写出来而已。
     * SPARSE：**实验档**——ECO 引擎 + AR 那种「漏」（每个实体最多保留 {@code maxCollision}
     *         个对手，超出的丢弃）。丢推挤换速度，只有生电量级的实体堆积才看得出来。
     * PARITY：始终使用纯 Java 的原版等价候选枚举（行为与原版一致，无需原生库）。
     * VANILLA：完全禁用加速路径。
     * GPU：AcceleratedRecoiling 的 OpenCL 后端枚举候选对，再由原版 doPush 施加推挤。
     *      候选来自 2.5 格哈希网格、会漏配，且实测比引擎慢，只作对照档。
     * 注：PARITY 恒按原版候选顺序枚举（原版等价是其设计本身，无需额外开关）。
     *
     * <p><b>AUTO 为什么走引擎</b>：它一度走 AR 的 GPU 候选枚举，因为那时引擎要 45.8 ms、
     * 比它慢 20 ms。引擎修好（§40 那个延迟回写丢摩擦、冲量永不衰减的 bug）又改成异步之后
     * 结论反过来：引擎 20.9 ms 且一个不漏，AR 那条路 25.8 ms 且漏推。默认档就该是引擎。
     *
     * <p><b>异步推挤</b>不区分档位：帧在 tick HEAD 就把推挤交给 worker 线程，服务器线程只在
     * tick 末尾取结果。输入是帧首快照，与同步时完全一致，所以行为不变，但那约 4 ms 不再占
     * tick 的时间（实测 25.7 → 20.9 ms）。实现在 {@code EcoFrame.submitPush/finishPush}。
     *
     * <p><b>推挤算力放哪</b>由 {@link #gpuEnginePush} 决定。默认关：CPU 原生 4.2 ms/帧 对
     * 显卡 4.9 ms/帧（核显与渲染争用），所以 CPU 后端更快。打开它就把整段全量推交给 OpenCL，
     * 与 CPU 原生逐位一致（对拍 6000 帧、0 项不一致）。
     *
     * <p><b>实测全景</b>（2088 实体、稳态）：{@code SPARSE} 18.5 ms（丢推挤）、
     * {@code AUTO}/{@code NATIVE} 20.9 ms（一个不漏）、{@code GPU} 约 25.8 ms（漏推）、
     * {@code PARITY} 93~107 ms（原版等价参照）。
     */
    public static String kernelMode = "AUTO";

    /**
     * 推挤是否异步计算。**默认开。** 只影响走引擎的档（AUTO / NATIVE / SPARSE）。
     *
     * <p>开着的时候：帧在 tick HEAD 把推挤交给 worker 线程，服务器线程只在 tick 末尾取结果。
     * 输入是帧首快照，与同步时完全一致，所以**结果不因这个开关而变**，变的只是那约 4 ms
     * 算在哪个线程上（实测 tick 25.7 → 20.9 ms）。
     *
     * <p><b>关掉它就是为了兼容性</b>：跨线程跑原生代码时，如果别的模组也在这条路上有自己的
     * 钩子，或者对实体/帧状态做了非线程安全的假设，就可能出怪事。这时切成 false，推挤回到
     * 服务器线程同步算——结果一样，只慢约 4 ms。**换档后遇到怪事，先关这个排查。**
     *
     * <p>一条指令同时切档位与它（值后面可以续接下一对参数）：
     * {@code /acceleratedrecoiling kernelMode SPARSE asyncPush false}
     */
    public static boolean asyncPush = true;

    /**
     * tick 耗时拆解埋点。开启后 /check 会显示总 tick / 实体 tick / Entity.move / Entity.collide
     * 的耗时，用来定位 tick 时间归属；代价约每 tick 万次 nanoTime（2600 实体场景约 0.5ms），
     * 不再需要诊断时可关掉。
     */
    public static boolean tickProfiling = true;

    /**
     * 移动求解接管：{@code Entity.collide} 的方块侧（逐轴裁剪 + 台阶候选）改由原生
     * {@code solveMovement} 完成。形状仍从原版 {@code getBlockCollisions} 取，
     * 且只在「形状全是单位满方块」时适用（台阶、栅栏、世界边界一律回退原版）。
     *
     * <p>裁剪算式已离线逐位对拍通过：7 个种子共 42 万例、0 处不一致。
     * 进游戏后还有第二道闸 {@link #debugMovementParity}：影子对拍为 0 才把结果换成原生的。
     */
    public static boolean enableMovementTakeover = true;

    /**
     * 移动求解影子对拍。**默认关闭**（曾经默认开启，那是个坑，见下）。
     *
     * <p>开启时方块侧同时算两遍——原生 {@code solveMovement} 与已验证的 Java 等价实现——
     * 逐位比对，**但返回 Java 结果**。也就是说开着它时：原生快速路径被跳过（走 Java 等价实现），
     * 同时又额外跑一遍原生只为比对。**既没拿到加速，又付了两遍钱。**
     *
     * <p>所以它只该在排查时开。默认必须关——对拍读数早已为 0
     * （离线 7 个种子 35 万+ 例逐位一致），关掉之后原生结果才真正被采用。
     * 这里的默认值、以及默认配置 JSON 里的值，都必须是 {@code false}：只要有一个是 true，
     * 任何删配置重建 / 全新安装的人都会掉进「开着模组却更慢」的坑。
     */
    public static boolean debugMovementParity = false;
    /**
     * 查询影子对拍：同跑原生与原版并逐项比对，用来判断原生查询是否等价。
     * 只在 {@link #enableEntityGetterOptimization} 打开时有意义；代价是查询耗时翻倍。默认关闭。
     */
    public static boolean debugQueryParity = false;
    /**
     * GPU 推挤的差分对拍：同一批 body 行上再跑一遍 CPU 全量推当参考，逐位比对 GPU 结果
     * （含 ±0.0 与 NaN 的位差别）。**只影响读数**：对拍在原生里先存快照、跑完再还原，
     * 帧里留下的仍然是 GPU 的结果，所以开与不开游戏行为相同。
     *
     * <p>代价是每帧多一次 CPU 全量推，只在核对 GPU 后端时才打开。
     */
    public static boolean debugPushParity = false;
    /**
     * 自动交替对照：写成 {@code "<档A> <档B> <每段tick> <段数>"}（例如
     * {@code "ENGINE_GPU ENGINE_CPU 100 6"}），世界加载后自动跑一轮，逐段结果写进日志
     * （{@code [AB]} 前缀）。留空表示不跑。
     *
     * <p>两个伪档名 {@code ENGINE_GPU} / {@code ENGINE_CPU} 都是同一个引擎，只差推挤算力
     * 放显卡还是 CPU——要单独量「引擎搬到显卡值不值」只能这样切。
     */
    public static String autoAbTest = "";
    private static final File CONFIG_FILE = new File("acceleratedRecoiling.json");

    public static void loadConfig() {
        Logger logger = AcceleratedRecoiling.LOGGER;
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        JsonObject defaultConfigJson = new JsonObject();
        defaultConfigJson.addProperty("enableEntityCollision", true);
        defaultConfigJson.addProperty("gpuEnginePush", false);
        defaultConfigJson.addProperty("enableEntityGetterOptimization", false);
        defaultConfigJson.addProperty("maxCollision", 32);
        defaultConfigJson.addProperty("gridSize", 1);
        defaultConfigJson.addProperty("densityWindow", 4);
        defaultConfigJson.addProperty("densityThreshold", 16);
        defaultConfigJson.addProperty("maxThreads", maxThreads);
        defaultConfigJson.addProperty("kernelMode", "AUTO");
        defaultConfigJson.addProperty("asyncPush", true);
        defaultConfigJson.addProperty("tickProfiling", true);
        defaultConfigJson.addProperty("enableMovementTakeover", true);
        defaultConfigJson.addProperty("debugMovementParity", false);
        defaultConfigJson.addProperty("debugQueryParity", false);
        defaultConfigJson.addProperty("debugPushParity", false);
        defaultConfigJson.addProperty("autoAbTest", "");

        String defaultConfigStr = gson.toJson(defaultConfigJson);

        if (!CONFIG_FILE.exists()) {
            try {
                if (CONFIG_FILE.createNewFile()) {
                    Files.writeString(CONFIG_FILE.toPath(), defaultConfigStr);
                }
            } catch (IOException e) {
                logger.error("Cannot create config file", e);
            }
        }

        String configFileContent;
        try {
            configFileContent = Files.readString(CONFIG_FILE.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Failed to read config, reason: {}. Using default.", e.getMessage());
            configFileContent = defaultConfigStr;
        }

        try {
            JsonObject configJson = JsonParser.parseString(configFileContent).getAsJsonObject();
            applyJson(configJson);
        } catch (Exception e) {
            logger.warn("Config broken: {}. Overwriting.", e.getMessage());
            try {
                Files.writeString(CONFIG_FILE.toPath(), defaultConfigStr);
            } catch (IOException ignored) {}
            applyJson(JsonParser.parseString(defaultConfigStr).getAsJsonObject());
        }

        logger.info("Configuration loaded successfully");
    }
    private static void applyJson(JsonObject configJson) {
        if (configJson.has("enableEntityCollision")) enableEntityCollision = configJson.get("enableEntityCollision").getAsBoolean();
        if (configJson.has("gpuEnginePush")) gpuEnginePush = configJson.get("gpuEnginePush").getAsBoolean();
        if (configJson.has("enableEntityGetterOptimization")) enableEntityGetterOptimization = configJson.get("enableEntityGetterOptimization").getAsBoolean();
        if (configJson.has("maxCollision")) maxCollision = configJson.get("maxCollision").getAsInt();
        if (configJson.has("gridSize")) gridSize = configJson.get("gridSize").getAsInt();
        if (configJson.has("densityWindow")) densityWindow = configJson.get("densityWindow").getAsInt();
        if (configJson.has("densityThreshold")) densityThreshold = configJson.get("densityThreshold").getAsInt();
        if (configJson.has("maxThreads")) maxThreads = configJson.get("maxThreads").getAsInt();
        if (configJson.has("asyncPush")) asyncPush = configJson.get("asyncPush").getAsBoolean();
        if (configJson.has("kernelMode")) {
            String requested = configJson.get("kernelMode").getAsString();
            kernelMode = requested;
            // 配置文件里写错名字会被 KernelMode.fromName 静默回退成 AUTO，这里至少留一条日志
            if (com.wiyuka.acceleratedrecoiling.kernel.KernelMode.parseOrNull(requested) == null) {
                AcceleratedRecoiling.LOGGER.warn("Config kernelMode \"{}\" 无法识别，将按 AUTO 处理；可选 {}",
                        requested, com.wiyuka.acceleratedrecoiling.kernel.KernelMode.names());
            }
        }
        if (configJson.has("tickProfiling")) tickProfiling = configJson.get("tickProfiling").getAsBoolean();
        if (configJson.has("enableMovementTakeover")) enableMovementTakeover = configJson.get("enableMovementTakeover").getAsBoolean();
        if (configJson.has("debugMovementParity")) debugMovementParity = configJson.get("debugMovementParity").getAsBoolean();
        if (configJson.has("debugQueryParity")) debugQueryParity = configJson.get("debugQueryParity").getAsBoolean();
        if (configJson.has("debugPushParity")) debugPushParity = configJson.get("debugPushParity").getAsBoolean();
        if (configJson.has("autoAbTest")) autoAbTest = configJson.get("autoAbTest").getAsString();
    }
}