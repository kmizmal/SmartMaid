# P3 跳跃标定：基线记录（进行中）

记录日期：2026-11-26。本文是 P3 的**基线、改动和探索记录**，不是完成验收报告；没有 5/5 场景证据前不写 PASS。

## 1. 基线与环境

- 独立分支：`feature/jump-calibration`，从 `feature/kotlin-migration` 的 `d7f087c` 切出；未推送、未合并。
- 生产客户端：Minecraft 26.2，Loom runClient，测试用户名 `P3Tester`；测试输入目录 `C:\Users\zmal\AppData\Local\Temp\SmartMaid-P3-20261126-1700`。
- 基线文件 SHA256（完整值，均相对 `SmartMaid` 模块）：

| 文件 | SHA256 |
|---|---|
| `src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidJumpTable.kt` | `D477E54FBBC9DC8326F49D961B0E8DAA99D54B1667639ACB380E9AFF9C0DFEFB` |
| `src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidMoveControl.kt` | `1138BD20FB6F97163473B5B31B64C6FD9829D7694107C41510EE6797106323F1` |
| `src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidActionExecutor.kt` | `ABC5355E0D39253C14C79376C6CACBD1A4FC2E74A4909437B69E013DCFCAD60E` |
| `src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/JumpPhysics.kt` | `8FA08F638543BBE81D8F553A527DA5BAF13BF472F169C56222AB51DD8D97EFC8` |
| `src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidGroundPathNavigation.kt` | `AA1883EF9E4B67F38DF9549692F5337E4E79FD775C97EEB2910DAA50B9B43F0F` |
| `tools/maid_jump_sim.js` | `A71C6DA9F4E2577049B127A1D6C5DEC44615C423403A3B94FAFF4BC9E6927F7B` |
| `tools/gen_jump_table.js` | `483A74C7991AAF5768C4743BA893F9BA28C59F9EA58DDD1CB53C8D97E3489A8A` |

### 1.1 离线模拟器基线

`node tools/maid_jump_sim.js`（原基线日志 `jump-sim-baseline.log`）：

- `MOVEMENT_SPEED = 0.1`（脚本当前值，**与生产 `SmartMaidEntity.createAttributes()` 的 `0.16` 不一致**）。
- 走：0.220 blocks/tick / 4.41 m/s；跑：0.286 / 5.73 m/s。
- 走跳纯惯性落点 0.91 格、走跳空中输入 2.05 格；跑跳纯惯性 2.68 格、跑跳空中输入 3.83 格；飞行 12 tick。
- pillar 最佳放置 tick 3，最高 y=1.252 @t6，落地 t12。

**结论：** 模拟器的速度常量与当前生产属性不一致，其落点数字只能作为旧基线，不能直接当作当前女仆的验收值；后续需要把模拟器常量对齐 0.16，或明确它的 0.1 是另一档参数。

## 2. 本轮改动与测试工具

### 2.1 生产移动修复（待完整回归）

- `MaidStraightNav.kt`：跳跃执行器处于 `POSITIONING/JUMP/COOLDOWN` 时立即清掉直线降级目标；当直线降级还在活动时，先尝试把 `from -> target` 交给 `MaidActionExecutor.tryHandle()`。
- `MaidGroundPathNavigation.kt`：动作执行器活动期间不重新建立直线降级目标，`tick()` 不调用原版 `PathNavigation`，避免原版路径继续向 `MoveControl` 写入目标。
- `MaidActionExecutor.kt`：`start()` 清掉 `MaidGroundPathNavigation.straightNav`；`tickJump()` 的空中前进输入由 `2.0f` 改为原版持续前进语义的 `1.0f`。
- **本轮没有改 `MaidJumpTable.kt`、`JumpPhysics.kt` 或 `gen_jump_table.js`：** 观测到的偏差可归因于直线导航/原版路径覆盖跳跃定位，修复放在执行器与导航层；跳表数值保留原基线，不把未改表误报成已重新生成。

这些改动解决了本轮实际观察到的“定位阶段被直线导航/原版路径拉过起跳边缘，60 tick 后才从沟内起跳”的问题。当前 26.2/26.3 `clean build` 均通过，测试均为 **8 suites / 40 tests / 0 failures / 0 errors / 0 skipped**（26.2、26.3 日志及 XML 见证据目录）；仍需继续回归既有移动场景并补安全断言。

### 2.2 测试钩子与输入

- `MaidAutoTest.kt`：新增可选 `recordTicks`，每次 server tick 记录 `tick,x,y,z,dx,dy,dz,onGround`，用于逐 tick 轨迹；只记录，不参与 PASS/FAIL 计数。
- `SmartMaid/tools/autotest.p3-jump.json`：3 格短沟单场景（平台、移除沟、`maidtasks move` + `recordTicks`）。
- `SmartMaid/tools/autotest.p3-scenarios.json`：平地、1 格台阶、3 格短沟、4 格边界四组场景；每组先构造地形再移动。
- `run/config/smartmaid/main.json` 本轮设 `debug=true`、`debugVerbose=true`，原文件已备份；测试世界均从 `SmartMaid-T4-20261124-A` 复制并清空 `smartmaid/maids`。

## 3. 本轮真机记录

### 3.1 单场景（`autotest.p3-jump.json`）

世界 `SmartMaid-P3-20261126-I` / `J`（同一版本、同一场景）记录的可复现轨迹：

