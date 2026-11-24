# SmartMaid Kotlin 迁移：T4 退役与执行环境交接

> 历史取证快照：下文记录 T4 当时现场，不是当前 HEAD 或最新测试计数。当前结论见 [迁移状态](../../../SmartMaid/KOTLIN_MIGRATION_STATUS.md) 和 [修复复核](2026-11-24-kotlin-migration-risk-review.md)；接续执行 [下一阶段开发计划](../plans/2026-11-24-next-stage-development.md)，不重复下文已完成的起始任务。

## IDEA MCP 接续终验更新（2026-11-24，北京时间）

**T4 / M4 本轮技术检查通过。以下此前的环境阻断和 NOT RUN 表格为历史记录，以本节及迁移状态顶部为当前结论。**

证据根目录仍为 `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/T4-20261124/`。取证时 HEAD 为 `98715aed043c2707035468083ad61020ef37e0d6`；当时未修改业务源码，未提交、暂存、推送或合并。此后已提交 T4，并完成命令兼容修复；当前提交与验证范围见顶部链接。

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

> 日期：2026-11-24（北京时间）
> 仓库：`E:\study\bash\GitHub\zmalP\SmartMaid`
> 模块：仓库下 `SmartMaid`
> 分支：`feature/kotlin-migration`
> 当前 HEAD：`98715aed043c2707035468083ad61020ef37e0d6`（`docs: 添加 Loom 验收后交接文档`）
> 本轮改动全部未提交、未暂存；未推送、未合并。

## 1. 当前结论与用户决定

**两个旧启动脚本已删除，当前文档已切换到 Loom。删除后 26.2 clean build 和 33 项测试通过；T4/M4 尚未完成，剩余为 26.3 构建、静态总门完整复跑，以及删除后基础 AutoTest / GUI 复跑。**

- 用户要求下一阶段使用 **IDEA MCP** 继续。新会话先确认真正获得 IDEA 工具，再执行后续步骤。
- 用户明确不补做 **26.3 专用世界和键盘验证**；26.3 仍需完成构建及互操作测试，不将构建成功写成输入兼容已验证。
- 桌宠本轮 **N/A**，沿用此前用户决定。
- 保持“暂缓/修复后合并”，不自动合并。
- 用户已有 `SmartMaid/DESIGN_AI_INTERFACE.md` 修改不得覆盖、回退、暂存或纳入提交。

本交接接续 [原 Kotlin 迁移交接](2026-11-24-kotlin-migration-handoff.md)。原交接及 [Loom 玩法验收报告](2026-11-24-loom-gameplay-acceptance-report.md) 保留为历史快照；当前 T4 状态以本文件、[退役记录](2026-11-24-launcher-retirement-report.md) 和 [迁移状态](../../../SmartMaid/KOTLIN_MIGRATION_STATUS.md) 为准。

## 2. 本轮未提交变更

| 路径 | 变更 |
| --- | --- |
| `SmartMaid/tools/launch_game.py` | 定向删除：原 HMCL 日志/凭证重放启动入口 |
| `SmartMaid/tools/run_e2e_test.py` | 定向删除：原一键桌宠/游戏联调入口 |
| `SmartMaid/README.md` | 工具表、命令示例和路线图改为 Wrapper / AutoTest / clienttest；说明历史旧命令已退役 |
| `SmartMaid/DEVELOPMENT_PLAN.md` | 当前端到端操作入口改为 Loom 操作卡 |
| `SmartMaid/KOTLIN_MIGRATION_STATUS.md` | 顶部新增当前 T4 状态，保留历史验证时间线 |
| `docs/superpowers/handoffs/2026-11-24-loom-test-runbook.md` | 新操作卡：分轮归档、部署、启动、报告、可选桌宠和构建门禁 |
| `docs/superpowers/handoffs/2026-11-24-launcher-retirement-report.md` | 新退役记录：职责替代、引用分类、验证结果和 daemon 诊断 |
| 本交接文件 | 新增，供后续会话接续 |
| `SmartMaid/DESIGN_AI_INTERFACE.md` | **用户原有修改，本轮未改** |

