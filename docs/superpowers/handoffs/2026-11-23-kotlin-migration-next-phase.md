# SmartMaid Kotlin 迁移下一阶段交接文档

> 历史快照：保留当时的计划、失败与取证结果；旧未完成项和命令不作为当前执行入口。当前状态见 [迁移状态](../../../SmartMaid/KOTLIN_MIGRATION_STATUS.md)，后续任务见 [下一阶段开发计划](../plans/2026-11-24-next-stage-development.md)。

> 交接日期：2026-11-23\
> 当前分支：`feature/kotlin-migration`\
> 当前提交：`60b5d82`（`修复 Kotlin 迁移合并门禁与验证工具`）\
> 当前结论：**修复后合并，不是无条件合并**

## 1. 交接目标

下一阶段的唯一主目标是补齐真实游戏环境中的 AutoTest/gameplay 行为证据，完成以下验收后再决定是否合并：

1. 服务端 AutoTest 完整执行并生成 PASS/FAIL 证据；
2. GUI、库存、持久化/恢复、队列与行为链路完成真机覆盖；
3. 客户端 `clienttest` 脚本完成运行并收集报告；
4. 桌宠遥测/WebSocket 链路按需验证；
5. 若出现失败，只修复可归因于本分支的代码问题，不覆盖用户现有改动。

## 2. 当前已完成基线

### 代码与工具

- `SmartMaid/tools/gen_jump_table.js` 已恢复为可执行 Node 脚本，输出 Kotlin `MaidJumpTable.kt`。
- `SmartMaid/tools/verify_all.py` 已与 `gradle.properties` 的 Fabric API `0.161.0+26.2` 对齐。
- `verify_all.py` 支持 `SMARTMAID_FABRIC_JAR` 和 Gradle module cache fallback，并使用 UTF-8 解码子工具输出。
- `CraftExecutor.kt` 的现有 nullability/API 修改已人工审查；26.2 中不可避免的 `Ingredient.items()` 弃用调用已局部抑制并写明原因。
- 状态记录见 `SmartMaid/KOTLIN_MIGRATION_STATUS.md`。
- 执行台账见 `.superpowers/sdd/2026-11-23-kotlin-migration-merge-fixes/progress.md`。

### 已通过的静态/构建验证

- 26.2 `clean test`：6 suites / 33 tests / 0 failures/errors/skips；
- 26.3（显式引用 Loom 1.18.4 等参数）`clean test`：通过；
- 跳表生成器：15 solutions，0 unsolvable；
- 背包模拟器：19 通过，0 失败；
- `verify_all.py --series 26.2`：A/B/C 全部通过；
- PAL 1.2.6：0 fatal / 0 warning；
- 依赖成员：209 项全部匹配；
- `git diff --check`：退出码 0。

## 3. 当前阻断与已知环境事实

本机当前默认路径：

```text
C:\Users\zmal\AppData\Roaming\.minecraft
```

目前该目录缺少：

- HMCL 最近一次启动命令日志；
- `versions/26.2` 游戏目录；
- 可供 `run_e2e_test.py` 使用的有效单机存档入口。

已实际尝试：

```powershell
cd SmartMaid
python tools/run_e2e_test.py --no-pet --skip-token-check --timeout 30
```

结果：启动前报“找不到 HMCL 启动命令”。

另一次验证：

```powershell
python tools/clienttest.py report --series 26.2
```

结果：找不到 `C:\Users\zmal\AppData\Roaming\.minecraft\versions\26.2`。

因此，当前没有真实 gameplay 失败证据，只有运行环境缺失证据；不能将该阻断误判为源码回归。

## 4. 下一阶段执行顺序

### Phase 0：准备真实游戏环境

- [ ] 安装或恢复 Minecraft 26.2 对应 HMCL 实例。
- [ ] 用 HMCL 成功启动一次目标实例，使 `tools/launch_game.py` 能读取启动命令。
- [ ] 创建或确认一个可重复进入的单机存档，例如 `SmartMaid-AutoTest`。
- [ ] 若不使用默认路径，设置：

```powershell
$env:SMARTMAID_MC_DIR = "C:\path\to\.minecraft"
```

- [ ] 确认 `versions/26.2`、`logs`、`saves/<存档名>` 均存在。
- [ ] 不要提交 `tools/local_paths.json`、token、HMCL 私有日志或游戏存档。

### Phase 1：部署服务端 AutoTest 配置

仓库已有可复用配置：

- `SmartMaid/config/smartmaid/autotest.json`：CraftExecutor 基础回归；
- `SmartMaid/tools/autotest.modpack-compat.json`：搭路白名单、tag、破坏/事件否决、整理背包；
- `SmartMaid/tools/autotest.queue-full.json`：队列、战斗打断、熔炉租约、箱子存储等完整队列覆盖；
- `SmartMaid/tools/autotest.queue.json`、`autotest.queue-ops.json`、`autotest.queue-ops2.json`：分阶段排错用；
- `SmartMaid/tools/autotest.script.example.json`：配置格式参考。

将一次只部署一个配置到：

```text
<游戏目录>/config/smartmaid/autotest.json
```

