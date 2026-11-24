# Kotlin Migration Merge Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `feature/kotlin-migration` 相对 `main` 已确认的工具链、文档和验证阻断问题，并以可复现的构建、测试和运行验证为合并门禁。

**Architecture:** 将工作拆成三条可控路径：Node 生成器修复、仓库文档/换行清理、Gradle 验证环境恢复。生成器修复与环境恢复可并行；最终状态文档必须在验证结果确定后更新，完整回归和合并前审查统一串行执行。

**Tech Stack:** Kotlin 2.4.21、Fabric Language Kotlin 1.14.2+kotlin.2.4.21、Gradle Wrapper 9.9.1、JDK 25、Node.js、Python、JUnit、Git。

**Spec:** 本计划依据本次 `feature/kotlin-migration` 对 `main` 的审查结果；审查基线为当前 HEAD `0c84442`。

## Global Constraints

- 不改变迁移后的生产行为、网络 payload 字段顺序、序列化格式或 Java↔Kotlin 公共 API。
- 生产源码保持 `java=0, kotlin=131`；6 个 Java 测试继续保留作为互操作回归门。
- 构建必须使用 JDK 25；目标 wrapper 为 Gradle 9.9.1。
- 26.2 与 26.3 的版本特定源码只能通过各自 `src/mc26.2/kotlin` / `src/mc26.3/kotlin` 源集验证，不得混入另一版本实现。
- 不把当前环境的 Gradle 缓存/权限故障误判为源码通过；所有验证必须记录实际命令和退出码。
- 所有修复先在独立分支或隔离 worktree 中完成；回滚以单任务提交为单位，不允许用整分支 reset 覆盖其他协作者改动。

## Review Focus

- 生成器必须在干净工作目录实际写出 `MaidJumpTable.java`，不能只通过 `node --check`。
- 重新生成前后不能产生未预期的源码差异；生成结果必须与现有提交可解释地一致。
- Gradle wrapper 锁文件、JDK、离线依赖和并发 daemon 状态必须可诊断，不能只重试同一条失败命令。
- 文档中的迁移数量、Gradle 版本和验证结论必须与 Git tree 和实际日志一致。
- 换行规范化不得改变文件内容或把用户/其他协作者的非目标改动纳入提交。

---

## 问题与优先级总览

| 优先级 | 问题 | 严重程度 | 任务 | 依赖 |
|---|---|---|---|---|
| P0 | `gen_jump_table.js` 的 `require`/`__dirname` 被占位函数破坏，且仍生成已删除的 Java 路径 | 高 | Task 1 | 无 |
| P0 | 当前机器无法进入 Gradle 编译，wrapper 报 `gradle-9.9.1-bin.zip.lck` `FileNotFoundException` | 阻断验证 | Task 2 | 无 |
| P1 | `KOTLIN_MIGRATION_STATUS.md` 与当前 tree、wrapper 和验证状态不一致 | 中 | Task 4 | Task 2、Task 3 |
| P1 | 新增文件的 CRLF 导致 `git diff --check` 大量 trailing-whitespace | 低 | Task 3 | Task 1 对生成器文件需先完成 |
| P0 | 修复后必须统一运行 build、JUnit、脚本 smoke、启动/回归检查并完成风险复核 | 阻断合并 | Task 5 | Task 1-4 |

---

## Task 1: 修复跳表生成器并建立可重复 smoke test

**负责人:** 修复者 A（Node/工具链）\
**预计耗时:** 30–60 分钟\
**并行:** 可与 Task 2 并行；Task 3 对 `gen_jump_table.js` 的换行处理需等待本任务完成。

**Files:**
- Modify: `SmartMaid/tools/gen_jump_table.js:116-133`
- Modify: `SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidJumpTable.kt:1-10` only if generator output reveals a real table mismatch
- Test: `SmartMaid/tools/gen_jump_table.js`（命令行 smoke）
- Reference: `SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidJumpTable.kt`

**Inputs:** 当前坏脚本、现有 Kotlin 跳表、项目文档中规定的生成命令。\
**Outputs:** 可执行且面向 Kotlin 输出的生成器、成功生成的 Kotlin 表、命令输出日志和生成前后 diff 结论。\
**协作要求:** 不生成或重新引入 `src/main/java` 生产源码；生成器必须输出 `src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidJumpTable.kt` 的 Kotlin `object`/`@JvmRecord` 结构，保持 `get(dist: Int, dy: Int): JumpSolution?` 和表项语义不变；不得覆盖其他任务的文件；提交前报告生成前后 SHA/diff。

- [ ] **Step 1: 写失败复现记录**

运行：

```text
cd SmartMaid
node tools/gen_jump_table.js
```

