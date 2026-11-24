# SmartMaid Kotlin 迁移：Fabric 标准启动与玩法验收｜修订版执行计划

> 历史快照：保留当时的计划、失败与取证结果；旧未完成项和命令不作为当前执行入口。当前状态见 [迁移状态](../../../SmartMaid/KOTLIN_MIGRATION_STATUS.md)，后续任务见 [下一阶段开发计划](../plans/2026-11-24-next-stage-development.md)。

> **For agentic workers:** 实施时使用 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development`，按复选框跟踪。本文件是计划，不是测试通过报告。

**Goal：** 批准开工后 3 个工作日内，交付可复跑的 Loom 启动操作卡、玩法证据、旧脚本退役记录及合并建议。期限内遇到未解决的阻断，也要交付失败证据，不降低验收标准。

**Architecture：** 游戏只由项目 Gradle Wrapper 调用 Fabric Loom 启动；单机客户端的集成服务器执行 AutoTest，客户端执行 GUI 和键盘测试。配置部署、日志观测和报告读取独立于启动，不再用 Python 还原 Java 命令或处理登录凭证。

**Tech Stack：** Windows、JDK 25、Gradle Wrapper、Fabric Loom、Python。**Spec：** [原始交接及验收标准](../handoffs/2026-11-23-kotlin-migration-next-phase.md)；本次调整指定 `SmartMaid/run`、Fabric 标准启动和旧自定义启动脚本退役。

## 1. 调整动因与新旧对比

历史上 `runClient` 已完成客户端初始化，`runServer` 已启动，但进程在冒烟窗口后终止。后来失败的是 HMCL 启动命令查找和客户端工具的版本目录查找，而不是游戏内断言。故调整的是启动和取证方法，不是质量门槛。

| 决策 | 原计划 | 修订后 |
|---|---|---|
| 游戏实例 | 恢复 HMCL、登录并准备 `versions/26.2` | 唯一主验收 gameDir：`E:\study\bash\GitHub\zmalP\SmartMaid\SmartMaid\run`；不依赖用户 `.minecraft`、HMCL 或 token |
| 启动方式 | 自定义 Python 读取启动器日志并重放命令 | 模块目录执行 `gradlew.bat runClient`；`runServer` 仅用于独立服务端冒烟，不能替代玩家、GUI 或本机桌宠联调 |
| 优先级 | 先恢复启动器，再做联调 | 先跑通专用单机世界和基础断言，再测队列、持久化、GUI、按键；桌宠按需；新路有证据后再删旧路 |
| 删除与保留 | 保留一键 E2E 和 HMCL 启动脚本 | 删除 `SmartMaid/tools/launch_game.py`、`SmartMaid/tools/run_e2e_test.py` 两个旧入口；保留 `clienttest.py`、共享 `_paths.py`、静态检查、只读监测、测试配置和报告。用操作卡接替部署、起桌宠、观测、归档职责，不造第三个启动器 |

**全局约束：** 26.2 是主玩法线，26.2/26.3 均须构建并跑互操作测试。不同版本不用同一存档；不清空 `run`，不触碰用户个人世界，不回退用户改动，不提交私有配置、凭证、原始日志或存档，不批量 `git add docs/`。本计划不覆盖发布 JAR 在玩家整合包中的安装兼容性，不自动合并。

**重点复核：** 错把 `run` 拼成 `run/versions/26.2`（T1）；标题画面或无人在线（T1/T2）；旧报告假绿（T2/T3）；跨重启状态丢失（T2）；删启动代码时留下活动调用或失去桌宠观测（T4）。

## 2. 阶段、时间线与交付物

**建议排期：2026-11-24 至 11-26，均为北京时间。** 未假定人员已预约；批准延迟则顺延。开发负责人执行并留证，测试操作人负责交互，复核人签验收单，项目负责人决定是否合并。人员可以兼任；同一 `run` 同时只允许一轮游戏测试。

| 时间 / 阶段 | 前置与资源 | 必交成果 | 里程碑退出条件 |
|---|---|---|---|
| T1 启动准备：11/24 09:00–12:00 | 开工批准；开发 + 操作人，JDK 25、已有项目缓存、桌面与备份空间 | 标准启动操作卡、实际版本清单、专用世界基线 | M1：不依赖 HMCL，客户端在指定目录启动，玩家进入 26.2 测试世界，留有本轮日志 |
| T2 服务端玩法：11/24 13:00–11/25 12:00 | M1；开发、三套配置、专用世界快照 | 三套独立断言记录、一次跨进程恢复对照 | M2：三套完整、逐项断言无漏跑且 FAIL=0；召唤、位移、库存、队列恢复均有证据 |
| T3 客户端与联调：11/25 13:00–18:00 | M1；T2 释放实例后串行运行；操作人、实体键盘、按需桌宠 | GUI 与按键报告；桌宠证据或适用性决定 | M3：GUI 29 步、按键 3 步均完整，FAIL/ERROR=0；必测桌宠时满足遥测标准 |
| T4 删除与终验：11/26 09:00–17:00 | M1–M3；开发 + 复核，保留 14:00–16:00 复跑缓冲 | 两个旧文件的删除 diff、当前 README、引用清单、双版本结果、合并判定单 | M4：活动调用归零、删后复跑通过、双版本及静态门禁通过；否则交阻断单 |

**预算估算：** 开发 1 人约 2–2.5 人日；操作人 1–2 小时，复核约 1 小时；复用现有机器，新增采购预算 0 元。操作人和复核人时段须在 T1 锁定。磁盘不足或缓存缺失先协调空间/受控联网，不另装启动器作为捷径。

## 3. WBS：操作与验收

### T1｜先证明“进了对的世界”

- [ ] 开发在仓库根记录 `git status --short --branch`、`git rev-parse HEAD`；记录 `run/mods`、游戏配置与已有世界。现有 `run/world` 是服务端目录，不等于已有单机存档。先备份，不删除或覆盖已有文件。核对额外 mod，有未解释的依赖时不宣称“纯净”。
- [ ] 在 Windows PowerShell 执行下列操作卡。确认 JDK 25；创建允许命令的专用单机世界 `SmartMaid-AutoTest`，进入、正常退出后做干净快照。归档本轮 Minecraft/Loader/Fabric API、SmartMaid 初始化和玩家入场日志。旧日志与 26.2 属性声明的 Loader 版本不同，以本次实际加载值记录并说明，不凭旧证据签字。

```powershell
Set-Location 'E:\study\bash\GitHub\zmalP\SmartMaid\SmartMaid'
$env:SMARTMAID_MC_DIR = (Resolve-Path '.\run').Path
.\gradlew.bat --version
.\gradlew.bat help --task runClient
.\gradlew.bat runClient
```

- [ ] 同一 PowerShell 后续执行 `clienttest.py` **不传 `--series`**：已给真实 gameDir，传版本将再拼 `versions/<series>`。换终端需重设环境变量。核对客户端报告命令查找的是 `run/config/smartmaid`；首次无报告是“未执行”，不是失败或通过。此约束不适用于静态工具 `verify_all.py`。

### T2｜三套 AutoTest 与持久化/恢复

- [ ] 每套从专用世界干净副本开始；游戏关闭时将下表输入复制为模块下的 `run/config/smartmaid/autotest.json`，一次只放一套。每轮用 `runClient` 启动、进入世界；不能只启动空服务器或停在标题画面。

| 轮次 | 输入（相对模块目录） | 核对重点 |
|---|---|---|
| A 基础 | `config/smartmaid/autotest.json` | 女仆召唤、CraftExecutor 合成/缺料预期、库存变化 |
| B 兼容规则 | `tools/autotest.modpack-compat.json` | 方块、库存、搭路白名单、tag、破坏事件否决；仅证明本配置覆盖的规则 |
| C 完整队列 | `tools/autotest.queue-full.json` | 出入队/去重、战斗打断、熔炉租约、箱子存储；记录当前状态与指令结果 |

- [ ] 每轮归档输入和 `run/logs/latest.log`，核对每个 `expect` / `check` 的 ID、本轮 `AutoTest 汇总` 中 PASS>0、FAIL=0 和 `AutoTest: 执行完成`。检查输入被标成 `autotest.done.json`；仅有 done 文件不算通过。下一轮重新部署，下一次启动前归档，不能共用一份不断被覆盖的日志。
- [ ] 单独做跨重启用例：确认女仆能移动并记录前后坐标；记录同一女仆 UUID、背包物品及数量、可恢复的排队任务和参数；保存世界、退出整个客户端，再启动同一版本并进入**同一测试存档**。比较 UUID/库存/队列，并观察恢复后继续推进。此次不得部署会在开头清队列的完整队列脚本。留两次进程各自的证据，不能用一次进世界截图替代。

### T3｜GUI、实体按键及可选桌宠

- [ ] 无待触发的服务端输入时依次跑 `gui-chain`、`key-encoding`。各自新启进程，先部署、后进世界；下例完成后先归档报告，再替换第一行的套件名重复。新输入目的地必须是 `run/config/smartmaid/clienttest.json`。

```powershell
python tools/clienttest.py run gui-chain
.\gradlew.bat runClient
# 确认本轮执行完毕，正常退出游戏
python tools/clienttest.py report
python tools/clienttest.py errors
```

- [ ] `gui-chain` 要求 29 步无卡死、无未知 payload；`key-encoding` 由操作人聚焦窗口，按游戏提示按住 Shift+E 约 5 秒，核对实际采样、按键码及状态，而不只看操作人说“按过”。两份报告分别核对版本、用例数、时间窗、判定与 `errors.jsonl` 本轮增量。26.2 的结果不代表 26.3 SDL；若宣称验证该差异，使用 T4 的 26.3 参数启动并**另建专用世界**、另交键盘报告。
- [ ] 负责人在 T1 定桌宠是否必测。需要时单独启动桌宠，记录双方日志起点，用 Loom **单机集成服务器**进世界；要求 ≥2 个本轮遥测窗口、“开始上报感知”以及桌宠侧 WebSocket/遥测接收记录。明确不适用则记录理由和决定人，不写 PASS；必测但资源未就绪则阻断。

### T4｜删除旧入口，验证无悬空依赖

- [ ] 删除前在整个仓库核对 `launch_game` / `run_e2e_test` 的 import、命令、CI、共享 IDE 配置及当前文档；记录旧功能由哪个新步骤接手。启动 → T1；部署和断言 → T2/T3；桌宠进程和日志取证 → T3。发现尚在用且无替代的调用，先迁移该消费者，不先删文件。
- [ ] 仅定向删除 `SmartMaid/tools/launch_game.py` 和 `SmartMaid/tools/run_e2e_test.py`；更新 `SmartMaid/README.md` 的工具表、示例及“一键联调”描述，更新 `SmartMaid/KOTLIN_MIGRATION_STATUS.md` 的当前入口和实测结论。保留原交接为历史快照，从当前文档链接本计划。含 HMCL 字样的 API 分析、JDK 探测、`maid_monitor.ps1` 不因此成为删除对象。
- [ ] 在仓库根运行 `git grep -n -E 'launch_game|run_e2e_test'`，另查未跟踪的活动文档/运行配置。允许说明退役或记录历史，**活动引用必须为 0**。删后仅按新 README 重新开进程，至少复跑基础 AutoTest 和 GUI，收一套全新证据。源码业务修复若发生在后，受影响的套件须在最终代码上重跑。
- [ ] 从模块目录执行以下门禁。每次 `clean` 前先归档上一版本的测试 XML；既有 6 suites / 33 tests 双版本零失败、零错误、零跳过，新增用例另外记录。静态 A/B/C 全通过，PAL 0 fatal/0 warning，原 209 个依赖成员匹配。`git diff --check` 无输出、退出 0，工作树仅有预期改动。

```powershell
.\gradlew.bat --offline --no-daemon clean build
.\gradlew.bat --offline --no-daemon clean build '-Pmc_series=26.3' '-Pminecraft_version=26.3' '-Ploader_version=0.19.5' '-Ploom_version=1.18.4' '-Pfabric_api_version=0.161.0+26.3'
$env:PYTHONIOENCODING = 'utf-8'
python tools/verify_all.py --series 26.2
git diff --check
git status --short
```

- [ ] 复核人逐项签 M1–M4；全绿才建议“可以合并”，不自行合并。未跑、缺报告、超时、断言失败均列阻断及负责人，不把历史 PASS 移植到本轮。若提交，只定向暂存并使用实际作者的 `协作模型: <真实模型名>` 最后一行 trailer。

## 4. 风险与应急预案

| 风险/触发 | 预防 | 补救 |
|---|---|---|
| JDK/依赖缓存不齐，实际版本不对 | T1 核对 Wrapper、解析值和日志 | 开发留首个错误，校正 JDK/参数；需联网先协调，不用 HMCL 替代 |
| 覆盖个人世界、旧报告假绿 | 隔离存档；停机快照；运行号与时间窗 | 立即停止该轮，只恢复测试副本，重新部署取证。历史报告不覆盖首次失败 |
| 配置不触发、单轮超时、无人工键盘 | 预约交互桌面；每轮确认玩家及输入路径 | 单轮 15 分钟未完成即留现场。查是否进世界、配置名、玩家/女仆与日志；缺操作人改约，可先跑独立构建，不能豁免按键 |
| 退役影响外部调用/桌宠 | 删前列消费者和等价取证方法 | 复核人暂缓退役或仅回退本次定向删除，完成迁移后复跑；不把 token 读取恢复成默认入口 |
| 出现迁移回归、多人争用 `run` 或进度滞后 | 实例单人串行；记录失败 ID、HEAD、输入、初始状态 | 分环境/脚本/业务/既有用户改动；只修可归因代码，重跑失败套件、26.2 测试和静态门禁，最终复核双版本。超缓冲交阻断单 |

## 5. 跟踪、反馈与结项

- 开工 10 分钟锁定责任人、桌面窗口及桌宠适用性。每套结束立即更新台账：`轮次｜HEAD/实际版本｜世界/配置｜应跑/实跑/通过/失败｜证据位置｜负责人｜下一步/期限`。本地证据按“日期–提交–版本–套件–轮次”分目录存于 Git 之外；仓库只留脱敏摘要。
- 每天 09:00 对齐阻断，17:00 复核证据；在 M1–M4 另设退出检查。里程碑延迟当日通知项目负责人，说明影响项、责任人和最早复测时间。
- 失败闭环：保留首次失败 → 分类和指派 → 修复或调整环境 → 相同输入复跑 → 附前后证据 → 复核人关闭。最终报告逐项列 PASS / FAIL / 阻断 / 经批准不适用，不以统计平均值掩盖单项失败。