注意：`MaidAutoTest` 完成后会将配置重命名为 `autotest.done.json`，下一轮必须重新复制配置或恢复文件名。

推荐顺序：

1. `autotest.json`（CraftExecutor 基础链）；
2. `autotest.modpack-compat.json`（世界/事件/背包检查）；
3. `autotest.queue-full.json`（完整队列和行为链）。

### Phase 2：运行服务端 AutoTest + E2E 联调

如果只验证游戏侧而不启动桌宠：

```powershell
cd SmartMaid
python tools/run_e2e_test.py `
  --game-dir "<游戏目录>" `
  --no-pet `
  --quick-play "<存档名>" `
  --skip-token-check `
  --timeout 900
```

如果同时验证桌宠：

```powershell
python tools/run_e2e_test.py `
  --game-dir "<游戏目录>" `
  --pet-dir "<桌宠目录>" `
  --quick-play "<存档名>" `
  --timeout 900
```

脚本应观察到：

- `AutoTest: 执行完成`；
- 至少 2 个 `遥测窗口写入`；
- `开始上报感知`；
- 若启动桌宠，还要检查桌宠日志增量及 WebSocket/遥测接收记录。

脚本退出后保留报告、`latest.log` 和遥测 JSONL；不要把 token 或整个游戏目录加入 Git。

### Phase 3：运行客户端脚本

当前可用客户端脚本：

- `SmartMaid/tools/clienttest.gui-chain.json`：29 步 GUI 链路；
- `SmartMaid/tools/clienttest.key-encoding.json`：3 步键盘编码测试，需要人工按住 `Shift + E` 约 5 秒。

执行：

```powershell
cd SmartMaid
python tools/clienttest.py run --series 26.2 gui-chain
python tools/clienttest.py report --series 26.2
python tools/clienttest.py errors --series 26.2
```

然后人工执行键盘脚本要求的 Shift+E 操作，再重复：

```powershell
python tools/clienttest.py run --series 26.2 key-encoding
python tools/clienttest.py all --series 26.2
```

验收重点：GUI 链路不能卡死、不能出现未知 payload、编码报告不能出现错误按键码或重复状态。

### Phase 4：失败分类与修复规则

每次失败必须先分类：

1. **环境失败**：缺少版本、存档、登录、Fabric API、桌宠目录；记录路径和命令，不改业务代码；
2. **脚本/配置失败**：JSON 不合法、配置未部署、`autotest.done.json` 未恢复；先修复测试准备；
3. **代码失败**：有明确 `AutoTest [id] ... FAIL`、异常堆栈、payload 解码错误或 GUI 客户端错误；只修改能解释该证据的最小文件；
4. **既有用户改动**：特别是 `CraftExecutor.kt` 未经新证据不得回退或重写。

每个代码修复都必须重新执行至少对应的 26.2 `clean test`、静态门禁和失败配置，不能只看游戏日志。

## 5. 最终验收标准

下一阶段可以将结论从“修复后合并”升级为“可以合并”的必要条件：

- [ ] 至少一套基础 AutoTest 配置完整完成，日志明确给出执行完成和断言汇总；
- [ ] `autotest.modpack-compat.json` 的 block/inventory/bridge/tag/veto 检查全部 PASS；
- [ ] `autotest.queue-full.json` 的队列、战斗打断、熔炉、箱子存储链路有完整报告；
- [ ] `gui-chain` 客户端测试完成且无错误报告；
- [ ] `key-encoding` 完成人工 Shift+E 步骤并通过；
- [ ] 必要时桌宠联调满足至少 2 个遥测窗口和感知上报证据；
- [ ] 26.2/26.3 构建与 33 个 Java↔Kotlin 测试仍通过；
- [ ] `git diff --check` 通过；
- [ ] 工作树中没有误加入 token、存档、HMCL 日志或用户已有 `docs/` 内容；
- [ ] 最终提交说明包含 `协作模型: <真实模型名>` trailer。

## 6. 交接时应优先查看的文件

1. `SmartMaid/KOTLIN_MIGRATION_STATUS.md`
2. `.superpowers/sdd/2026-11-23-kotlin-migration-merge-fixes/progress.md`
3. `SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/bridge/MaidAutoTest.kt`
4. `SmartMaid/tools/run_e2e_test.py`
5. `SmartMaid/tools/clienttest.py`
6. `SmartMaid/tools/autotest.modpack-compat.json`
7. `SmartMaid/tools/autotest.queue-full.json`
8. `SmartMaid/tools/clienttest.gui-chain.json`
9. `SmartMaid/tools/clienttest.key-encoding.json`
10. `SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/craft/CraftExecutor.kt`

## 7. 当前 Git 注意事项

- 已提交：`60b5d82`。
- 用户已有未跟踪 `docs/` 内容必须保留，不要批量 `git add docs/`。
- 后续新增交接报告、运行日志摘要或修复代码前，先执行 `git status --short`，确认不覆盖用户改动。
- 不要提交 Minecraft 游戏目录、HMCL 日志、access token、桌宠运行日志或 `local_paths.json`。