Expected：失败并包含 `TypeError: Cannot read properties of undefined (reading 'join')`。

- [ ] **Step 2: 修复 Node 入口依赖和 Kotlin 输出模板**

在 `SmartMaid/tools/gen_jump_table.js` 中：

- 删除本地定义的 `function require()`；
- 删除本地定义的 `function __dirname()`；
- 恢复 `const fs = require('fs')`；
- 恢复 `const path = require('path')`；
- 将输出路径从已删除的 `src/main/java/.../MaidJumpTable.java` 改为 `src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidJumpTable.kt`；
- 将生成模板改为当前 Kotlin `object MaidJumpTable`、`@JvmRecord data class JumpSolution`、`@JvmStatic fun get(...)` 结构；
- 保持 dist/dy 表索引、跳跃解和边界行为不变。

- [ ] **Step 3: 执行生成 smoke test**

运行：

```text
cd SmartMaid
node tools/gen_jump_table.js
```

Expected：退出码 0，输出 `[gen] written ...`，不出现异常；目标 Kotlin 文件存在、可读且不再创建 `src/main/java` 文件。

- [ ] **Step 4: 审核生成结果**

运行：

```text
git diff -- SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidJumpTable.kt
git status --short -- SmartMaid/src/main/java SmartMaid/src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidJumpTable.kt
```

Expected：生成文件只包含预期的 Kotlin 模板/表项变化；`src/main/java` 不新增文件；任何表项变化必须逐项说明原因，否则停止任务并回滚生成产物。

- [ ] **Step 5: 提交独立变更**

提交信息建议：

```text
fix: restore jump table generator runtime dependencies
```

提交前确认只包含 `gen_jump_table.js` 及经审核的生成产物。

**回滚:** 若生成结果出现未解释的表项变化，保留脚本修复但回退生成产物；若脚本修复本身失败，使用本任务提交的反向提交恢复旧文件，不回滚其他任务。

---

## Task 2: 恢复可复现的 Gradle/JDK 验证环境

**负责人:** 验证者 B（Windows/Gradle）\
**预计耗时:** 30–90 分钟，取决于 wrapper 分发缓存\
**并行:** 可与 Task 1、Task 3（非 `gen_jump_table.js` 文件）并行。

**Files:**
- Do not modify project source by default.
- Inspect: `SmartMaid/gradle/wrapper/gradle-wrapper.properties`
- Inspect: `SmartMaid/gradlew.bat`, `SmartMaid/gradle.properties`
- Optional local-only cleanup: `C:/Users/zmal/.gradle/wrapper/dists/...`（不得提交）

**Inputs:** 当前错误 `FileNotFoundException ... gradle-9.9.1-bin.zip.lck`、JDK 25 安装路径、Gradle wrapper 配置。\
**Outputs:** 可复现的构建命令、完整日志、退出码；或者明确记录仍阻断的环境原因和未修改的项目状态。\
**协作要求:** 不修改 `build.gradle`、版本号或源码来规避环境错误；如需要删除锁文件，先确认没有活跃 Gradle/Java 进程。

- [ ] **Step 1: 诊断 JDK 与进程状态**

确认：

```text
java -version
javac -version
where java
where javac
```

Expected：实际使用 JDK 25；记录 `JAVA_HOME` 和 Java 绝对路径。确认没有占用目标 wrapper 分发目录的 Gradle 进程。

- [ ] **Step 2: 处理 wrapper 分发缓存锁**

在确认无活跃 Gradle 进程后，检查并清理损坏的 `gradle-9.9.1-bin` 分发临时目录/锁文件，或指定一个新的、可写的 `GRADLE_USER_HOME`。不得删除项目源码或用户的其他 Gradle 缓存。

- [ ] **Step 3: 验证最小 Gradle 入口**

运行：

```text
cd SmartMaid
gradlew.bat --offline --no-daemon --console=plain help
```

Expected：退出码 0，并能解析 Gradle 9.9.1、Kotlin 2.4.21、Loom 1.17.21 和本地依赖。

- [ ] **Step 4: 运行完整构建**

运行：

```text
gradlew.bat --offline --no-daemon --console=plain clean build
```

Expected：`BUILD SUCCESSFUL`；保存完整日志和 JUnit 汇总。

- [ ] **Step 5: 验证 26.3 参数路径**

使用项目既定的 26.3 参数运行独立 build，至少确认 `mc_series=26.3`、Minecraft 26.3、Loader 0.19.5、Loom 1.18.4、Fabric API 0.161.0+26.3 的组合可解析并完成测试。

Expected：26.3 也得到 `BUILD SUCCESSFUL`；若依赖网络不可用，记录缺失坐标和重现命令，不宣称通过。