本轮没有修改 Kotlin 业务源码。`CraftExecutor` 与 `MaidDataManager` 修复已在历史代码基线 `d9245721f36792c969e125f08f7084701713f51f` 中完成。

用户设计文档交接时 SHA-256：`90982E0DB300D408ADD8EA61637CB64CABB74F394987C82504F41FAB0AFAE1A1`。若用户后续继续编辑，以新内容为准，不为匹配哈希回退文件。

## 3. 删除与引用核查

删除前检查了全仓库跟踪文件、未跟踪且未忽略的文本、根和模块的 `.idea` / `.vscode` / `.run` 及根 `.github` 配置。旧 E2E 脚本导入旧启动脚本是唯一代码依赖，已随两个文件删除消除；未发现其他代码、CI 或 IDE 调用。

**活动代码和运行配置引用为 0；全仓库文本匹配不是 0。** 允许保留的匹配包括退役说明、原计划和历史交接/故障记录。当前开发计划中的活动命令已替换。

`DESIGN_AI_INTERFACE.md` 中仍有旧启动小节：为保护用户修改，本轮未编辑它。当前 README 和操作卡明确其旧命令已被取代。详细逐文件分类在 [退役记录](2026-11-24-launcher-retirement-report.md)。

保留 `_paths.py`、`clienttest.py`、`maid_monitor.ps1`、静态验证工具和测试 JSON；不额外编写第三个游戏启动器。

## 4. 本轮证据与通过范围

证据根目录（相对仓库）：`.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/T4-20261124/`。其中原始日志、缓存、配置备份和存档仅作本机证据，不提交。

| 项目 | 本轮结果 | 证据与限制 |
| --- | --- | --- |
| 普通 PowerShell 的 Wrapper `help` | PASS | 用户提供 `BUILD SUCCESSFUL in 14s`；Gradle 9.9.1 / JDK 25.0.5.1 / Loom 1.17.21 |
| 26.2 `clean build` | PASS | 用户普通 PowerShell 提供 `BUILD SUCCESSFUL in 54s`，8 个任务全部执行 |
| 26.2 测试 XML | PASS：6 suites / 33 tests / 0 failures/errors/skips | 代理直接读取并归档至 `26.2-user-build-20261124-173451/test-results/`；汇总见该目录 `summary.json` |
| 26.3 `clean build` / 测试 | NOT RUN | 本轮命令已给用户，但截至交接尚无执行结果；上一轮 PASS 不能计入本轮 |
| `verify_all.py --series 26.2` | 总门未通过 | `verify-all-26.2.log` / `.json`：Work 内 A 因 daemon 连接失败；B Mixin PASS（PAL 1.2.6，0 fatal/0 warning），C 依赖成员 PASS |
| 删除后的基础 AutoTest / GUI | NOT RUN | 游戏尚未启动，新输入尚未部署；历史 A=6/6、GUI=29/29 不是本轮证据 |
| `git diff --check` | PASS | 无空白错误；存在 Git 原有行尾转换提示 |
| 用户改动 / 原配置 / 旧报告 / options | PASS | 用户设计文档哈希未变；运行配置和旧报告、options 与运行前归档逐字节一致 |

26.2 XML 时间戳为北京时间 2026-11-24 17:33:42–17:33:43；在下一轮 clean 前完成了归档。保留控制台输出来源与 XML 来源的区别，不声称用户命令由 Work 代理成功执行。

编译仍有弃用、空值检查、参数名和 `RETURN_TYPE_MISMATCH_ON_OVERRIDE` 抑制警告；本轮未扩展为警告清理任务。构建通过不等于消除这些风险。

## 5. 执行环境：Work localhost 受限，普通终端可用

### 已确认事实

1. 用户已开启工作模式公共互联网访问，并明确已选 Full access；用户当前界面输入框下方没有权限菜单。**不要再次要求用户寻找该菜单或重复授权。**
2. Work 工具进程实测 `TokenIsAppContainer=true`，完整性等级为 Low。
3. daemon 正常启动并监听 `127.0.0.1`；Work 内客户端连接报 `java.net.SocketException: Permission denied: getsockopt`。
4. 与 Gradle 无关的 Python 父子进程 localhost 测试也报 `WinError 10013`；证据指向 Work 实际执行环境的本机网络限制，尚未确定具体拦截规则。
5. 用户普通 PowerShell 7.6.10 中，同一项目 / JDK / Wrapper 的 `help` 和 26.2 `clean build` 均成功。

