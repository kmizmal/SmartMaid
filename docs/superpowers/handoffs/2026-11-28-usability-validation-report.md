# SmartMaid 可用性验收 — 2026-11-28

**结论：部分完成。** 26.2 的合成旧布局迁移、装备／库存恢复、最新快照选取及复制失败后的只读回退已有本轮单机证据。最后一次重启的附加队列断言失败；非 OP 真实菜单 17 项均缺少运行前置。此轮不据此扩大到普通玩家或公服验收，也不把 26.3 构建当作可玩性证据。

## 1. 基线和隔离

- 本轮 `20261128-1101-cst`（Asia/Shanghai），Windows / JDK 25.0.5.1 / Gradle Wrapper 9.9.1，`feature/jump-calibration` 起始 SHA `d82a305b036d4ea57db3aee49e72d7dd4ff34eb7`。生产与 JUnit 基线 `ae0d558`：之后仅 README、分析记录及跨维度 AutoTest 输入有差异。未修改产品代码、协议、已有测试或两份未跟踪计划。
- 26.2：Loom 1.17.21、Fabric API 0.161.0+26.2；Gradle 声明 Loader 0.19.3，但 Fabric Language Kotlin 的依赖使 `runtimeClasspath` 解析为 0.19.5，实机日志也显示 0.19.5。本轮不验证实际运行在 Loader 0.19.3 的实例。
- 基线离线 `test --rerun`：**PASS**，退出 0，新跑 15 套 / 61 项，失败 / 错误 / 跳过为 0 / 0 / 0。见本机 [清单](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/manifest.md) 与 [基线摘要](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/01-baseline/result.json)。本机证据链接指向 Git 忽略的目录，不随仓库分发。
- 只用隔离实例和虚构单机主人；世界模板仅两份世界元数据，未复制区块、玩家数据或原实例全局配置，未接触个人存档。桌宠连接关闭，未开联机。用于验收及诊断的各次客户端均正常保存并退出 0；每轮实际运行版本、时间和输入／新 `.done.json` 的一致 SHA-256 见各自摘要。Gradle 的 `--offline` 不表示游戏客户端不发起任何外部请求；开发客户端有账户属性／证书 HTTP 401 日志，未影响上述单机测量。

## 2. 26.2 合成旧布局：恢复通过，队列持续有边界

只将本轮正常停机后的种子压缩 NBT 放为新世界 `smartmaid/maids/<owner>.dat`，初次迁移前没有 `.latest`、现代文件或全局旧档。**这是合成路径布局，不是旧发布版本实际输出。**

