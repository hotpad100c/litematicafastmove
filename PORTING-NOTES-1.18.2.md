# Port notes: Minecraft 1.18.2 (Litematica 0.11.7 / malilib 0.12.1)

The upstream branch targets Minecraft 26.3 with Litematica 0.29.1. This branch ports it to 1.18.2, the last
version before Minecraft's client rendering was reworked for 1.19 (`VertexConsumer`/`RenderType` still exist
here, but the buffer objects and the matrix type are the old ones). Base of the port is the 1.19.3 branch
(`port/1.19.3`, Litematica 0.13.2 / malilib 0.14.1-pre.1).

## Build

- `net.fabricmc.fabric-loom-remap` 1.18.1, official Mojang mappings, Java release 17.
- Fabric API 0.77.0+1.18.2, loader 0.19.5, EXIT 0.1.0+1.18.2 (published from the matching EXIT worktree;
  `publishToMavenLocal` succeeded there without the duplicate-artifact workaround).
- Litematica 0.11.7 (`maven.modrinth:bEpr0Arc:pF33JBAV`), malilib 0.12.1 (`maven.modrinth:malilib:V21ryIsV`).
- Iris/Sodium nested libraries extracted from their own jars for the dev runtime (`nestedLibraries`); the ids
  are the ones the EXIT 1.18.2 branch builds against (iris `ogIRhnAJ`, sodium `74Y5Z8fo`).
- `fabric.mod.json`: `minecraft ~1.18.2`, `java >=17`.

`./gradlew build` is green and `remapJar` reports no unresolved mixin target. Nothing has been run in a client
yet (machine policy: one client at a time, and a client run has to be announced).

## Minecraft 1.18.2 render-code work (the real part of this port)

1. **Matrices are Mojang's own, not JOML.** `org.joml.Matrix4f` → `com.mojang.math.Matrix4f` in
   `PlacementMeshRenderer`, `MixinLitematicaRenderer`, `MixinRenderHandler`, `MixinWorldRendererSchematic`.
   Litematica 0.11.7's own signatures use `com.mojang.math.Matrix4f` too, so only the type name changed.
2. **A finished `BufferBuilder` *is* the buffer.** 1.18.2 has no `BufferBuilder.RenderedBuffer`: `end()`
   returns `void`, the data stays inside the builder's direct `ByteBuffer`, and `VertexBuffer#upload` takes the
   `BufferBuilder` itself and pops the data on the render thread (`VertexBuffer#upload_`). So:
   - `PlacementMesh.CpuLayer` holds the ended `BufferBuilder`; `free()` became a no-op (there is no pooled
     buffer to hand back, the direct `ByteBuffer` dies with the builder).
   - `MeshUploader` uploads with `vertexBuffer.upload(layer.buffer())` (unchanged call, new meaning).
   - `TranslucentSorter` is `begin → restoreSortState → setQuadSortOrigin → end → vertexBuffer.upload(builder)`;
     this is exactly 1.18.2's own vanilla re-sort (`ChunkRenderDispatcher.RenderChunk.ResortTransparencyTask`),
     which produces an *index-only* buffer from the retained quad centres and lets `VertexBuffer#upload_`
     update only the index buffer. The 1.19.3 code could pass `end()`'s return value around; here `end()` gives
     nothing back.
3. **Reading the draw state needs an accessor, because 1.18.2 has no handle on finished buffer data before the
   upload pops it.** `MeshSectionBuilder` needs the index count, the byte size (upload budget) and the raw
   vertex bytes (the content hash that lets a rebuild keep the old GPU buffer when the geometry is unchanged).
   `MeshSectionBuilder` builds on a worker thread and `popNextBuffer()` cannot be used to read them without
   destroying the buffer the upload needs, so the new `BufferBuilderAccessor` mixin exposes
   `drawStates` / `totalRenderedBytes` / `buffer` and the metrics are read on the builder thread right after
   `end()`. This is the one new mixin on this branch; it only adds read-only accessors.
4. **`ClientLevel#setServerVerifiedBlockState` does not exist in 1.18.2.** 1.19.3 routes both the single-block
   update and the section-blocks update through it. In 1.18.2 the single-block update goes through
   `ClientLevel#setKnownState` and the section update calls `Level#setBlock` directly, so hooking either would
   miss half of the updates. `MixinClientLevel` now injects into `ClientLevel#setBlocksDirty`, which `Level`
   leaves empty, `ClientLevel` overrides, and which both paths reach — and only when the state really changed.
5. **`ChunkPos` and `VertexBuffer`.** `net.minecraft.world.level.ChunkPos` with `getX(long)`/`getZ(long)`;
   `new VertexBuffer()` without a `Usage`; `vertexFormat.clearBufferState()` after drawing (there is no
   `RenderType#clearBufferState` in 1.18.2).

## Litematica 0.11.7 / malilib 0.12.1 API differences

- `SchematicPlacementManager#getPlacementPartsInChunk(chunkX, chunkZ)` does not exist. 0.11.7 keeps its
  placement parts per 16^3 sub-chunk (`getAllPlacementsTouchingSubChunk(SubChunkPos)`), which is also how its
  own `ChunkRendererSchematicVbo#rebuildWorldView` fills the box list. `MixinChunkRendererSchematicVbo` uses
  that lookup with the renderer's own sub-chunk and the public `PlacementPart#getPlacement()` / `#getBox()`
  getters (the fields are private in 0.11.7).
