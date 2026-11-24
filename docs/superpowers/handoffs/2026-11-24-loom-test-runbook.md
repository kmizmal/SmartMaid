# SmartMaid Loom 测试操作卡

日期：2026-11-24。所有命令从仓库的 `SmartMaid` 模块目录执行，使用 JDK 25。默认验收 Minecraft 26.2，唯一 gameDir 为模块下 `run`。本卡接替旧启动脚本的当前操作说明；原交接、开发问题记录和设计文档中的 HMCL/token/一键启动示例只作历史记录。

## 1. 每轮准备与归档

同一 `run` 一次只运行一个客户端。先正常退出游戏，确认 `latest.log` 中世界保存完成及客户端停止，再开始下一轮。不清空 `run`，不覆盖个人世界。使用专用、允许命令的测试世界；A 套件从干净世界副本开始，避免已有库存或侧车队列影响断言。

```powershell
$env:SMARTMAID_MC_DIR = (Resolve-Path '.\run').Path
$env:PYTHONIOENCODING = 'utf-8'
# 若 PATH 中的 Java 不是 JDK 25，在当前终端设置 JAVA_HOME 为本机 JDK 25 目录。
.\gradlew.bat --version
```

每轮先选一个尚不存在的证据目录（下例在仓库外）；归档已有报告、日志、错误通道、配置与干净世界快照，然后才部署新输入：

```powershell
$evidence = Join-Path $env:TEMP ('SmartMaid-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $evidence -ErrorAction Stop
Copy-Item -LiteralPath '.\run\config\smartmaid' -Destination (Join-Path $evidence 'prior-config') -Recurse
if (Test-Path '.\run\logs\latest.log') {
    Copy-Item -LiteralPath '.\run\logs\latest.log' -Destination (Join-Path $evidence 'prior-game.log')
}
if (Test-Path '.\run\smartmaid\errors.jsonl') {
    Copy-Item -LiteralPath '.\run\smartmaid\errors.jsonl' -Destination (Join-Path $evidence 'prior-errors.jsonl')
}
```

确认没有上一轮待执行的 `autotest.json` / `clienttest.json`；有则先查明并归档，不直接覆盖。每套独立归档，旧报告存在不代表本轮通过。输入及运行后的 `.done.json` 应与本轮来源逐字节对应。

## 2. 基础 AutoTest

```powershell
Copy-Item -LiteralPath '.\config\smartmaid\autotest.json' -Destination '.\run\config\smartmaid\autotest.json'
Copy-Item -LiteralPath '.\config\smartmaid\autotest.json' -Destination (Join-Path $evidence 'input.json')
.\gradlew.bat --offline --no-daemon --console=plain runClient
```

在客户端进入本轮专用世界；允许使用 Loom 参数 `"--args=--username T4Tester --quickPlaySingleplayer <专用世界文件夹名>"` 自动进入已准备的世界。用相同用户名保持测试玩家身份稳定。检查 `run/logs/latest.log` 中每个断言 ID、`AutoTest 汇总: PASS=6 FAIL=0` 和 `AutoTest: 执行完成`；确认输入改名为 `autotest.done.json`。正常保存退出后，归档 `latest.log`、done 文件及控制台输出。

B/C 套件分别使用 `tools/autotest.modpack-compat.json`（21 个断言）和 `tools/autotest.queue-full.json`（30 个断言），一次只部署一套。需要恢复测试时按 [验收报告中的侧车设计](2026-11-24-loom-gameplay-acceptance-report.md) 检查重新召唤后的库存/队列，UUID 与坐标不跨重启保持。

## 3. GUI 与错误通道

确认没有待执行的 AutoTest 输入。用新的证据目录重复归档步骤，再执行：

```powershell
python tools/clienttest.py run gui-chain
.\gradlew.bat --offline --no-daemon --console=plain runClient
# 进入专用世界，等待测试完成；正常退出后读取本轮结果。
python tools/clienttest.py report
python tools/clienttest.py errors
```

期望 `minecraft=26.2`、套件 `gui-chain`、29 个用例全部 PASS、FAIL/ERROR=0。归档 `run/config/smartmaid/clienttest.report.json`、`clienttest.done.json`、输入、`latest.log` 和 `run/smartmaid/errors.jsonl` 本轮增量；核对时间窗、进程和世界，不重复使用旧结果。`clienttest.py run` 默认会删除旧报告，必须先归档。已设置真实 gameDir 时不要传 `--series`，否则会错误追加 `versions/<series>`。

`key-encoding` 需要操作人聚焦窗口并按提示按住 Shift+E 约 5 秒。本轮 T4 仅复跑基础 AutoTest 和 GUI；先前 26.2 键盘结果不代表 26.3。用户已决定不补做 26.3 专用世界及输入验证，保持未验证状态。

## 4. 可选桌宠观测

本轮游戏侧验收中桌宠为 N/A，沿用用户决定。未来需要联调时，先独立启动桌宠项目的 `main.py`（工作目录为该项目，使用其 Python 环境；后台启动使用隐藏窗口），确认本机 `127.0.0.1:21420` 监听并记录 `logs/maid_link.log` 起点。再通过上述 Loom 单机客户端进入测试世界，不用独立服务器代替玩家联调。

记录本轮至少两个遥测窗口、游戏日志“开始上报感知”和桌宠侧 WebSocket/遥测接收记录。归档 `run/deskpet/maid/*.jsonl`、游戏日志与桌宠日志本轮增量；已有遥测文件的总数不能当成本轮窗口数。需要调资源或日志时可独立使用保留的 `tools/maid_monitor.ps1`。必测但桌宠不可用时记录阻断，不写 PASS。

## 5. 构建与门禁

```powershell
.\gradlew.bat --offline --no-daemon --console=plain clean build
# 先把 build/test-results/test 的 XML 归档到本轮 26.2 证据目录，再 clean。
.\gradlew.bat --offline --no-daemon --console=plain clean build '-Pmc_series=26.3' '-Pminecraft_version=26.3' '-Ploader_version=0.19.5' '-Ploom_version=1.18.4' '-Pfabric_api_version=0.161.0+26.3'
# 将本次 XML 归档到本轮 26.3 证据目录。
python tools/verify_all.py --series 26.2
git diff --check
```

期望双版本各 6 suites / 33 tests / 0 failures/errors/skips；静态 A/B/C 通过、PAL 0 fatal/0 warning、209 个依赖成员匹配。这里 `verify_all.py --series` 是静态工具的版本选择，与客户端 gameDir 规则不同。构建成功不等于 26.3 游戏输入已经验证。当前范围与门禁结果见 [迁移状态](../../../SmartMaid/KOTLIN_MIGRATION_STATUS.md) 和 [T4 退役记录](2026-11-24-launcher-retirement-report.md)；不自动合并。
