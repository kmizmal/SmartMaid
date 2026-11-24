# 设计文档索引

更新：2026-11-24。当前实现说明与历史设计分开维护；下一步开发只从 [下一阶段计划](../docs/superpowers/plans/2026-11-24-next-stage-development.md) 接续，验证结果以 [迁移状态](KOTLIN_MIGRATION_STATUS.md) 为准。

| 文档 | 当前用途 | 状态 |
| --- | --- | --- |
| [AI 接口](DESIGN_AI_INTERFACE.md) | 独立接口手册；启动方式以操作卡为准 | 保留独立维护，本轮未修改 |
| [原子脚本协议](DESIGN_ATOMIC_PROTOCOL.md) | 当前执行模型、源码入口与维护边界 | 现行实现导航 |
| [女仆菜单](DESIGN_MAID_MENU.md) | 主菜单、二级页、真实容器与占位页的边界 | 现行实现说明 |
| [菜单 N1 旧入口](DESIGN_MAID_MENU_N1.md) | 兼容原链接，跳转现行菜单说明及归档 | 不再重复维护设计 |
| [任务队列](DESIGN_MAID_TASK_QUEUE.md) | 已实现队列、持久化与回归入口 | 不再标为“未开工” |
| [多版本构建](DESIGN_MULTIVERSION.md) | Kotlin 版本源集、当前构建值和验收边界 | 26.2/26.3 构建通过；26.3 输入未验证 |

## 统一操作与历史

- [测试体系](TEST_SYSTEM.md) 与 [Loom 操作卡](../docs/superpowers/handoffs/2026-11-24-loom-test-runbook.md) 提供当前命令和取证步骤；设计文档不重复复制启动器/构建实验命令。
- [历史设计归档](../docs/archive/design/README.md) 保存五份旧稿中的用户决策、语言示例、视觉修订、API 调研及失败记录。归档中的状态和代码行号只对应当时现场。
- 当前菜单的历史“N1”与下一阶段的“N1 命令执行/权限回归”是不同任务；原子协议历史“P3”也不等于下一阶段的跳跃 P3。
- 不把旧验收中的“全部完成”移作 Kotlin 分支的新证据；桌宠 N/A、26.3 专用世界/输入不补做的既有决定保持不变。
