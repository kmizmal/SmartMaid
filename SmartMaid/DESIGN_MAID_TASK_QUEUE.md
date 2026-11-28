# 女仆任务队列：当前设计与实现

更新：2026-11-24；实现基线 `4516815`。队列已经实现，本页取代“没有队列 / 设计未开工”的旧现场描述。完整导航见 [设计索引](DESIGN_INDEX.md)。

## 模块职责

| 模块 | 当前职责 |
| --- | --- |
| [MaidTaskQueue.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/maidtask/MaidTaskQueue.kt) | 当前项、短期/长期队列及基本移动、暂停、恢复 |
| [QueuedTask.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/maidtask/QueuedTask.kt) | 队列项、状态、暂停原因和元数据 |
| [MaidTaskManager.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/maidtask/MaidTaskManager.kt) | 入队/去重、长期任务、组操作、tick 调度、快照及恢复 |
| [TaskMutex.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/maidtask/TaskMutex.kt) | 动作与参数层面的互斥键 |
| [MaidDataManager.kt](src/main/kotlin/com/oyxdsg/smartmaid/data/MaidDataManager.kt) | 世界内的女仆侧车数据保存与读取 |

## 当前边界

- 短期与长期是两个独立队列，当前代码容量分别为 16 和 4。调度器优先取短期项；长期项完成后回到长期队列。
- 队列项具有排队、运行、暂停及结束等状态；基本容器操作与业务调度分层。不要只根据 MaidTaskQueue 的方法推断所有抢占/恢复行为，须同时检查 MaidTaskManager。
- 组操作、互斥判重、长期切换与参数持久化已有实现，不再作为“待引入的新队列”开发。
- 保存/恢复走 MaidTaskManager 的 `saveToTag` / `loadFromTag`，由侧车恢复。女仆实体不按原 UUID/坐标跨进程存活，不能把“同一实体直接重载”作为当前恢复前提。
- 本页不重新定义既有 payload 字段或状态机；任何调度/序列化修改应先对照源代码和原设计决策，再补回归。

## 验证入口

[队列基本操作测试](src/test/java/com/oyxdsg/smartmaid/entity/ai/maidtask/MaidTaskQueueTest.java)、[管理器队列测试](src/test/java/com/oyxdsg/smartmaid/entity/ai/maidtask/MaidTaskManagerQueueTest.java) 与 [持久化测试](src/test/java/com/oyxdsg/smartmaid/entity/ai/maidtask/MaidTaskManagerPersistenceTest.java) 是现有 JVM 回归入口。

历史游戏测试中有队列与侧车恢复证据，见 [迁移状态](KOTLIN_MIGRATION_STATUS.md) 的分期记录；它们不能替代修改后的新测试。后续开发见 [战斗与移动安全阶段计划](../docs/superpowers/plans/2026-11-27-post-p3-team-plan.md)，涉及共享队列行为时重跑受影响套件。

## 历史规格

原深化设计与用户决策已由 Git 历史留存，包含当时的完整规格、修订和实现对照。旧稿“未开工 / 没有队列”和 Java 行号仅描述当时现场，不是当前缺陷结论。
