# SmartMaid Kotlin 迁移：Loom 玩法验收报告

**日期：** 2026-11-24（北京时间）
**基线：** `feature/kotlin-migration` @ `60b5d82f37b01ce1f8a5a253e8f736a842b50fe2`
**游戏目录：** `E:\study\bash\GitHub\zmalP\SmartMaid\SmartMaid\run`
**启动方式：** Windows `gradlew.bat --offline --no-daemon --console=plain runClient`；未使用 HMCL、token 或旧的 `launch_game.py` / `run_e2e_test.py`。
**环境：** Minecraft 26.2 / Fabric Loader 0.19.5 / Fabric API 0.161.0+26.2 / SmartMaid 0.1.3 / JDK 25.0.5.1 / Gradle 9.9.1。

## 结论

**暂不建议宣布“可以合并”。** M1 通过，T3 的 GUI 与键盘测试通过，B/C 服务端套件通过；T2-A 的 `CraftExecutor` 干跑误报已于 2026-11-24 修复并复跑 PASS=6 FAIL=0。剩余阻断是 26.2 键盘结果不能替代 26.3 SDL 验证、旧脚本未删除、T4 退役门禁未执行。

## 1. 里程碑结果

| 项目 | 结果 | 证据 |
|---|---|---|
| T1 标准启动进入专用世界 | PASS | `M1-world/entry.log:2,81,194,243-244`；实际进入 `SmartMaid-AutoTest`，正常保存退出 |
| T2-A 基础 AutoTest | PASS（修复后 PASS=6 FAIL=0；修复前 5/6） | 修复前 `A-20261124-first/game.log:196-202`；修复后 `A-full2-20261124-afterfix/game.log:190-201` |
| T2-B 兼容规则 AutoTest | PASS（PASS=21 FAIL=0） | `suite-B-20261124/game.log:249` |
| T2-C 完整队列 AutoTest | PASS（PASS=30 FAIL=0） | `suite-C-20261124/game.log:315` |
| T2 持久化/恢复 | 修复后 PASS（PASS=2 FAIL=0） | `persist-phase2-success/game.log:163,197-201` |
| T3 GUI `gui-chain` | PASS（29/29，FAIL/ERROR=0） | `suite-GUI-20261124/clienttest.report.json` |
| T3 键盘 `key-encoding` | PASS（3/3） | `suite-key-20261124-success/clienttest.report.json` |
| 桌宠联调 | N/A | 用户在 T1 明确本轮不验桌宠 |
| 26.2 clean build / tests | PASS（6 suites，33 tests，0 failures/errors/skips） | `craftfix-final-20261124/26.2-test-results/` |
| 26.3 clean build / tests | PASS（6 suites，33 tests，0 failures/errors/skips） | `craftfix-final-20261124/26.3-test-results/` |
| `verify_all.py --series 26.2` 静态 A/B/C | PASS | `craftfix-final-20261124/verify-all-26.2.log` |

## 2. 已修复项：A 套件 `c3` 干跑误报

A 套件实际输出：

- `c3` 期望 `result.craftable=false`，实际 `true`。
- 实际 `craft_plan: ["minecraft:iron_nugget×9"]`，来源是背包中的铁锭先被递归当成铁粒材料。
- 随后 `c4` 执行 `craft` 后，背包仍只有 1 个铁锭（没有真正增加产出）。
- 汇总：`AutoTest 汇总: PASS=5 FAIL=1`。

独立复核 `C3-probe2`：

- 空背包：`craftable=false`（PASS）。
- 给 1 个铁锭：`craftable=true`，`craft_plan` 为 9 个铁粒；这是递归自举，不是可用材料。
- 执行 `craft` 后实际仍为 1 个铁锭。
- 给 1 个铁块：`craftable=true`，执行后实际 10 个铁锭（正常路径）。

判定：**迁移前既有逻辑问题，不是 Kotlin 迁移新引入的偏差**。对照 `main`（`c7094701bea239a2ea8eb9033ee2cec3f206b1a5`）中的 `CraftExecutor.java` 与当前 `CraftExecutor.kt`，`resolveMissing` / `resolveItem` 的递归与材料计数逻辑一致；当前 Kotlin 代码只是等价转写。是否修复该产品行为需要单独决定，不能通过修改 `autotest.json` 期望值来掩盖。

### 2.1 2026-11-24 修复与复跑

- 修改 `SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/craft/CraftExecutor.kt`：干跑规划改为预算式 `PlanResult`，目标物品加入 blocked/visited，`planMissing`/`planItem` 使用 `Map<Item,Int>` 库存预算并按配方分支消费/回滚，记录多余产出；同时 `ensureIngredient` 的库存计数排除当前展开链，防止实际 `craft` 路径也把祖先物品当普通库存。
- 独立探针 `c3-fix-20261124`：`c3` PASS（`craftable=false`），汇总 PASS=4 FAIL=0。
- 干净世界 `SmartMaid-CraftFix-A2` 完整 A 套件：PASS=6 FAIL=0，证据 `A-full2-20261124-afterfix/game.log:190-201`。
- 修复后 26.2/26.3 clean build 均 6 suites / 33 tests / 0 failures / 0 errors / 0 skipped；`verify_all.py --series 26.2` A/B/C 全部通过。

## 3. 持久化验收与修复

### 3.1 发现的问题

首次 `persist-after` 失败：