**回滚:** 本任务默认不改仓库文件；若误改了本地 Gradle 配置，只恢复环境变量和缓存，不产生 Git 提交。

---

## Task 3: 清理换行噪声并保护差异边界

**负责人:** 整洁性维护者 C（Git/格式）\
**预计耗时:** 20–45 分钟\
**并行:** 可与 Task 2 并行；涉及 `gen_jump_table.js` 的行尾处理需在 Task 1 后执行。

**Files:**
- Modify as needed: `SmartMaid/tools/maid_inventory_sim.js`
- Modify as needed: `SmartMaid/tools/verify_all.py`
- Modify as needed:新增/修改的 `SmartMaid/src/test/java/**/*.java`
- After Task 1: `SmartMaid/tools/gen_jump_table.js`
- Optional policy: root `.gitattributes`

**Inputs:** `git diff --check main...HEAD` 输出、当前 Git attributes 和工作树状态。\
**Outputs:** 仅换行/尾空白清理的可审查 diff，且不改变解析后内容。\
**协作要求:** 先保存 `git status --short` 和 `git diff --stat`；不得使用全仓库无差别换行转换；不得吸收其他未提交改动。

- [ ] **Step 1: 建立文件级基线**

记录目标文件的 SHA-256、行数和 `git diff --numstat`；确认每个文件的变更属于本分支。

- [ ] **Step 2: 统一目标文件换行**

仅对目标文件按仓库约定规范化 CRLF/LF，并保留源码字符、编码、JSON/Python/JS 语义不变。若增加 `.gitattributes`，只添加最小必要规则，并说明对现有文件的影响。

- [ ] **Step 3: 做语义等价检查**

运行：

```text
git diff --check main...HEAD
node --check SmartMaid/tools/gen_jump_table.js
node --check SmartMaid/tools/maid_inventory_sim.js
python -m py_compile SmartMaid/tools/verify_all.py
```

Expected：无 trailing-whitespace；Node/Python 语法检查全部通过。

- [ ] **Step 4: 审核差异范围**

确认 `git diff --stat` 没有新增非目标文件，且生产 Kotlin/Java 文件内容没有被换行任务修改。

**回滚:** 若出现整文件误改、编码变化或非目标内容变化，按 Step 1 的 SHA 精确恢复该文件，不使用全分支 reset。

---

## Task 4: 更新迁移状态文档

**负责人:** 文档维护者 D（需在 Task 2 结果确定后执行）\
**预计耗时:** 20–40 分钟\
**并行:** 可提前准备草稿；正式修改依赖 Task 2、Task 3 的最终结果。

**Files:**
- Modify: `SmartMaid/KOTLIN_MIGRATION_STATUS.md:1-94`
- Reference: `SmartMaid/gradle.properties`
- Reference: `SmartMaid/gradle/wrapper/gradle-wrapper.properties`
- Reference: `SmartMaid/src/main/resources/fabric.mod.json`

**Inputs:** Task 2 的构建日志、Task 3 的 diff-check 结果、Git tree 文件统计。\
**Outputs:** 与当前 HEAD 一致的迁移状态和验收记录。\
**协作要求:** 不把历史成功记录写成当前验证；每个“通过”必须对应命令、时间和日志位置。

- [ ] **Step 1: 更新真实迁移范围**

将“仅迁移 8 个文件/剩余 120+4 个 Java”改为当前实际统计，并说明 6 个 Java 测试是有意保留的互操作测试。

- [ ] **Step 2: 更新工具链版本**

准确记录 Gradle 9.9.1、Kotlin 2.4.21、Fabric Language Kotlin 1.14.2+kotlin.2.4.21、JDK 25 和对应 Loom/Fabric API 版本。

- [ ] **Step 3: 更新验证章节**

分别记录：

- 26.2 build/JUnit 结果；
- 26.3 build/JUnit 结果；
- `gen_jump_table.js` 和 `maid_inventory_sim.js` smoke 结果；
- 是否完成 server/client 启动烟测；
- 任何未完成项和阻断原因。

- [ ] **Step 4: 更新验收清单**

删除已完成事项，保留真实未完成项；不得继续写“git diff --check passed”，除非 Task 3 已给出无错误结果。

- [ ] **Step 5: 文档自检**

运行：

```text
git diff --check -- SmartMaid/KOTLIN_MIGRATION_STATUS.md
```

Expected：文档本身无换行错误，所有版本号与配置文件一致。

**回滚:** 若 Task 2 尚未最终确定，保留草稿不提交；若文档写错，按单独提交回退，不回滚源码修复。

---

## Task 5: 统一功能验证、回归测试与发布风险检查

