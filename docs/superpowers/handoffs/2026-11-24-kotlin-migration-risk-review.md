# Kotlin 迁移分支剩余风险审查

## 修复复核（2026-11-24，基线 4516815）

后续验证和功能开发见 [下一阶段开发计划](../plans/2026-11-24-next-stage-development.md)，本文件保留审查与修复取证。

**R1、R2、R3 已关闭。** 命令兼容性修复提交 `4516815`；行尾规范化独立提交 `ae65347`。原始审查保留在后文，不能继续将其中已修复问题当作当前状态。

- 恢复 10 个原有子命令及 attack target、mine range/count、farm range、craft/smelt count 路径，保留原默认值、参数边界和任务派发语义。与 main 的注册文本差集复核：28 个 literal（含根）及 30 个 argument 出现次数均一致；该静态比较不是树结构完整证明，另有真实 Brigadier 注册/解析回归覆盖。
- 新增 `MaidTaskCommandTest.java` 的 4 项回归：全部原有子命令集合、恢复的原子命令解析、默认/可选任务参数、越界拒绝。修复前 4 项中 3 项失败（红灯），修复后 4/4 通过。测试使用实际生产 register 创建的节点；为脱离在线玩家，只在解析夹具中移除根权限上下文，未改生产权限规则，也不执行回调。
- 双版本 `--offline --no-daemon --console=plain clean build` 均退出 0：26.2 用时 50 秒，26.3 用时 37 秒；各 **7 suites / 37 tests / 0 failures/errors/skips**。新增 1 个 suite、4 项测试，原 33 项保留。26.3 使用操作卡全部五个版本参数。两轮 XML 均在下一次构建覆盖前独立归档。
- 修复后 `verify_all.py --series 26.2 --json ...` 退出 0，A/B/C 全 PASS；PAL 1.2.6 的 Mixin 0 fatal / 0 warning。此前 209 个依赖成员的详情证据仍保留，新总门的 C 也通过。
- 16 个格式目标文件仅规范 CRLF 行尾；3 个 Markdown 文件将尾随两个空格的硬换行改为显式反斜杠硬换行。代码/配置的行内容等价已核对，crying.json 解析结果一致；Node 语法和 Python 编译检查通过。提交后 `git diff --check main...HEAD` 已退出 0。
- 模块忽略规则新增 `.kotlin/`，防止本轮 Kotlin 构建缓存进入提交。生产源码仍为 Java=0、Kotlin=131；Java 测试文件由 6 个增至 7 个。
- 本轮证据根目录：`.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/command-fix-20261124/`，含 red/green 日志和退出码、红灯 XML、26.2/26.3 XML 与 summary、构建日志/退出码、静态报告。原始日志和缓存不提交。
- 仍有限制：本轮没有进游戏执行命令的运行时复测；Brigadier 测试不证明世界操作、真实女仆容器或玩家权限上下文的完整行为。此前 T4 AutoTest/GUI 仅作为修复前独立证据，不挪作本轮源码的全套真机验证。26.3 世界/实体按键仍按用户决定不补做，桌宠 N/A；弃用和现有 Kotlin 警告未作为本轮清理目标。
- 合并决定仍交项目负责人；本轮明确回归已修复，不扩大为全部迁移源码等价或无风险保证。用户设计文档哈希保持原值，未提交、推送或合并用户改动。


## 原始审查快照（c244b5f，修复前）

日期：2026-11-24（北京时间）。审查基线：`main...c244b5f5ac59ac3df2b91fab01e3d329a22c5586`。本轮为风险审查，不修改业务代码；用户 `DESIGN_AI_INTERFACE.md` 工作区修改不属于本轮提交或审查差异。

## 结论

**暂缓合并：存在已确认的命令兼容性回退。** T4 删除后技术验证通过仍然成立，但不等于整条 Kotlin 迁移分支功能等价。先恢复以下命令树，再补命令注册/解析回归与双版本测试。

## 发现

### R1 / P1：迁移后丢失 10 个现有 /maidtasks 子命令

定位：[MaidTaskCommand.kt](../../../SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/command/MaidTaskCommand.kt)，register 第 23–41 行。

与 `main:SmartMaid/src/main/java/com/oyxdsg/smartmaid/command/MaidTaskCommand.java` 对比，Kotlin 注册树没有以下 literal：

`transfer`、`chestopen`、`chestput`、`chesttake`、`look`、`break`、`place`、`use`、`equip`、`store`。

