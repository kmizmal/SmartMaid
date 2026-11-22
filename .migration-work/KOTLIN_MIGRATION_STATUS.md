# Kotlin migration — in progress

Checked on 2026-11-22. This is an incremental, locally committed migration.
As of this update the **full `gradlew build` passes with JDK 25 offline**, including
all 33 existing tests. Migration of the remaining Java sources is still in progress.

## Branch and baseline

- Local branch: `feature/kotlin-migration`.
- Base: `main`, commit `c7094701bea239a2ea8eb9033ee2cec3f206b1a5`.
- GitHub compare confirmed this commit and remote `main` were identical.
- Migration work is in the outer repository on the feature branch.
- GitHub branch creation returned HTTP 403 (`Resource not accessible by
  integration`); no remote branch was created.

## Dependency configuration

| Dependency | Configured version | Notes |
| --- | --- | --- |
| Minecraft | 26.2 | Unchanged |
| Fabric API | 0.161.0+26.2 | Already the latest stable artifact for 26.2; unchanged |
| Fabric Loader | 0.19.3 | Unchanged |
| Fabric Loom | 1.17.21 | Replaces 1.17-SNAPSHOT with a fixed stable release |
| Kotlin plugin / stdlib | 2.4.21 | New |
| Fabric Language Kotlin | 1.14.2+kotlin.2.4.21 | New mandatory runtime dependency |
| Gradle | 9.7.1 | Existing wrapper, unchanged |
| Java / Kotlin target | 25 | Both languages use the same toolchain target |
| Mappings | None | Retain Minecraft 26.2's official unobfuscated names |

Sources:

- https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/
- https://maven.fabricmc.net/net/fabricmc/fabric-loom/
- https://github.com/FabricMC/fabric-language-kotlin#usage

Fabric Language Kotlin supplies the shared runtime. The explicit stdlib compile
dependency is not separately embedded in the mod jar.

## Actual migration scope

109 of the original 128 `src/main/java` files have been replaced by
Kotlin under `src/main/kotlin`, with their Java implementations removed. The
first 30 are listed below; later batches added network payloads, perception and
structure helpers, all 36 previously-flagged Java twins, seven small GUI/task
files (MaidControlMenuProvider, MaidInventoryMenuProvider, ScriptQueryTask,
MoveToTask, MaidTheme, CollectTask, CraftTask), and sixteen maidtask/navigation/
bridge classes (AttackTask, BuildTask, ChestOpenTask, ChestStoreTask, EatTask,
FeedTask, GuardTask, MaidDebug, MaidActor, MaidMonitor, MaidStraightNav,
MaidGroundPathNavigation, MaidWalkNodeEvaluator, BridgeConfig, ItemIndexPayload,
MaidTelemetryWriter). Batch four migrated 16 more: `MaidControlMenu`,
`MaidInventoryMenu`, `SmartMaidModel`, `SmartMaidRenderer`, `ScriptQueries`,
`ScriptTask`, `StructureScan`, `HarvestTask`, `FarmTask`, `MineTask`, `SmeltTask`,
`TransferTask`, `MaidTaskManager`, `MaidActionExecutor`, `MaidBlockPlacer`, and
`MaidInventoryTidy`. Selected earlier migration details:

- `SmartMaid`: public no-argument Fabric initializer, initialization order,
  command registration, lifecycle/tick callbacks and logging retained. `MOD_ID`
  remains a static constant and `LOGGER` uses `@JvmField`.
- `entity.ai.maidtask.TaskState`: JSON position helpers retain static Java APIs
  through `@JvmStatic`; nullable input and results are explicit.
- `network.MaidChatPayload`, `MaidCommandPayload`, `MaidMenuActionPayload`,
  `MaidMenuStatePayload`, `MaidOpenMenuPayload` and `MaidSettingsPayload`:
  Java record accessors, packet type/codec fields, action constants and factory
  methods are retained for existing Java callers; Kotlin records use
  `@JvmRecord`, `@JvmField` and `@JvmStatic` where required.
- `entity.ai.maidtask.MaidTaskNames`: task display strings, parameter defaults
  and nullable JSON behavior retained through a Kotlin object with static Java API.
- `init.ModMenus` and `init.ModEntities`: registry keys, menu feature flags,
  entity dimensions, no-save behavior and default attributes retained through
  Kotlin objects with `@JvmField` registry fields and `@JvmStatic register()`.
- `entity.ai.slot.ItemSlot`: slot reach, acceptance, swap, peek, extract and
  insert contracts retained as a Kotlin interface with Java-compatible method
  names and default implementations.
- `entity.MaidFoodData`: food, saturation, exhaustion, regeneration and hunger
  damage rules retained for the `LivingEntity` tick path.
- `gui.MaidArmorSlot`: armor/offhand placement rules, binding-curse pickup guard,
  active-slot checks and synchronization to the maid's equipment slots retained.
- `client.gui.MaidNet`: guarded client payload sending and connection checks retained
  through a Kotlin object with static Java-compatible methods; CodeGraph found no
  current callers for this helper.
- `entity.ai.perception.Sense`: perception channel interval, section name and
  collection contract retained as a Kotlin interface.
- `entity.ai.bridge.MaidCommandResult`: record accessors, `ok`/`fail` factories and
  JSON serialization retained through a Kotlin `@JvmRecord`.
- `entity.ai.structure.ProduceFilter`: exact-id, tag and wood-family block matching
  plus species inference retained through a Kotlin object and `@JvmRecord` result.
