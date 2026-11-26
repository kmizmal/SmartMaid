# Kotlin migration — N1/N2 本机验证完成，待合并决定

## 当前状态（2026-11-26，N1/N2 本机验证完成）

下一阶段见 [开发计划](../docs/superpowers/plans/2026-11-24-next-stage-development.md)：N1/N2 已按计划完成本机验证，详细证据与限制见 [N1/N2 验证报告](../docs/superpowers/handoffs/2026-11-26-n1-n2-validation-report.md)；N3 合并材料复核和 P3 尚未执行。

- N1：`SmartMaid/tools/autotest.n1-commands.json` 在 26.2 真机世界 `SmartMaid-N1-20261126-F` 执行，`AutoTest 汇总: PASS=41 FAIL=0`；覆盖真实玩家命令源、transfer/chest/equip/store/look/break/place/use/attack/mine/farm 及拒绝路径。证据见临时目录 `C:\Users\zmal\AppData\Local\Temp\SmartMaid-N1-20261126-150236\runClient-N1-F-final-PASS.log`。
- N2：`SmartMaid/tools/clienttest.inventory-live.json` 在 26.2 真机世界 `SmartMaid-N2-20261126-D` 执行，`clienttest.report.json` 为 37/37 PASS、0 FAIL/ERROR；真实 `MaidInventoryScreen`/`MaidInventoryMenu maidId=147`、装备同步、shift-click 数量守恒和关开重进均通过。证据见 `clienttest.report.N2-D-PASS.json` 和 `runClient-N2-D-final-PASS.log`。
- 构建/测试：26.2、26.3 `clean build` 均成功，各 **8 suites / 40 tests / 0 failures/errors/skips**；26.2 `verify_all.py --series 26.2` A/B/C 全通过；工作树、暂存区、`main...HEAD` 的 `git diff --check` 均为 0。
- 未验证：26.3 专用世界/实体键盘未补做，桌宠 N/A；专用服务端默认权限未单独搭建；N3 合并材料、PR/批准/必需检查未处理；不自动推送或合并。

- 审查 R1/R2 已由 `4516815` 修复：恢复 /maidtasks 的 10 个子命令和可选目标/范围/数量参数；新增真实 Brigadier 注册/解析与越界拒绝回归。修复前 4 项中 3 项失败，修复后 4/4 通过。
- 修复后 26.2、26.3 clean build 均成功（50 秒 / 37 秒），各 **7 suites / 37 tests / 0 failures/errors/skips**；新增 1 个 suite、4 项测试，原 33 项保留。XML 已分版本归档。
- 修复后 26.2 静态总门 A/B/C 全通过，PAL 0 fatal / 0 warning，依赖成员检查通过。
- R3 已由 `ae65347` 独立修复：限定 16 个文件规范行尾，保留 Markdown 硬换行语义；全分支 `git diff --check main...HEAD` 退出 0。模块新增忽略 `.kotlin/`；生产 Java=0、Kotlin=131，Java 测试文件本轮前 7 个，新增 `SlotsTest` 后 8 个。
- 证据：`.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/command-fix-20261124/`；详细红绿测试、命令、范围与限制见 [修复复核记录](../docs/superpowers/handoffs/2026-11-24-kotlin-migration-risk-review.md)。
- 本轮未进游戏执行命令，解析测试不替代世界操作/权限/真实容器运行验证；此前 T4 记录保留为修复前的独立证据。26.3 专用世界/输入按用户决定仍不补做，桌宠 N/A。已确认的三项问题已修复，最终合并仍由负责人结合验证范围决定；不自动推送或合并。
- 根目录 LICENSE 已替换为 GPLv3 完整正文，与 GPL-3.0.txt 一致；上游 MIT 原文保存在 LICENSE-MIT-upstream.txt，二者及 NOTICE 随 jar 打包，构建和包内字节核对通过。
- 用户设计文档保持原有修改，未纳入提交。现有 Kotlin/弃用警告未扩展清理。


## T4 终验记录（命令修复前，保留独立证据）


