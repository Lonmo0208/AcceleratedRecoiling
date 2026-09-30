# 加速碰撞 (Accelerated Recoiling) — 1.21.1 ECO 合并版

加速碰撞是一个专注于优化服务端实体碰撞逻辑的模组。它利用 FFM (Foreign Function & Memory) API/JNI 接管实体 AABB 碰撞检测，将高密集计算压力转移至 C++ 原生库，从而显著提升服务器性能。

> **本分支（`1.21.1/ECO-port`）说明**：这是把 **[实体碰撞优化 (Entity Collision Optimizer)](https://github.com/water2004/EntityCollisionOptimizer)**
> 的帧引擎内核合并进加速碰撞（NeoForge 1.21.1 / Java 21）的移植分支。**默认档即走 ECO 引擎**：
> 一次扫描所有候选对、一个候选都不丢，推挤在 worker 线程异步计算。
> 档位、开关与实测见下方「合并增强：ECO 内核」一节。

**默认档（`AUTO`）不丢推挤、物理量与保真参照贴齐，可以正常使用；但模组整体仍是实验性质，建议做好存档备份。**
唯一会主动丢推挤的是实验档 `SPARSE`（用超过 `maxCollision` 个对手的部分换速度），它只建议在生电量级的实体堆积下使用。

原加速碰撞的官方交流群：1023713677

## 特性介绍

*   **实体碰撞性能提升**：把实体推挤（候选枚举 + 逐对判定 + 冲量累加）整段交给原生层或显卡，
    打破 Java 在处理海量实体碰撞时的性能瓶颈。
*   **一次扫描所有候选对**：取代原版「每个实体各查一次邻域」的写法——后者在数千实体时是主要开销
    （原版等价内核 `PARITY` 实测 90～107 ms，引擎档 21～24 ms）。
*   **默认档保真**：默认的 `AUTO` 档一个候选都不丢，配对集合与逐对算式对齐原版推挤；
    想逐字复刻原版用 `PARITY`，想拿速度换保真用实验档 `SPARSE`。详见「合并增强」一节。
*   **异步推挤**：推挤在 worker 线程算，服务器线程只在 tick 末尾取结果，那约 4 ms 不再占 tick 时间。
    提供 `asyncPush` 开关一键回到同步路径（兼容性排查用）。
*   **双端原生支持**：内建 Windows (`.dll`) 与 Linux (`.so`) 的动态链接库，面板服、VPS 还是 Docker 容器，都能一键部署。
*   **多后端**：
    * FFM  （使用FFM API与C++层进行通信 **该后端需要CPU支持AVX2指令集**）
    * JNI  （使用JNI与C++层进行通信 **该后端需要CPU支持AVX2指令集**）
    * Java （使用加速碰撞的Java原生算法 **注：MacOS系统将被fallback到此后端**）
    * GPU  （用 OpenCL 在显卡上枚举候选对，判定与冲量仍回 Java 逐个 `doPush`；**候选会漏配**，作对照档）

## 环境要求与前置

*   **Java 21 或更高（>= 21）**：1.21.1 至少要求 Java 21；本分支的 jar 已剥离预览标记，更高版本的 JDK 同样可用。
*   **64位操作系统**：本机库 (`.dll` / `.so`) 仅支持 64 位环境。
*   **Windows 平台**：需安装 [Microsoft Visual C++ 运行库](https://aka.ms/vs/17/release/vc_redist.x64.exe)（如启动失败请优先安装）。
*   **Leaves 端**：启动参数中必须包含 `-Dleavesclip.enable.mixin=true`。
*   **Linux/Docker**: 需确保系统中已安装 `libgomp1` 依赖。

## 安装与配置

首次启动时，模组会自动在根目录释放本机库文件，并生成 `acceleratedRecoiling.json` 配置文件。

**默认配置及说明：**
```json
{
   "enableEntityCollision": true,           // 是否启用实体碰撞优化总开关
   "kernelMode": "AUTO",                    // 内核档位，见下方「合并增强」一节
   "asyncPush": true,                       // 推挤是否异步计算（兼容性排查用，关掉回到服务器线程）
   "gpuEnginePush": false,                  // 引擎的推挤算力放显卡还是 CPU（默认 CPU 更快）
   "maxCollision": 32,                      // 单个实体最大碰撞交互数；SPARSE 档用它当对手上限
   "gridSize": 1,                           // 算法网格大小
   "densityWindow": 4,                      // 密度平滑窗口
   "densityThreshold": 16,                  // 触发加速路径的周围实体密度阈值
   "maxThreads": 1,
   "enableEntityGetterOptimization": false, // 实体查询走原生空间索引（实测更慢，默认关）
   "tickProfiling": true,                   // tick 耗时拆解埋点，/check 与 abtest 的读数靠它
   "enableMovementTakeover": true,          // 方块侧移动求解接管
   "debugMovementParity": false,
   "debugQueryParity": false,
   "debugPushParity": false,                // GPU 推挤与 CPU 原生的逐位对拍
   "autoAbTest": ""                         // 世界加载后自动跑一轮交替对照，结果写日志
}
```
*注：若开启后性能不升反降，请尝试调低 `densityThreshold`，或换用 `PARITY` 档作对照。*

## 常见问题 (Q&A)

**Q: 为什么游戏崩溃或无法启动？** <br>
**A:** 请按以下步骤排查：
1. 确认已正确安装 Java 21 或更高版本（最低 21）。
2. 若使用 **Leaves** 服务端，确保启动参数包含 `-Dleavesclip.enable.mixin=true`。
3. 如果更新过模组，尝试删除根目录或 `.minecraft` 下的 `acceleratedRecoilingLib.dll` 与 `acceleratedRecoiling.json`，然后重启游戏让其重新生成。

**Q: 开启后实体挤压表现和原版一样吗？** <br>
**A:** 分档而言。**默认档（`AUTO`/`NATIVE`）不丢任何推挤**，配对集合与逐对算式都对齐原版推挤，
物理量已与保真参照 `PARITY` 贴齐；但**没有做过「最终速度逐位等价」的证明**，要那一级的等价请用
`PARITY`（纯 Java，慢但逐字复刻原版）。实验档 `SPARSE` 会主动丢弃超过 `maxCollision` 个对手的推挤，
挤压表现与原版有差异。**无论哪个档，都建议先做好存档备份。**

**Q: 会影响生电特性吗？** <br>
**A:** 默认档（`AUTO`/`NATIVE`）不丢推挤，理论上对生电机器是安全的；`SPARSE` 档与 `GPU` 档会丢推挤，
**可能与实体挤压有关的设计表现出差异**，用作生电环境前请先小范围验证。`PARITY` 档可作为对照基准。

**Q: 换档之后出现怪事，怎么排查是不是本模组？** <br>
**A:** 两条按顺序试：
1. `/acceleratedrecoiling set asyncPush false` —— 关掉异步推挤。结果不变（输入同样是帧首快照），
   只是推挤回到服务器线程同步算、慢约 4 ms。若这样就好了，说明是别的模组与跨线程执行冲突。
2. `/acceleratedrecoiling set kernelMode PARITY` —— 换成纯 Java 的原版等价内核。若这样也好了，
   说明问题在原生/引擎那条路上，把它连同 `/check` 的输出一起反馈即可。

**Q: 为什么开启模组后，服务器性能反而下降了？** <br>
**A:** 可能是周围实体密度未达到触发优化的条件，因此同时走了原版和加速碰撞的两条路径。请尝试打开配置文件，适当调低 `densityThreshold` 的数值。或尝试调低 `maxThreads`。

**Q: 在 Docker 中运行服务端时，报错提示找不到 `libgomp.so` 怎么办？** <br>
**A:** Docker 中使用的 Ubuntu 镜像不包含 `libgomp.so`，因此只需在构建镜像的 Dockerfile 中添加以下命令并重新构建镜像即可：
```dockerfile
RUN apt-get update && \
    apt-get install -y libgomp1
```

## 性能实测

### 合并 ECO 内核后的读数（本分支）

**测试环境**：AMD 7600X | AMD Radeon PRO V710（RDNA3 独显，性能约 RX 7700～7700XT 之间）| 32GB | NeoForge 1.21.1 | GraalVM JDK 21。
全部读数来自 `abtest` **交替对照**（每段 100～200 tick，切换与统计全由程序做，场景漂移被均摊），
同一场景、同一批实体，可直接横向比较：

| 档 | tick（稳态） | 是否丢推挤 |
| :--- | :--- | :--- |
| `SPARSE`（实验） | **18.5 ms** | 丢超过上限的推挤 |
| **`AUTO` / `NATIVE`（默认）** | **21～24 ms** | 否 |
| `AUTO` + `asyncPush=false` | 26～28 ms | 否（逐实体发布，结构更贴原版） |
| `GPU`（AR 候选枚举，对照档） | ～26 ms | 是 |
| `PARITY`（原版等价参照） | 90～107 ms | 否 |

引擎档的 `move + collide` 为 23.4 ms，对 `PARITY` 的 22.1 ms（**代价已与保真参照贴平**，差 6%）。

### 原加速碰撞的历史数据（合并前，保留作参考）

**测试环境:** i5-12600KF | 32GB RAM | RTX 3060 Ti | Leaves 1.21.8 | GraalVM JDK 21

**测试一：TPS 变化 (同一区块 2x2 空间内生成实体)**
| 实体数量 | Leaves + 加速碰撞 | 原版 Leaves | 提升倍率 |
| :--- | :--- | :--- | :--- |
| **2,048** | **20.0 TPS** (16 MSPT) | 3.0 TPS | 20.8x |
| **4,096** | **20.0 TPS** (27 MSPT) | 0.5 TPS | 74x |
| **16,384** | **8.6 TPS** (115 MSPT) | - | - |
| **32,768** | **4.3 TPS** (230 MSPT) | - | - |

## 开发计划 (TODO)

*   兼容 MacOS
*   Luminol 支持
*   **把「引擎与原版最终速度逐位等价」证掉**：目前只证到配对集合与逐对算式，见「合并增强」里的说明。
*   **定因「群体平均速度比原版高 2.8 倍」**：代价已贴平、不影响帧时间，但它是真实的行为差异。
    已排除「发布时刻」与「候选集规模」两条嫌疑，剩下指向**候选受理集合与冲量累加**。
    要先做出能单变量隔离的实验，**不要先改代码再解释**（这类推测性改动试过一次，反而更慢，已回滚）。
*   收窄 `GPU` 档（AR 候选枚举）的漏配：它是唯一还能被优化的对照档。

## 源码编译

**本分支**用一条 `tools/build_all.ps1` 跑完全流程（原生双端 → Java → 打包 → 启动预检 → 部署），
详细说明与可选开关见下方「合并增强：ECO 内核」里的**构建**一节；那里也说明了为什么
**不要直接用 `gradlew jar`**（mixin 注入签名错误只在类加载时才抛，编译期发现不了）。

> 提示：仓库里已包含预编译好的原生库（`AcceleratedRecoiling-third-party/out/`），
> 只想打 jar 的话克隆后直接 `gradlew build` 即可，无需安装 MSVC/zig；
> 需要重新编译原生库时再用 `tools/build_all.ps1`。

## 支持与赞助

如果你喜欢 **加速碰撞 (Accelerated Recoiling)**，欢迎来 **[这里](https://github.com/wiyuka0/AcceleratedRecoiling/blob/master/3ae91be2c6a1e7447635b7b1b7454ffc.jpeg)** 请砂糖吃一顿带鱼哦 owo

## 合并增强：实体碰撞优化 (ECO) 内核

本分支把 **[实体碰撞优化 (Entity Collision Optimizer)](https://github.com/water2004/EntityCollisionOptimizer)**
的核心内核合并了进来：保留原加速碰撞的全部功能之外，新增一条「一次扫描所有候选对」的帧引擎。
合并后**默认档就是这条引擎**，而且它一个候选都不丢。

### 档位（`kernelMode`）

| 档 | 走哪条路 | 是否丢推挤 |
| :--- | :--- | :--- |
| **`AUTO`（默认）** | ECO 帧引擎（原生一次扫描所有候选对），推挤异步计算 | 否 |
| `NATIVE` | 与 `AUTO` 同路，显式写出来而已 | 否 |
| `SPARSE`（实验） | 同为 ECO 引擎，但每个实体最多保留 `maxCollision` 个对手，超出的丢弃 | **是**（用保真换速度） |
| `PARITY` | 纯 Java 的「原版等价」候选枚举（逐字复刻 1.21.1 的 `Level#getEntities`），无需原生库 | 否 |
| `GPU` | OpenCL 后端**只枚举候选对**，判定与冲量仍回 Java 逐个 `doPush`；候选来自 2.5 格哈希网格、会漏配 | **是**（对照档） |
| `VANILLA` | 完全禁用加速路径 | 否 |

引擎那条路（`AUTO` / `NATIVE` / `SPARSE`）与 `PARITY` 的差别在**实现方式**，不在配对方程：
配对集合与逐对算式都对齐原版推挤，离线逐位对拍（`tools/test` 下的 `PushRunEquivalenceTest`、
`NativeMovementParityTest`、`PushProbe`）都通过。**但它并没有与原版做过「最终速度逐位等价」的
证明**——要那一级的等价，请用 `PARITY`。具体还差什么，诚实地说有两点：

* 引擎内部「一次遍历每对」与「逐 source 各跑一遍」这两个实现之间有一处**强度差异未对齐**
  （离线对拍实测逐 source 是前者的 1.89 倍）。两个版本的受理规则本就不同（一次遍历那版多一道
  队伍过滤、且只检查扫描序靠后那个的休眠状态），要真正对齐得先把受理规则统一。**注意：**
  试过按 1.89 倍补上，结果游戏内 move/collide 反而翻倍更慢，所以那一版已回滚——这条注释里
  原本写的「两版等价」应视为**未证实**。
* 「什么时候推」与原版有结构差异：原版是每个实体在自己 tick 时推周围的实体，引擎是把整帧的
  推挤算完后在 tick 末尾回写。**这条已经做过对照实验，结论是它与下面那条速度残差无关**：
  把 `asyncPush` 关掉会改用「逐实体在自己 `pushEntities` 上落盘」的结构（落点与原版一致），
  实测速度仍是 1.1～1.2、`move/collide` 也没变，只多花 1.8 ms。
* **群体平均速度仍高于原版**：同一场景（2088 实体）交替对照，引擎的全场平均速度是
  **1.1～1.2 m/tick**，而 `PARITY`（原版）是 **0.45～0.48**——**约 2.8 倍**。代价上已经贴平
  （`move + collide` 23.4 ms 对 22.1 ms，只差 6%），所以它不影响帧时间，但**是一个真实存在、
  尚未定因的行为差异**：密集场景里畜群比原版「活」。
  已排除两条嫌疑：**发布时刻**（上面那条实验否掉了）、**候选集规模**（`kernelMode=GPU` 时序是
  原版的、候选是漏的，速度落在 0.22，比原版还低一半，说明候选多寡对速度影响极大但方向相反）。
  剩下指向**候选受理集合与冲量累加**这条轴。**不建议先改代码再解释**——这一类推测性改动试过一次
  （按 1.89 倍补冲量），游戏内 `move/collide` 反而翻倍，已回滚。

### 两个配套开关

* **`asyncPush`（默认 `true`）**：推挤交给 worker 线程算，并在 tick 末尾批量落盘。
  帧在 tick HEAD 就把推挤交出去，服务器线程只在 tick 末尾取结果；输入是帧首快照，
  **所以结果不因这个开关而变**，变的只是那约 4 ms 算在哪个线程上（实测 tick 25.7 → 20.9 ms）。
  **关掉它会同时换掉落点**：推挤回到服务器线程同步算，并且改成在**实体自己的 `pushEntities`**
  上逐实体落盘——落点与原版「轮到谁推谁」一致。代价是 +1.8 ms（异步那部分重叠没了）；
  实测它不改变行为（速度、`move/collide` 都一样），所以留着它是给「要结构上更贴原版」用的。
  **遇到换档后出怪事也先关它排查**：跨线程跑原生代码时，别的模组若在这条路上有自己的钩子、
  或对实体/帧状态做了非线程安全的假设，关掉就回到完全同步的路径。
* **`gpuEnginePush`（默认 `false`）**：引擎的推挤算力放显卡还是 CPU。
  打开后整段全量推交给 OpenCL 内核（与 CPU 原生逐位一致，对拍 6000 帧 0 项不一致）。
  默认关：实测显卡 4.9 ms/帧 对 CPU 4.2 ms/帧，**CPU 更快**。注意这不是「核显争用」——测试卡的 OpenCL 设备名是 gfx1101（RDNA3 独显，Radeon PRO V710），并行数远超 2088 个实体的负载所需；慢在每次推挤要经历上传→内核→下载的完整往返（约 4.8 ms 等结果），而 CPU 原生只有约 4.2 ms 且结果就地可用。独立显存/更强的卡上结论可能反转，所以留成开关。
  注意它**只影响引擎自己**，不改变上面的选路；和 `kernelMode=GPU`（AR 的候选枚举）不是一回事。

### 指令

```
/acceleratedrecoiling check                                  # 看原生可用性、各档读数、全部配置
/acceleratedrecoiling set kernelMode SPARSE asyncPush false maxCollision 24
/acceleratedrecoiling set kernelMode AUTO asyncPush true      # 一条指令可改多对参数
/acceleratedrecoiling asyncPush                               # 单对快捷写法，布尔不给值即取反
/acceleratedrecoiling save                                    # 写入 JSON
/acceleratedrecoiling abtest AUTO SPARSE 100 6                # 交替对照，结果写日志
```
`abtest` 会按段来回切档并统计每段均值——手动「切档 → 等 → 读」会被场景漂移骗（同一档位内部
`collide` 单次就能摆动十倍），交替对照把漂移均摊掉。另有四个伪档名，用来单独隔离某个变量：

| 伪档名 | 切的是什么 |
| :--- | :--- |
| `ENGINE_GPU` / `ENGINE_CPU` | 同一个引擎、只差推挤算力在显卡还是 CPU |
| `ENGINE_ASYNC` / `ENGINE_SYNC` | 同一个引擎、只差推挤的**发布时刻**（末尾批量 / 逐实体） |

### 内核为什么需要异步、默认为什么是引擎

合并过程中查出过一个关键 bug：推挤的速度回写原本放在 tick 末尾，但写入值是在帧首算好的
`帧首速度 + 冲量`，**把实体自己 tick 里算出的摩擦与重力衰减整份覆盖掉了**——冲量因此每帧叠加、
永不衰减，畜群速度一路涨到 3～6 m/tick（原版安静态是 0.41），`move/collide` 因此贵出 2～4 倍。
改成「存冲量、落到实体时现读现加」之后 `move + collide` 从 52.2 落到 23.4。

`AUTO` 一度走的是 `GPU` 那条候选枚举（当时引擎要 45.8 ms、比它慢 20 ms），修好这个 bug 并
改成异步之后结论反过来：引擎又快又不漏，所以默认档换成了引擎。**整段过程中默认档从 45.8 ms
降到 21～24 ms，而它现在是保真的那个。**

### 构建

本分支用 `tools/build_all.ps1` 一条命令跑完全流程（原生双端 → Java → 打包 → 启动预检 → 部署）：

```powershell
& .\tools\build_all.ps1                 # 全流程，含启动预检
& .\tools\build_all.ps1 -SkipNative     # 只重打 Java/jar
& .\tools\build_all.ps1 -NativeOnly     # 只编原生库
& .\tools\build_all.ps1 -NoDeploy       # 只产出不部署
```

* 原生编译需要 JDK 21、MSVC（`vcvars64.bat`）与 zig（交叉编译 Linux `.so`），路径在脚本顶部集中配置。
* **不要直接用 `gradlew jar`**：mixin 的注入签名错误只在类被加载、注入被施加的那一刻抛出，
  编译器与打包脚本都发现不了。脚本里的**启动预检**会真起一次服务端把这类问题挡在部署之前。
* 只想打 jar（不重编原生）：仓库里已带预编译 natives（`AcceleratedRecoiling-third-party/out/`），
  克隆后直接 `gradlew build` 即可。
* 产物：`dist/acceleratedrecoiling-EcoUpAdd-21.1.14-eco.jar`，内含
  `natives/windows-x64/AcceleratedRecoiling.dll` 与 `natives/linux-x64/AcceleratedRecoiling.so`，
  以及打包进去的 jocl（GPU 后端依赖）。
* 版本号在 `gradle.properties` 的 `mod_version`（当前 `21.1.14-eco`）与 `tools/build_all.ps1` 顶部的
  `$ModVersion` 两处，改的时候要一起改；脚本会在打包时用 gradle 新生成的 `neoforge.mods.toml` 覆盖基准
  jar 里的旧版本号，并在部署后回读校验「文件名与游戏内显示的版本一致」。
* 部署时会自动把同 modId 的其它 jar 改名为 `.disabled`（同 modId 共存会直接启动崩溃），
  改完回读确认，有残留就让构建失败。

## 鸣谢与开源协议

本项目基于 **MIT 协议** 开源。

### Entity Collision Optimizer (ECO)

本模组的实体碰撞内核合并自 **[EntityCollisionOptimizer](https://github.com/water2004/EntityCollisionOptimizer)**（MIT 协议），
在此向 ECO 项目及其作者表示特别感谢——本构建的核心能力直接来自该项目：

*   **[water2004](https://github.com/water2004)** — ECO 仓库维护者。
    合并进来的内容包括：「一次扫描所有候选对」的帧引擎与原生全量推挤（`eco-native/` 的主体）、
    `PARITY` 原版等价内核，以及多次经实测验证的关键修复（可推实体查询盒对齐原版、
    段内顺序在构建时定型、静默失败留痕等）。
*   **[wiyuka-owo](https://github.com/wiyuka-owo)** — ECO 的主要开发者（270+ 次提交），
    上述内核与修复的绝大部分代码出自其手。

没有 ECO 就没有这个合并分支。如需原版 Fabric 版本（MC 26.2+、Java 25），
请直接使用上游仓库，它的更新比本分支活跃得多。

### Accelerated Recoiling

特别感谢以下开发者对本项目的核心思路与代码移植提供的巨大帮助：
*   **[Argon4W](https://github.com/Argon4W)**: 原始构思与核心思路。
*   **[fireboy637](https://github.com/fireboy637)**: Architectury API 移植方案的核心代码。
*   **[hydropuse](https://github.com/hydropuse)**: JDK 22 兼容方案的核心代码。
*   **[wellcoming](https://github.com/wellcoming)**: Docker Ubuntu镜像解决方案。