Work 原本还遇到用户目录 `.gradle` 的 Wrapper 锁不可写，于是复制离线缓存到上述证据目录的 `gradle-home`；版本查询成功，但 localhost 连接仍失败。普通终端使用用户原 `.gradle` 缓存，因此并非同一缓存路径的严格对照；独立 Python 测试则补充证明本机通信限制不限于 Gradle。

具体 Work 日志：`build-26.2.log`、`daemon-diagnosis-debug.log`、`daemon-recheck-2.log`。最后一次 Wrapper 重试在北京时间 17:14，仍为相同连接拒绝。

本轮未改系统权限、防火墙、loopback 豁免、项目依赖或 JVM 配置。不要以修项目依赖、清空所有缓存或恢复 HMCL 启动代替处理执行环境。

### IDEA MCP 状态

只读检查 `C:\Users\zmal\.codex\config.toml`，确认已配置：

```toml
[mcp_servers.idea]
enabled = true
url = "http://127.0.0.1:64342/stream"
```

但当前 Work 会话工具目录没有 IDEA / IntelliJ 工具，查询资源返回 `unknown MCP server 'idea' for the selected environment`。通过当前 shell 直接连接该端口仍报 `PermissionError / WinError 10013`。**“配置存在”不等于“当前会话已加载”。** 没有通过 IDEA MCP 执行任何构建或测试。

用户要求使用 IDEA MCP 继续。下一会话先枚举实际可调用的 IDEA 工具，确认能读取本项目或执行构建/终端，再执行后续验证；不要虚构工具名或声称已连接。当前证据不能判定 IDEA 服务本身是否运行正常，因为连接在权限层已被拒绝。

## 6. 下一步执行顺序

### 6.1 先核对现场

- 读取本交接、当前 `git status --short` 和相关文档 diff；不假定交接后工作区未变化。
- 在已接入 IDEA MCP 的会话中确认正确项目及模块目录。若 MCP 没有构建/终端能力，说明具体缺失，不虚构执行。
- 使用 JDK 25；普通终端的默认用户 Gradle 缓存已验证可用，无需为了后续构建改用 Work 的复制缓存。
- 26.2 测试 XML 已归档，可执行下一轮 clean。

### 6.2 26.3 clean build / 测试

从模块目录执行；通过 IDEA MCP 终端执行时也要确保工作目录、环境和参数一致：

```powershell
Set-Location 'E:\study\bash\GitHub\zmalP\SmartMaid\SmartMaid'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.5.1'
$env:SMARTMAID_MC_DIR = (Resolve-Path '.\run').Path
$env:PYTHONIOENCODING = 'utf-8'

.\gradlew.bat --offline --no-daemon --console=plain clean build `
  '-Pmc_series=26.3' `
  '-Pminecraft_version=26.3' `
  '-Ploader_version=0.19.5' `
  '-Ploom_version=1.18.4' `
  '-Pfabric_api_version=0.161.0+26.3'
