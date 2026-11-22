# Kotlin migration — source complete (runtime acceptance pending)

Checked on 2026-11-22. This is an incremental, locally committed migration.
As of this update **all 128 `src/main/java` files have been replaced by Kotlin**:
`src/main/java` and both `src/mc<series>/java` trees are empty. The full
`gradlew build` passes with JDK 25 offline, including all 33 existing tests.
What is *not* yet done is runtime acceptance (client/server startup) and the
26.3 build (see below).

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

All 128 of the original 128 `src/main/java` files have been replaced by
Kotlin under `src/main/kotlin`, with their Java implementations removed — plus
the four version-specific `compat` files, which now live in
`src/mc26.2/kotlin` / `src/mc26.3/kotlin` (`build.gradle` adds
`src/mc${mcSeries}/kotlin` to `kotlin.srcDirs`). The
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
`MaidInventoryTidy`. Batch five migrated `SmartMaidClient`, `MaidAnimManager`,
`ModNetworking`, and `MaidErrorSink`. Selected earlier migration details:

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

The last shared batch migrated the client GUI screens (`MaidSubScreen` plus the
six screens built on it) and `MaidBlockBreaker`; the final batch migrated
`SmartMaidEntity`, `MaidActions`, `CraftExecutor`, `MaidAIBridge`,
`MaidWsClient`, `MaidAutoTest`, `MaidClientTest` and all four version-specific
`MaidCompat`/`MaidAnimCompat` files. **No production Java source remains.**

The only Java left in the repository is the six existing JUnit tests under
`src/test/java`. This is deliberate: they are compiled by `compileTestJava`
against the Kotlin classes on every build, so they act as a permanent
Java-interop regression guard (Java callers must keep resolving Kotlin
`@JvmStatic`/`@JvmField`/getter APIs). They can be migrated later without
affecting the mod artifact.

## Validation (updated 2026-11-22)

- Environment is fully cached and works offline: JDK 25
  (`C:\Program Files\Java\jdk-25.0.5.1`), Gradle 9.7.1, Kotlin 2.4.21,
  fabric-loom 1.17.21, Fabric Language Kotlin 1.14.2+kotlin.2.4.21. The earlier
  claims that loom was uncached / Maven downloads blocked / the wrapper build was
  blocked by DNS are **superseded**. Run gradlew.bat via cmd.exe (MSYS cannot run
  the POSIX gradlew script with Windows Java).
- Build root cause (historical, now removed): overriding `sourceSets.main.java.srcDirs`
  left KotlinCompile with no Java source (`Java source files:` empty in `--info`),
  causing 670 spurious Unresolved errors. The migration-era workaround
  `tasks.withType(KotlinCompile).configureEach { source(sourceSets.main.java.srcDirs) }`
  reduced this to real errors; once `src/main/java` was emptied the whole
  `sourceSets` override and that workaround were deleted. `build.gradle` is back
  to the standard Fabric Loom layout: default `src/main/java` + `src/main/kotlin`,
  with only `kotlin.srcDir "src/mc<series>/kotlin"` added for the version
  compatibility layer (`build.gradle:42-48`).
- Incremental compileKotlin error count: 670 → 104 → 37 → 10 → 1 → 32 → **0**.
  Java↔Kotlin API mismatches fixed: `@JvmField` on `const val`, generic
  `MoveControl<T>`, `MoveControl.maxUpStep()`, `AbstractArrow.setBaseDamage`,
  `JsonArray.size()`, Kotlin `is` operator for `FluidState.is`, Gson wildcard list,
  explicit `Registry.register<...>` generics, `@JvmRecord` for `JumpSolution`,
  `const val` for `MaidCommandPayload.ACTION_*` (Java switch-case constants),
  removal of duplicate setter declarations in `QueuedTask`, and
  `MaidAITask.forceStop(maid: SmartMaidEntity?)` (tests pass null).
- `gradlew.bat --offline --no-daemon build`: **BUILD SUCCESSFUL**, 33 tests passed
  on 26.2 after batches four and five.
