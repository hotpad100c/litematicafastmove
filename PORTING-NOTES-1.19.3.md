# Port notes: Minecraft 1.19.3 (Litematica 0.13.2 / malilib 0.14.1-pre.1)

The upstream branch targets Minecraft 26.3 with Litematica 0.29.1. This branch ports it to 1.19.3, the oldest
target with the Litematica `ChunkRendererSchematicVbo` design. Base of the port is the 1.20.1 branch
(`port/1.20.1`, Litematica 0.15.4 / malilib 0.16.3), which in turn comes from `port/1.20.4`.

Litematica 0.13.2 has the same renderer design and, for everything this mod touches, the same API as 0.15.4:
`ChunkRendererSchematicVbo` with `boxes` / `position` / `schematicWorldView` / `rebuildWorldView` /
`rebuildChunk` / `resortTransparency`, `ChunkRenderDataSchematic#addBlockEntity` + `setTimeBuilt`,
`WorldRendererSchematic#renderBlockLayer(RenderType, PoseStack, Camera, Matrix4f)` +
`renderBlockOverlay(...)` + `setupTerrain(Camera, Frustum, int, boolean)` + `renderEntities(...)`,
`SchematicPlacementManager#processQueuedChunks` with `chunkRebuildQueue` / `chunksToRebuild` /
`chunksPreChange`, `LitematicaRenderer#piecewiseRenderOverlay/Entities`, `RenderHandler#onRenderWorldLast`,
`util.SchematicWorldRefresher#updateAll`, `SchematicVerifier#verifyChunks/reset/checkChangedPositions` and
`GuiConfigs#getConfigs`. All of them were verified in the 0.13.2 jar with `javap`. malilib 0.14.1-pre.1 also
has `LayerRange` in `fi.dy.masa.malilib.util`, so the same `@At` target fix as on 1.20.1 applies.

## Build

- `net.fabricmc.fabric-loom-remap` 1.18.1, official Mojang mappings, Java release 17.
- Fabric API 0.76.1+1.19.3, loader 0.19.5, EXIT 0.1.0+1.19.3 (Fabric-only, Iris has no NeoForge build here).
- Litematica 0.13.2 (`maven.modrinth:bEpr0Arc:Vj4qf8W5`), malilib 0.14.1-pre.1
  (`maven.modrinth:malilib:vEQ2PjZV`).
- Iris/Sodium nested libraries extracted from their own jars for the dev runtime (`nestedLibraries`).
- `fabric.mod.json`: `minecraft ~1.19.3`, `java >=17`.

`./gradlew build` is green. Nothing has been run in a client yet (machine policy: one client at a time, and a
client run has to be announced).

## Minecraft 1.19.3 render-code work (the real part of this port)

Minecraft's buffer API here is not yet the 1.20 one — it is one step earlier, and it is the API Litematica
0.13.2 itself uses. Five compile errors, all in the mesh layer; nothing else in the codebase differs.

1. **Quad sorting.** 1.19.3 has no `com.mojang.blaze3d.vertex.VertexSorting`. `BufferBuilder` sorts around a
   point instead: `setQuadSortOrigin(float, float, float)`, together with `getSortState()` /
   `restoreSortState(SortState)`. That is exactly the vanilla chunk resort
   (`ChunkRenderDispatcher.RenderChunk.ResortTransparencyTask`: `restoreSortState` → `setQuadSortOrigin` →
   `getSortState` → `end`). `MeshSectionBuilder` (first build) and `TranslucentSorter` (re-sort) now call
   `setQuadSortOrigin(cameraX, cameraY, cameraZ)` where the 1.20 branches called
   `setQuadSorting(VertexSorting.byDistance(...))` — the sort origin has the same meaning (the camera in the
   section-relative space the section's vertices are in), and `setQuadSortOrigin` computes the quad centres
   itself the first time.
2. **`VertexBuffer` construction.** 1.19.3's `VertexBuffer` has no `Usage`: `new VertexBuffer()` instead of
   `new VertexBuffer(VertexBuffer.Usage.STATIC)`. `upload(BufferBuilder.RenderedBuffer)`, `bind()`,
   `drawWithShader(...)` and `close()` are the same as on 1.20.

## Dropped, because the 0.13.2 feature does not exist

Same list as 1.20.4 / 1.20.1 — intentional removals, not silent losses:

- `MixinIRISCompat` — no `fi.dy.masa.litematica.compat.iris.IrisCompat`; EXIT routes the drawing.
- `MixinLegacyTerrainFixUniform` — no `render.uniform.LegacyTerrainFixUniform`.
- `MixinRenderUtils.mainTarget` — malilib 0.14.1 has no `mainTarget()`.
- `MixinFluidModelRendererSchematic` — no `FluidModelRendererSchematic`.
- `BlockModelCacheSchematicAccessor` — no `BlockModelCacheSchematic`.
- `MixinPlacementManagerTaskRebuild` / `PlacementManagerTaskAccessor` — 0.13.2 rebuilds chunks in
  `processQueuedChunks`; the pending-move filter is applied there.
- Verifier: 0.13.2 has no `DIFF_BLOCK`, no ignored-block registry, no ignore-crop-age. The async verifier
  keeps the 0.13.2 rules (missing / extra / wrong block / wrong state, `ignoreExistingFluids`).
- Overlay: no `ENABLE_SCHEMATIC_OVERLAY_CULLING`, no different-block grouping, no `ENABLE_SCHEMATIC_FLUIDS`
  switch.
- No `onToggleMainRendering`; a pending move is committed by the tick timer, on schematic rebuild, on
  placement removal, on `clear`, and at the end of a move that no longer defers.
- Config GUI: the FastMove options are appended to Litematica's Generic tab; there is no "All" tab.

## Known limitations

Inherited from the 1.20.4 / 1.20.1 ports:

- Schematic block entities are registered through `ChunkRenderDataSchematic#addBlockEntity` only; the extra
  `setBlockEntities` set 0.13.2 uses for renderers that draw outside their bounding box cannot be populated
  from outside `rebuildChunk` without local variable tables.
- Detached meshes are drawn per layer after Litematica's own chunks of that layer; translucent detached
  sections are sorted back to front among themselves.

## Not verified here

`./gradlew build` is green. Mixin application and in-game behaviour still need a `runClient` smoke test.
