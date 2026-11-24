# Kotlin 迁移分支剩余风险审查

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
