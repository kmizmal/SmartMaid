# SmartMaid Kotlin 迁移：Loom 验收后交接

> 交接日期：2026-11-24（北京时间）\
> 分支：`feature/kotlin-migration`\
> 代码基线提交：`d9245721f36792c969e125f08f7084701713f51f`（`fix: 修复合成材料规划与女仆侧车恢复`）\
> 远端：`origin/feature/kotlin-migration` 已包含该代码提交。

## 1. 当前结论

**代码修复和 26.2 游戏验收已完成；T4 未执行，26.3 专用验证按用户决定未做，合并建议仍是“暂缓/修复后合并”，不自动合并。**

- 26.2 玩法验收中的 `CraftExecutor` c3 误报已修复并复跑通过。
- `MaidDataManager` 的侧车装备槽位恢复问题已修复并复跑通过。
- 26.2/26.3 构建、33 个互操作测试、静态验证均通过。
- `SmartMaid/tools/launch_game.py` 和 `SmartMaid/tools/run_e2e_test.py` 仍保留；`README.md` 旧入口说明未改。
- 用户明确不补做 26.3 专用世界、26.3 键盘验证；不要把它写成已验证。
- 用户已有 `SmartMaid/DESIGN_AI_INTERFACE.md` 修改仍在工作区，未提交；不要覆盖、回退或纳入提交。

## 2. 已完成结果

| 项目 | 结果 | 证据 |
|---|---|---|
| T1 标准 Loom 启动并进入专用世界 | PASS | `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/M1-world/entry.log` |
| T2-A 基础 AutoTest（修复后） | PASS=6 FAIL=0 | `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/A-full2-20261124-afterfix/game.log:190-201` |
| T2-B 兼容规则 | PASS=21 FAIL=0 | `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/suite-B-20261124/game.log:249` |
| T2-C 完整队列 | PASS=30 FAIL=0 | `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/suite-C-20261124/game.log:315` |
| T2 持久化/恢复（修复后） | PASS=2 FAIL=0 | `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/persist-phase2-success/game.log:163,197-201` |
| T3 GUI `gui-chain` | PASS 29/29，FAIL/ERROR=0 | `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/suite-GUI-20261124/clienttest.report.json` |
| T3 键盘 `key-encoding` | PASS 3/3（仅 26.2） | `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/suite-key-20261124-success/clienttest.report.json` |
| 26.2 clean build / tests | PASS，6 suites / 33 tests / 0 failures / 0 errors / 0 skips | `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/craftfix-final-20261124/26.2-test-results/` |
| 26.3 clean build / tests | PASS，6 suites / 33 tests / 0 failures / 0 errors / 0 skips | `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/craftfix-final-20261124/26.3-test-results/` |
| `verify_all.py --series 26.2` | A/B/C PASS；PAL 0 fatal/0 warning；209 依赖成员匹配 | `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/craftfix-final-20261124/verify-all-26.2.log` |

完整验收记录见 [Loom 玩法验收报告](2026-11-24-loom-gameplay-acceptance-report.md)；当前迁移状态见 [KOTLIN_MIGRATION_STATUS.md](../../../SmartMaid/KOTLIN_MIGRATION_STATUS.md)。

## 3. 本次代码变更

### 3.1 `CraftExecutor` 循环自举/材料计数修复

文件：`SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/craft/CraftExecutor.kt`

- 干跑规划改为预算式 `PlanResult`；
- `describe(... targetItem)` 用 `inventoryCounts()` 和目标物品 blocked/visited；
- `planMissing`/`planItem` 使用 `Map<Item, Int>` 预算，在每个配方分支消费/回滚，并记录多余产出；
- `ensureIngredient` 的库存计数排除当前展开链，实际合成也不再把祖先物品当普通库存。

修复前 c3 为 `craftable=true`、`craft_plan=["minecraft:iron_nugget×9"]`，执行 craft 后背包仍只有 1 个铁锭；修复后 c3 为 `craftable=false`，独立探针 PASS=4 FAIL=0，完整 A 套件 PASS=6 FAIL=0。

### 3.2 `MaidDataManager` 侧车恢复修复

文件：`SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/data/MaidDataManager.kt`

- 第 29 行：保存装备槽位从 `slot.name` 改为 `slot.getName()`（Java 原实现使用小写序列化名）；
- 第 55 行：读取时对槽位名 `lowercase()`，并用 `runCatching { ... }.getOrNull()` 容错，兼容旧 Java 小写文件以及迁移期间生成的大写文件。

