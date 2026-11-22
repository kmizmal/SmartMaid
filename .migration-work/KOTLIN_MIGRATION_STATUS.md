# Kotlin migration — incomplete, not accepted

Checked on 2026-11-22. This is a locally committed initial portion of the requested
migration, not a completed release or a successful full build.

## Branch and baseline

- Local branch: `feature/kotlin-migration`.
- Base: `main`, commit `c7094701bea239a2ea8eb9033ee2cec3f206b1a5`.
- GitHub compare confirmed this commit and remote `main` were identical.
- Original tracked files are unchanged; work is in this independent checkout.
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

Twenty-two of the original 128 `src/main/java` files were replaced by Kotlin under
`src/main/kotlin`, with their Java implementations removed:

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

The default Fabric adapter can still construct the initializer class. Entrypoint
names and adapter are unchanged; metadata only gains the required Kotlin runtime
dependency. Resource assets and packet formats are unchanged. The baseline has
no mod-owned Mixin classes or Mixin configuration; bundled dependency Mixins are
unchanged.

The remaining 106 common Java files, four version-specific Java compatibility
files and existing Java tests remain. These are unfinished scope, not claimed
technical exceptions. The client initializer has not been migrated. Both common
source directories remain enabled during this initial stage.

## Validation

- `git diff --check`: passed.
- Outer CodeGraph sync after this batch: 2 changed files added, then 2 Java files
  removed; Java callers resolve through `ItemSlot` and `MaidFoodData` symbols.
- `gradlew.bat compileKotlin --offline --no-daemon`: blocked before compilation;
  exact error: `Plugin [id: 'net.fabricmc.fabric-loom', version: '1.17.21'] was not found`.
- The initial two Kotlin files compiled successfully with cached Kotlin 1.9.22,
  JVM target 21, against the baseline mod jar, Minecraft 26.2 and cached Fabric
  dependencies. This checks source syntax and Java linkage only, not the
  configured Kotlin 2.4.21 / Java 25 toolchain or the complete source tree.
- The six newly migrated packet files are indexed and their Java call sites are
  resolved by CodeGraph; a clean compiler run for this batch is still pending
  because the local ad-hoc compiler classpath is incomplete and the configured
  Maven artifacts are not cached.
- Wrapper build: blocked by DNS failure resolving the distribution host.
- Cached Gradle 9.7.1 offline build: blocked by inability to connect to its local
  daemon, also with `--no-daemon` (Gradle starts a single-use daemon).
- Maven downloads are unavailable; Kotlin 2.4.21 and Fabric Language Kotlin are
  absent from the local dependency cache.
- Full Gradle build, existing tests, client/server startup and runtime entrypoint
  validation: **not completed**.

## Remaining acceptance work

1. Use an environment with Git metadata write access, Maven/distribution network
   access, local Gradle daemon connectivity and GitHub repository write access.
2. Migrate the remaining common and version compatibility sources. Preserve
   record accessors/codecs, null behavior, registry fields, event callbacks,
   persistence and Java test call sites; remove each replaced Java file.
3. Run `gradlew.bat clean build` with JDK 25 from `SmartMaid`, including existing
   tests, then verify dedicated server and client startup on Minecraft 26.2.
4. Verify entrypoints, dependency Mixins, animation, networking, menus and task
   persistence, then publish the branch and mark the migration complete.