- The overlay pass has one public entry point: `WorldRendererSchematic#renderBlockOverlays(PoseStack, Camera,
  Matrix4f)` calls the per-type `renderBlockOverlay` twice (OUTLINE, then QUAD). That per-type method is
  *private* in 0.11.7 where 0.13.2 made it protected, so the mixin wraps the public method and draws the
  detached overlays for both types after Litematica's own two passes instead of interleaved per type. Same
  drawing order, one EXIT portal.
- Chunk rebuilds are scheduled per section: `WorldRendererSchematic#scheduleChunkRenders(chunkX, chunkY,
  chunkZ)` has three arguments, and `WorldSchematic#scheduleChunkRenders(chunkX, chunkZ)` is the whole-column
  overload 0.13.2 introduced at the renderer. `PlacementBlockEntities` uses the latter.
- Everything else the mod touches exists unchanged: `ChunkRendererSchematicVbo` with `boxes` / `position` /
  `schematicWorldView` / `rebuildWorldView` (private) / `rebuildChunk` / `resortTransparency` +
  `OverlayRenderType`, `ChunkRenderDataSchematic#addBlockEntity` + `setTimeBuilt`,
  `WorldRendererSchematic#renderBlockLayer(RenderType, PoseStack, Camera, Matrix4f)` +
  `setupTerrain(Camera, Frustum, int, boolean)` + `renderEntities(Camera, Frustum, PoseStack, float)` +
  `loadRenderers`, `LitematicaRenderer#piecewiseRenderOverlay/Entities`,
  `event.RenderHandler#onRenderWorldLast`, `util.SchematicWorldRefresher#updateAll`,
  `SchematicPlacementManager#processQueuedChunks` with `chunkRebuildQueue` / `chunksToRebuild` /
  `chunksPreChange`, `SchematicVerifier#verifyChunks/reset/checkChangedPositions`, `GuiConfigs#getConfigs`,
  `Configs#loadFromFile/saveToFile`, and malilib's `LayerRange#isPositionAtRenderEdgeOnSide`. All of them were
  verified in the remapped 0.11.7 / 0.12.1 jars with `javap -p`.

## Dropped, because the 0.11.7 / 0.12.1 feature does not exist

Same list as 1.20.4 / 1.20.1 / 1.19.3 — intentional removals, not silent losses:

- `MixinIRISCompat` — no `fi.dy.masa.litematica.compat.iris.IrisCompat`; EXIT routes the drawing.
- `MixinLegacyTerrainFixUniform` — no `render.uniform.LegacyTerrainFixUniform`.
- `MixinRenderUtils.mainTarget` — malilib 0.12.1 has no `mainTarget()`.
- `MixinFluidModelRendererSchematic` — no `FluidModelRendererSchematic`.
- `BlockModelCacheSchematicAccessor` — no `BlockModelCacheSchematic`.
- `MixinPlacementManagerTaskRebuild` / `PlacementManagerTaskAccessor` — 0.11.7 rebuilds schematic chunks in
  `processQueuedChunks`; the pending-move filter is applied there.
- Verifier: 0.11.7 has no `DIFF_BLOCK`, no ignored-block registry, no ignore-crop-age. The async verifier
  keeps the 0.11.7 rules (missing / extra / wrong block / wrong state, `ignoreExistingFluids`).
- Overlay: no `ENABLE_SCHEMATIC_OVERLAY_CULLING`, no different-block grouping, no `ENABLE_SCHEMATIC_FLUIDS`
  switch.
- No `onToggleMainRendering`; a pending move is committed by the tick timer, on schematic rebuild, on placement
  removal, on `clear`, and at the end of a move that no longer defers.
- Config GUI: the FastMove options are appended to Litematica's Generic tab; there is no "All" tab.

## Known limitations

Inherited from the 1.20.4 / 1.20.1 / 1.19.3 ports:

- Schematic block entities are registered through `ChunkRenderDataSchematic#addBlockEntity` only; the extra
  `setBlockEntities` set 0.11.7 uses for renderers that draw outside their bounding box cannot be populated
  from outside `rebuildChunk` without local variable tables.
- Detached meshes are drawn per layer after Litematica's own chunks of that layer (and, for overlays, after
  both of Litematica's overlay passes); translucent detached sections are sorted back to front among
  themselves.
- Uploaded vertex data now lives in `BufferBuilder`-owned direct buffers until the render thread pops them, so
  the upload budget is the only back-pressure on native buffer memory; it is unchanged from 1.19.3, but on
  1.18.2 there is no allocator to return freed buffers to.

## Not verified here

`./gradlew build` is green. Mixin application and in-game behaviour still need a `runClient` smoke test.

## Cross-version note (not introduced by this branch)

The mod's MC-targeting mixins all use `remap = false` and the project declares no mixin refmap (upstream
targets Minecraft 26.3, where class and member names are not obfuscated). On obfuscated versions the *target
class* is still remapped by Loom (it is a class constant), while method/field *names* in the annotation strings
are not, so mixins into Minecraft classes will not resolve in production builds. This affects the 1.19.3 and
newer branches in the same way; fixing it means adding a refmap to `litematicafastmove.mixins.json` and is left
outside this port. Within this branch, target names were chosen so that they resolve in the development runtime
and `remapJar` reports no unresolved target.
