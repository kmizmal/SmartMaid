# N1 / N2 本机验证报告

日期：2026-11-26（北京时间）。分支：`feature/kotlin-migration`；验证时 HEAD 为 `444b6c8`，另含本报告对应的工作树改动。**未推送、未合并、未发布。**

## 1. 本轮改动

- `SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/bridge/MaidAutoTest.kt`：增加真实玩家命令源 `source:"player"`、结果/异常/任务断言、`wait` 条目、槽位/容器/朝向/任务检查；`look` 命令增加立即朝向断言。
- `SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/client/test/MaidClientTest.kt`：增加 `sendCommand`、`waitForMaid`、`waitForScreen`、`assertNearestMaid`、`assertMenuMaid`、`assertSlot`、`assertMenuTotal`、`clickSlot`、`assertMaidEquipment` 等真实客户端链路操作。
- `SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/command/MaidTaskCommand.kt`：`store` 修复主手槽回填造成的物品复制；`look` 设置头部朝向与 `lookControl` 同步。
- `SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidActions.kt`：`storeToBackpack` 增加起始槽位参数，避免把主手槽 0 当作收纳目标。
- `SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/bridge/MaidAIBridge.kt`：同步修复 `store` 的库存回写。
- `SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/slot/Slots.kt`：拒绝 0–40 之外的 `MaidSlot`。
- `SmartMaid/src/test/java/com/oyxdsg/smartmaid/command/MaidTaskCommandTest.java`：保留并修复跨 26.2/26.3 的 Brigadier 权限/回调测试。
- `SmartMaid/src/test/java/com/oyxdsg/smartmaid/entity/ai/slot/SlotsTest.java`：新增槽位边界测试。
- `SmartMaid/tools/autotest.n1-commands.json`：新增 N1 服务端 AutoTest 套件（56 条记录；汇总 41 个断言通过）。
- `SmartMaid/tools/clienttest.inventory-live.json`：新增 N2 真实容器客户端套件（37 项）。

## 2. N1 结果

- 世界：`SmartMaid/run/saves/SmartMaid-N1-20261126-F`（从 `SmartMaid-T4-20261124-A` 复制，清空 `smartmaid/maids`）。
- 启动：`runClient --username N1Tester --quickPlaySingleplayer SmartMaid-N1-20261126-F`。
- 结果：`AutoTest 汇总: PASS=41 FAIL=0 （全部通过）`。
- 覆盖：真实玩家命令源下的 transfer/chestopen/chestput/chesttake、equip/store、look/break/place/use、attack/mine/farm 及拒绝路径；权限测试使用玩家源与注册权限层级。
- 证据：`C:\Users\zmal\AppData\Local\Temp\SmartMaid-N1-20261126-150236\runClient-N1-F-final-PASS.log`。

## 3. N2 结果

- 世界：`SmartMaid/run/saves/SmartMaid-N2-20261126-D`（最终代码独立副本，`clienttest.inventory-live.json`）。
- 结果：`clienttest.report.json` 为 `total=37 / pass=37 / fail=0 / error=0 / verdict=PASS`；`MaidInventoryMenu maidId=147`，真实 `MaidInventoryScreen` 打开，装备槽与 shift-click 数量守恒、关开重进均通过。
- 证据：`C:\Users\zmal\AppData\Local\Temp\SmartMaid-N1-20261126-150236\clienttest.report.N2-D-PASS.json` 和 `runClient-N2-D-final-PASS.log`。
- 限制：不宣称实体 Shift+E 键盘路径已自动化；gui-chain 仍是轻量菜单链路，不能替代真实容器套件。

## 4. 构建与静态门禁

- 26.2 `clean build`：`BUILD SUCCESSFUL`，**8 suites / 40 tests / 0 failures / 0 errors / 0 skipped**。
- 26.3 `clean build`：`BUILD SUCCESSFUL`，**8 suites / 40 tests / 0 failures / 0 errors / 0 skipped**。
- 26.2 `verify_all.py --series 26.2`：A/B/C 全部通过，PAL 1.2.6 致命 0 / 警告 0。
- 工作树、暂存区、`main...HEAD` 的 `git diff --check` 均为 0。
- 构建日志：`C:\Users\zmal\AppData\Local\Temp\SmartMaid-N1-20261126-150236\clean-build-26.2-final.log`、`clean-build-26.3-final.log`、`verify-all-26.2-final.log`。

## 5. 未完成 / 未验证

- N3 合并材料复核、最终候选 SHA、PR/批准和必需检查未处理。
- 26.3 专用世界和实体键盘输入按既有决定未补做；DeskPet 仍为 N/A。
- 专用服务端的默认权限配置未单独搭建；N1 只覆盖集成服真实玩家源与注册权限层级。
- 真实女仆容器套件未覆盖实体 Shift+E 键盘路径；只验证菜单入口与合成容器点击。
- 未提交、未推送、未合并。