- 两个旧入口 `tools/launch_game.py`、`tools/run_e2e_test.py` 已定向删除，README、开发计划和 [Loom 测试操作卡](../docs/superpowers/handoffs/2026-11-24-loom-test-runbook.md) 指向 Wrapper / AutoTest / clienttest。未修改业务源码。
- 删除后 26.2 `clean build`：用户普通 PowerShell 成功（54 秒），本机核对并归档 6 suites / 33 tests / 0 failures/errors/skips。随后通过 IDEA MCP 完整复跑 `verify_all.py --series 26.2`，A/B/C 全部通过，A 构建 53 秒；本轮 26.2 XML 亦为 6 suites / 33 tests / 全零失败、错误、跳过。
- 删除后 26.3：IDEA MCP 终端按操作卡的全部五个版本参数执行 `--offline --no-daemon --console=plain clean build`，`BUILD SUCCESSFUL in 1m 1s`；6 suites / 33 tests / 0 failures/errors/skips。XML 已在静态总门前归档至 `T4-20261124/26.3-build-20261124-175221-538/`，成功输出保存在同目录 `daemon-build.log`。首次终端调用超时返回，构建继续完成；未把超时响应当作成功退出码。
- 静态 Mixin：PAL 1.2.6，0 fatal / 0 warning。依赖签名：84 个引用类型全部存在、209 个成员全部匹配。证据为 `verify-all-26.2-idea.log` 和 `dep-26.2-idea.log`。
- 基础 AutoTest：独立 26.2 世界 `SmartMaid-T4-20261124-A`，启动前 level.dat 与干净快照哈希一致；c1–c6 全部 PASS，17:55:58 汇总 PASS=6 FAIL=0，done 与本轮输入逐字节一致。用户正常保存退出，日志确认所有维度保存和客户端停止。证据 `A-idea-20261124-175417/`。
- GUI：独立新进程、世界 `SmartMaid-T4-20261124-GUI`；报告时间 18:00:26–18:00:28（北京时间），Minecraft 26.2 / SmartMaid 0.1.3，gui-chain 29/29 PASS，FAIL/ERROR=0，done 与输入一致。错误通道本轮新增 5 条 INFO，0 WARN/ERROR；13:43 的按键 ERROR 为历史记录。正常保存退出已核对，证据 `GUI-idea-20261124-175950/`。
- GUI 覆盖菜单链路与入口调用；报告 nearestMaid=-1，invokeOpenInventory=false，未断言真实女仆容器打开，也不替代实体按键验证。26.3 专用世界/输入按用户决定不补做，保持未验证；桌宠 N/A。
- 活动代码/运行配置调用仍为 0；IDEA workspace.xml 的两个 beforePath 为删除文件的 changelist 元数据，不是调用。历史和退役文本保留。用户设计文档 SHA-256 保持 `90982E0DB300D408ADD8EA61637CB64CABB74F394987C82504F41FAB0AFAE1A1`。
- IDEA MCP 终端可用，已完成构建和客户端测试；先前 Work shell 的 localhost 限制记录保留为历史，不再作为本轮阻断。没有修改系统权限、依赖或 JVM 配置。
- T4 / M4 本轮技术检查通过；合并建议维持“暂缓/修复后合并”，待项目负责人结合未验证范围作决定。不自动提交、推送或合并；工作树仅含交接时已有的预期改动。完整证据与范围见 [T4 退役记录](../docs/superpowers/handoffs/2026-11-24-launcher-retirement-report.md)。

## 历史验证记录（以下保留原时间线，以顶部当前状态为准）

Checked on 2026-11-23 during execution of `docs/superpowers/plans/2026-11-23-kotlin-migration-merge-fixes.md`.
This document records the current working-tree state and the commands actually run in this checkout.
Task 5 static checks completed on 2026-11-23. The migration is not marked as unconditional merge-ready: the static gate is green, but full AutoTest/gameplay E2E could not start because this machine has no HMCL launch log or installed 26.2 game directory.

## Branch and baseline

- Working branch: `feature/kotlin-migration`.
- Review baseline from the migration plan: HEAD `0c84442`.
- Existing untracked `docs/` content was preserved; no unrelated files were intentionally modified.
- The plan requested an isolated worktree, but this host sandbox denied writes to both an external worktree location and Git metadata; execution therefore continued in the current branch with path-scoped verification.

## Dependency configuration

| Dependency | Configured version | Notes |
| --- | --- | --- |
| Minecraft 26.2 | 26.2 | Default `mc_series=26.2` |
| Minecraft 26.3 | 26.3 | Verified through quoted Gradle `-P` overrides |
| Fabric API 26.2 | 0.161.0+26.2 | Verified by 26.2 build |
| Fabric API 26.3 | 0.161.0+26.3 | Verified by 26.3 build |
| Fabric Loader 26.2 | 0.19.3 | Repository configuration |
| Fabric Loader 26.3 | 0.19.5 | Verified by 26.3 build |
| Fabric Loom 26.2 | 1.17.21 | Verified by 26.2 build |
| Fabric Loom 26.3 | 1.18.4 | Verified by quoted 26.3 build |
| Kotlin plugin / stdlib | 2.4.21 | Verified by both builds |
| Fabric Language Kotlin | 1.14.2+kotlin.2.4.21 | Verified by both builds |
| Gradle Wrapper | 9.9.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Java / Kotlin target | 25 | Local runtime: JDK 25.0.5.1 |
| Mappings | None | Official unobfuscated names |

