# Port notes: Minecraft 1.20.4 (Litematica 0.17.4 / malilib 0.18.3)

The upstream branch targets Minecraft 26.3 with Litematica 0.29.1. This branch ports it to 1.20.4, where
Litematica's chunk renderer is still the old `ChunkRendererSchematicVbo` / `BufferBuilderCache` design and
Minecraft's renderer is the immediate-mode `RenderType` + `BufferBuilder` + `VertexBuffer` one.

## Build

- `net.fabricmc.fabric-loom-remap` 1.18.1, official Mojang mappings (1.20.4 is obfuscated, unlike 26.3).
- Litematica/malilib/EXIT are `modImplementation` so their intermediary names are remapped to Mojang names.
- Java release 17.

## Render path

Litematica 0.17.4 draws its chunks per `RenderType` in `WorldRendererSchematic#renderBlockLayer`, binding one
`VertexBuffer` per chunk and offsetting it with `ShaderInstance.CHUNK_OFFSET`. The detached placement meshes are
appended to exactly that path: `MixinWorldRendererSchematic` wraps `renderBlockLayer` in an EXIT portal, calls the
original and then draws the detached sections for the same layer with the same shader and offset uniform.

`Private` details that shaped the port:

- `MeshSectionBuilder` builds one `BufferBuilder` per `RenderType` per section and keeps the `RenderedBuffer`
  (1.20.4 name; 1.21+ calls it `MeshData`) until `MeshUploader` uploads it to a `VertexBuffer` on the render thread.
  There is no `ByteBufferBuilder`/`MeshData` copy step as in 26.3.
- Translucent re-sorting cannot run on a worker thread: `BufferBuilder` owns the sorting points, and re-sorting is
  `begin` + `restoreSortState` + `setQuadSorting` + `end` (index-only) on the render thread. `TranslucentSorter`
  therefore re-sorts a bounded number of sections per frame synchronously, like Litematica's own
  `resortTransparency`. Content hashing still avoids re-uploading unchanged sections.
- Overlays use 0.17.4's `RenderUtils` helpers with `BakedModel` instead of the 0.29 `BlockStateModelPart` API.

## Litematica 0.17.4 API differences

- `PlacementManagerTaskRebuild` does not exist. Chunk rebuilds happen directly in
  `SchematicPlacementManager#processQueuedChunks`, so the freeze of chunks whose schematic data is still at the
  pre-move position is implemented by filtering `chunkRebuildQueue` / `chunksToRebuild` at the head of that method.
- No `onToggleMainRendering`; a pending move is committed by the tick timer (`commitSettled`), on schematic rebuild,
  on placement removal, on `clear`, and on the end of a move that no longer defers.
- `fi.dy.masa.malilib.util.position.*` is `fi.dy.masa.malilib.util.*`; `IntBoundingBox` exposes public fields,
  `LayerRange` has `getLayerMin()/getLayerMax()` instead of `getMinLayerBoundary()/getMaxLayerBoundary()`.
- malilib 0.18.3 has no per-option translation-key prefix (`apply(...)`). Display names come from
  `config.name.<lowercase name>` and comments from `config.comment.<lowercase name>`; those keys were added to the
  lang files next to the existing ones. The config GUI appends the options to the Generic tab only (0.17.4 has no
  "All" tab).
- `Configs.loadFromFile/saveToFile` are hooked with `@WrapOperation` on the `ConfigUtils` calls instead of an
  ordinal + `@Local`, because Litematica's published jar has no local variable tables (so `@Local` cannot be used).

## Dropped, because the 0.17.4 feature does not exist

These are intentional removals, not silent losses:

- `MixinIRISCompat` — 0.17.4 has no `fi.dy.masa.litematica.compat.iris.IrisCompat`.
- `MixinLegacyTerrainFixUniform` — no `render.uniform.LegacyTerrainFixUniform`; Iris handling goes through EXIT only.
- `MixinRenderUtils.mainTarget` — no `mainTarget()`; on 1.20.4 EXIT's portal redirects the bound framebuffer and
  `Minecraft.getMainRenderTarget()` itself.
- `MixinFluidModelRendererSchematic` — no `FluidModelRendererSchematic`; 0.17.4 renders fluids through
  `BlockRenderDispatcher#renderLiquid` without layer-range edge checks.
- `BlockModelCacheSchematicAccessor` — no `BlockModelCacheSchematic`; models come from `BlockRenderDispatcher`.
- `MixinPlacementManagerTaskRebuild` / `PlacementManagerTaskAccessor` — see above.
- Verifier: 0.17.4 has no `DIFF_BLOCK`, no ignored-block registry, no ignore-crop-age. The async verifier keeps the
  0.17.4 rules only (missing / extra / wrong block / wrong state, `ignoreExistingFluids`).
- Overlay: no `ENABLE_SCHEMATIC_OVERLAY_CULLING`, no different-block grouping, no `ENABLE_SCHEMATIC_FLUIDS` switch;
  behaviour matches 0.17.4's own overlay.

## Known limitations

- Schematic block entities are registered through `ChunkRenderDataSchematic#addBlockEntity` only. The extra
  `blockEntities` set that 0.17.4 uses for renderers that draw outside their bounding box cannot be populated from
  outside `rebuildChunk` without local variable tables, so those (rare) renderers may be culled at chunk edges for
  taken-over placements.
- Detached meshes are drawn per layer after Litematica's own chunks of that layer, not merged into their ordering.
  Opaque layers do not care; for translucent geometry the detached sections are sorted back to front among
  themselves.

## Not verified here

`./gradlew build` is green. No client was started from this worktree (machine policy: one client at a time, and a
client run has to be announced). Mixin application and in-game behaviour still need a `runClient` smoke test.