- Final batch verification (2026-11-22): `gradlew build` **BUILD SUCCESSFUL**;
  task log shows `:compileKotlin`, `:compileJava`, `:compileTestJava`, `:test`,
  `:jar` all executed; JUnit XML aggregate `tests=33 skipped=0 failures=0 errors=0`.
  `node .migration-work/check-migration-bytecode.cjs` prints
  `OK: navigation forwards nullable vanilla paths; friendly-fire guard resolves the arrow owner.`
  `git diff --cached --check` clean, all new Kotlin files LF (CR=0).
- Cross-file nullability: `MaidTaskManager.TaskFactory.create` declares all four
  parameters nullable (it is a legacy Java-shaped callback), while
  `MaidAIBridge.buildTask` keeps the original non-null contract. `MaidDataManager`
  now guards the callback arguments and skips a task that cannot be rebuilt
  instead of letting a null reach `buildTask`.
- Method-set comparison against the pre-migration Java (`git show HEAD:…`,
  regex-extracted declaration names) shows no missing methods for
  `SmartMaidEntity` (52 vs 50 `fun`, the two gaps are Kotlin properties
  `isAiBusy` / `recentAttacker` that still emit the original getters),
  `MaidActions` (31/31), `MaidWsClient` (30/30), `MaidAIBridge` (20/20),
  `MaidAutoTest` (21/21), `MaidClientTest` (17 vs 19, only added private helpers),
  `CraftExecutor` (23 `fun` + 2 nested records `CraftResult`/`IngredientNeeds`).
- `SmartMaid/tools/verify_all.py` no longer silently passes when the Java tree is
  empty: `step_compile` detects the absence of `*.java` and falls back to a real
  `gradlew.bat --offline --no-daemon build` (it refuses to run if
  `gradle.properties`'s `mc_series` differs from `--series`). The 26.2 invocation
  of its B/C stages still cannot run in this environment because
  `.minecraft/mods/fabric-api-*.jar` is absent; `gradlew build` is the
  authoritative compile gate. Existing `ResumableTest` cases now also verify
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
- Actual runtime startup on Minecraft 26.2: **dedicated server verified** via the
  standard Loom task `gradlew.bat --offline --no-daemon runServer`
  (log `.migration-work/runserver.log`): Fabric loaded 61 mods including
  `smartmaid 0.1.3`; `Smart Maid initialized (debug=false, verbose=false)`;
  default `config/smartmaid/main.json` and `bridge.json` generated; world
  prepared; `Done (3.258s)! For help, type "help"`. The only ERROR in the log is
  the benign first-run `NoSuchFileException: server.properties`, and the
  dedicated-server branch logs `专用服务器：跳过桌宠联动`. Client startup
  (`runClient`) and in-game behaviour remain unverified.

## Remaining acceptance work

1. **Client-side runtime acceptance on 26.2 (not performed).** The dedicated
   server now boots (see above), but `runClient` was not launched, so the seven
   self-drawn GUI screens, 3D preview, animation (`MaidAnimCompat` 26.2 swing
   hack), networking against the desktop-pet bridge, and task persistence across
   save/load are still unverified in-game.
2. **26.3 build (blocked, not a code claim).** Offline resolution fails before
   compilation because `net.fabricmc.fabric-loom:…:1.18-SNAPSHOT` is not cached,
   and neither `versions/26.3/26.3.jar` nor a 26.3 Fabric API jar is present.
   The 26.3 `MaidCompat`/`MaidAnimCompat` Kotlin files are a line-by-line port of
   the previously verified Java versions but were **not compiled**. Once the
   artifacts are available: switch the five `gradle.properties` keys to 26.3 and
   run `verify_all.py --series 26.3`.
3. Optional cleanup: migrate the six Java tests to Kotlin (only after deciding
   to give up the Java-interop compile guard they currently provide), and
   normalise the six `getXValue()` compatibility getters on `SmartMaidEntity`
   that exist only so both `maid.settings` and `maid.getSettings()` call styles
   keep working.
4. Publish the branch (`feature/kotlin-migration`) and mark the migration
   complete.
