# T4 旧启动入口退役记录

## IDEA MCP 接续终验更新（2026-11-24，北京时间）

**T4 / M4 本轮技术检查通过。以下此前的环境阻断和 NOT RUN 表格为历史记录，以本节及迁移状态顶部为当前结论。**

证据根目录仍为 `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/T4-20261124/`。HEAD 仍为 `98715aed043c2707035468083ad61020ef37e0d6`，未修改业务源码，未提交、暂存、推送或合并。

| 检查 | 本轮结果 | 新证据 |
| --- | --- | --- |
| 26.2 clean build | PASS | 此前用户普通终端 54 秒、6 suites / 33 tests / 全零失败错误跳过，归档保持原样 |
| 26.3 clean build / Java 互操作测试 | PASS | IDEA MCP 执行全部五个版本参数；成功输出 1m 1s；6 suites / 33 tests / 全零失败错误跳过；`26.3-build-20261124-175221-538/test-results/` 和 `daemon-build.log` |
| 26.2 静态总门 A/B/C | PASS | `verify-all-26.2-idea.log`，A 构建 53 秒；PAL 0 fatal / 0 warning；本轮 26.2 XML 归档于 `26.2-idea-static-test-results/` |
| 依赖成员详情 | PASS | `dep-26.2-idea.log`：84 个引用类型存在、209 个成员全部匹配 |
| 基础 AutoTest | PASS | `A-idea-20261124-175417/`：c1–c6 全通过，17:55:58 PASS=6 FAIL=0；done 与输入一致，`game-final.log` 确认保存退出 |
| GUI gui-chain | PASS（套件覆盖范围） | `GUI-idea-20261124-175950/`：18:00:26–18:00:28，26.2 / 0.1.3，29/29 PASS，FAIL/ERROR=0；done 与输入一致，保存退出完成 |
| GUI 错误通道增量 | PASS | `errors-delta.jsonl` 仅 5 条 INFO，新增 WARN/ERROR=0；原 13:43 按键 ERROR 保留为历史 |
| 用户设计文档 | 保持原样 | SHA-256 仍为 `90982E0DB300D408ADD8EA61637CB64CABB74F394987C82504F41FAB0AFAE1A1` |
| 26.3 专用世界 / 实体按键 | 未验证（用户决定不补做） | 不以构建或 26.2 GUI 代替 |
| 桌宠 | N/A | 沿用用户决定 |

A 世界启动前的 level.dat 哈希与交接干净快照一致。A 和 GUI 分轮归档旧配置、报告、日志和 options 后部署新输入，同一 gameDir 串行运行；用户分别正常保存退出，最终日志已归档。原世界未覆盖。GUI 报告 nearestMaid=-1、invokeOpenInventory=false，证明所覆盖的菜单链路和入口调用，不证明真实女仆容器打开或实体按键通过。客户端日志出现开发身份的 profile key / Realms 认证错误，不是本轮 SmartMaid 错误通道新增项，也不将整个游戏日志声称为零错误。

IDEA MCP 的实际模块读取、终端、构建及 runClient 已工作；无须更改系统权限或恢复 HMCL。首次 26.3 终端调用超时后进程继续运行，后续依据新 XML 和归档 daemon 中的 BUILD SUCCESSFUL 核对成功，没有编造首次调用的退出码。

最终引用复查：活动代码/运行配置调用为 0；`.idea/workspace.xml` 的两个 `beforePath` 仅记录已删除文件的 changelist，不是执行调用。其余文本按原分类保留。合并建议仍为“暂缓/修复后合并”，由项目负责人结合 26.3 输入未验证范围决定；不自动合并。


## 接续前历史记录（保留取证时间线）

日期：2026-11-24（北京时间）。分支：`feature/kotlin-migration`；代码基线 `d924572`，本轮开始 HEAD `98715ae`。用户指示“开始”后推进退役；不补做 26.3 游戏输入验证，不自动合并。执行依据为 [Loom 验收计划 T4](../plans/2026-11-23-fabric-loom-gameplay-validation.md)，当前操作见 [Loom 测试操作卡](2026-11-24-loom-test-runbook.md)。

