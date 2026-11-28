# P3 跳跃标定：基线、固定跑道与边界安全（收尾中）

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

这些改动解决了本轮实际观察到的“定位阶段被直线导航/原版路径拉过起跳边缘，60 tick 后才从沟内起跳”的问题。当前 26.2/26.3 `clean build` 均通过，测试均为 **8 suites / 40 tests / 0 failures / 0 errors / 0 skipped**（26.2、26.3 日志及 XML 见证据目录）；本轮已补 record 断言、助跑、战斗石标和朝向冒烟，未覆盖项见第 4 节。

### 2.2 测试钩子与输入

- `MaidAutoTest.kt`：新增可选 `recordTicks`，每次 server tick 记录 `tick,x,y,z,dx,dy,dz,onGround`，用于逐 tick 轨迹；record 结束后生成 `record-summary`（samples/minY/maxY/minTargetDist），`check:{"record":...}` 可对 `minTargetDistMax`/`minYMin`/`maxYMin`/`maxYMax` 做正式断言。
- `tp` 现在先取消任务并调用 `MaidActionExecutor.reset()`，清掉跳跃执行器和直线降级状态，避免重复场景起点漂移。
- `SmartMaid/tools/autotest.p3-jump.json`：3 格短沟单场景（平台、移除沟、`maidtasks move` + `recordTicks`）。
- `SmartMaid/tools/autotest.p3-scenarios.json`：平地、1 格台阶、3 格短沟、4 格边界四组场景；每组先构造地形再移动。
- `SmartMaid/tools/autotest.p3-repeat.json`：平地、1 格台阶、3 格短沟各 5 次并加 `check.record`；4 格边界只做记录，不把失败场景改写成 PASS。
- `SmartMaid/tools/autotest.p3-boundary.json`：4 格边界单独安全复测，只断言 `minY`，本轮结果为 `PASS=2 FAIL=1`（`minY=65`），保留失败证据。
- `SmartMaid/tools/autotest.p3-combat-safety.json`：攻击任务期间的石标保护复测。
- `SmartMaid/tools/autotest.p3-runup.json`：从固定锚点先直行约 5 格再跨越 3 格短沟的助跑场景，重复 5 次。
- `SmartMaid/tools/autotest.p3-orientation.json`：东向/北向各一次的同几何短沟朝向冒烟。
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

### 3.4 正式轨迹断言（R 世界）

在修正 `tp` 重置并给 `autotest.p3-repeat.json` 加入 `check.record` 后，使用世界 `SmartMaid-P3-20261126-R` 执行同一套重复场景：

- `AutoTest 汇总: PASS=47 FAIL=0 （全部通过）`。这 47 项由 16 次移动的 `expectResult`/`expectTask`（32 项）和 15 次 `check.record`（平地/台阶/短沟各 5 次）组成。
- 平地 5 次：`minTargetDist=0.959/1.019/1.053/1.222/1.055`，五次 `minY=66`、`maxY=66`。
- 1 格台阶 5 次：`minTargetDist=1.074/1.010/1.057/1.402/0.554`，五次 `minY=66`，`maxY=67.252` 或 `68.252`。
- 3 格短沟 5 次：`minTargetDist=0.081/1.154/0.076/1.154/0.079`，五次 `minY=66`。
- 4 格边界：`minY=65`、`minTargetDist=0.856`，未通过“不落入沟底”的安全判定；仍标记为失败/未支持，不把任务的 `expectResult`/`expectTask` 通过当成跳跃通过。

### 3.5 静止/坐下安全（`autotest.p3-safety.json`，世界 N）

`AutoTest 汇总: PASS=3 FAIL=0`，三次命令分别是 `maidtasks sit`、再次 `maidtasks sit`（解除）和 `maidtasks stop`。`record` 的 60 个 tick 全部为 `82.739,66.000,90.261`，位移与速度均为 0；说明坐下期间 `MaidActionExecutor.tick()`/导航没有推动女仆。战斗期间的石标保护见 3.6。

