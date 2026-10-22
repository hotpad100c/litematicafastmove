# Porting LitematicaFastMove

The upstream branch `LFMEmergencyExitImpl` targets Minecraft **26.3** only. Everything older is done here.
The mod depends on Litematica, malilib and [EXIT](https://github.com/hotpad100c/emergency-exit), which we have
already ported down to 1.16.5 (our fork `wwwzzzxxx/emergency-exit`, one `port/<mc>` branch per version).

## Version matrix

| MC | EXIT branch / artifact version | Fabric API | Java | Litematica | malilib |
|---|---|---|---|---|---|
| 26.3 | `master` = 0.1.0+26.3 | 0.162.0+26.3 | 25 | 0.29.1 | 0.30.2 |
| 1.21.11 | `port/1.21.11` = 0.1.0+1.21.11 | 0.141.6+1.21.11 | 21 | 0.26.16 | 0.27.20 |
| 1.21.8 | `port/1.21.8` = 0.1.0+1.21.8 | 0.132.x+1.21.8 | 21 | 0.23.7 | 0.25.7 |
| 1.21.4 | `port/1.21.4` = 0.1.0+1.21.4 | – | 21 | 0.21.7 | 0.23.5 |
| 1.21.1 | `port/1.21.1` = 0.1.0+1.21.1 | – | 21 | 0.19.61 | 0.21.10 |
| 1.20.6 | `port/1.20.6` = 0.1.0+1.20.6 | 0.100.8+1.20.6 | 21 | 0.18.3 | 0.19.2 |
| 1.20.4 | `port/1.20.4` = 0.1.0+1.20.4 | 0.97.3+1.20.4 | 17 | 0.17.4 | 0.18.3 |
| 1.20.2 | `port/1.20.2` = 0.1.0+1.20.2 | 0.91.6+1.20.2 | 17 | 0.16.1 | 0.17.0 |
| 1.20.1 | `port/1.20.1` = 0.1.0+1.20.1 | 0.92.12+1.20.1 | 17 | 0.15.4 | 0.16.3 |
| 1.20 | `port/1.20` = 0.1.0+1.20 | 0.83.0+1.20 | 17 | none published | 0.16.3 |
| 1.19.3 | `port/1.19.3` = 0.1.0+1.19.3 | 0.76.1+1.19.3 | 17 | 0.13.2 | 0.14.1-pre.1 |
| 1.18.2 | `port/1.18.2` = 0.1.0+1.18.2 | 0.77.0+1.18.2 | 17 | 0.11.7 | 0.12.1 |
| 1.17.1 | `port/1.17.1` = 0.1.0+1.17.1 | 0.46.1+1.17 | 16 | 0.9.0 | 0.10.0-dev.26 |
| 1.16.5 | `port/1.16.5` = 0.1.0+1.16.5 | 0.42.0+1.16 | 16 | 0.0.0-dev.20210917 | 0.10.0-dev.21+arne.8 |

Litematica/malilib versions are the newest release on Modrinth for that game version. 1.20 has no Litematica
release of its own, so the 1.20 target may have to stay out.

Work per version:

1. `mkdir -p Y:/MC/mod-dev/lfm-wt && git worktree add Y:/MC/mod-dev/lfm-wt/<mc> -b port/<mc> feat/render-delay`
2. Set `gradle.properties` (minecraft_version, loader, loom, fabric_api_version, exit_version, iris/sodium
   Modrinth ids) and `build.gradle` (Java `release`, Litematica/malilib coordinates, `loom_version`).
3. Set `exit_version` to the matching EXIT tag, `<EXIT version>-<mc>` (for example `0.1.0-1.21.11`). Gradle takes
   EXIT from JitPack: `com.github.Shader-Escape.emergency-exit:emergency_exit-fabric:<tag>`.
4. Port the source. The moving parts are the mixins into Litematica (`PlacementManagerTaskRebuild`,
   `SchematicPlacementManager`, `SchematicWorldRefresher`, `LitematicaRenderer`, `RenderHandler`, …) whose class
   and method names change between Litematica releases, and the mesh code, which uses Minecraft classes that
   changed a lot before 1.18 (`RenderType`, `VertexConsumer`, `BufferBuilder`, `PoseStack`, `ChunkPos`).
5. `./gradlew build` must pass. Then a `runClient` smoke test: the game must start with Litematica installed and
   the mod's mixins must apply (Litematica's own log prints the mixin count).
6. Commit per version on `port/<mc>`. Local commits only; pushing is done from the main checkout.

## Building every version at once