`fabric.mod.json` declares Java `>=25`, Fabric Language Kotlin, Minecraft, Fabric API and Player Animation Library dependencies. Licenses remain GPL-3.0-only, MIT and CC-BY-NC-SA-4.0.

## Actual migration scope

Current working-tree counts are:

- `src/main/java`: **0** production Java files;
- `src/main/kotlin`: **127** shared Kotlin files;
- `src/mc26.2/kotlin` and `src/mc26.3/kotlin`: **4** version-specific Kotlin files;
- all production Kotlin sources: **131**;
- Java tests: **6**, intentionally retained as Java↔Kotlin interoperability regression tests.

The migration keeps the public Java-facing contracts, payload formats, serialization order and runtime behavior unchanged. The current fix also restores `SmartMaid/tools/gen_jump_table.js` so it emits `src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidJumpTable.kt` with the Kotlin `object`, `@JvmRecord` and `@JvmStatic` API shape.

## Validation performed

### Generator and script checks

- Initial reproduction of `node tools/gen_jump_table.js` failed with:
  `TypeError: Cannot read properties of undefined (reading 'join')`.
- After the generator fix, the same command exited 0 and reported:
  `[gen] written .../MaidJumpTable.kt: 15 solutions; unsolvable: none (all covered)`.
- `node --check tools/gen_jump_table.js`: passed.
- `node --check tools/maid_inventory_sim.js`: passed.
- `python -m py_compile tools/verify_all.py`: passed.
- `git diff --check`: passed after normalizing only the target generator file to LF.

### Gradle/JDK checks

- `java -version` / `javac -version`: JDK `25.0.5.1`.
- No active Java/Gradle process was observed during wrapper-cache diagnosis.
- Offline wrapper `help` with Gradle 9.9.1: passed.
- 26.2 build:
  `gradlew.bat --offline --no-daemon --console=plain clean build`
  → `BUILD SUCCESSFUL`.
- 26.3 build:
  `gradlew.bat --offline --no-daemon --console=plain clean build -Pmc_series=26.3 -Pminecraft_version=26.3 -Ploader_version=0.19.5 -Ploom_version=1.18.4 -Pfabric_api_version=0.161.0+26.3`
  → `BUILD SUCCESSFUL`.
- The 26.3 `-P` values must be quoted when entered through PowerShell; the unquoted form incorrectly resolved Loom as version `1` and failed plugin lookup, while the quoted form passed.
- Builds emitted existing deprecation and Kotlin inspection warnings, but no compilation errors.

### Still pending

- `python tools/verify_all.py --series 26.2` initially exposed a stale hard-coded `0.158.0+26.2` expectation and a Windows output-decoding issue. `tools/verify_all.py` now uses the configured `0.161.0+26.2` range, falls back to the Gradle module cache (or `SMARTMAID_FABRIC_JAR`), decodes child-tool output as UTF-8, and includes the Gradle module cache in B/C classpaths. With `PYTHONIOENCODING=utf-8`, A/B/C all pass: `BUILD SUCCESSFUL`, PAL 1.2.6 has 0 fatal/0 warning Mixin issues, and all 209 dependency members match.
- `python tools/run_e2e_test.py --no-pet --skip-token-check --timeout 30` could not start: no HMCL launch command was found. `_paths.py` resolves the default `.minecraft` path, but it contains no `versions/26.2` directory and `clienttest.py report --series 26.2` reports the same missing version directory. Full AutoTest/gameplay assertions (summon/movement, GUI/inventory, persistence/recovery) therefore remain blocked until a real 26.2 instance is installed/launched once (or `SMARTMAID_MC_DIR` points to one).
- `clienttest.py list --series 26.2` found the available scripts `gui-chain` (29 steps) and `key-encoding` (3 steps; requires manual Shift+E interaction), but neither could be deployed without the game directory.
- CraftExecutor warning review: the existing nullability/API cleanup was retained; `filledInput()` now uses the non-null `CraftingInput` return directly, and the intentional deprecated `Ingredient.items()` calls are narrowly suppressed with comments because 26.2 exposes no non-deprecated enumeration API. The clean 26.2/26.3 compiles no longer report CraftExecutor warnings; unrelated project warnings remain.

## Remaining acceptance work

1. Task 5 tool checks completed: generator smoke passed (15 solutions), inventory simulator passed (19/19), and syntax checks passed.
2. 26.2 and quoted-parameter 26.3 `test` both passed: 6 suites, 33 tests, 0 failures/errors/skips. This covers the retained Java↔Kotlin interoperability tests.
3. Payload review confirmed six `@JvmRecord` payloads expose `@JvmField TYPE` and `@JvmField STREAM_CODEC`; `ModNetworking.register()` registers four serverbound and two clientbound payloads. Both 26.2/26.3 compilation and server/client startup loaded the payload classes and `smartmaid` initializer.
4. Earlier startup smoke passed through `runServer` (`Done`, `Smart Maid initialized`) and `runClient` (`Smart Maid initialized`, `SmartMaid 客户端初始化完成`); processes were terminated after the smoke window. The 26.2 dev jar contains the generated jump-table classes, all six payload classes, `META-INF/LICENSE`, and `META-INF/NOTICE.md`.
5. The corrected P0 verification command now passes A/B/C with the Gradle cache classpath. Static merge gate is green, but the branch remains `修复后合并` until the real game instance is available and AutoTest/clienttest gameplay evidence is collected.