修复前 `MaidDataManager.load` 抛 `java.lang.IllegalArgumentException: Invalid slot 'MAINHAND'`，导致背包和任务队列恢复中断；修复后恢复 `restart_guard` 任务和 7 个金锭，PASS=2 FAIL=0。

## 4. 尚未完成与下一步

### 4.1 T4：旧启动入口退役（未执行）

仍存在：

- `SmartMaid/tools/launch_game.py`
- `SmartMaid/tools/run_e2e_test.py`
- `SmartMaid/README.md` 中相关旧入口说明

进入 T4 前必须先确认项目负责人明确授权，并完成：

1. 全仓库检查 `launch_game` / `run_e2e_test` 的调用者、文档、CI、IDE 配置和实际用途；
2. 把启动职责指向 `gradlew.bat runClient`，把部署/报告职责指向 AutoTest/clienttest 操作卡；
3. 只在确认没有活动依赖后定向删除两个旧脚本；
4. 删除后按新文档重新启动进程，复跑基础 AutoTest 和 GUI，收集全新报告；
5. 检查 `git grep` 活动引用为 0，再更新 README、状态文档和最终合并建议。

本阶段没有执行上述任何删除。

### 4.2 26.3 专用验证（用户决定不补做）

当前只有 26.2 的 GUI/键盘/玩法证据。`key-encoding` 本身针对 26.3 SDL，但本轮 3/3 PASS 来自 26.2，不能据此声明 26.3 输入兼容。

如果用户改变范围、要求 26.3 发布验证：

- 使用独立的 26.3 专用世界及备份，不升级、不覆盖 26.2 存档；
- 用 T4 计划中的 26.3 `-P` 参数启动；
- 另交 GUI/键盘报告并明确版本。

### 4.3 合并建议

保持“暂缓/修复后合并”，不自动合并。T2-A 已消除，但 T4 未执行、26.3 未验证；应由项目负责人根据验收报告和发布目标决定下一步。

## 5. 下次开工命令

从模块目录执行（Windows PowerShell，当前终端有效）：

```powershell
Set-Location 'E:\study\bash\GitHub\zmalP\SmartMaid\SmartMaid'
$env:SMARTMAID_MC_DIR = (Resolve-Path '.\run').Path
.\gradlew.bat --offline --no-daemon --console=plain runClient
```

客户端报告工具在设置了真实 `SMARTMAID_MC_DIR` 时**不要传 `--series`**，否则会再拼 `versions/<series>`：

```powershell
python tools/clienttest.py report
python tools/clienttest.py errors
```

每轮开始前先归档已有报告；`clienttest.py` 复制配置时会覆盖目标并删除旧报告，除非显式使用 `--keep`。

## 6. 关键陷阱

- `ModEntities.kt:29` 使用 `.noSave()`：女仆实体不进入世界存档，退出后重新召唤从 `run/saves/<存档>/smartmaid/maids/<owner-uuid>.dat` 侧车恢复背包/装备/队列；**UUID/坐标不跨重启保持**，验收应检查侧车恢复，不是同一实体 UUID。
- `MaidDataManager.load` 只在 `SummonMaidCommand.doSummon` 新召唤时调用；不要把侧车恢复误解为原生实体 NBT 持久化。
- `MaidAutoTest` 完成后会把 `autotest.json` 改名为 `autotest.done.json`；下一轮必须重新部署输入，且一次只部署一套配置。
- `CraftExecutor` 的干跑现在按库存预算递归；不要再改回只按裸库存 `countMatching(inv, ...)` 判断，否则 c3 循环自举和材料重复计数会回归。
- 游戏必须完整退出（正常关闭或 `CloseMainWindow()`），确认 `Saving worlds`、`PersistTester退出了游戏` 后再启动下一轮。
- `run/options.txt` 已恢复 `pauseOnLostFocus:true`；若临时修改，测试后恢复。
- 用户已有 `SmartMaid/DESIGN_AI_INTERFACE.md` 修改保持未暂存；不要将它纳入任何提交。
- 本地 `kotlin-migration-local` 远程路径 `.migration-work/repo` 已不存在；`git status` 可能显示相对它的 `ahead 29`，这是陈旧 tracking。实际 `origin/feature/kotlin-migration` 已是 `d9245721f36792c969e125f08f7084701713f51f`。

## 7. 交付清单

- 代码修复：`CraftExecutor.kt`、`MaidDataManager.kt`
- 状态记录：`SmartMaid/KOTLIN_MIGRATION_STATUS.md`
- 验收报告：`docs/superpowers/handoffs/2026-11-24-loom-gameplay-acceptance-report.md`
- 本交接：`docs/superpowers/handoffs/2026-11-24-kotlin-migration-handoff.md`
- 本机证据目录：`.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/`