### 3.6 战斗期间方块安全（`autotest.p3-combat-safety.json`，世界 U）

`AutoTest 汇总: PASS=4 FAIL=0`：成功向牛下发 `attack` 任务并等待攻击完成；攻击前后预先放置的 `minecraft:stone` 标记仍然完整（`check:block at=lastRun` 通过）。这覆盖了“战斗期间不挖方块”的最低证据，但尚未单独探测战斗路径的放置/搭路行为，不能扩大为完整“不挖不搭”结论。

### 3.7 助跑场景（`autotest.p3-runup.json`，世界 V）

`AutoTest 汇总: PASS=15 FAIL=0`（5 次移动 ×2 项断言 + 5 次 `check.record`）。5 次 `minTargetDist=0.686/0.393/0.630/0.809/0.688`，五次 `minY=66`、`maxY=67.252`；说明从锚点先直行约 5 格再进入 3 格短沟时，5/5 到达目标且未掉沟。该场景仍由固定锚点提供起跑距离，不宣称覆盖所有地形起跳速度。

### 3.8 朝向冒烟（`autotest.p3-orientation.json`，世界 W）

W 世界向东、向北各测一次：旧断言汇总 `PASS=5 FAIL=1`。东向 `minY=66`、`minTargetDist=0.078`；北向 `minY=65`、`minTargetDist=0.209`。整段最低高度不能说明去程是否跳短。原输入逐条按女仆当前位置展开坐标，且未核验对岸净空，不能当作等几何的方向对照。

#### P0 接续：W 原始轨迹回放（基线 `5c77f97`）

| 方向 | 首次离地 tick | 首次着地 tick / 位置 | 首次低于 `65.9` 的 tick / 位置 |
|---|---|---|---|
| 东 | 75 | 86 / `(88.522, 66, 89.684)` | 本窗口未观察到 |
| 北 | 224 | 232 / `(82.666, 67, 85.373)` | 250 / `(82.409, 65.855, 87.369)` |

北向先到对岸，之后才跌落；第 252 tick 落在沟底 `y=65`。调试日志另有目标回到 `BlockPos{x=82, y=66, z=90}` 的事件，但导航日志只有墙钟时间，不能与样本绑定到同一个精确 tick。北向首落地 `y=67` 也比预期平台 `y=66` 高一格，故此轮只定位到**落地后**，不能签为等高去程通过、认定是主人跟随造成跌落，或据此改跳表。

在模块目录用 [回放工具](../../../SmartMaid/tools/p3_replay.py) 复算（`$w` 为证据目录下的 `game-W.log`）：

```powershell
python -B -m unittest discover -s tools -p test_p3_replay.py
python -B tools/p3_replay.py $w p3.o.north.move --expected-samples 100 --platform-y 66 --takeoff-zone 81 89 84 92 --landing-zone 81 85 84 86
python -B tools/p3_replay.py $w p3.o.east.move --expected-samples 100 --platform-y 66 --takeoff-zone 81 88 84 91 --landing-zone 87 88 90 91
```

区域参数顺序是 `minX minZ maxX maxZ`，上界不含。W 的区域依据日志中的实际沟位置，只用于历史回放，不是正式验收预设。退出码 `0` 表示首落地位置及落地后窗口的轨迹检查通过，`1` 表示观测到越界或跌落，`2` 表示证据或参数不足；三者都不替代地形或跟随状态检查。

#### P0 固定场地工具（本轮新增，尚未真机验收）

新增 [固定跑道生成器](../../../SmartMaid/tools/p3_course.py) 和 14 项离线测试。`python -B tools/p3_course.py east|north` 生成同一绝对起点 `(82,66,90)` 周围的旋转跑道：平台底板 `y=64..65`、三格沟的 `along=1..3`/`side=-1..1`、起跳与落地区 `y=66..69` 净空，并以 `execute if block ... run say p3-course-audit` 分批核对每个施工格。重复 fixture 会先重铺、清空、挖沟，再核验；故意在落地区放方块会被离线审计拒绝。

