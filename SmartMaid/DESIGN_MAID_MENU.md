# 女仆菜单：当前设计与实现

更新：2026-11-24；实现基线 `4516815`。本页取代早期菜单改造规划，不表示所有界面行为均已重新验收。完整导航见 [设计索引](DESIGN_INDEX.md)。

## 当前信息架构

| 主菜单入口 | 当前实现 | 范围 |
| --- | --- | --- |
| 任务 | `MaidTaskScreen`、`MaidAddTaskScreen` | 展示任务队列与任务操作 |
| 背包 | 发送 `ACTION_OPEN_INVENTORY`，服务端打开 `MaidInventoryMenu` | 真实容器，不是本地主菜单的嵌入页 |
| 对话 | `MaidPlaceholderScreen` | 当前为占位页，不宣称完整游戏内聊天页已完成 |
| 设置 | `MaidSettingsScreen` | 服务端修改字段、保存并回推状态 |
| 动作 | `MaidPlaceholderScreen` | 当前为占位页；不等于所有动作命令不可用 |

## 实现入口与约束

- [MaidMenuScreen.kt](src/main/kotlin/com/oyxdsg/smartmaid/client/gui/MaidMenuScreen.kt)：主菜单入口分发；[ModNetworking.kt](src/main/kotlin/com/oyxdsg/smartmaid/network/ModNetworking.kt)：服务端按主人查找女仆、处理菜单动作并回推状态。
- [MaidInventoryMenu.kt](src/main/kotlin/com/oyxdsg/smartmaid/gui/MaidInventoryMenu.kt)：女仆 41 个槽位及快速移动；客户端容器副本与服务端真实库存分别构造。保留既有“不补玩家 36 格、不重做背包布局”的设计边界。
- 队列后端已经实现，见 [任务队列说明](DESIGN_MAID_TASK_QUEUE.md)，不再把“从单任务改造为队列”列为新任务。
- 当前主菜单、二级页、容器之间的导航以代码为准；历史稿中的木纹/WBS 视觉修订与旧 API 行号不作为新的实施清单。

## 验证与待办

既有 gui-chain 29 项通过只证明其覆盖的菜单链路。该套件使用 maidId=-1，未证明真实女仆容器成功打开或物品同步正确。真实容器、快速移动及库存恢复的后续测试见 [下一阶段 N2](../docs/superpowers/plans/2026-11-24-next-stage-development.md)。

当前测试和构建结果见 [迁移状态](KOTLIN_MIGRATION_STATUS.md)，执行方法见 [Loom 操作卡](../docs/superpowers/handoffs/2026-11-24-loom-test-runbook.md)。不要把历史菜单方案的“N1”与下一阶段计划的“N1 命令回归”混为一项。

## 历史决策

原始 [菜单规划](../docs/archive/design/DESIGN_MAID_MENU.md) 与 [菜单 N1 实施稿](../docs/archive/design/DESIGN_MAID_MENU_N1.md) 已归档，保留用户决策和当时修订，不再作为当前开工指令。
