# N3 合并材料复核

日期：2026-11-26（北京时间）。本报告只做候选复核，不代表合并或发布；代理不自动推送或合并。

## 候选与基线

- 分支：`feature/kotlin-migration`
- 候选代码提交：`83047c4`（`feat: 完成 N1/N2 真机验证与容器测试`），父提交 `444b6c8`；N3 报告随文档提交收尾
- 目标基线：本地 `main` 为 `c709470`
- 远端状态：`origin/feature/kotlin-migration` 仍为 `444b6c8`，本地分支领先但未推送
- 工作树：`git status --short` 为空；`git diff --check`、`git diff --cached --check`、`git diff --check main...HEAD` 均为 0
- 生产源码统计：`src/main/kotlin` 127 个共享文件，`src/mc26.2/kotlin` 2 个、`src/mc26.3/kotlin` 2 个，生产 Kotlin 合计 **131**；`src/main/java` 生产文件 **0**；Java 测试文件 **8** 个

N1/N2 代码提交只修改测试运行器、N1/N2 套件、相关生产修复和验证文档；提交清单不含 `network/`、`build.gradle`、`gradle.properties`、`fabric.mod.json` 或 `run/` 内容。N3 文档提交只更新状态、测试说明和复核报告。

## 通过（PASS）

- **N1 命令执行与权限**：`SmartMaid/tools/autotest.n1-commands.json` 在 26.2 世界 `SmartMaid-N1-20261126-F` 执行，`AutoTest 汇总: PASS=41 FAIL=0`。
- **N2 真实女仆容器**：`SmartMaid/tools/clienttest.inventory-live.json` 在 26.2 世界 `SmartMaid-N2-20261126-D` 执行，报告 `total=37 / pass=37 / fail=0 / error=0 / verdict=PASS`；真实 `MaidInventoryScreen`、`MaidInventoryMenu maidId=147`、装备同步、shift-click 数量守恒和关开重进均通过。
- **基础 AutoTest**：`config/smartmaid/autotest.json` 在 26.2 世界 `SmartMaid-N3-Base-A` 执行，`AutoTest 汇总: PASS=6 FAIL=0`。
- **GUI 轻量链路**：`tools/clienttest.gui-chain.json` 在 26.2 世界 `SmartMaid-N3-GUI-A` 执行，报告 `total=29 / pass=29 / fail=0 / error=0 / verdict=PASS`。
- **双版本构建/测试**：26.2 与 26.3 `clean build` 均 `BUILD SUCCESSFUL`，各 **8 suites / 40 tests / 0 failures / 0 errors / 0 skipped**；JUnit XML 已按版本归档。
- **静态门禁**：26.2 `verify_all.py --series 26.2` 的 A 编译、B Mixin、C 依赖成员全部通过；PAL 1.2.6 致命 0 / 警告 0。
- **Payload / Java API 边界**：6 个网络 payload 均保留 `@JvmRecord` 与 `@JvmField TYPE/STREAM_CODEC`；`ModNetworking.register()` 仍注册 4 个 C2S、2 个 S2C。本轮 HEAD 未修改 `network/` 或 `fabric.mod.json`，未新增协议字段。
- **分发元数据**：已核对 26.2 与 26.3 生成的 `smartmaid-0.1.3.jar`：`fabric.mod.json` 分别声明 `~26.2` / `~26.3`、`fabric-language-kotlin`、Java `>=25`，并分别嵌入 PAL `1.2.6` / `1.2.7`；两个 jar 都包含 `META-INF/GPL-3.0.txt`、`LICENSE`、`LICENSE-MIT-upstream.txt`、`NOTICE.md`。
- **用户文档和个人世界**：本轮 HEAD 提交不含 `DESIGN_AI_INTERFACE.md` 或个人存档；`git ls-files 'SmartMaid/run/**'` 为空，`run/` 不在版本控制中。

## 失败（FAIL）

本轮执行的门禁未发现失败项。此结论只覆盖上列命令与报告；不代表未列出的安装、专服或 26.3 游戏输入已通过。

## 未验证（UNVERIFIED）

- 26.3 专用世界、实体键盘输入和桌宠联调未补做；既有决定仍是不补做，不能据此宣称 26.3 游戏验收通过。
- 专用服务端默认权限配置未单独搭建；N1 只覆盖集成服真实玩家源和注册权限层级。
- 真实物理 Shift+E 开背包仍需人工按键，`key-encoding` 未在本轮重跑。
- 玩家整合包/外部自动化环境的实际安装与升级未验证；开发客户端结果不扩大为整合包兼容结论。
- 目标仓库的 PR、批准、分支保护和必需 CI 检查仍未确认；本地 `main` 与远端目标的最终基线需负责人确认。

## N/A

- DeskPet 桌宠联调：既有范围 N/A。
- P3 跳跃标定：独立后续任务，不作为本次迁移合并的前置条件。
- 26.3 专用世界/输入：按既有决定 N/A，不补做。

## 合并判断

技术门禁和本轮 N1/N2、基础 AutoTest、gui-chain 证据齐全，候选可供负责人评审；但 26.3 游戏输入、专用服务端权限、真实整合包验证及 PR/CI 仍未完成或未确认。是否合并、是否接受这些未验证项，由负责人决定；本次未推送、未合并。

## 证据位置

- 本轮证据根目录：`C:\Users\zmal\AppData\Local\Temp\SmartMaid-N3-20261126-1600\`
- N1/N2 证据：`C:\Users\zmal\AppData\Local\Temp\SmartMaid-N1-20261126-150236\`
- N1/N2 报告：[2026-11-26-n1-n2-validation-report.md](2026-11-26-n1-n2-validation-report.md)
- 测试体系：[TEST_SYSTEM.md](../../../SmartMaid/TEST_SYSTEM.md)
