# 多版本构建：当前设计与验证边界

更新：2026-11-24。旧调研已归档，本页描述当前 Kotlin / Loom 构建；完整导航见 [设计索引](DESIGN_INDEX.md)。

## 源码与构建隔离

- 共享代码位于 `src/main/kotlin`，版本适配位于 `src/mc26.2/kotlin` 和 `src/mc26.3/kotlin`，由 `mc_series` 选择其中一条版本源集。
- [build.gradle](build.gradle) 按目标版本选择 PAL、表达式引擎及模组依赖范围；每个目标单独构建 jar。不同版本不得混入同一存档或复用未经核对的运行目录。
- 当前生产 Java=0；旧 `compile_check.py --package` 的纯 javac 流程不是 Kotlin 发布入口。构建统一使用 Wrapper / Fabric Loom。
- 26.1 是历史调研方向，当前没有把它计作已实现/已验证版本。

| 配置 | 26.2 默认线 | 26.3 参数线 |
| --- | --- | --- |
| Minecraft / mc_series | 26.2 / 26.2 | 26.3 / 26.3 |
| Loader（构建配置） | 0.19.3 | 0.19.5 |
| Loom | 1.17.21 | 1.18.4 |
| Fabric API | 0.161.0+26.2 | 0.161.0+26.3 |
| PAL | 1.2.6 | 1.2.7 |
| 共用工具链 | Gradle 9.9.1、JDK 25、Kotlin 2.4.21、Fabric Language Kotlin 1.14.2+kotlin.2.4.21 | 与 26.2 相同 |

以上是仓库构建值，不把它们全部解释成运行时最低版本；元数据声明见 [fabric.mod.json](src/main/resources/fabric.mod.json)。

## 执行与取证

完整双版本命令、带引号的五个 26.3 参数和 XML 归档顺序见 [Loom 操作卡](../docs/superpowers/handoffs/2026-11-24-loom-test-runbook.md)。不要在两次 clean 之间丢失上一版本的 XML 或同名 jar。

当前双版本各 7 suites / 37 tests 全通过，26.2 静态 A/B/C 全通过；结果和证据基线见 [迁移状态](KOTLIN_MIGRATION_STATUS.md)。构建通过不等于两个版本具有同等游戏验收覆盖。

26.2 是当前游戏回归线；用户已决定不补做 26.3 专用世界/实体输入验证，桌宠 N/A。当前不把 26.3 输入或玩家整合包安装兼容性写成 PASS。

## 后续与历史

26.2 命令执行/权限与真实容器回归已完成，证据见 [N1/N2 验证报告](../docs/superpowers/handoffs/2026-11-26-n1-n2-validation-report.md)；后续战斗与移动安全见 [阶段计划](../docs/superpowers/plans/2026-11-27-post-p3-team-plan.md)。

旧调研与适配实验全文已由 Git 历史留存，包含当时 API 差异、失败和决策；其中“待评审 / 未开工 / Gradle 未实跑”、旧版本表和纯 javac 命令均不作为当前行动指令。