| 步骤（CST） | 结果 | 预期与本轮实测 |
|---|---|---|
| 种子；11:13:41 | PASS | 真实单机正常召唤、装备并保存；金头盔 ×1 在头部，背包第 10 格金锭 ×7。当前长期 `restart_guard`（护卫 半径6），后续 `restart_followup`（收集），短期空；存盘顺序一致。AutoTest 9/0，[摘要](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/02-migration/01-seed-verified/result.json)。 |
| 新世界首次恢复；11:15:09 | PASS | 装备、第 10 格 ×7、队列名字与顺序符合种子。新快照 367 B，`.latest` 精确匹配其 UUID，旧档 SHA 不变。AutoTest 4/0，[摘要](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/02-migration/02-first-summon/result.json)。 |
| 同世界重启、7→9；11:16:01 | PASS | 重召后仍有头盔、第 10 格 ×7 和同序队列；加 2 后第 10 格 ×9，保存后新指针指向有 9 的快照，旧档 SHA 不变。AutoTest 5/0，[摘要](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/02-migration/03-update/result.json)。 |
| 同世界再重启，最新装备／库存和磁盘；11:16:46 | PASS | 头盔真实穿戴，第 10 格恢复的是 **9 而非旧档的 7**。[退出后快照](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/02-migration/04-latest/post-exit/disk.json) 177 B，指针字节精确匹配新文件 UUID；此前选中快照的哈希及旧档 SHA 都未变。 |
| 同次额外断言：继续运行护卫，保留等待项 | FAIL | 26.2 最后一次重启，预期 `restart_guard` 为当前且长期有 1 项等待；实测反序列化计数为 2，但查询没有当前项、两队列空，保存后也为空。保留 [原始 AutoTest 3 PASS / 1 FAIL](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/02-migration/04-latest/client.log#L179-L195)，不以其他字段通过替代。 |
| 独立重放更新快照以定位队列；11:25:10 | PASS | 仅重建同一更新快照及其指针和旧档，启用测试实例调试日志；复现队列为空，读取的实体 UUID 与**本次** `.latest` 一致，仍恢复 9。诊断的 4/0 验证的是原因和磁盘选取，不是原队列断言转绿。见 [摘要](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/02-migration/05-restart-cap-diagnostic/result.json)。 |
| 离线 `MaidDataFilesTest` | PASS | 新跑 5/5，退出 0；覆盖快照选取、复制、源保留，以及选中快照缺失时不回退陈旧旧档。见 [定向摘要](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/02-data-files-offline/result.json)。 |
| 复制抛出 `IOException` 后，真实加载原档 | PASS（补记） | 12:06:55–12:07:53 在独立 `games/copy-fallback-proof` 单机运行。仅在 `MaidDataManager.load` 的目标复制路径临时占据目标文件名，`prepareSummon` 的真实文件移动产生 `AccessDeniedException`，生产代码走 `catch (IOException)` 只读回退到世界内旧档；装备／库存／队列 4/0，正常保存退出，见 [摘要](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/02-migration/06-copy-fallback/result.json) 与 §6。 |

- 旧档进出 SHA-256 均为 `a7e8c98415ad2af7dbf8892a49322348e915c9ad0c871d7c92c6ac0396237375`。初次启动夹具不计入：校验器误认 Loader（`BLOCKED`），运行另有 3 条夹具断言 `FAIL`（`difficulty peaceful` 的成功返回值预期错误，两个槽位输入形状错误）。使用全新种子重跑，保留 [首次结果](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/02-migration/01-seed/result.json)。
- **护卫重跑上限：** 盘上 `attempts` 在种子、首次恢复、更新后依次为 1、2、3。[调度器](../../../SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/maidtask/MaidTaskManager.kt#L456-L476) 重新启动非 `Resumable` 项先加 1，超过 3 则跳过；这次第 4 次启动产生 `重跑超限，跳过: guard`。存档成功读出任务，不等于任务能一直运行。
- **长期收集的既有缺陷：** 同一调试重放里，`collect` 因没有可收集物在 1 tick 完成，随后日志为 `运行时任务缺失，跳过: collect`。[队列](../../../SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/maidtask/MaidTaskQueue.kt#L14-L16) 把成功长期项回队首，而 [管理器](../../../SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/maidtask/MaidTaskManager.kt#L402-L415) 清理其运行对象。`5ecafb2` 的父版本也有无条件移除，不是本轮存档变更引入。单列后续任务，不改队列策略来让测试转绿。

## 3. 26.2 普通玩家真实菜单

**BLOCKED**：本轮没有已确认的私有测试连接和实际第二客户端角色（非房主、非 OP，拥有自己的女仆）。未开端口，未用房主、控制台、合成菜单或伪造消息替代。各行实际玩家缺失，服务端前／后状态均未采集。以下是所需证据，不是观察到的行为；详见 [前置与矩阵](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/03-menu/matrix.md)。

| 菜单操作 | 应观察到的服务端状态 | 结果 |
|---|---|---|
| `mine` | 挖矿入队或运行 | BLOCKED |
| `guard` | 护卫入队或运行 | BLOCKED |
| `collect` | 收集入队或运行 | BLOCKED |
| `eat` | 进食入队或运行 | BLOCKED |
| `attack` | 攻击入队或运行 | BLOCKED |
| `move` | 移动入队或运行 | BLOCKED |
| `farm` | 耕作入队或运行 | BLOCKED |
| `cheststore` | 存箱入队或运行 | BLOCKED |
| `query` | 返回权威状态、未改队列 | BLOCKED |
| `cancelCurrent` | 原当前项取消、其他项保留 | BLOCKED |
| 短期 `clear` | 只清短期排队项 | BLOCKED |
| 长期 `clear` | 只清长期排队项 | BLOCKED |
| `stop` | 指定长期项停止、其他项保留 | BLOCKED |
| `promote` | 指定长期项变为当前项 | BLOCKED |
| `moveUp` | 指定第二项上移一位 | BLOCKED |
| `remove` | 仅移除指定短期项 | BLOCKED |
| `stopGroup` | 仅移除匹配组，其他项保留 | BLOCKED |

离线 `MaidMenuPolicyTest`：**PASS**，3/3、退出 0；见 [定向摘要](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/03-menu-policy-offline/result.json)。只验允许列表、分类与默认拒绝，不证明普通玩家正向 UI 可用。

## 4. 未启用的可选验收

| 场景 | 结果 |
|---|---|
| 26.3 实机 GUI 链（29 项） | NOT RUN |
| 26.3 真实 Shift+E 输入 | NOT RUN |
| 桌宠正常认证、遥测及只读查询 | NOT RUN |
| 私有 26.2 专用服 30 分钟 | NOT RUN |
| 真实旧版产出的存档 | NOT RUN |

## 5. 双版本离线构建与交付

全部测试客户端正常保存退出后，按计划串行执行 `--offline --no-daemon --console=plain clean build`，先完成 26.2 归档再执行 26.3。每版都重新跑 `:test`；二进制、源码 jar、SHA-256、JUnit XML 和 Gradle 日志各自保留。

| 版本 / 时间（CST） | 结果 | 退出码；套 / 项；失败 / 错误 / 跳过 | 归档二进制 SHA-256 |
|---|---|---|---|
| 26.2；11:30:14–11:31:24 | PASS | 0；15 / 61；0 / 0 / 0 | `d6df7b9fadf247019acfaa208462d2239399ce12cc02f74abb6f5ec1149bbe8b` · [摘要](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/05-build-26.2/result.json) |
| 26.3；11:31:25–11:32:33 | PASS | 0；15 / 61；0 / 0 / 0 | `6bd7788f84128c6101501e1cbd51ffd937d62c7e764ce6a5fd6ec1f61d9d4cea` · [摘要](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/05-build-26.3/result.json) |

26.3 属性为 `-Pmc_series=26.3 -Pminecraft_version=26.3 -Ploader_version=0.19.5 -Ploom_version=1.18.4 -Pfabric_api_version=0.161.0+26.3`。归档 jar 的 `fabric.mod.json` 目标范围分别为 `~26.2`、`~26.3`；26.3 的 Loader 是构建声明值，未启动 26.3 客户端。

后续：提供获准私有环境和实际第二角色后，逐行补齐正向菜单；复制失败分支已在本轮补证（§6）；长期任务完成后再调度的问题另开最小回归，不在本报告内声称已修复。此轮只留本地报告提交，不合并、不推送、不发布。

---

## 6. 交付后补记：复制失败的加载回退（2026-11-28 12:06–12:07 CST）

- **范围与版本：** `HEAD 2d15a8efc6bf8344589a52e8ca3a31987707f96c` 仅为本报告提交，生产与 JUnit 代码仍对齐基线 `ae0d558`；独立 26.2 单机 `games/copy-fallback-proof`，实际日志 `Loading Minecraft 26.2 with Fabric Loader 0.19.5`，退出 0，AutoTest `PASS=4 FAIL=0`。
- **故障方式：** 仅对本测试子 JVM 使用本机 Windows 共享内存 JDWP（`dt_shmem`）附加；在 `MaidDataFiles.prepareSummon` 的断点处、真实正常召唤的 `MaidDataManager.load` 调用内，把本次新女仆的目标 `<owner>-<maidUUID>.dat` 临时建成非空目录，使 `replaceFile` 的 `Files.move(temp, target, …)` 目标冲突。`load` 返回前删除该夹具；未改 ACL、权限或产品代码。
- **实测：** [client.log:189-190](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/02-migration/06-copy-fallback/client.log#L189-L190) 为 `女仆存档复制失败，仅读取原存档 .\saves\SmartMaid-Validation\.\smartmaid\maids\<owner>.dat` 及 `java.nio.file.AccessDeniedException`。随后恢复正常调用链，队列仍为 `restart_guard`（当前、RUNNING）和 `restart_followup`（等待），头盔／第 10 格金锭 ×7 读回；存档新增 367 B 快照 `e404346c-2339-38cb-bc07-2906e3348681-9060e56d-1f53-4995-9676-2f367339c1dd.dat`，旧档 SHA-256 仍为 `a7e8c98415ad2af7dbf8892a49322348e915c9ad0c871d7c92c6ac0396237375`。
- **夹具自身核验：** [probe.log](../../../.superpowers/sdd/2026-11-28-usability-validation/20261128-1101-cst/02-migration/06-copy-fallback/probe.log) 记录附加、目标占据、`load` 在目标仍被占据时返回、随即移除夹具四步；两个因验收脚本自身的 `.done` mtime 比较误差而拒绝的先前运行保存为 `06-copy-fallback-attempt1-harness-timing` 与 `06-copy-fallback-attempt2-harness-mtime`，它们也显示真实故障和 4/0，但不计入 PASS；最终干净的第三次运行才写入 `result.json` 的 `status: PASS`。
- **边界：** 这证明 `MaidDataManager.load` 在真实 `prepareSummon` 复制失败时会读取原档；不等于长期保存写故障时仍可召唤成功（`registerSummoned` 仍需要选中现代快照），也不替代真实旧发布版本输出或非 OP 菜单。

## 7. 队列缺陷的最小修复边界（仅分析，未改代码）

- **已确认的因果链：** `MaidTaskQueue.finishCurrent` 对成功完成的长期项把它改回 `QUEUED` 并插回长期队首；`MaidTaskManager.tick` 随后无条件 `forgetRuntime(cur)`；下一个 `startNextLoop` 在 `runtimeTasks[next.id()]` 为空时直接 `finishCurrent(SKIPPED, "运行时任务缺失")`。26.2 的 `collect` 空场 1 tick 完成，正好触发该路径。父提交 `5ecafb2` 已有无条件移除，不是本次迁移引入。
- **最小回归边界：** 不改变长期队列“成功完成回队首”的现有产品语义或 `MAX_ATTEMPTS=3` 重跑上限。候选修复是在管理器保留可重建任务的 `TaskFactory`，在 `startNextLoop` 发现已回队的运行时对象缺失时按原 `cmd/params` 重建，并保留 `startedIds`／`runAttempts` 语义；或由产品负责人明确决定“一次性 collect 不应作为长期任务入队”，但那会改变用户可见行为，不能作为测试专用补丁。
- **必须先 RED 的最小测试：** 一个长期 `FakeTask` 完成一次后，队列仍为同一项且 `runtimeTasks[next.id()]` 非空；再启动后能执行而非被 “运行时任务缺失” 跳过；同时第 4 次非 `Resumable` 启动仍按上限 `SKIPPED`。未编写或提交该修复，留作下一独立任务。