本轮尝试把北向 fixture 部署到临时 gameDir `C:\Users\zmal\AppData\Local\Temp\SmartMaid-P3-Course-20261126-202733-244\game`，世界副本为 `SmartMaid-P3-Course-North`，输入 SHA256 为 `BF2C4C9E5337C5E0B8EA8B86B05207AC7FA6E300064657158A793AB4D0C1608D`。客户端进程启动但在该临时目录没有产出服务端 `latest.log` 或 AutoTest 证据，无法确认已进入世界；为避免残留进程已停止该实例。本次固定场地真机验收记为**未运行/未通过**，不替代 W 旧证据。

**下一轮固定场地契约（待实现、待真机验证）：** 新专用世界中固定起步方块 `(82,66,90)`，铺等高石地 `y=65`，清空起跳、沟与落地区 `y=66..69`。三格沟从起步方块朝测试方向偏移 `1..3`，横向 `-1..1`，目标在同方向偏移 `4`；东向与北向只旋转这组偏移。全部施工使用同一组绝对坐标；连续两次重置并核验起点支撑、沟地板空气、等高对岸和净空后，才摆放不重叠的主人与女仆。正式记录还须逐 tick 对齐任务状态、导航目标和首次接手跟随的 tick，并分别出具去程与返程判定；缺失则标为无效或未验证。

### 3.9 N1 命令回归（移动改动后）

在 P3 移动改动之后重跑 `SmartMaid/tools/autotest.n1-commands.json`（世界 `SmartMaid-P3-20261126-P`）：`AutoTest 汇总: PASS=41 FAIL=0`。这覆盖真实玩家命令源下的 10 个恢复子命令、参数拒绝和攻击/挖掘/耕作等路径，说明本轮 `MaidStraightNav`/`MaidGroundPathNavigation` 改动没有破坏既有命令执行；它不替代 P3 的专项到达/安全断言。

### 3.10 P0/P1 固定跑道、方向与四格边界（2026-11-26 收尾轮）

固定跑道工具已从“仅生成”推进到真机执行（普通 `SmartMaid/run` 专用世界；不碰个人存档）：

- `SmartMaid/tools/p3_course.py` 现在支持 `--move`（先 `place [82.5,66,90.5]`、fixture、`tp`、`maidtasks move`、`check.record`）和 `--gap 3|4`；`place` 在 `MaidAutoTest` 中把女仆和玩家放到固定起点并记录 anchor，避免世界出生点漂移（`anchor`/`tp` 也保留）。离线测试 16 项通过（`test_p3_course.py` 7 + `test_p3_replay.py` 9）。
- `p3_course.py north --move` 生成 SHA256 `38A0440A64AADF96CE2C1616278FCDE0CFD3D05DC4BC2F9859C0A21E57B134A7`；`east --move` 为 `BD058F13F993D9EE4F461E33FE70F9B59BCCE3B28332ADB85F0A08DBD001BC07`；四格沟输入为 `6CE045563CC7D2E9BAA56CEF9CE8A0DC00F12CCE89CF6B95F7A6E17B80709FB5`。
- 固定场地 setup 审计（世界 `SmartMaid-P3-Course-North-2`）：`AutoTest 汇总: PASS=58 FAIL=0`；`p3.clear` 的 `fill ... air` 在已空区域返回“没有方块被填充”，不影响审计。
- 三格沟最终真机结果（`place` 固定起点；世界 `SmartMaid-P3-Course-North-2` / `SmartMaid-P3-Course-East-1`，最终代码）：
  - 北向：`PASS=32 FAIL=0`；`record-summary samples=120 minY=66.0 maxY=67.252 minTargetDist=0.04800000000000182`；`p3_replay` 首离地 tick 365、首着地 tick 376、位置 `(81.716,66,85.860)`，`outbound=landed_in_zone`、`post_landing=no_below_threshold_observed`。
  - 东向：`PASS=32 FAIL=0`；`record-summary samples=120 minY=66.0 maxY=67.252 minTargetDist=0.07881624198095018`；`p3_replay` 首离地 tick 366、首着地 tick 377、位置 `(87.798,66,89.781)`，`outbound=landed_in_zone`、`post_landing=no_below_threshold_observed`。
  - 两方向去程均证明“跳到对岸且未在窗口内跌破 65.9”；返程另行 `place` 到对岸后执行 `maidtasks move` 回起点，两个方向均 `PASS=35 FAIL=0`（29 项 fixture 审计 + 去程 2 项 + 去程 check + 返程 2 项 + 返程 check）。北向返程输入 SHA256 `73D6464AA788A05DEB0E981F5E46638DA1F86A384BC4E3436204CF695CFA0EB9`，返程 `minY=66.0 maxY=67.252 minTargetDist=0.009000000000000341`；东向返程输入 SHA256 `661C13CBA733749AC0CC26A394A91EE210D3F4371A7877B67CBE21A90FB9D49D`，返程同为 `minY=66.0 maxY=67.252 minTargetDist=0.009000000000000341`。两次返程都从对岸重新起跳并回到起点侧，窗口内未跌破 `65.9`；120 tick 仍不是无限返程/跟随证明。