## 变更和职责替代

仅删除 `SmartMaid/tools/launch_game.py` 与 `SmartMaid/tools/run_e2e_test.py`，更新 README、开发计划和迁移状态。保留 `_paths.py`、`clienttest.py`、监测/静态工具及所有测试配置。未增加新的启动器，未改业务代码。

| 原职责 | 当前操作 |
| --- | --- |
| 读取 HMCL 日志、补 token、重放启动命令 | 模块目录 `gradlew.bat runClient`，Fabric Loom 管理运行时，不需要 HMCL/token |
| 自动进入存档 | 客户端进入专用测试世界，或 Wrapper `--args` 传入 `--quickPlaySingleplayer` |
| 部署基础/队列/兼容测试 | 操作卡逐轮复制输入至 `run/config/smartmaid/autotest.json` |
| 部署 GUI、读取报告和错误 | 保留的 `clienttest.py run/report/errors`；真实 gameDir 下不传 `--series` |
| 拉起桌宠与等待遥测 | 按需独立启动桌宠，记录日志起点，核对本轮 ≥2 个窗口、感知上报及桌宠接收；本轮 N/A |
| 关游戏、出报告 | 正常保存退出，再归档本轮输入、done、日志、GUI JSON 及错误增量，不复用历史结果 |

## 全仓库引用核查

使用 `git grep -n -I -E 'launch_game|run_e2e_test'` 检查跟踪文件；额外检查未跟踪、非忽略的文本文件，以及根和模块的 `.idea` / `.vscode` / `.run`、根 `.github` 中本机配置。未发现两个旧脚本以外的代码 import、CI 或 IDE 启动调用。原唯一 import 为旧 E2E 脚本导入旧启动脚本，已随删除消除。

| 剩余引用位置 | 判定与处理 |
| --- | --- |
| `SmartMaid/README.md`、迁移状态、本退役记录 | 明确说明退役；当前步骤指向 Wrapper 和操作卡 |
| `SmartMaid/DEVELOPMENT_PLAN.md` | 原活动命令已替换为操作卡链接 |
| `SmartMaid/DESIGN_AI_INTERFACE.md` | 用户已有修改，逐字节保留；旧启动小节由 README 的退役声明及当前操作卡取代，不再作为操作入口 |
| `SmartMaid/HANDOVER.md`、`HANDOVER_MENU_QUEUE.md` | 历史交接快照；当前 README 已注明其中旧命令不适用 |
| `SmartMaid/DEVELOPMENT_ISSUES.md` | 历史故障及处理记录，保留原上下文 |
| `docs/superpowers/handoffs/2026-11-23-kotlin-migration-next-phase.md`、`2026-11-24-kotlin-migration-handoff.md`、`2026-11-24-loom-gameplay-acceptance-report.md` | 原交接/验收快照保留；不能当作本轮测试通过证明 |
| `docs/superpowers/plans/2026-11-23-fabric-loom-gameplay-validation.md` | 记录原计划及退役对象，属于允许的退役说明 |
| `.superpowers/sdd/2026-11-23-kotlin-migration-merge-fixes/progress.md` | 历史尝试失败台账 |

活动代码/配置调用数为 0；文本匹配保留历史和退役说明，因此不宣称全仓库匹配行数为 0。

## 本轮验证

**退役变更完成，M4 终验受环境阻断，未通过。** 本机证据根目录（相对仓库）：`.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/T4-20261124/`，仅作本机取证，不提交原始日志、缓存或存档。

