# 原子脚本协议：当前实现入口

更新：2026-11-24；实现基线 `4516815`。本页清理旧分期与 Java 文件清单，保留当前实现导航；完整历史语言设计和示例见文末归档。完整导航见 [设计索引](DESIGN_INDEX.md)。

## 执行模型

`script` 将多步动作交给服务端任务执行器。当前 [ScriptTask.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/script/ScriptTask.kt) 处理顺序步骤、变量绑定 `as`、赋值 `assign`、条件 `if`、循环 `loop` 和提前结束 `terminate`。

| 实现 | 职责 |
| --- | --- |
| [MaidAIBridge.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/bridge/MaidAIBridge.kt) | 外部命令入口与任务派发 |
| [ScriptTask.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/script/ScriptTask.kt) | 步骤调度、子任务等待、结束与结构化结果 |
| [ScriptContext.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/script/ScriptContext.kt) / [ScriptRef.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/script/ScriptRef.kt) | 执行上下文、引用替换与条件求值 |
| [ScriptQueries.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/script/ScriptQueries.kt) | inventory、block_at、find、find_entity、find_item、distance 查询 |
| [StructureRegistry.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/structure/StructureRegistry.kt) / [StructureScan.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/structure/StructureScan.kt) | 结构识别注册与扫描 |
| [HarvestTask.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/structure/HarvestTask.kt) / [ProduceFilter.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/structure/ProduceFilter.kt) | 采集过程与目标产物过滤 |

## 维护边界

- 当前协议字段、可用动作和回执以 MaidAIBridge、ScriptTask、ScriptQueries 为准；本轮文档清理不修改协议或脚本执行行为。
- ScriptTask 有总耗时与单步耗时保护；相关常量在文件内。不能因为历史示例存在循环就假定脚本可以无限运行。
- 早期“P1/P2/P3 已完成”和桌宠测试记录属于历史验收，不等于本次 Kotlin 分支重新完成了桌宠/网页 AI 联调。
- 桌宠本阶段 N/A，不把原稿中的外部测试命令作为当前默认执行步骤。当前本机验证使用 [操作卡](../docs/superpowers/handoffs/2026-11-24-loom-test-runbook.md)，实际通过范围见 [迁移状态](KOTLIN_MIGRATION_STATUS.md)。

## 接口与历史示例

[DESIGN_AI_INTERFACE.md](DESIGN_AI_INTERFACE.md) 保留为独立接口手册；其中启动说明以当前操作卡为准。历史原子协议 v4 全文（已由 Git 历史留存）保留语言示例、设计原因与用户决策，使用示例前核对当前实现，不将其旧 Java 清单或外部环境当作现状。