- 起始 `82.739,66.000,90.261`；定位阶段向后退到 `z=89.426`（起跳边缘），约第 56 tick `Jump fired`，随后跨过 `z=91..93` 的 3 格沟，落到 `z≈95.6`，落点 `y=66`，没有掉入沟底。
- 修复前同类场景表现为先被拉进沟内（tick 67 已 `y=65.922,z=92.189,onGround=false`），60 tick 后才在沟内 `Jump fired`；修复后不再出现该现象。
- `Jump start`/`Jump fired`/`Jump landed` 是 `MaidActionExecutor` 调试日志；`record` 是 AutoTest 逐 tick 采样。

### 3.2 四场景批量（`autotest.p3-scenarios.json`）

世界 `SmartMaid-P3-20261126-K`，`AutoTest 汇总: PASS=8 FAIL=0`。**这个数字只是四条 `run` 的 `expectResult`/`expectTask` 断言（4×2），不是到达或安全性验收。**

| 场景 | 记录摘要 | 判定 |
|---|---|---|
| 平地 | 起始 `82.739,66,90.261`；无跳跃日志；任务在目标附近结束（水平约 1.3 格） | 1/1 本次通过（非 5/5） |
| 1 格台阶 | 目标 `84,67,93`；记录到 `y=67` 的台阶面，水平约 1.2 格 | 1/1 本次通过（非 5/5） |
| 3 格短沟 | 起点约 `82.449,66,91.304`；约 tick 344 从 `z=90.6` 起跳，落到 `z≈96.6`，未掉沟 | 1/1 本次通过；另在 I/J 两次重复通过，共 3/3 |
| 4 格边界 | 起点约 `81.885,66,89.694`；无跳跃日志，tick 499 落入沟底（`y=65`），停在 `z≈91.7`，距目标中心约 2.8 格 | **失败/未支持**，不能写 PASS |

补充：`autotest.p3-repeat.json` 在早期 L 世界尝试时，`tp` 重置后玩家跟随目标和已存在平台使部分记录起点漂移，因此 L 记录不用于验收。修正 `tp`（同时传送玩家并取消当前任务）后，M 世界重新执行了完整重复序列，结果见 3.3。

### 3.3 五次重复（`autotest.p3-repeat.json`，世界 M）

世界 `SmartMaid-P3-20261126-M`，`AutoTest 汇总: PASS=32 FAIL=0`。这仍是 16 次 `run` 的 `expectResult`/`expectTask` 断言（16×2），**不是到达/安全性判定**；下表直接从 `record` 逐 tick 采样计算，判定标准为本轮预先采用的“轨迹中最小目标水平距离 ≤ 2 格，且全程 `minY` 不低于起始脚底 `y - 0.1`”。

| 场景 | 五次记录的最小目标距离 / 最低 y | 判定 |
|---|---|---|
| 平地 | 0.98 / 1.02 / 0.84 / 1.05 / 1.08；五次 `minY=66` | **5/5** 到达且无掉落 |
| 1 格台阶 | 1.10 / 1.09 / 1.21 / 0.97 / 1.36；五次 `maxY=67.25`、`minY=66` | **5/5** 越过台阶并到达 |
| 3 格短沟 | 0.13 / 1.25 / 0.13 / 0.07 / 0.08；五次 `minY=66` | **5/5** 跨沟且无掉落 |
| 4 格边界 | 最小水平距离 0.80，但 `minY=65`（落入沟底） | **0/1：失败/未支持**，不能写 PASS |

### 3.4 静止/坐下安全（`autotest.p3-safety.json`，世界 N）

`AutoTest 汇总: PASS=3 FAIL=0`，三次命令分别是 `maidtasks sit`、再次 `maidtasks sit`（解除）和 `maidtasks stop`。`record` 的 60 个 tick 全部为 `82.739,66.000,90.261`，位移与速度均为 0；说明坐下期间 `MaidActionExecutor.tick()`/导航没有推动女仆。战斗状态下“不挖不搭”约束尚未在本轮单独测试。

### 3.5 N1 命令回归（移动改动后）

在 P3 移动改动之后重跑 `SmartMaid/tools/autotest.n1-commands.json`（世界 `SmartMaid-P3-20261126-P`）：`AutoTest 汇总: PASS=41 FAIL=0`。这覆盖真实玩家命令源下的 10 个恢复子命令、参数拒绝和攻击/挖掘/耕作等路径，说明本轮 `MaidStraightNav`/`MaidGroundPathNavigation` 改动没有破坏既有命令执行；它不替代 P3 的专项到达/安全断言。

## 4. 当前结论与待办

- 已建立离线基线和逐 tick 真机记录；已发现并修复“直线导航/原版路径覆盖跳跃定位”的问题；平地、1 格台阶、3 格短沟按上述几何标准各 **5/5**，4 格边界记录为失败/未支持；坐下 60 tick 位移为 0。
- **P3 仍需收尾：** 上述 5/5 是逐 tick 轨迹几何判定，不是 AutoTest 内置正式断言；下一步需要把这些判定加入可重复的断言/报告器，补战斗不挖不搭/助跑等安全约束，并继续跑 26.2/26.3 既有移动套件回归（双版本 `clean build` 已通过）。不能仅凭 `PASS=32` 声称所有场景通过。
- 下一轮优先：实现到达/未掉沟/未穿墙断言；补战斗不挖不搭和助跑场景；确认 4 格边界是否应作为不支持场景记录，或调整参数后重新测；对既有移动场景继续回归。

## 5. 证据路径

- 证据根目录：`C:\Users\zmal\AppData\Local\Temp\SmartMaid-P3-20261126-1700`
- `baseline-sha256.txt`、`jump-sim-baseline.log`
- `run-I.log`、`run-J.log`、`run-K.log`、`run-M.log`（Gradle 运行输出）
- `game-K.log`、`game-M.log`、`game-N.log`、`game-P.log`（K/M/N/P 轮游戏日志副本）
- `prior-main.json`（本轮修改前的 `main.json`）
