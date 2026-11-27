# SmartMaid 项目可用性分析

> 评估对象：`E:\study\bash\GitHub\zmalP\SmartMaid`<br>
> 评估版本：`feature/jump-calibration` @ `2030947681c64940b4dfd39c271e23fef4a228d8`（2026-11-27 16:47:35 +0800）<br>
> 评估日期：2026-11-27<br>
> 评估方式：源码静态审查 + 26.2/26.3 双版本 clean build + JUnit 结果解析 + 运行日志核对 + 文档交叉检查<br>
> 未覆盖：真人 GUI/UX 测试、专用服务器联调、长时间压力/浸泡测试、26.3 实机输入验证、代码覆盖率统计

以下评分与原始风险记录保留为基线；实施后的状态见[修复与验收](#修复与验收2026-11-27)。

---

## 一、结论先行

**一句话结论：SmartMaid 已经达到“可开发、可单机游玩、可内部技术预览”的状态；尚未达到“可公开服务器运营、可直接上架整合包平台”的生产可用状态。**

| 使用场景 | 可用性判断 | 评分 | 说明 |
|---|---|---:|---|
| 单机 / 本地开发 | **可用** | 8.4 / 10 | 构建、启动、核心玩法链路均已验证 |
| 好友局域网联机 | **有条件可用** | 6.8 / 10 | 需管理员可控，且先处理权限与存档问题 |
| 公共服务器 | **不建议直接开放** | 3.8 / 10 | 存在权限绕过、WS 鉴权、存档覆盖等高风险项 |
| 整合包 / 平台分发 | **有条件可用** | 5.5 / 10 | 代码与元数据基础不错，但许可、英文文档、平台收录仍未闭环 |
| 二次开发 | **可用** | 8.0 / 10 | Kotlin 工程结构清晰、文档充足，但 CI/覆盖率不足 |

**综合评分：6.6 / 10（有条件可用）。**

三条最重要的判断：

1. **构建可靠性已经过关。** 26.2 与 26.3 两套配置均完成 clean build，结果都是 `8 suites / 40 tests / 0 failures / 0 errors / 0 skipped`。
2. **核心玩法已经成形。** 跟随、战斗、跳跃寻路、挖矿建造、合成烧炼、任务双队列、女仆菜单、感知快照等功能齐全；不装桌宠也能独立玩核心内容。
3. **上线前必须先修安全与稳定性问题。** 菜单动作可绕过 `/maidai` 的 GM 权限、WS 握手形同虚设、跨维度可重复召唤并覆盖同一存档、任务 tick 异常未隔离、脚本参数无上限。

---

## 二、评估口径与证据

### 2.1 评分维度

| 维度 | 权重 | 得分 | 判断 |
|---|---:|---:|---|
| 可构建性 | 15% | 9.0 | 双版本 clean build、40 项测试全绿 |
| 可安装性 | 10% | 7.0 | jar 即插即用，但版本绑定强、无 ModMenu |
| 可运行性 | 15% | 8.0 | 开发环境日志健康；专用服/长稳未验证 |
| 功能可用性 | 20% | 8.5 | 功能面完整，跳跃/战斗有真机验收 |
| 多人安全 | 20% | 3.5 | 权限、WS、存档、异常隔离均有缺口 |
| 可维护性 | 10% | 6.5 | Kotlin 化完成；测试面窄、无 CI/静态门禁 |
| 易用性/本地化 | 5% | 5.0 | 中文硬编码、lang 仅 4 个键、配置手改 |
| 分发合规 | 5% | 4.5 | GPL + CC BY-NC-SA + GPL 表情 + 未上平台 |

> 权重按“对公开上线的影响”主观设定，不是行业标准；单机场景应重点看构建/功能两项，公共服务器场景应重点看多人安全项。

### 2.2 关键证据

| 证据项 | 实测结果 | 来源 |
|---|---|---|
| 26.2 clean build | `BUILD SUCCESSFUL in 58s`；8 suites / 40 tests / 0 failures / 0 errors / 0 skipped | `build-usability-20261127.log` |
| 26.3 clean build | `BUILD SUCCESSFUL in 1m 1s`；同样 8 suites / 40 tests 全通过 | `build-usability-26.3-20261127.log` |
| 构建产物 | `smartmaid-0.1.3.jar`：26.2 为 1,297,977 bytes；26.3 为 1,274,893 bytes；均含 sources jar | `SmartMaid/build/libs/` |
| 内置依赖 | 26.2 打包 `player_animation_library-1.2.6.jar`；26.3 打包 `player_animation_library-1.2.7.jar`；jar 内共 322 条目 | 产物解包核对 |
| 测试分布 | 8 个测试类、40 个测试：SmokeTest 1、MaidTaskCommandTest 5、FurnaceLeaseTest 3、MaidTaskManagerPersistenceTest 2、MaidTaskManagerQueueTest 11、MaidTaskQueueTest 11、ResumableTest 5、SlotsTest 2 | `SmartMaid/build/test-results/test/TEST-*.xml` |
| 运行日志 | `Loading Minecraft 26.2 with Fabric Loader 0.19.5`；`Smart Maid initialized (debug=true, verbose=true)`；末行 16:45:44 感知快照正常 | `SmartMaid/run/logs/latest.log` |
| 运行异常 | 无模组异常/崩溃；仅 3 条无关 ERROR：`Failed to fetch user properties`（401）、`Failed to fetch Realms feature flags`、`Failed to retrieve profile key pair`（401） | `latest.log` |
| 代码规模 | `src/main/kotlin` 128 个 `.kt`、17,386 行；`src/main/java` 为 0；TODO/FIXME/XXX/HACK 计数为 0 | 静态统计 |
| 工作区状态 | 干净；HEAD 领先 `main` 59 个提交 | `git status` / `git log` |

### 2.3 文档与验证状态

- `SmartMaid/KOTLIN_MIGRATION_STATUS.md`：N1 自动测试 41 PASS / 0 FAIL；N2 clienttest 37/37 PASS；N3 记录 26.2/26.3 各 8 suites / 40 tests；P3 平地、1 格台阶、3 格短沟各 5/5，R 世界 `check.record` 47 PASS / 0 FAIL，4 格沟安全停止（`minY=66`，不跨越）。
- `SmartMaid/TEST_SYSTEM.md`：离线单测、AutoTest、ClientTest、静态门禁、错误通道五层；26.3 游戏输入按用户决定不补做。
- `SmartMaid/README.md:11`：明确写着“双版本构建通过不代表 26.3 游戏输入已验证”。
- `SmartMaid/README.md:144`：仍写“当前各 7 suites / 37 tests 全通过”，与实测 8 suites / 40 tests 不一致，属于陈旧文档。

---

## 三、分维度分析

### 3.1 可构建性：9.0 / 10

**结论：构建系统可靠，双版本可重复产出。**

- 26.2 默认配置：`gradlew.bat --offline --no-daemon --console=plain clean build`，`BUILD SUCCESSFUL in 58s`，8 suites / 40 tests 全绿。
- 26.3 配置：`-Pmc_series=26.3 -Pminecraft_version=26.3 -Ploader_version=0.19.5 -Ploom_version=1.18.4 -Pfabric_api_version=0.161.0+26.3`，`BUILD SUCCESSFUL in 1m 1s`，同样 40 tests 全绿。
- 产物为 `smartmaid-0.1.3.jar` 与 sources jar；PAL 通过 jar-in-jar 内置，用户不需要单独安装。
- `fabric.mod.json` 依赖声明完整：Java >=25、Fabric Loader >=0.18.4、Fabric Language Kotlin >=1.14.2+kotlin.2.4.21、Fabric API >=0.161.0+26.2（26.3 线对应 +26.3）、PAL >=1.2.6（26.3 线 >=1.2.7）。

**扣分项：**

- 依赖 JDK 25，门槛高于常见 Minecraft 模组开发环境。
- 26.2/26.3 是两条构建线，分别产出不同 jar，不能一个 jar 通吃；用户下错版本会直接启动失败。
- 本次使用 `--offline` 能通过，依赖本机 Gradle 缓存；全新机器首次构建仍需联网拉依赖，未做“无缓存冷启动”验证。

### 3.2 可安装性：7.0 / 10

**结论：对有经验的玩家是“拖进 mods 就能用”，对普通玩家仍有门槛。**

- 安装路径简单：装 Fabric Loader + Fabric API + Fabric Language Kotlin，把 jar 放进 `mods/` 即可；PAL 已内置，Java 25 是硬要求。
- 26.2/26.3 分别有独立 jar；`README.md` 的环境表已经写清楚，但普通玩家容易忽略版本线。
- 缺少 ModMenu / 图形化配置入口：`config/smartmaid/main.json` 需要手改；女仆自己的 `Shift + 右键` 菜单可以改跟随、护主、属性、掉落等设置，但不能替代模组级配置。
- 没有 CurseForge / Modrinth 项目页；只有 GitHub Release / Actions 下载，普通玩家发现和安装成本较高。
- 适合“整合包作者或技术玩家手动安装”，不适合“零基础玩家一键安装”。

### 3.3 可运行性：8.0 / 10

**结论：开发环境运行健康，但缺少专用服务器和长时间稳定性证据。**

- `latest.log` 显示 Minecraft 26.2 + Fabric Loader 0.19.5 正常启动；`Smart Maid initialized (debug=true, verbose=true)`；客户端世界 P3Tester 正常；末行 16:45:44 服务端感知快照完整（女仆位置 147.7/66/91、iron_sword、health 23.7/24、主人 CREATIVE）。
- 无 `crash-reports`；日志中 3 条 ERROR 都是 Minecraft 在线服务/Realms 相关，与模组无关。
- 但目前证据主要来自开发环境的内置服务器，**没有专用服务器（dedicated server）联调记录**，也没有 30 分钟以上的 soak test。
- `MaidTaskManager.kt:358` 的 `task.tick(maid)` 没有 try/catch，且唯一调用方 `SmartMaidEntity.kt:639` 也没有兜底；单个任务异常可能冒泡到服务端 tick，长期运行稳定性存在隐患。

### 3.4 功能可用性：8.5 / 10

**结论：功能面完整，核心玩法独立可用，属于项目最强项。**

已实现的功能包括：

- 召唤与归属：`/summonmaid`，每玩家一只，退出消失、重召恢复。
- 跟随、坐/站、跳跃寻路：跨 1~3 格沟、上 1 格高台、下跳 1~3 格，离线精确解表。
- 战斗：近战、走位、远程弓箭、盾牌格挡、半血进食回血；战斗优先级高于 AI 任务。
- 生产任务：挖矿、耕作、建造、收集、合成、烧炼、两步存箱子、物品转移。
- 任务系统：长期/短期双队列、优先级插队、打断暂停、进度接续。
- 原子脚本协议 `script`：`find`/`inventory`/`block_at`/`distance` + `harvest`/`move`/`equip` 等组合，支持 `as`/`if`/`loop`/`assign`/`terminate`。
- 感知快照 `/maidperception`、AI 指令桥 `/maidai`、桌宠 WebSocket 联动（默认关闭）。
- 游戏内女仆菜单：3D 预览、装备概览、生命/饱食状态、任务页、设置页。
- 整合包友好：命令权限自动降级、桥接默认关闭、搭路白名单 `#smartmaid:bridge_blocks`、战斗排除表 `#smartmaid:never_target`、按存档隔离女仆数据、合成白名单。

**验证证据：**

- P3 跳跃跑道：平地、1 格台阶、3 格短沟各 5/5；R 世界 `check.record` 47 PASS / 0 FAIL。
- 4 格沟：安全停止（`minY=66`，不跨越），属于已声明的能力边界。
- 战斗：COMBAT-01（每 tick 调度）、COMBAT-02（拉弓误判进食）、COMBAT-04（缓存目标不刷新）已在 HEAD 修复；COMBAT-03（动作切换/盾后恢复语义）、COMBAT-05（持盾 0.5s 语义）仍开放。

**扣分项：**

- 26.3 游戏输入未验证；26.3 目前只证明“能构建、能跑测试”，不能证明“能正常玩”。
- 战斗任务期间 `status.combat` 字段可能一直为 `false`，P3 报告已把它标为不可靠。
- 120 tick 观测窗口不能替代无限返程/长时间运行验证。
- 4 格沟不支持跨越，是明确的能力边界，不是 bug，但用户预期需要写清楚。

### 3.5 多人安全：3.5 / 10

**结论：这是当前最大的短板，公共服务器不能直接开放。**

#### P0-1 菜单动作绕过 GM 权限

- 位置：`SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/network/ModNetworking.kt:98-139`
- 事实：`MaidMenuActionPayload` 收到任意 `cmd` / `params` 后，直接 `MaidAIBridge.execute(maid, json)`；全程没有 `player.hasPermissions(2)` 或任何 GM 检查。
- 对比：`/maidai` 在 `SmartMaid.kt:34-40` 被注册为 `admin = Commands.LEVEL_GAMEMASTERS`；菜单 payload 却绕过了这个限制。
- 影响：普通玩家可以伪造 C2S 包，对自己的女仆执行本应 GM 才能用的 `script` / `build` / `mine` 等 AI 指令；在公共服务器上属于权限边界失效。
- 缓解事实：`findOwnerMaid(level, player)` 只遍历当前维度，且只允许操作自己的女仆，因此不是“控制别人的女仆”，而是“绕过 GM 使用高级指令”。

#### P0-2 WebSocket 握手与鉴权形同虚设

- 位置：`SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/bridge/MaidWsClient.kt:406-408`、`:441-450`
- 事实：`hello_ack` 分支无条件 `handshaken = true`，即使 `ok(msg) == false` 也只多打一条日志；`handshaken` 在整个文件中只赋值，从未在 `command` 前校验。
- 配置：`BridgeConfig.kt:87-100` 的 `ws.token` 默认空字符串，`ws.url` 默认 `ws://127.0.0.1:21420`（明文）。
- 影响：本机任意进程或伪桌宠服务端可以在未完成有效握手的情况下直接发送 `command`，进而调用 `MaidAIBridge.execute`。
- 缓解事实：`BridgeConfig.defaults()` 已把 `ws.enabled` 默认设为 `false`，所以默认配置不暴露；但一旦用户开启桌宠联动，鉴权缺口就存在。

#### P1-3 跨维度重复召唤覆盖同一存档

- 位置：`SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/command/SummonMaidCommand.kt:33-40`、`SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/data/MaidDataManager.kt:91-97`
- 事实：`SummonMaidCommand` 只遍历 `player.level()` 当前维度查找同 owner 女仆；换维度可以再召唤第二只。`MaidDataManager.fileFor` 以 `dirFor(maid).resolve("$owner.dat")` 命名，`dirFor` 使用存档根 `server.getWorldPath(LevelResource.ROOT)/smartmaid/maids`，与维度无关。
- 影响：两只同 owner 女仆会读写同一个 `.dat`，后写覆盖前写，装备/任务状态可能丢失或串档。
- 缓解事实：README 已实现“按存档隔离”，多存档之间不会串；但同一存档内的跨维度重复仍存在。

#### P1-4 任务 tick 异常未隔离

- 位置：`SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/maidtask/MaidTaskManager.kt:358`、`SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/SmartMaidEntity.kt:639`
- 事实：`task.tick(maid)` 直接调用；`SmartMaidEntity.aiStep` 服务端分支直接 `this.maidTaskManager.tick()`，两层都没有 try/catch。
- 影响：单个任务抛异常可能冒泡到服务端 tick，轻则任务队列卡死，重则服务器 tick 崩溃。
- 建议：每个 task tick 包 try/catch，记录任务 id/异常栈，失败任务标记 `FAILED` 并继续下一个。

#### P1-5 脚本参数无上限

- 位置：`SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/bridge/MaidAIBridge.kt:532-536`、`SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/maidtask/ScriptTask.kt:61-65`、`:219`
- 事实：`intParam` 只做 `asInt`，没有范围限制；`max_steps = max(1, 值)`、`max_iter = max(1, 值)` 也都没有上限。
- 影响：恶意或误配置的 `script` 可以设置极大的循环次数/步数，导致服务端长时间卡顿甚至看门狗崩溃。
- 建议：为 `max_steps`、`max_iter`、循环次数、单次命令长度设置硬上限，并增加每玩家/每女仆的速率限制。

#### 其他相关事实

- `BridgeConfig.kt:114`：`enabled = !cfg.has("enabled") || cfg.get("enabled").asBoolean`；`:128` 对 `ws.enabled` 同理。缺键时默认开启，只有旧/残缺配置会触发；新生成的默认文件是安全的，但迁移逻辑应显式写死默认 `false`。
- `SmartMaidConfig.kt:55`：`commandPermission="auto"` 在 `INTEGRATED` 返回 `LEVEL_ALL`，专用服务器返回 `LEVEL_GAMEMASTERS`；这解决了单机整合包“不开作弊无法 `/summonmaid`”的问题，但也意味着局域网集成服务器上的普通玩家会获得 `common` 命令权限，若开放 LAN 给不信任玩家需显式改为 `gamemasters`。
- `MaidMenuActionPayload` 没有速率限制；结合 P0-1，公共服务器上可被高频包刷任务队列。

### 3.6 可维护性：6.5 / 10

**结论：代码结构清晰、迁移完成度高，但测试与工程化不足。**

- 正面：`src/main/java` 为 0，Kotlin 迁移完成；源码 128 个 `.kt`、17,386 行；TODO/FIXME/XXX/HACK 为 0；`KOTLIN_MIGRATION_STATUS.md`、`TEST_SYSTEM.md`、`DESIGN_*` 文档较完整。
- 负面：`src/test` 只有 8 个 Java 测试类、40 个测试，且全部集中在任务队列/槽位/持久化/命令等局部逻辑；没有任何 Kotlin 测试，没有网络层、WS、GUI、存档迁移、跨维度场景测试。
- 没有 `.github` 目录/CI workflow；根目录 `qodana.yaml` 是 `qodana.starter` 配置，没有 failureConditions/覆盖率门禁，不能阻止回归。
- 大文件需要关注：`MaidAutoTest.kt` 54,664B、`SmartMaidEntity.kt` 43,714B、`CraftExecutor.kt` 37,942B、`MaidClientTest.kt` 30,577B、`MaidAIBridge.kt` 29,969B、`MaidTaskManager.kt` 29,200B；后续修改的回归半径较大。
- 没有覆盖率数据；AutoTest/ClientTest 偏“现场验收工具”，不是稳定 CI 测试。

### 3.7 易用性 / 本地化：5.0 / 10

**结论：中文玩家可用，英文玩家和国际化场景明显不足。**

- `assets/smartmaid/lang/zh_cn.json` 与 `en_us.json` 各只有 4 个键：`key.smartmaid.open_inventory`、`gui.smartmaid.maid_inventory`、`category.smartmaid`、`gui.smartmaid.maid_control`。
- 约 59 处 `Component.literal("中文…")` 硬编码；93/128 个 Kotlin 文件含中文、2,074 行含中文（含注释）。大量界面/提示文本不会随语言切换。
- 对中文玩家：菜单、提示、文档都是中文，接受度高。
- 对英文玩家：UI 会中英混杂，README 也没有英文版；Modrinth 规则要求英文描述，这会直接影响上架。
- 配置：模组级配置需要手改 `config/smartmaid/main.json`，没有 ModMenu 入口；女仆菜单只能改玩家绑定设置，不能改全局配置。
- 文档陈旧：`SmartMaid/README.md:144` 的 7 suites/37 tests 与当前 8/40 不一致，容易误导验证结论。

### 3.8 分发 / 合规：4.5 / 10

**结论：代码许可清楚，但素材许可与平台元数据限制了商业整合包和平台收录。**

依据 `NOTICE.md` 与 `SmartMaid/docs/MODPACK_COMPATIBILITY.md`：

- 代码：上游 MIT 部分保留 MIT；2026-11-21 起维护部分为 `GPL-3.0-only`。
- 女仆主皮肤：`smart_maid.png` 与根目录 `皮肤大肥鱼.png` 为 **CC BY-NC-SA 4.0**，**禁止商业用途**，改编需同许可分发。
- 表情：`assets/smartmaid/emotes/` 下 11 个 Emotecraft JSON 为 **GPL-3.0** 未修改副本，随 jar 分发需要履行 GPL 分发义务。
- `MODPACK_COMPATIBILITY.md`（2026-09-28）还记录：商店图标疑似 AI 生成（Modrinth 明令禁止 AI 图标）、未声明 AI 生成内容、README 无英文版。
- 平台：目前只在 GitHub，没有 CurseForge / Modrinth 项目页；整合包无法通过平台项目引用。
- 正面：当前 `fabric.mod.json` 已补 `contact` / `suggests` / 精确版本约束；PAL 版本已在元数据声明；桥接默认关闭、命令权限自动降级、搭路白名单、战斗排除表、按存档隔离等“整合包友好”改造已落地。

**结论：** 适合非商业整合包/私人分发；不适合商业整合包、CurseForge Reward Program、Modrinth 上架（在素材与英文文档问题解决前）。

---

## 四、风险清单（按优先级）

| 级别 | 风险 | 位置 | 影响 | 建议 |
|---|---|---|---|---|
| **P0** | 菜单动作绕过 GM 权限 | `ModNetworking.kt:98-139`、`MaidAIBridge.kt:66` | 普通玩家可执行 `script`/`build`/`mine` 等高级 AI 指令 | 加 `player.hasPermissions(2)` 或配置权限；命令白名单；速率限制 |
| **P0** | WS 握手不校验、token 默认空 | `MaidWsClient.kt:406-408`、`:441-450`、`BridgeConfig.kt:87-100` | 本机任意进程/伪桌宠可直接下发命令 | `hello_ack` 校验 `ok`；未握手拒绝 command；token 必填；默认关闭 |
| **P1** | 跨维度重复召唤覆盖存档 | `SummonMaidCommand.kt:33-40`、`MaidDataManager.kt:91-97` | 装备/任务状态丢档、串档 | 全维度查重或按 maid UUID 存文件 |
| **P1** | 任务 tick 异常未隔离 | `MaidTaskManager.kt:358`、`SmartMaidEntity.kt:639` | 单任务异常可能打崩服务端 tick | 每个 task tick try/catch，失败任务隔离 |
| **P1** | 脚本参数无上限 | `MaidAIBridge.kt:532-536`、`ScriptTask.kt:61-65,219` | 大循环/大 step 导致卡顿或看门狗 | 硬上限 + 每玩家配额 + 速率限制 |
| **P2** | 旧配置缺键时桥接默认开启 | `BridgeConfig.kt:114,128` | 旧/残缺配置可能意外开 WS | 缺键一律 false，显式迁移 |
| **P2** | 本地化硬编码 | 各 GUI/提示；lang 仅 4 键 | 英文玩家不可用，平台审核受阻 | 文案抽 key，补齐 en_us |
| **P2** | 文档陈旧 | `SmartMaid/README.md:144` | 验证结论误传 | 改成 8 suites/40 tests，或引用状态文档 |
| **P2** | 无 CI/静态门禁 | 无 `.github`；`qodana.yaml` starter | 回归无法自动拦截 | GitHub Actions：build + test + detekt/ktlint |
| **P3** | 26.3 游戏输入未验证 | `README.md:11` | 不能宣称 26.3 可玩 | 补 26.3 实机输入验收 |
| **P3** | 4 格沟不支持跨越 | P3 报告 | 用户预期落差 | 文档写明能力边界 |
| **P3** | 战斗 `status.combat` 不可靠 | P3 报告 | 外部集成可能误判 | 修复字段语义或标注 deprecated |

---

## 五、可用性场景结论

### 5.1 单机 / 本地开发：推荐使用

- 构建、启动、召唤、任务、跳跃、战斗链路均已验证。
- `/summonmaid` 在集成服务器默认 `LEVEL_ALL`，单机不开作弊也能用。
- 不装桌宠也能玩核心内容；装桌宠才需要 WS。
- 建议：使用 26.2 稳定线；26.3 只用于构建/兼容性验证，不要作为主玩版本。

### 5.2 好友局域网：可用，但需管理员控制

- 建议把 `commandPermission` 显式设为 `gamemasters`，只给房主/管理员。
- 关闭 `ws.enabled`，或至少设置非空 token。
- 提前约定“每玩家一只女仆、不要跨维度重复召唤”的使用规则。
- 在修复 P0-1 前，不要让不可信玩家进入 LAN 世界。

### 5.3 公共服务器：暂不开放

必须至少完成：

1. 菜单动作权限校验；
2. WS 握手/token/command 门禁；
3. 跨维度召唤与存档隔离；
4. 任务 tick 异常隔离；
5. 脚本参数上限与速率限制；
6. 专用服务器 30 分钟以上 soak test。

在这些完成前，公共服务器上可能出现权限越界、存档丢失、服务端卡死等问题。

### 5.4 整合包 / 平台：有条件推进

- 适合：非商业整合包、私人整合包、技术向整合包。
- 不适合：商业整合包、CurseForge Reward Program、Modrinth 上架（素材/英文文档问题未解决）。
- 上架前需：重画/替换 AI 图标（如确实为 AI 生成）、明确 CC BY-NC-SA 声明、处理 GPL 表情分发、补英文 README、建立 CurseForge/Modrinth 项目页、提供 changelog。
- 现有优势：桥接默认关闭、权限自动降级、数据包 tag 可覆盖、PAL 版本声明、按存档隔离，这些已经明显降低整合包作者的接入成本。

---

## 六、建议路线图

### P0：公开测试前必须完成（约 1~3 天）

- [ ] 在 `MaidMenuActionPayload` 入口加权限检查，和 `/maidai` 保持同一权限级别。
- [ ] `MaidWsClient` 校验 `hello_ack` 的 `ok`，未握手拒绝 `command`；token 为空时拒绝启用 WS。
- [ ] 修复跨维度重复召唤：全维度查重，或按 maid UUID 保存文件。
- [ ] 给 `MaidTaskManager.tick` 加 per-task try/catch。

### P1：公共服务器前完成（约 1~2 周）

- [ ] `ScriptTask` 的 `max_steps` / `max_iter` / 循环次数加硬上限。
- [ ] 菜单/WS 命令加速率限制与审计日志。
- [ ] 增加网络层、WS、跨维度、存档迁移测试。
- [ ] 专用服务器 30 分钟 soak test；记录 TPS/内存/异常。
- [ ] 26.3 实机输入验收。

### P2：平台/整合包前完成（约 2~4 周）

- [ ] 处理 CC BY-NC-SA 皮肤、GPL 表情、AI 图标的许可与平台规则。
- [ ] 补英文 README / 英文描述。
- [ ] 增加 ModMenu 或等价配置 GUI。
- [ ] 建立 CurseForge/Modrinth 项目页与 changelog。
- [ ] 把 `SmartMaid/README.md:144` 的测试数改成 8 suites / 40 tests。
- [ ] 接入 GitHub Actions：双版本 build + test + detekt/ktlint + 覆盖率报告。

### P3：质量提升

- [ ] 目标覆盖率 ≥60%（核心任务/存档/网络层）。
- [ ] 随机化/边界测试：脚本循环、异常任务、跨维度、断线重连。
- [ ] 性能基准：100 女仆/大量任务下的 tick 时间。
- [ ] 记录 4 格沟、120 tick 窗口、`status.combat` 等已知限制。

---

## 七、上线验收清单

### 构建与测试

- [ ] `gradlew.bat --offline --no-daemon --console=plain clean build` 成功（26.2）
- [ ] 26.3 配置 clean build 成功
- [ ] `8 suites / 40 tests / 0 failures / 0 errors / 0 skipped`
- [ ] 产物 jar 可解包，PAL 版本与元数据一致

### 安全

- [ ] 非 OP 玩家发送 `MaidMenuActionPayload` 的 `script` 指令被拒绝
- [ ] WS 无 token / 错误 token / 未握手时 `command` 被拒绝
- [ ] 跨维度重复召唤被阻止，或两只女仆各自独立存档
- [ ] 脚本 `max_steps` / `max_iter` 超限被拒绝
- [ ] 高频包刷任务被限流

### 稳定性

- [ ] 单个任务抛异常不会打崩服务端 tick
- [ ] 专用服务器 30 分钟 soak test 无异常
- [ ] 26.3 实机输入验收通过（或明确标注不支持）

### 体验与分发

- [ ] `en_us` 下界面无中文硬编码（或明确标注仅中文）
- [ ] README 测试数与实际一致
- [ ] 许可/NOTICE 声明完整
- [ ] 平台项目页/英文描述/changelog 就绪（如上架）

---

## 附录 A：构建与测试命令

### 26.2

```bash
cd SmartMaid
gradlew.bat --offline --no-daemon --console=plain clean build
```

结果：`BUILD SUCCESSFUL in 58s`，8 suites / 40 tests 全绿。

### 26.3

```bash
cd SmartMaid
gradlew.bat --offline --no-daemon --console=plain clean build \
  -Pmc_series=26.3 \
  -Pminecraft_version=26.3 \
  -Ploader_version=0.19.5 \
  -Ploom_version=1.18.4 \
  -Pfabric_api_version=0.161.0+26.3
```

结果：`BUILD SUCCESSFUL in 1m 1s`，8 suites / 40 tests 全绿。

## 附录 B：测试类明细

| 测试类 | 测试数 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| `SmokeTest` | 1 | 0 | 0 | 0 |
| `MaidTaskCommandTest` | 5 | 0 | 0 | 0 |
| `FurnaceLeaseTest` | 3 | 0 | 0 | 0 |
| `MaidTaskManagerPersistenceTest` | 2 | 0 | 0 | 0 |
| `MaidTaskManagerQueueTest` | 11 | 0 | 0 | 0 |
| `MaidTaskQueueTest` | 11 | 0 | 0 | 0 |
| `ResumableTest` | 5 | 0 | 0 | 0 |
| `SlotsTest` | 2 | 0 | 0 | 0 |
| **合计** | **40** | **0** | **0** | **0** |

## 附录 C：主要证据文件

- `build-usability-20261127.log`
- `build-usability-26.3-20261127.log`
- `SmartMaid/build/test-results/test/TEST-*.xml`
- `SmartMaid/run/logs/latest.log`
- `SmartMaid/KOTLIN_MIGRATION_STATUS.md`
- `SmartMaid/TEST_SYSTEM.md`
- `SmartMaid/README.md`
- `SmartMaid/docs/MODPACK_COMPATIBILITY.md`
- `NOTICE.md`

## 修复与验收（2026-11-27）

**构建：** `feature/jump-calibration` @ `ae0d558` 的生产及测试代码分别执行 26.2、26.3 离线 `clean build`，耗时 50 秒、52 秒；每版均为 **15 suites / 61 tests / 0 failures / 0 errors / 0 skipped**，超过计划门槛（14 suites / 57 tests）。相对历史基线增加 7 个测试类、21 个测试。每版 jar、SHA-256、JUnit XML 和构建日志已在本地分开归档，未被后一次 `clean` 覆盖。

| 计划项 | 实施状态 | 验收覆盖 |
| --- | --- | --- |
| P0-1 菜单权限边界 | 已修复：服务端只接收已有的 8 项快捷任务和 8 项队列操作，继续校验归属，不改普通玩家权限。 | `MaidMenuPolicyTest` 验证允许列表及默认拒绝；未运行真人菜单操作矩阵。 |
| P0-2 WebSocket 认证与传输 | 已修复：非空 token、明确的布尔确认、远端明文默认禁用；修改传输配置后关闭旧连接。 | `MaidWsPolicyTest`、`BridgeConfigReloadTest` 验证策略与重读；未运行桌宠实连，不发送绕过消息。 |
| P1-3 跨维度召唤与存档 | 已修复：跨维度检查现存女仆并覆盖区块卸载的过渡窗口；按玩家及女仆 UUID 保存，旧档只读迁移。 | `MaidDataFilesTest` 验证隔离、复制、迁移失败回退及重召选档。新增[跨维度 AutoTest](../SmartMaid/tools/autotest.summon-crossdim.json)，未在游戏里运行。 |
| P1-4 任务 tick 异常 | 已修复：失败任务记录日志、清理运行态并推进队列；保留连续两次失败清空同类队列的策略。 | `MaidTaskManagerExceptionTest` 覆盖推进、清理、取消及致命错误重抛；未在运行中的世界注入异常。 |
| P1-5 脚本及参数上限 | 已修复：脚本最多 512 步（包含嵌套分支）、执行步数最多 512、单个循环最多 256 次；每 tick 限制解释器工作，范围和数量在数值收窄前钳制。 | `ScriptTaskLimitsTest`、`MaidAIBridgeLimitsTest` 覆盖边界、非法值与数值溢出；未运行实机长任务或压力测试。 |

**召唤口径调整：** 实体类型保留 `.noSave()`。只要原女仆尚未实际移除，即使暂时不在可见实体索引中，也不能重复召唤。实际移除后允许重召并恢复快照；若把磁盘快照当成永久召唤锁，退出、死亡或真实卸载都会阻断恢复。旧快照不会自动删除，长期保留策略另行处理。

**未验证：** 26.2/26.3 游戏内手工验收、桌宠联调、专用服及 26.3 键盘输入。本轮不使用用户存档，也不把既有运行日志当成这次修复的证据。
