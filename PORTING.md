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
3. Publish the matching EXIT branch once, so the dependency resolves:
   `cd Y:/MC/mod-dev/exit-porting/emergency-exit && git checkout port/<mc> && ./gradlew publishToMavenLocal`
   (the artifact is `ml.mypals.exit:emergency_exit-fabric:<version>`).
4. Port the source. The moving parts are the mixins into Litematica (`PlacementManagerTaskRebuild`,
   `SchematicPlacementManager`, `SchematicWorldRefresher`, `LitematicaRenderer`, `RenderHandler`, …) whose class
   and method names change between Litematica releases, and the mesh code, which uses Minecraft classes that
   changed a lot before 1.18 (`RenderType`, `VertexConsumer`, `BufferBuilder`, `PoseStack`, `ChunkPos`).
5. `./gradlew build` must pass. Then a `runClient` smoke test: the game must start with Litematica installed and
   the mod's mixins must apply (Litematica's own log prints the mixin count).
6. Commit per version on `port/<mc>`. Local commits only; pushing is done from the main checkout.

## Rules for a development client run (machine-wide)

- **Minecraft clients do not announce themselves**: no sound (no audio device, narrator off), hidden window,
  `resources` copied into the classes directory for the old Fabric loaders — all handled by
  `~/.gradle/init.d/mc-hidden-window.gradle`.
- **One client at a time**: the init script takes a machine-wide lock (`Y:/Temp/User/opencode/mc-client.lock`)
  and waits for it. Do not run two clients, do not work around the lock.
- **Never kill a `gradlew` client task with a shell timeout**: a cancelled build leaves a heartbeat thread in the
  surviving daemon that keeps refreshing the lock, which wedges every later client run.
- Say "starting a client, about N minutes" before any run, so whoever is at the machine can step away.