- `entity.ai.perception.sense.EnvironmentSense`, `SelfSense` and `OwnerSense`:
  environment, maid-self and owner snapshot collection behavior retained through
  Kotlin `Sense` implementations.
- `entity.ai.perception.MaidPerception`: snapshot root sections, event replacement,
  section copying and JSON serialization retained with a Java-visible `tick` field.
- `entity.ai.bridge.MaidTaskConfig`: task config persistence, save-directory migration,
  legacy fallback and clear/read/write operations retained through a Kotlin object.
- `entity.ai.script.ScriptContext`: public Java-visible variable map, step log,
  termination state, counters and `logStep` behavior retained.
- `entity.ai.script.ScriptRef`: recursive JSON variable resolution, `$last`, integer
  arithmetic and condition operators retained with static Java-compatible methods.

Structure helpers migrated: `StructureType`, `StructureComponent`, `OreStructure`, `TreeStructure`, and `StructureRegistry`. They compile as part of the verified 26.2 build.

The default Fabric adapter can still construct the initializer class. Entrypoint
names and adapter are unchanged; metadata only gains the required Kotlin runtime
dependency. Resource assets and packet formats are unchanged. The baseline has
no mod-owned Mixin classes or Mixin configuration; bundled dependency Mixins are
unchanged.

The remaining 19 common Java files (including SmartMaidEntity, CraftExecutor,
MaidActions, bridge clients, and client GUI screens),
four version-specific Java compatibility files and six existing Java tests
remain. These are unfinished scope, not claimed technical exceptions. The client
initializer has not been migrated. Both common source directories remain enabled
during this stage.

## Validation (updated 2026-11-22)

- Environment is fully cached and works offline: JDK 25
  (`C:\Program Files\Java\jdk-25.0.5.1`), Gradle 9.7.1, Kotlin 2.4.21,
  fabric-loom 1.17.21, Fabric Language Kotlin 1.14.2+kotlin.2.4.21. The earlier
  claims that loom was uncached / Maven downloads blocked / the wrapper build was
  blocked by DNS are **superseded**. Run gradlew.bat via cmd.exe (MSYS cannot run
  the POSIX gradlew script with Windows Java).
- Build root cause fixed in `build.gradle`: overriding `sourceSets.main.java.srcDirs`
  left KotlinCompile with no Java source (`Java source files:` empty in `--info`),
  causing 670 spurious Unresolved errors. Adding
  `tasks.withType(KotlinCompile).configureEach { source(sourceSets.main.java.srcDirs) }`
  reduced this to real errors.
- Incremental compileKotlin error count: 670 → 104 → 37 → 10 → 1 → 32 → **0**.
  Java↔Kotlin API mismatches fixed: `@JvmField` on `const val`, generic
  `MoveControl<T>`, `MoveControl.maxUpStep()`, `AbstractArrow.setBaseDamage`,
  `JsonArray.size()`, Kotlin `is` operator for `FluidState.is`, Gson wildcard list,
  explicit `Registry.register<...>` generics, `@JvmRecord` for `JumpSolution`,
  `const val` for `MaidCommandPayload.ACTION_*` (Java switch-case constants),
  removal of duplicate setter declarations in `QueuedTask`, and
  `MaidAITask.forceStop(maid: SmartMaidEntity?)` (tests pass null).
- `gradlew.bat --offline --no-daemon build`: **BUILD SUCCESSFUL**, 33 tests passed
  on 26.2 after batch four. Existing `ResumableTest` cases now also verify
  null snapshots are no-ops and null maids fail validation without throwing.
- Compared with Java and checked the compiled class files with `javap -c -p`:
  navigation preserves `null` from vanilla `createPath`, and the arrow's
  friendly-fire check calls `getOwner()` rather than reading `EntityReference`.
  Kotlin 2.4.21 warns that the local `RETURN_TYPE_MISMATCH_ON_OVERRIDE`
  suppression on the two navigation methods is unspecified; recheck the
  class files if updating the compiler or Minecraft signatures.
- IDEA MCP's direct build and converter are currently blocked by the IDE module
  model. Its named Gradle SDK `25` points to the absent `jdk-25.0.5.0.2`;
  the IDEA terminal works with `JAVA_HOME` set to `jdk-25.0.5.1` for that
  command. No global SDK changes are required by the repository.
- The documented 26.3 Gradle target stops before compilation in offline mode
  because `net.fabricmc.fabric-loom:net.fabricmc.fabric-loom.gradle.plugin:1.18-SNAPSHOT`
  is not cached. This is not a verified 26.3 build.
- `git diff --check`: passed on each committed batch. Tooling note:
  `mcp__idea__apply_patch` can flip LF to CRLF; run `sed -i 's/\r$//'` on touched
  files and `git diff --cached --check` before committing.
- Actual runtime startup (client/server) and entrypoint validation on Minecraft
  26.2: still not performed.

## Remaining acceptance work

1. Migrate the remaining common and version compatibility Java sources. Preserve
   record accessors/codecs, null behavior, registry fields, event callbacks,
   persistence and Java test call sites; remove each replaced Java file.
2. After each batch run `gradlew.bat --offline --no-daemon build` with JDK 25
   from `SmartMaid`, including the existing tests.
3. Verify dedicated server and client startup, entrypoints, dependency Mixins,
   animation, networking, menus and task persistence on Minecraft 26.2.
4. Publish the branch and mark the migration complete.