- 进入同一存档后 `AutoTest: 未找到女仆，已执行 summonmaid`。
- `MaidDataManager.load(MaidDataManager.kt:55)` 抛 `java.lang.IllegalArgumentException: Invalid slot 'MAINHAND'`。
- `inventory_after` FAIL（金锭 0，期望 7）；`queue_after` FAIL（无 `restart_guard`）；汇总 `PASS=0 FAIL=2`。

根因：Java 原实现保存 `slot.getName()`（小写 `mainhand`），Kotlin 迁移写成 `slot.name`（枚举名大写的 `MAINHAND`）；`EquipmentSlot.byName` 不接受大写，异常又中断了整个存档读取。

### 3.2 最小修复

`SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/data/MaidDataManager.kt`：

- 第 29 行：`slot.name` → `slot.getName()`。
- 第 55 行：读取时对槽位名做 `lowercase()` 并用 `runCatching {...}.getOrNull()` 容错；既能读旧 Java 小写文件，也能读迁移期间生成的大写文件，单条坏槽位不再中断库存/任务恢复。

修复后重跑 `persist-after`：

- `女仆任务队列已恢复 1 项（自动继续）`
- `inventory_after` PASS（金锭 7）
- `queue_after` PASS（`restart_guard`，`state: RUNNING`）
- `AutoTest 汇总: PASS=2 FAIL=0`
- 新侧车 NBT 中 `equipment[0].slot` 已为 `mainhand`。

### 3.3 设计说明

`SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/init/ModEntities.kt:29` 使用 `.noSave()`，女仆实体本身不进入世界存档；退出后重新召唤时从 `run/saves/<存档>/smartmaid/maids/<owner-uuid>.dat` 恢复背包/装备/任务队列。因此：

- **跨重启 UUID 与坐标不会保持**，这是现有产品设计，不是持久化失败。
- 验收应检查“重新召唤后侧车状态恢复”，而不是“同一实体 UUID 继续存在”。原计划中“记录同一女仆 UUID”的表述需要按此设计修正。

## 4. T3 键盘测试

- 第一次无人操作：`key.poll` FAIL（`KeyMapping.isDown=false`, `LShift(340)=false`, `RShift(344)=false`），已归档 `suite-key-20261124-first`。
- 操作人配合按住 Shift+E 约 6 秒后：`key-encoding` 3/3 PASS，`KeyMapping.isDown=true`、`LShift(340)=true`；归档 `suite-key-20261124-success`。
- 该结果来自 26.2 客户端。`key-encoding` 脚本本身针对 26.3 的 SDL scancode 行为；如要声明 26.3 输入兼容，需要按计划用 26.3 参数另建世界重跑，不能用本报告替代。

## 5. 门禁与 T4

- 26.2/26.3 `clean build` 均 `BUILD SUCCESSFUL`，测试 XML 均为 6 suites / 33 tests / 0 failures / 0 errors / 0 skipped。
- `python tools/verify_all.py --series 26.2` 的 A 编译、B Mixin、C 依赖成员全部通过。
- T2-A 已修复，但 26.3 SDL 键盘/玩法仍未单独验证，T4 的两个旧脚本删除仍未执行：`SmartMaid/tools/launch_game.py`、`SmartMaid/tools/run_e2e_test.py` 仍存在；`README.md` 的旧入口说明也未删除。
- 工作树只新增本次最小源码修复；用户已有的 `SmartMaid/DESIGN_AI_INTERFACE.md` 修改和未跟踪 `docs/` 保持不动。

## 6. 后续建议

1. `CraftExecutor` 循环自举/材料计数已按单独任务修复并验证；A 套件复跑 PASS=6 FAIL=0，不再需要修改 `autotest.json` 期望。
2. 如发布目标是 26.3，补做 26.3 专用世界的 GUI/键盘/玩法验证，不覆盖 26.2 存档。
3. T2-A 已消除；在 26.3 验证和 T4 退役门禁完成前，合并建议保持“暂缓/修复后合并”，不自动合并。

## 7. 2026-11-24 c3 复测：排除游戏暂停因素

用户要求再次确认 `c3` 是否由游戏暂停导致。复测在独立克隆世界 `SmartMaid-C3-Pause-A` 中进行，并从 `run/options.txt` 临时设置 `pauseOnLostFocus:false`（测试后已恢复 `true`）；输入与 A 套件同序列：给 5 个铁锭 → `craft_check iron_pickaxe` → `craft iron_pickaxe` → `craft_check iron_ingot`。

结果：

- `pauseOnLostFocus:false` 下 `c3` 仍为 **FAIL**：`result.craftable=true`，`craft_plan: ["minecraft:iron_nugget×9"]`。
- 紧接的 `checkInv minecraft:iron_ingot 实际=2 -> true` 证明前一步 `craft` 已正常完成（5 个铁锭被消耗 3 个，剩 2 个），不是暂停导致 `craft` 未完成或库存未更新。
- 汇总：`PASS=3 FAIL=1`；输入、完整日志、测试时 `options.txt` 与 `autotest.done.json` 归档于 `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/c3-pause-retest-20261124/`。

结论：**c3 的误报与游戏暂停无关**，是 `CraftExecutor` 干跑把“铁锭 → 铁粒 → 铁锭”的递归循环视为可满足所致；修复方向仍是单独处理循环自举/材料计数，不能通过改测试期望或调整暂停设置解决。