```

所有 `-P` 参数保持引号，避免 PowerShell 错误解析版本。核对 `BUILD SUCCESSFUL` 及 XML 6 suites / 33 tests / 0 failures/errors/skips。**下一次 build/clean 或静态总门前归档本轮 XML**，另建证据子目录，勿覆盖 26.2 证据：

```powershell
$t4Evidence = Join-Path (Resolve-Path '..').Path '.superpowers\sdd\2026-11-23-fabric-loom-gameplay-validation\T4-20261124'
$versionEvidence = Join-Path $t4Evidence ('26.3-build-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $versionEvidence -ErrorAction Stop
Copy-Item -LiteralPath '.\build\test-results\test' -Destination (Join-Path $versionEvidence 'test-results') -Recurse
```

此步骤不会启动 26.3 游戏或升级存档。

### 6.3 静态总门完整复跑

保留上述环境变量，执行默认 26.2 配置的总门；其 A 会调用 Gradle build，因此应在 daemon 连接正常的执行环境运行：

```powershell
python tools/verify_all.py --series 26.2
```

要求 A/B/C 全部通过，PAL 0 fatal / 0 warning，核对依赖成员数量及详情。归档日志和报告；该命令可能重写 build 下产物，所以务必先归档 26.3 XML。

### 6.4 删除后 26.2 基础 AutoTest 与 GUI

详见 [Loom 测试操作卡](2026-11-24-loom-test-runbook.md)。游戏目录仍为模块的 `run`，同一时刻只跑一套。

已从归档干净快照准备的专用世界：

- `run/saves/SmartMaid-T4-20261124-A`
- `run/saves/SmartMaid-T4-20261124-GUI`

两者截至交接均未启动使用。A 副本 `level.dat` 与 `M1-world/SmartMaid-AutoTest` 快照 SHA-256 均为 `BAFBBFBD8D52298C924CB88F65DF3D4783538ABC209CE6DED400C1CCA695DB07`；恢复会话先复核其是否仍为干净副本。

运行前归档目录已有 `prior-config/`、`prior-game.log`、`prior-errors.jsonl`、`options-before.txt`，它们是本轮首次准备的备份。后续每轮仍须新建证据目录、重新归档当前现场，不依赖旧备份代表新起点。

基础 A：确认游戏关闭、无待执行输入；先归档并移走旧 `autotest.done.json`，再复制 `config/smartmaid/autotest.json` 至 `run/config/smartmaid/autotest.json`。使用默认 26.2 Wrapper 启动专用 A 世界，例如：

```powershell
.\gradlew.bat --offline --no-daemon --console=plain runClient '--args=--username T4Tester --quickPlaySingleplayer SmartMaid-T4-20261124-A'
```

核对本轮断言 ID、`AutoTest 汇总: PASS=6 FAIL=0`、执行完成日志及新 done 文件。正常保存退出后归档日志、输入和 done。

GUI：确认没有待执行的服务端 AutoTest，另建证据目录并归档旧客户端报告后执行：

```powershell
python tools/clienttest.py run gui-chain
.\gradlew.bat --offline --no-daemon --console=plain runClient '--args=--username T4Tester --quickPlaySingleplayer SmartMaid-T4-20261124-GUI'
# 本轮完成，正常退出后：
python tools/clienttest.py report
python tools/clienttest.py errors
```

要求报告版本 26.2，`gui-chain` 29/29 PASS、FAIL/ERROR=0。归档输入、报告、done、日志及错误通道本轮增量。`clienttest.py run` 会删旧报告，必须先归档。`SMARTMAID_MC_DIR` 已是真实 gameDir 时，**clienttest 不传 `--series`**；静态总门的 `--series 26.2` 则需要保留。

不补做 26.3 专用游戏世界/键盘，不把本轮 26.2 GUI 结果当作 26.3 输入验证。

### 6.5 最终复核与交付

1. 再查 `git grep -n -I -E 'launch_game|run_e2e_test'`；活动调用必须为 0，按分类保留历史和退役说明。还应检查后续新增的本机 IDE 配置。
2. `git diff --check`；核对工作树仅含预期改动，用户设计文档仍保持原有修改。
3. 将新证据更新进退役记录和迁移状态，明确逐项 PASS / FAIL / 未运行 / N/A，不将历史结果填充本轮空缺。
4. 复核 M4 后才调整合并建议；26.3 游戏输入未验证的范围限制继续保留，不自动合并。
5. 若后续要求提交，只定向暂存本任务文件；不要 `git add .`，不要纳入用户设计文档、缓存、原始日志、运行配置或存档。遵循计划中的真实模型署名要求，不伪造署名。

## 7. 给接续会话的起始任务

读取本交接和当前 diff，确认 IDEA MCP 工具已实际加载。优先通过 IDEA MCP 在模块目录完成 26.3 clean build，归档 XML，再完整复跑 26.2 静态总门，最后按新操作卡复跑删除后的 26.2 基础 AutoTest 和 GUI。保护用户 `DESIGN_AI_INTERFACE.md` 修改；不补做用户已排除的 26.3 专用游戏验证，不自动提交或合并。