- 四格边界：世界 `SmartMaid-P3-Course-NorthGap4-1` 修复前 `PASS=31 FAIL=1`，`minY=65.0 minTargetDist=1.300`，实际落入沟底，已复现。
  - 修复：`MaidGroundPathNavigation.unsafeWideGap()` 在目标同高/更高、轴向距离 >4 且连续 4 格以上地面低于当前脚底时返回 null，并设置 `blockedByWideGap`；`MoveToTask` 检测到该标志后停止任务并返回原因“已停止（前方四格以上深沟）”。修复后世界 `SmartMaid-P3-Course-NorthGap4-2`：`PASS=32 FAIL=0`，`record-summary samples=120 minY=66.0 maxY=66.0 minTargetDist=5.216249323028946`；日志出现 `导航停止：前方四格以上深沟` 与 `Move 停止：前方四格以上深沟`，女仆停在起点侧，没有落入沟底。**结论是安全停止，不是跨过四格沟。**
- 战斗约束补充（`SmartMaid/tools/autotest.p3-combat-safety-2.json`，SHA256 `CA7C54514BBBE88507EDBFED500661AFF34F96A45BE06EEC455118A141A58CCD`，世界 `SmartMaid-P3-Combat2-1`）：`PASS=5 FAIL=0`；石墙标记仍为 `minecraft:stone`，女仆背包石料 `16 -> 16` 未消耗；`p3.c2.attack` 记录 `samples=80 minY=66 maxY=66`。但该窗口的感知快照 `status.combat=false`，因此只能作为“攻击任务期间未改变石墙/未消耗石料”的补充负面检查，不能据此宣称完整战斗激活态的“不挖不搭”；既有 U 世界仍只覆盖石标保护。该场景的 `setblock` 因墙已在同一格而返回“无法放置方块”，不影响 `lastRun` 坐标记录和石墙检查。
- 战斗激活补强（`SmartMaid/tools/autotest.p3-combat-safety-3.json`，SHA256 `1a6de11d34b7339c5df74c7749d4f68e770aa8b7d92627854fdc167c782eb79c`，世界 `SmartMaid-P3-Combat3-1`）：`PASS=5 FAIL=0`；`p3.c3.attack` 真实下发 `attack` 任务并出现 `task_started/task_done`，牛 health `10 -> 6 -> 2`，石墙 marker 仍为 `minecraft:stone`，背包石料 `16 -> 16` 未消耗；记录 `samples=80`（minY=63 maxY=66，因追击牛离开平台，不影响方块保护检查）。`status.combat=false` 是该感知字段的语义限制，不能把它当作“没有攻击”；但“不挖不搭”仍按石墙/石料不变作负向验证。证据目录 `C:\Users\zmal\AppData\Local\Temp\SmartMaid-P3-Combat3-20261127-123000`。