`scripts/build-all.sh` (Git Bash) builds the current checkout plus every local `port/<mc>` branch and copies the jars to
`dist/`. Each port branch is checked out once as a worktree under `worktrees/<mc>` (git-ignored) and reused. Build
logs of failed versions stay in `dist/build-<mc>.log`.
`scripts/build-all.sh 1.21.11 1.20.1` builds only those (`main` = the current checkout).

Changes made on the main branch reach the ports by merging it into each `port/<mc>`.

## Rules for a development client run (machine-wide)

- **Minecraft clients do not announce themselves**: no sound (no audio device, narrator off), hidden window,
  `resources` copied into the classes directory for the old Fabric loaders — all handled by
  `~/.gradle/init.d/mc-hidden-window.gradle`.
- **One client at a time**: the init script takes a machine-wide lock (`Y:/Temp/User/opencode/mc-client.lock`)
  and waits for it. Do not run two clients, do not work around the lock.
- **Never kill a `gradlew` client task with a shell timeout**: a cancelled build leaves a heartbeat thread in the
  surviving daemon that keeps refreshing the lock, which wedges every later client run.
- Say "starting a client, about N minutes" before any run, so whoever is at the machine can step away.

## What a green build does not prove

`./gradlew build` only says the code compiles. Mixin resolves a `method` name first and validates the handler afterwards,
so a handler whose parameters do not match its target, or an `@At` string with the wrong package, fails **when a client
loads the mod** - four real defects of that kind survived a green build (1.16.5, 1.20.6, 1.21.8, 1.21.11).

Run the checker before any client run: `python Y:/Temp/User/opencode/mixin-audit.py <worktree> <worktree>` (its docstring
lists what it compares). It now catches all of these, none of which a compiler sees:

- a bare `method = "markChunksForRebuild"` with two overloads resolves to the wrong one - pin the descriptor;
- package typos in `@At` targets (`level/blockentity/BlockEntity` vs `level/block/entity/BlockEntity`). Fingerprint: the
  built jar then keeps a *named* Minecraft class among intermediary ones;
- MixinExtras `@Local(name = ...)` types must match the target local **exactly** (`ArrayList`, not `List`; `Vector4f`,
  not `Vector4fc`); an assignable supertype is rejected at apply time;
- `@WrapMethod` handlers must declare **all** of the target's parameters - a missing `Matrix4f` is fatal;
- the checker must pick the right jars: with two Loom caches in one worktree directory it silently compared against the
  neighbouring version and "fixed" four handlers to the wrong API.

Check the metadata every port copies from its neighbour too: `fabric.mod.json`'s `depends.minecraft` (1.21.1 asked for
`~1.21.4`, so the loader refused the mod outright) and `gradle.properties`'s `version` (1.20.4/1.20.2 shipped `1.0.0`).

Development-runtime dependencies can bite as well: on 1.20.1 Litematica/malilib metadata pulls LWJGL 3.3.2-snapshot while
Sodium demands the 3.3.1 Minecraft ships, so the client dies in Sodium's own window before writing a log; that branch
pins `org.lwjgl` to 3.3.1 in `build.gradle`.

## What the mesh path is worth (measured on Minecraft 26.3, branch `bench/harness`)

`./gradlew -Pjava_version=25 :runBenchClient -Dlitematicafastmove.bench.pattern=<hollow|solid|checker|noise>` builds a
schematic of its own, places it through Litematica's API and measures both renderers in the same run. Numbers below are
medians, 5-7 repeats, with `-Dlitematicafastmove.bench.extraRebuild=false` so neither arm does a superseded build:

| pattern | exposed faces | our mesh | Litematica | ratio |
|---|---|---|---|---|
| hollow 64^3 | 47.6 k | 62.7 ms | 51.1 ms | 0.8 |
| hollow 128x64x128 | 98 k | 141.1 ms | 175.2 ms | 1.2 |
| checker 64^3, every face exposed | 786 k | 300.2 ms | 719.3 ms | **2.4** |

The advantage is real but conditional: it grows with the number of exposed block faces (the async build and the batching
pay off), while a small simple projection is slightly slower than Litematica's own path.

`fastMoveRenderDelay` (ms, 0 disables) holds a moved placement where it was and only starts drawing it at the new place
once it has been still for that long; moving it again inside the window restarts the wait, so the positions in between
are never drawn. Over a scripted drag of 20 moves: distinct displayed positions 80 -> 1 for any delay >= 250 ms, and
section rebuilds 196 -> 128 with the default commit delay. Note the division of labour: `fastMoveCommitDelay` is what
suppresses rebuilds; the render delay suppresses drawing and saves some partial rebuilds on top of it.
