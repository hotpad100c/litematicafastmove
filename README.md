# Litefastica (LitematicaFastMove)

[中文](README.zh-CN.md)

Make Litematica fly.

Features:

- **Independent meshes**: Each placement gets its own GPU meshes (blocks, fluids, overlays). Moving a placement only changes its draw position instead of rebuilding the mesh.
- **Smooth movement**: The displayed position interpolates toward the actual position, so dragging a placement no longer makes it jump one block at a time.
- **Deferred commits**: When a large placement is moved continuously, the placement world is not rewritten at every step. It is committed only after you stop moving.
- **Background building**: Meshes are built in batches of sections across multiple threads, with sections closer to the camera built first. GPU uploads are subject to a per-frame budget to prevent frame stalls.
- **Precise rebuilding**: Only sections near blocks that actually changed in the real world are rebuilt. If the real world is air both before and after a section moves, it is skipped entirely.
- **Multithreaded verification**: The Schematic Verifier in `Configure Schematic Placement` uses multithreaded scanning while continuing to display its results in Litematica's own verification UI.
- **Shader support**: Placements can now theoretically be rendered correctly with any shader pack.

Requires Litematica, malilib, and the bundled [EXIT](https://github.com/hotpad100c/emergency-exit), which allows placement rendering to bypass shader packs.

Supported versions: 26.3 (main branch), 1.21.11, 1.21.8, 1.21.4, 1.21.1, 1.20.6, 1.20.4, 1.20.2, 1.20.1, 1.19.3, 1.18.2, 1.17.1, 1.16.5 (respective `port/<mc>` branches).

## Configuration

All options are injected into Litematica's configuration screen under the Generic tab, in the `--- Litematica Fast Move ---` section.

### Basic

| Option | Default | Description |
|---|---|---|
| `fastMoveEnabled` Enable | On | Master switch. When disabled, behavior fully reverts to Litematica's original behavior. |
| `fastMoveTakeoverAllPlacements` Take over all placements | On | When enabled, all placements use independent meshes for rendering. When disabled, only placements whose volume is at least `Large Placement Volume` are taken over; smaller ones continue to be rendered by Litematica. |
| `fastMoveLargePlacementVolume` Large placement volume | 32768 (32³) | Placements whose volume (length × width × height) reaches this value defer rewriting the placement world while being moved continuously, and commit the new position after movement stops. When `Take Over All Placements` is disabled, this also determines which placements are taken over. |
| `fastMoveAsyncVerifier` Multithreaded verifier | On | Clicking Schematic Verifier performs a multithreaded scan directly against the placement data without going through the placement world. Results are displayed in Litematica's normal verification UI. |

### Movement

| Option | Default | Description |
|---|---|---|
| `fastMoveCommitDelay` Commit delay (ms) | 1500 | How long a large placement must remain stationary before its new position is written to the placement world. Until then, features that depend on the placement world, such as Easy Place and block information, still see the old position. Lower values make these features catch up faster, but cause more commits during continuous movement. |
| `fastMoveRenderDelay` Render delay (ms) | 0 | After a placement starts moving, keep it at its original position for this long before rendering it at the new position. Moving again during the wait resets the timer, so intermediate positions during dragging are never rendered, and the corresponding rebuilds are skipped as well. Useful for dragging extremely large placements. |
| `fastMoveSmoothing` Movement smoothing (ms) | 60 | Time constant for movement interpolation. Higher values produce smoother movement but make the placement feel more "laggy"; set to 0 to disable interpolation and jump directly to the new position. |
| `fastMoveSnapDistance` Snap distance (blocks) | 128 | When a single movement exceeds this distance, interpolation is skipped and the placement immediately snaps to the new position, preventing it from sliding all the way across the world. |

### Performance Budgets

| Option | Default | Description |
|---|---|---|
| `fastMoveUploadKbPerFrame` Upload budget (KB/frame) | 8192 | Maximum amount of mesh data uploaded to the GPU per frame. Excess data is deferred to later frames. Lower values reduce stutter spikes but make the placement appear more slowly. |
| `fastMoveUploadMsPerFrame` Upload budget (ms/frame) | 3.0 | Maximum time spent creating GPU buffers per frame. This takes effect alongside the data-size budget above; uploading stops as soon as either limit is reached. |
| `fastMoveBuildBatchSections` Build batch size (sections) | 32 | Number of 16×16×16 sections built in each mesh-building batch. Batches closer to the camera are built first, with batches distributed across all builder threads for parallel processing. Smaller batches make results appear sooner; partial rebuilds are applied batch by batch. |
| `fastMoveResortsPerFrame` Transparent resorts per frame | 32 | Maximum number of sections containing transparent blocks (glass, water, etc.) that are resorted per frame. Set to 0 to disable sorting; transparent blocks may appear incorrectly ordered from some angles. |
| `fastMoveResortDistance` Transparent resort distance (blocks) | 1.0 | A transparent section is resorted only after the camera has moved more than this distance relative to the section. |
| `fastMoveVerifierMergeMsPerTick` Verifier merge budget (ms/tick) | 50 | Results calculated by verifier threads are merged into Litematica's verifier on the main thread. This is the maximum amount of time spent merging per tick, measured from the start of each tick. Lower values keep the game smoother during verification, but make the results fill in more slowly. |

### Threads

| Option | Default | Description |
|---|---|---|
| `fastMoveMeshBuilderThreads` Mesh builder threads | 0 (auto) | Number of threads used to build placement meshes. `0` means CPU cores ÷ 4, with a minimum of 1. Takes effect immediately after changing the setting. |
| `fastMoveVerifierThreads` Verifier threads | 0 (auto) | Number of threads used for verification scans. `0` means CPU cores − 1, with a minimum of 1. Takes effect immediately after changing the setting. |

### Debugging

| Option | Default | Description |
|---|---|---|
| `fastMoveDebugGizmos` Debug gizmos | Off | Draws gradually fading boxes around mesh sections currently being processed. Amber: rebuild submitted; green: rebuild result applied; gray: both the previous and new positions are air in the real world and were skipped; blue: full placement rebuild. |
| `fastMoveDebugGizmoDuration` Debug gizmo duration (ms) | 800 | How long each debug box remains visible before fading out. |

## Building

- Single version: run `./gradlew build` in the corresponding branch or worktree.
- All versions: run `scripts/build-all.sh`. Generated JARs are collected in `dist/`.

See [PORTING.md](PORTING.md) for details on porting each version.

## Contributors

- Ryan100C (author)
- [wwwzzzxxx](https://github.com/wwwzzzxxx): ports for 1.16.5 – 1.21.11, render delay, and benchmarks