| 检查 | 本轮结果 | 证据/说明 |
| --- | --- | --- |
| Wrapper / JDK 版本 | PASS | Gradle 9.9.1 / Oracle JDK 25.0.5.1；工作区缓存启动成功 |
| 26.2 `clean build` | PASS（用户普通终端） | 用户输出 `BUILD SUCCESSFUL in 54s`；已核对并归档新 XML，6 suites / 33 tests / 0 failures/errors/skips；Work 原失败保留于 `build-26.2.log` |
| 静态 A 编译 | BLOCKED | `verify-all-26.2.log` / `.json`：同一 daemon 连接失败 |
| 静态 B Mixin | PASS | PAL 1.2.6，致命 0 / 警告 0 |
| 静态 C 依赖成员 | PASS | PAL 1.2.6 依赖成员检查通过 |
| 26.3 clean build / 测试 | NOT RUN | 尚未完成本轮复跑；上一轮 6 suites / 33 tests 结果仅作历史 |
| 删除后基础 AutoTest / GUI | NOT RUN | 同一启动阻断，尚未进入游戏；未部署新输入 |
| 活动代码/运行配置引用 | PASS | 0；历史文本和退役说明按上表保留 |
| 文档新链接、旧入口文件缺席 | PASS | 已逐项核对本轮相对链接及两个删除路径 |
| `git diff --check` | PASS | 退出 0，无空白错误（Git 有既有行尾转换提示） |
| 用户修改、运行配置与旧报告 | PASS | 用户文档 SHA-256 未变，配置/报告/options 与归档逐字节一致 |

环境处理：直接运行 Wrapper 首先遇到用户目录 `.gradle/wrapper/dists/.../gradle-9.9.1-bin.zip.lck` 不可写。JDK 显式指向本机 JDK 25；向允许写入的工作区复制已有 Wrapper 和离线依赖缓存，设置 `GRADLE_USER_HOME` 后版本核对成功，但构建仍无法连接 daemon。没有更改系统权限、联网设置或项目构建配置。临时目录试验未作为最终缓存；实际可复用缓存为证据目录内的 `gradle-home`。

`prior-config/`、`prior-game.log`、`prior-errors.jsonl`、`options-before.txt` 保存运行前状态。由 `M1-world/SmartMaid-AutoTest` 干净快照创建 `run/saves/SmartMaid-T4-20261124-A` 和 `SmartMaid-T4-20261124-GUI`，未启动使用；A 副本与快照的 `level.dat` SHA-256 均为 `BAFBBFBD8D52298C924CB88F65DF3D4783538ABC209CE6DED400C1CCA695DB07`。原世界保持原样。

用户已有 `SmartMaid/DESIGN_AI_INTERFACE.md` SHA-256：`90982E0DB300D408ADD8EA61637CB64CABB74F394987C82504F41FAB0AFAE1A1`。本轮结束已核对该值未变；未暂存或提交用户改动。

## 合并判定

暂缓/修复后合并。删除后复跑和构建门禁尚未完成；26.3 游戏输入兼容仍未验证（用户决定不补做），桌宠 N/A。未提交、未推送、未合并。

下一步：在能够正常连接 Gradle daemon 的执行环境中按新 README/操作卡运行 26.2 基础 AutoTest、GUI 和双版本构建，再跑静态 A/B/C，分别归档新证据。现有世界副本可用，部署新配置前仍须检查无待执行输入和活动游戏进程。更新本记录后再复核 M4；不恢复 HMCL/token 启动作为替代。

## 17:03–17:05 daemon 连接诊断（用户已打开工作模式网络访问）

再次用同一 JDK 25、同一工作区离线缓存运行 `gradlew.bat --offline --no-daemon --console=plain help --stacktrace --debug`，失败仍复现，完整本机日志为证据目录中的 `daemon-diagnosis-debug.log`。