例如原有 `/maidtasks transfer mainhand offhand 1`、`/maidtasks chestopen ~ ~ ~` 在新树中不存在对应节点，命令解析会在执行逻辑前拒绝。任务实现类或 `/maidai` 的同类功能仍存在，不能替代原命令入口兼容性。迁移计划要求不改变生产行为，未找到授权删除这些命令的决定。

验证方式：直接阅读两版 register，另以正则提取 `Commands.literal(...)` 的多重集合，差集恰为上述 10 项；未为此启动游戏，也不冒称运行时复现。

建议：逐项恢复原注册树及原辅助方法的行为，增加 Brigadier 树结构/解析回归，覆盖槽位转移与箱子指令的参数路径。

### R2 / P2：仍存在的命令丢失可选参数路径

定位：同文件第 24、28–33 行。

| 原有路径 | 当前变化 | 可见影响 |
| --- | --- | --- |
| `attack <range> <target>` | 只注册 range | 无法通过原命令点名实体类型 |
| `mine <pos> [range] [count]` | 只注册 pos | 无法指定范围和数量 |
| `farm <pos> [range]` | 只注册 pos | 只能使用默认范围 |
| `craft <item> [count]` | 只注册 item，count 固定为 1 | 例如 `/maidtasks craft minecraft:stick 8` 有多余输入，无法解析 |
| `smelt <item> [count]` | 只注册 item，count 固定为 1 | 同样无法批量指定数量 |

这些不是 Kotlin 参数默认值的等价转换，而是 Brigadier 节点缺失。与 R1 同次修复，但验收应分别覆盖 literal 与 argument 路径，避免只恢复命令名仍遗失参数。

### R3 / P2：全分支空白门禁仍失败

执行 `git diff --check main...HEAD`，退出码 **2**；统计 16 个文件、5511 处 trailing-whitespace / EOF 提示。抽查 `git ls-files --eol`：`.gitignore`、`SmartMaid/gradle.properties`、`MaidTaskQueueTest.java` 的索引均为 `i/crlf`，因此多数提示为已提交 CRLF，而不是业务内容错误。

涉及根文档/忽略文件、部分历史文档、Gradle 配置、3 个 Java 测试、`maid_inventory_sim.js` 和 `crying.json` 等。此前 `git diff --check` / `git diff --cached --check` 通过，只证明当时工作树/该次暂存差异无空白错误，不能填作全分支检查 PASS。

建议：限定上述文件做行尾规范化或制定明确仓库策略，再执行全分支门禁；不批量转换整个仓库，不覆盖用户设计文档。此项不影响已取得的构建/运行证据，不等同于运行时缺陷。

## 验证范围与局限

- 已复查：构建与模组依赖声明、网络类型注册与服务端处理、背包菜单、命令注册、队列基础操作、设置、感知增量/事件、跟随与导航、战斗目标过滤和部分远程/威胁逻辑。对候选问题与 main 的 Java 基线对照，未把已有逻辑作为新回归。
- 这是重点抽查，未逐行证明 131 个 Kotlin 生产文件全部等价，也未审计全部外部 Java 二进制 API 使用者。未确认的新风险不列为缺陷。
- 现有 6 个 Java suite 覆盖 FurnaceLease、任务管理/持久化/队列、Resumable 与 Smoke；本轮文本检查未发现 MaidTaskCommand / CommandDispatcher 覆盖。33 项通过不能证明命令树完整。
- GUI gui-chain 的 29 项 PASS 是菜单测试覆盖范围内的结论：nearestMaid=-1，invokeOpenInventory=false，未断言真实女仆背包容器打开。属于覆盖限制，不直接认定为迁移引入的代码缺陷。
- 26.3 世界/实体按键按用户决定不补做，保持未验证；桌宠 N/A。不能把这些限制写成 PASS，也不因此擅自扩展测试范围。
- 沿用前一轮双版本各 33 项测试、静态 A/B/C、基础 AutoTest 6/6 和 GUI 29/29 的真实证据；本轮业务代码未改变，未重复启动构建或游戏。
- 未发现需要本轮修改系统配置或依赖才能处理的问题。先修 R1/R2，再做受影响回归；R3 单独处理。

## 提交与边界

T4 已提交为 `c244b5f`（8 个文件）；未推送、未合并。用户设计文档保持未提交。本审查记录与迁移状态补充单独保存，业务代码修复留待后续实施。