**负责人:** 发布验证负责人 E（合并前唯一门禁负责人）\
**预计耗时:** 60–180 分钟\
**执行顺序:** 必须在 Task 1-4 完成后串行执行。

**Files:**
- Read-only verification across all changed files.
- Test outputs: use ignored `build_logs/` or an external temporary directory; do not commit generated logs.

**Inputs:** Task 1-4 的提交和日志。\
**Outputs:** 一份带命令、退出码、关键输出和未决风险的合并门禁报告。\
**协作要求:** 若任一 P0 检查失败，不得给出“可合并”结论；失败项必须关联到具体任务或回滚方案。

- [ ] **Step 1: 检查 Git 差异与生产源统计**

确认：

```text
git diff --check main...HEAD
git ls-tree -r --name-only HEAD SmartMaid/src/main/java SmartMaid/src/main/kotlin SmartMaid/src/mc26.2 SmartMaid/src/mc26.3
```

Expected：无 whitespace 错误；生产统计仍为 `java=0, kotlin=131`。

- [ ] **Step 2: 运行完整构建和测试**

执行 Task 2 的 26.2/26.3 build；记录 `BUILD SUCCESSFUL` 和 JUnit 结果。当前目标为历史基线的 `33 passed, 0 failed, 0 errors, 0 skipped`，若测试数量变化必须说明原因。

- [ ] **Step 3: 运行工具回归**

执行：

```text
cd SmartMaid
node tools/gen_jump_table.js
node tools/maid_inventory_sim.js
python tools/verify_all.py --series 26.2
```

若本地依赖不足，必须将 `verify_all.py` 的失败区分为环境缺失、工具失败或源码失败。

- [ ] **Step 4: 运行网络/序列化和互操作回归**

检查 6 个 payload 的注册与 codec，运行现有 Java 测试，确认 `@JvmStatic`、`@JvmField`、`@JvmRecord` 入口仍可被 Java 测试调用。

- [ ] **Step 5: 运行启动/运行时烟测**

在隔离的 server/client 运行目录执行项目已有的启动和 AutoTest 流程；至少确认：

- Mod initializer 成功加载；
- payload 注册不报 `Cannot register handler as no payload type has been registered`；
- 女仆召唤与基本移动成功；
- GUI/背包入口成功；
- 任务持久化/恢复不抛空安全异常。

- [ ] **Step 6: 做发布风险检查**

检查：

- jar 中的许可证文件和 `fabric.mod.json` 依赖声明；
- 没有残留生产 Java；
- 没有未提交 build/log/cache 文件；
- 依赖版本和 Java `>=25` 声明与目标 Minecraft 版本一致；
- 生成器修复没有改变跳表算法输出。

- [ ] **Step 7: 输出合并结论**

只有全部 P0 通过且没有未解释的高风险差异时，结论才可为“可以合并”。否则结论为“修复后合并”，列出阻断项和下一步。

**回滚:** 发布验证失败时按失败所属任务回退单独提交；若发现运行时回归，优先回滚最近的行为/工具修改提交，保留独立的文档和换行修复，禁止一次性回退整个迁移分支。

---

## 推荐执行顺序

### 第一阶段：并行，低冲突

- Task 1：修复跳表生成器；
- Task 2：恢复 Gradle/JDK 验证环境；
- Task 3：先处理非生成器文件的换行和差异边界。

### 第二阶段：串接依赖

- Task 1 完成后，Task 3 处理 `gen_jump_table.js` 的最终换行；
- Task 2、Task 3 完成后，Task 4 更新状态文档。

### 第三阶段：统一门禁

- Task 5 串行执行完整 build、测试、工具 smoke、启动烟测和风险检查。

### 失败处理原则

- 工具脚本失败：先回滚脚本/生成产物任务，不动 Kotlin 迁移；
- Gradle 环境失败：修复缓存/权限/进程，不通过修改源码规避；
- build 或运行时回归：暂停合并，定位到最近任务提交后做最小回滚；
- 文档或换行问题：可独立回滚，不影响已验证的源码修复。

## 自检结论

- 已覆盖审查中确认的高严重度脚本问题、中严重度状态文档问题、低严重度换行问题和当前 Gradle 验证阻断。
- 已明确并行任务、任务依赖、负责人角色、输入/输出、时间预算、验收标准与回滚边界。
- 已将完整构建、JUnit、工具 smoke、Java↔Kotlin 互操作、网络注册、启动烟测和许可证/发布检查统一收敛到 Task 5。
- 未将先前误判的 `MaidTaskScreen.kt` 作为修复任务；其类内 `arrayOf(key): JsonArray` helper 已确认能够保持现有语义。
