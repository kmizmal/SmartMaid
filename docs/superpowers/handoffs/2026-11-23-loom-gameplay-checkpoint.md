# 26.2 Loom 玩法验收交接 · 2026-11-23 晚

**停在 T1 通过、T2 未开跑。** 操作人决定玩法测试改日继续；本轮只验游戏侧，桌宠 WebSocket / 遥测不适用。没有启动下一轮、删除旧入口或合并。不要把本记录当成玩法通过报告。

| 项目 | 已核实的结果 |
| --- | --- |
| 分支基线 | `feature/kotlin-migration`，HEAD `60b5d82`；开工前已有设计文档改动和未跟踪的 `docs/`，未处理它们 |
| 标准入口 | 从模块目录运行 `gradlew.bat --offline --no-daemon --console=plain runClient`；JDK 25.0.5.1、Gradle 9.9.1、Loom 1.17.21。一次性授权 Wrapper 使用用户 Gradle 缓存后启动，无需 HMCL 或 token |
| 真实加载 | Minecraft 26.2、Loader **0.19.5**、Fabric API 0.161.0+26.2、SmartMaid 0.1.3。Loader 是运行值，不是属性文件的默认值 |
| 进世界与退出 | 22:59:53 玩家进入专用世界 `SmartMaid-AutoTest`；23:00:14 全维度保存；23:00:16 客户端停止，Gradle 退出 0。未启动 AutoTest，不能据此判断合成、队列、恢复或 GUI |
| 证据 | 本机忽略目录 `.superpowers/sdd/2026-11-23-fabric-loom-gameplay-validation/M1-world/` 中有 `entry.log`（2、81、194、243–244 行）和退出后的世界快照。快照 `level.dat` 与原存档 SHA-256 均为 `bafbbfbd8d52298c924cb88f65df3d4783538abc209ce6ded400c1cca695db07`；未提交原始日志或世界 |

## 下次从这里继续

1. 游戏关闭时先检查 `SmartMaid/run/saves/SmartMaid-AutoTest-A`。截至本次收工它不存在：在游戏创建的 `saves` 下新建副本曾收到 `Permission denied`。**不要覆盖原世界，不要重复覆盖后来手工建好的副本。** 只需从已保存的干净基线复制出 A 轮专用存档。
2. `SmartMaid/run/config/smartmaid/autotest.json` 在失败后未生成。确认副本可用后再部署基础合成输入，只进 A 轮副本，核对 `c1`–`c6` 全部结果及本轮完成行，归档后再换套件。
3. 再按[执行计划](../plans/2026-11-23-fabric-loom-gameplay-validation.md#L56-L102)跑兼容规则、队列、跨重启恢复、GUI 和实体按键。GUI 工具指定真实 `SMARTMAID_MC_DIR` 时不传 `--series`。达到退役条件后才删除旧启动入口，再做双版本终验。

**合并建议：暂缓。** 当前缺玩法证据，不是已发现 SmartMaid 代码回归。无须重新建基线世界，也不需要转回 HMCL。