## 2026-11-24 Loom 玩法验收（实际运行，仍为“修复后合并”）

使用 `SmartMaid/run` 作为游戏目录、Windows `gradlew.bat --offline --no-daemon --console=plain runClient` 启动；未使用 HMCL/token。详细证据与路径见 `docs/superpowers/handoffs/2026-11-24-loom-gameplay-acceptance-report.md`。

- T1 进入专用世界并正常保存退出：通过。
- T2-A 基础 AutoTest（修复前）：**FAIL，PASS=5 FAIL=1**。`c3` 期望铁锭 `craft_check=false`，实际 `craftable=true`，`craft_plan` 为 9 个铁粒；执行 `craft` 后库存仍为 1 个铁锭。独立探针确认这是 `CraftExecutor` 递归自举/材料计数误报；对照 `main` 的 Java 与当前 Kotlin，相关 `resolveMissing`/`resolveItem` 逻辑一致，判定为迁移前既有问题，不是 Kotlin 迁移回归，需单独产品决策。该问题已于同日修复，见下方“CraftExecutor 循环自举/材料计数修复”。
- T2-B 兼容套件：PASS=21 FAIL=0；T2-C 队列套件：PASS=30 FAIL=0。
- T2 持久化：首次失败，`MaidDataManager.load` 抛 `IllegalArgumentException: Invalid slot 'MAINHAND'`；原因为 Kotlin 迁移把 Java `slot.getName()` 写成 `slot.name`。已最小修复 `MaidDataManager.kt` 第 29、55 行（保存用 `getName()`，读取兼容大小写）。修复后重跑 PASS=2 FAIL=0：工具队列恢复 1 项、金锭 7、`restart_guard` RUNNING。
- `ModEntities.kt:29` 的 `.noSave()` 使女仆实体不进入世界存档；退出后重新召唤从侧车恢复背包/装备/队列是现有设计，UUID/坐标不跨重启保持。
- T3 `gui-chain` 29/29 PASS；`key-encoding` 3/3 PASS（26.2，操作人实际按住 Shift+E）。26.3 SDL 键盘尚未单独验证；桌宠本轮 N/A。
- 26.2/26.3 `clean build` 均通过，各 6 suites / 33 tests / 0 failures / 0 errors / 0 skipped；`python tools/verify_all.py --series 26.2` A/B/C 全通过。
- T4 未执行：`SmartMaid/tools/launch_game.py`、`SmartMaid/tools/run_e2e_test.py` 未删除，README 旧入口未改；T2-A 已解决，但仍需完成 26.3 验证和退役门禁后再进入 T4。
- 2026-11-24 c3 复测：在 `pauseOnLostFocus:false` 的独立世界 `SmartMaid-C3-Pause-A` 重跑同序列，`c3` 仍 FAIL（`craft_plan` 为 9 个铁粒），且 `checkInv minecraft:iron_ingot 实际=2` 证明 `craft` 已完成，排除游戏暂停因素；证据在 `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/c3-pause-retest-20261124/`。

## 2026-11-24 CraftExecutor 循环自举/材料计数修复

- 修改 `SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/craft/CraftExecutor.kt`：干跑规划改为预算式 `PlanResult`，目标物品加入 blocked/visited，`planMissing`/`planItem` 使用 `Map<Item,Int>` 库存预算并按配方分支消费/回滚，记录多余产出；同时 `ensureIngredient` 的库存计数排除当前展开链，防止实际 `craft` 路径也把祖先物品当普通库存。
- 独立探针 `c3-fix-20261124`：`c3` PASS（`craftable=false`），汇总 `PASS=4 FAIL=0`。
- 干净世界 `SmartMaid-CraftFix-A2` 完整 A 套件：`PASS=6 FAIL=0`，证据 `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/A-full2-20261124-afterfix/game.log:190-201`。
- 修复后 26.2/26.3 `clean build` 均 `BUILD SUCCESSFUL`，各 6 suites / 33 tests / 0 failures / 0 errors / 0 skipped；`python tools/verify_all.py --series 26.2` A/B/C 全部通过。
- 仍不自动合并：T4 未执行，26.3 SDL 键盘/玩法未单独验证；`SmartMaid/tools/launch_game.py`、`SmartMaid/tools/run_e2e_test.py` 保留。