- daemon PID 36880 成功启动，在 `127.0.0.1:43213` 监听；客户端随后连接该地址时抛 `java.net.SocketException: Permission denied: getsockopt`。失败发生于本机 TCP 连接，尚未执行项目任务。
- 独立 Python 父进程监听随机 `127.0.0.1` 端口，子进程连接同一地址：子进程返回 `PermissionError / WinError 10013`，父进程等待连接超时。该测试不涉及 Gradle、JDK 或 Minecraft。
- 对当前工具进程读取 Windows 访问令牌：`TokenIsAppContainer=true`；`whoami /groups` 显示 Low Mandatory Level。用户的授权与网络开关尚未使本次实际执行进程脱离网络隔离。
- 当前进程对 `docs.gradle.org` 的 DNS 查询亦失败（`getaddrinfo` / 11001）；这一点只记录实测，不能单独作为本机 TCP 失败的原因。
- 只读运行 `CheckNetIsolation.exe LoopbackExempt -s` 也返回“访问被拒绝，请以管理员身份运行”；未改任何豁免或防火墙规则。

直接原因已确认：Windows 拒绝当前执行环境的本机 TCP 连接。结合 AppContainer 令牌，证据指向执行环境的网络隔离；未取得 WFP 拦截事件，不能进一步断言具体哪条过滤规则。Microsoft 官方说明 AppContainer/打包应用的 loopback IPC 默认受限，网络外连与本机进程通信需分别考虑：[Windows IPC — Loopback](https://learn.microsoft.com/en-us/windows/apps/develop/communication/interprocess-communication#loopback)。

需要使当前执行环境具备实际的本机进程通信权限，再重跑独立 loopback 测试和 Wrapper `help`。本次未更改项目依赖、JVM 参数、系统权限或网络安全设置。

## 普通 PowerShell 对照：用户提供的成功证据

用户在普通 PowerShell 7.6.10 中进入同一模块目录，显式设置 `JAVA_HOME=C:\Program Files\Java\jdk-25.0.5.1`，执行 `gradlew.bat --offline --no-daemon --console=plain help`。用户贴出的完整输出显示：Gradle 9.9.1 的 single-use daemon 正常工作，项目配置加载 Fabric Loom 1.17.21，`:help` 成功，`BUILD SUCCESSFUL in 14s`（1 actionable task: 1 executed）。该次使用用户目录原有 `.gradle` 缓存，Work 侧诊断使用工作区缓存，因此两次不是同一缓存路径的严格对照。

结合 Work 中与 Gradle 无关的 Python localhost 连接也被 Windows 以 10013 拒绝，故障证据集中于 Work 命令执行环境的本机网络限制。普通终端已证明这台机器上的同一 JDK/Wrapper 可以连接 daemon 并配置项目；无需据此修改项目依赖、清空缓存或全局关闭防火墙。具体拦截策略仍未确定。

此结果只证明用户终端的 `help` 成功，未执行 clean build、33 项测试、基础 AutoTest 或 GUI。M4 保持受阻，后续可在已验证可用的普通终端按操作卡完成剩余门禁并归档证据。

## 17:34 更新：用户普通终端的 26.2 clean build 通过

用户继续执行 `gradlew.bat --offline --no-daemon --console=plain clean build`，贴出 `BUILD SUCCESSFUL in 54s`，8 actionable tasks 全部执行。随后直接读取本轮 `SmartMaid/build/test-results/test/TEST-*.xml`，确认 6 suites / 33 tests / 0 failures / 0 errors / 0 skipped；XML 时间戳为 2026-11-24 09:33:42–09:33:43 UTC（北京时间 17:33:42–17:33:43）。

本轮 XML 和汇总已在下一轮 clean 前归档至证据目录 `26.2-user-build-20261124-173451/test-results/` 和 `summary.json`。归档来源明确区分用户提供的控制台输出与本机读取的 XML；未伪造 Work 内执行成功记录。用户设计文档 SHA-256 再次核对未变。

构建包含弃用、空值检查、参数名和 `RETURN_TYPE_MISMATCH_ON_OVERRIDE` 抑制警告；本轮只进行入口退役，未修改这些源码。构建通过不代表消除了这些技术风险。剩余门禁仍为 26.3 clean build / 测试、静态总门完整复跑，以及删后基础 AutoTest 和 GUI；不自动合并。