## 4. 当前结论与待办

- 已建立离线基线和逐 tick 真机记录；已发现并修复“直线导航/原版路径覆盖跳跃定位”的问题；平地、1 格台阶、3 格短沟按上述几何标准各 **5/5**，R 世界正式 `check.record` 为 **47 PASS / 0 FAIL**；4 格边界已复现并修复为安全停止（`minY=66`，不跨沟）；坐下 60 tick 位移为 0。
- 最终门禁：26.2/26.3 `clean build` 均成功，各 **8 suites / 40 tests / 0 failures / 0 errors / 0 skipped**；26.2 `verify_all.py --series 26.2` A/B/C 全部通过（PAL 1.2.6 致命 0/警告 0，依赖成员通过）。测试 XML 已分别归档到 `final-test-results-26.2/`、`final-test-results-26.3/`。
- **仍需注意：** 四格边界现在安全停止而非跨过；战斗约束有攻击任务真实造成伤害期间石墙/石料不变的证据，但 `status.combat` 字段始终为 false，且没有独立的“挖掘/放置动作”断言；北向 W 旧试次跌落原因仍未完全确认；120 tick 窗口不替代无限返程/跟随证明。R/V/固定跑道结果不能扩大解释为所有地形或所有朝向。
- 下一步：在等高固定场地补正式“未跌出”断言与连续多次重复；补完整战斗激活态的挖/搭证据；复核 26.2/26.3 clean build、26.2 verify_all 和既有 N1 命令回归；未推送/未合并。

## 5. 证据路径

- 证据根目录：`C:\Users\zmal\AppData\Local\Temp\SmartMaid-P3-20261126-1700`
- `baseline-sha256.txt`、`jump-sim-baseline.log`
- `run-I.log`、`run-J.log`、`run-K.log`、`run-M.log`（Gradle 运行输出）
- `game-K.log`、`game-M.log`、`game-N.log`、`game-P.log`（K/M/N/P 轮游戏日志副本）
- `run-R.log`、`game-R.log`、`p3-repeat-summary-R.log`（R 轮正式断言证据）
- `run-S.log`、`game-S.log`、`p3-boundary-summary-S.log`（4 格边界安全复测：`PASS=2 FAIL=1`，记录 `minY=65`，未修复）
- `game-U.log`、`p3-combat-safety-summary-U.log`（战斗期间石标保护：`PASS=4 FAIL=0`）
- `game-V.log`、`p3-runup-summary-V.log`（助跑 3 格短沟 5 次：`PASS=15 FAIL=0`）
- `game-W.log`、`p3-orientation-summary-W.log`（东向通过、北向 `minY=65` 落沟的朝向冒烟：`PASS=5 FAIL=1`）
- `p3_course.py`、`test_p3_course.py`（固定跑道生成器及 16 项离线审计测试）
- `SmartMaid-P3-Course-North3-Final-20261126-230800/`、`SmartMaid-P3-Course-East3-Final-20261126-231000/`、`SmartMaid-P3-Course-NorthReturn2-Final-20261126-232100/`、`SmartMaid-P3-Course-EastReturn-Final-20261126-232300/`、`SmartMaid-P3-Course-NorthGap4-Final-20261126-230500/`、`SmartMaid-P3-Combat2-20261126-230000/`、`SmartMaid-P3-Combat3-20261127-123000/`（固定跑道去返程、四格安全停止与战斗补充；含 client-output、game-log、input 与 replay-north/east）
- `SmartMaid-P3-Course-20261126-202733-244/`（临时 gameDir 尝试；客户端启动但未产出 AutoTest/server log，未计真机通过）
- `final-clean-build-26.2.log`、`final-clean-build-26.3.log`、`final-test-results-26.2/`、`final-test-results-26.3/`、`verify-all-26.2.log`（最终构建/测试/静态门证据；各 8 suites / 40 tests，0 failures/errors/skips）
- `prior-main.json`（本轮修改前的 `main.json`）
