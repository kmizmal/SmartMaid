# Kotlin migration — Task 5 verification complete; merge decision: 修复后合并

Checked on 2026-11-23 during execution of `docs/superpowers/plans/2026-11-23-kotlin-migration-merge-fixes.md`.
This document records the current working-tree state and the commands actually run in this checkout.
Task 5 static checks completed on 2026-11-23. The migration is not marked as unconditional merge-ready: the static gate is green, but full AutoTest/gameplay E2E could not start because this machine has no HMCL launch log or installed 26.2 game directory.

## Branch and baseline

- Working branch: `feature/kotlin-migration`.
- Review baseline from the migration plan: HEAD `0c84442`.
- Existing untracked `docs/` content was preserved; no unrelated files were intentionally modified.
- The plan requested an isolated worktree, but this host sandbox denied writes to both an external worktree location and Git metadata; execution therefore continued in the current branch with path-scoped verification.

## Dependency configuration

| Dependency | Configured version | Notes |
| --- | --- | --- |
| Minecraft 26.2 | 26.2 | Default `mc_series=26.2` |
| Minecraft 26.3 | 26.3 | Verified through quoted Gradle `-P` overrides |
| Fabric API 26.2 | 0.161.0+26.2 | Verified by 26.2 build |
| Fabric API 26.3 | 0.161.0+26.3 | Verified by 26.3 build |
| Fabric Loader 26.2 | 0.19.3 | Repository configuration |
| Fabric Loader 26.3 | 0.19.5 | Verified by 26.3 build |
| Fabric Loom 26.2 | 1.17.21 | Verified by 26.2 build |
| Fabric Loom 26.3 | 1.18.4 | Verified by quoted 26.3 build |
| Kotlin plugin / stdlib | 2.4.21 | Verified by both builds |
| Fabric Language Kotlin | 1.14.2+kotlin.2.4.21 | Verified by both builds |
| Gradle Wrapper | 9.9.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Java / Kotlin target | 25 | Local runtime: JDK 25.0.5.1 |
| Mappings | None | Official unobfuscated names |

`fabric.mod.json` declares Java `>=25`, Fabric Language Kotlin, Minecraft, Fabric API and Player Animation Library dependencies. Licenses remain GPL-3.0-only, MIT and CC-BY-NC-SA-4.0.

## Actual migration scope

Current working-tree counts are:

- `src/main/java`: **0** production Java files;
- `src/main/kotlin`: **127** shared Kotlin files;
- `src/mc26.2/kotlin` and `src/mc26.3/kotlin`: **4** version-specific Kotlin files;
- all production Kotlin sources: **131**;
- Java tests: **6**, intentionally retained as Java↔Kotlin interoperability regression tests.

The migration keeps the public Java-facing contracts, payload formats, serialization order and runtime behavior unchanged. The current fix also restores `SmartMaid/tools/gen_jump_table.js` so it emits `src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidJumpTable.kt` with the Kotlin `object`, `@JvmRecord` and `@JvmStatic` API shape.

## Validation performed

### Generator and script checks

- Initial reproduction of `node tools/gen_jump_table.js` failed with:
  `TypeError: Cannot read properties of undefined (reading 'join')`.
- After the generator fix, the same command exited 0 and reported:
  `[gen] written .../MaidJumpTable.kt: 15 solutions; unsolvable: none (all covered)`.
- `node --check tools/gen_jump_table.js`: passed.
- `node --check tools/maid_inventory_sim.js`: passed.
- `python -m py_compile tools/verify_all.py`: passed.
- `git diff --check`: passed after normalizing only the target generator file to LF.

### Gradle/JDK checks

- `java -version` / `javac -version`: JDK `25.0.5.1`.
- No active Java/Gradle process was observed during wrapper-cache diagnosis.
- Offline wrapper `help` with Gradle 9.9.1: passed.
- 26.2 build:
  `gradlew.bat --offline --no-daemon --console=plain clean build`
  → `BUILD SUCCESSFUL`.
- 26.3 build:
  `gradlew.bat --offline --no-daemon --console=plain clean build -Pmc_series=26.3 -Pminecraft_version=26.3 -Ploader_version=0.19.5 -Ploom_version=1.18.4 -Pfabric_api_version=0.161.0+26.3`
  → `BUILD SUCCESSFUL`.
- The 26.3 `-P` values must be quoted when entered through PowerShell; the unquoted form incorrectly resolved Loom as version `1` and failed plugin lookup, while the quoted form passed.
- Builds emitted existing deprecation and Kotlin inspection warnings, but no compilation errors.

### Still pending

- `python tools/verify_all.py --series 26.2` initially exposed a stale hard-coded `0.158.0+26.2` expectation and a Windows output-decoding issue. `tools/verify_all.py` now uses the configured `0.161.0+26.2` range, falls back to the Gradle module cache (or `SMARTMAID_FABRIC_JAR`), decodes child-tool output as UTF-8, and includes the Gradle module cache in B/C classpaths. With `PYTHONIOENCODING=utf-8`, A/B/C all pass: `BUILD SUCCESSFUL`, PAL 1.2.6 has 0 fatal/0 warning Mixin issues, and all 209 dependency members match.
- `python tools/run_e2e_test.py --no-pet --skip-token-check --timeout 30` could not start: no HMCL launch command was found. `_paths.py` resolves the default `.minecraft` path, but it contains no `versions/26.2` directory and `clienttest.py report --series 26.2` reports the same missing version directory. Full AutoTest/gameplay assertions (summon/movement, GUI/inventory, persistence/recovery) therefore remain blocked until a real 26.2 instance is installed/launched once (or `SMARTMAID_MC_DIR` points to one).
- `clienttest.py list --series 26.2` found the available scripts `gui-chain` (29 steps) and `key-encoding` (3 steps; requires manual Shift+E interaction), but neither could be deployed without the game directory.
- CraftExecutor warning review: the existing nullability/API cleanup was retained; `filledInput()` now uses the non-null `CraftingInput` return directly, and the intentional deprecated `Ingredient.items()` calls are narrowly suppressed with comments because 26.2 exposes no non-deprecated enumeration API. The clean 26.2/26.3 compiles no longer report CraftExecutor warnings; unrelated project warnings remain.

## Remaining acceptance work

1. Task 5 tool checks completed: generator smoke passed (15 solutions), inventory simulator passed (19/19), and syntax checks passed.
2. 26.2 and quoted-parameter 26.3 `test` both passed: 6 suites, 33 tests, 0 failures/errors/skips. This covers the retained Java↔Kotlin interoperability tests.
3. Payload review confirmed six `@JvmRecord` payloads expose `@JvmField TYPE` and `@JvmField STREAM_CODEC`; `ModNetworking.register()` registers four serverbound and two clientbound payloads. Both 26.2/26.3 compilation and server/client startup loaded the payload classes and `smartmaid` initializer.
4. Earlier startup smoke passed through `runServer` (`Done`, `Smart Maid initialized`) and `runClient` (`Smart Maid initialized`, `SmartMaid 客户端初始化完成`); processes were terminated after the smoke window. The 26.2 dev jar contains the generated jump-table classes, all six payload classes, `META-INF/LICENSE`, and `META-INF/NOTICE.md`.
5. The corrected P0 verification command now passes A/B/C with the Gradle cache classpath. Static merge gate is green, but the branch remains `修复后合并` until the real game instance is available and AutoTest/clienttest gameplay evidence is collected.
