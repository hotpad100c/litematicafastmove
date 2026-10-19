# 1.21.4 port notes

Branch `port/1.21.4`, base `feat/render-delay` (Minecraft 26.3).

| | 26.3 (upstream) | 1.20.6 (previous port) | 1.21.4 (this branch) |
|---|---|---|---|
| Minecraft | 26.3 | 1.20.6 | 1.21.4 (obfuscated) |
| Loom | `net.fabricmc.fabric-loom` 1.18-SNAPSHOT | `fabric-loom-remap` 1.18.1 | `fabric-loom-remap` 1.18.1 |
| Mappings | none (26.3 is deobfuscated) | `loom.officialMojangMappings()` | `loom.officialMojangMappings()` |
| Java | 25 | 21 | 21 |
| Fabric API | 0.162.0+26.3 | 0.100.8+1.20.6 | 0.119.4+1.21.4 |
| Litematica | 0.29.1 | 0.18.3 | 0.21.7 (`Z7jnVo9V`) |
| malilib | 0.30.2 | 0.19.2 | 0.23.5 (`ifi4uRpL`) |
| EXIT | 0.1.0+26.3 | 0.1.0+1.20.6 | 0.1.0+1.21.4 |

`./gradlew build` is green. Nothing has been run in a client yet.

## How the port is built

The 1.20.6 port is the starting point. 1.21.4 has the 1.21.2 buffer rework, so the mesh upload/draw half was
moved onto those primitives, otherwise unchanged:

- build: one `ByteBufferBuilder` + one `BufferBuilder` per section-layer; the builder finishes with
  `MeshData` (`BufferBuilder#build()`, null for an empty layer) and `MeshData#sortQuads(allocator, sorting)`
  for the translucent/overlay-QUAD layers, which is exactly what Litematica 0.21.7 does in
  `ChunkRendererSchematicVbo#postRenderBlocks`;
- upload: `VertexBuffer(BufferUsage.STATIC_WRITE)` + `bind/upload/unbind` on the render thread, within the same
  per-frame byte/time budget; `VertexBuffer#upload` closes the `MeshData` itself;
- translucency: keep the `MeshData.SortState`, and re-sort with
  `SortState#buildSortedIndexBuffer(allocator, sorting)` + `VertexBuffer#uploadIndexBuffer` on a sorter thread
  with a temporary allocator — the same pattern as `SectionRenderDispatcher.RenderSection.ResortTransparencyTask`;
- draw: Litematica 0.21.7 still draws one `RenderType` at a time, so the hook stays on
  `WorldRendererSchematic#renderBlockLayer` (RETURN) / `#renderBlockOverlay` (RETURN); the buffers are drawn with
  `VertexBuffer#bind` + `drawWithShader(Matrix4f, Matrix4f, CompiledShaderProgram)` + `unbind`, like Litematica
  itself does;
- overlays: `OverlayRenderType` (now a top-level class) is used with Litematica's own
  `RenderUtils#drawBlockModelQuadOverlayBatched` / `#drawBlockModelOutlinesBatched` (both take a `RandomSource`
  since 0.21.x) and malilib's batched box helpers;
- EXIT integration is unchanged from 1.20.6: `ExitChannel#batch(MultiBufferSource)` + portal.

The "fast move" logic itself (move deferral, interpolation, render delay, detached meshes, clip rebuilds,
async verifier) is unchanged.

## API renames that are pure porting work

- `new ResourceLocation(ns, path)` → `ResourceLocation.fromNamespaceAndPath(ns, path)`.
- `LevelHeightAccessor#getMinBuildHeight` → `getMinY`.
- `BufferBuilder#begin/setQuadSorting/getSortState/endOrDiscardIfEmpty/release` and
  `BufferBuilder.RenderedBuffer/DrawState/SortState` → `ByteBufferBuilder` + `BufferBuilder(allocator, mode,
  format)` + `MeshData`/`MeshData.SortState`/`MeshData.DrawState` + `ByteBufferBuilder.Result`.
- `VertexBuffer.Usage.STATIC` → `BufferUsage.STATIC_WRITE`.
- `ShaderInstance` → `CompiledShaderProgram`; the position-color shader is now a key,
  `RenderSystem.setShader(CoreShaders.POSITION_COLOR)`; `GameRenderer#getPositionColorShader` is gone.
- `Tesselator#getBuilder` → `Tesselator#begin(mode, format)`; `BufferBuilder#vertex(...).color(...).endVertex()`
  → `addVertex(x, y, z).setColor(r, g, b, a)`; `BufferBuilder#end()` → `build()` / `buildOrNull`
  (`BufferUploader.drawWithShader(MeshData)`).
- `BlockState#isLiquid()` → `BlockState#liquid()`; `Block#shouldDrawSide(state, neighborState, side)` →
  `Block#shouldRenderFace(state, neighborState, side)`.
- `ChunkRendererSchematicVbo.OverlayRenderType` → top-level
  `fi.dy.masa.litematica.render.schematic.OverlayRenderType`.
- Every `LitematicaRenderer#piecewiseRender*` (and `renderSchematicOverlay`) now takes a `ProfilerFiller`, and
  0.21.7 added `piecewiseRenderTripwire`, which is routed like the other layers.
- `WorldRendererSchematic#renderBlockLayer` takes the profiler and the `CompiledShaderProgram` as well;
  `#setupTerrain` / `#loadRenderers` / `#renderBlockOverlay` take the profiler; the block entities moved from
  `#renderEntities` into their own `#renderBlockEntities(Camera, Frustum, PoseStack, BufferSource, BufferSource,
  float, ProfilerFiller)`, so the detached block-entity hook and the block-entity shift wrap moved there.
- `RenderHandler#onRenderWorldLast(Matrix4f, Matrix4f)` → `#onRenderWorldLastAdvanced(Matrix4f, Matrix4f, Frustum,
  Camera, FogParameters, ProfilerFiller)` (malilib's advanced event).
- `ChunkRendererSchematicVbo#rebuildChunk` / `#resortTransparency` take the profiler;
  `SchematicVerifier#verifyChunks` / `#checkChangedPositions` take the profiler.
- `EntityRenderDispatcher#render` lost its yaw parameter (8 args now).

## Features that came back compared to 1.20.6 (0.21.7 has them again)

1. **`IgnoreBlockRegistry` and `MismatchType.DIFF_BLOCK` (Ported).** The async verifier and the overlay builder
   now consult `Configs.Generic.ENABLE_DIFFERENT_BLOCKS` + malilib's `BlockUtils.isInSameGroup/matchPropertiesOnly`
   and the ignore-block registry, and the mixin writes the new `diffBlocksPositions` map.
2. **`ENABLE_SCHEMATIC_OVERLAY_CULLING` (Ported).** `OverlayMeshBuilder#shouldCullOverlayPos` is the 0.21.7
   implementation.
3. **`ENABLE_SCHEMATIC_FLUIDS` (Ported).** Fluid tessellation and the overlay fluid cull now honour the option
   again instead of the 1.20.6 "always on".
4. **malilib config translations (Changed).** 0.23.5 has `IConfigBase#apply(translationPrefix)`, so
   `FastMoveConfig` uses it and the lang files carry `litematicafastmove.config.{name,prettyName,comment}.<option>`
   instead of the 1.20.6 bare-name style.

## Things that could not be ported as-is (and why)

1. **`MixinIRISCompat` (Dropped).** 0.21.7 *has* `fi.dy.masa.litematica.compat.iris.IrisCompat`, but it no longer
   registers render pipelines: it only probes `IrisApi` via `MethodHandles` and exposes
   `isShaderActive`/`isShadowPassActive`. There is no `registerPipelines` left to suppress.
2. **`MixinRenderUtils` (Dropped).** malilib 0.23.5 has no `RenderUtils#mainTarget`. The hook had nothing to
   replace (EXIT's portal redirects the framebuffer).
3. **`MixinLegacyTerrainFixUniform` (Dropped).** `fi.dy.masa.litematica.render.uniform.LegacyTerrainFixUniform`
   does not exist in 0.21.7.
4. **`MixinFluidModelRendererSchematic` (Dropped).** `FluidModelRendererSchematic` does not exist in 0.21.7;
   fluids still go through `BlockRenderDispatcher#renderLiquid`.
5. **`BlockModelCacheSchematicAccessor` (Dropped).** No `BlockModelCacheSchematic` in 0.21.7; models come from
   `BlockRenderDispatcher#getBlockModel`.
6. **`MixinPlacementManagerTaskRebuild` / `PlacementManagerTaskAccessor` (Replaced).** 0.21.7 still has no
   `PlacementManagerTask`; the deferral stays on `SchematicPlacementManager#markChunkForRebuild(ChunkPos)` and
   `#markChunksForRebuild(Collection)`, exactly as in the 1.20.6 port.
7. **`onToggleMainRendering` hook (Replaced).** `SchematicWorldRefresher#updateAll` still drives the commit-all.

## Assumptions / things to watch in the client run

- `MeshData#sortQuads` is called on the build thread; the sorted index buffer is what gets uploaded first. The
  later re-sorts use a fresh temporary `ByteBufferBuilder` on the sorter thread.
- `renderLayer`/`drawOverlays` re-read `RenderSystem.getShader()` after `RenderType#setupRenderState()`; the shader
  can be null in theory, the draws are skipped then.
- The detached block entities are rendered at the RETURN of `renderBlockEntities` into the vanilla
  `renderBuffers().bufferSource()`, wrapped by `SchematicExit.batch` (same approach as 1.20.6).
- `fabricloader >=0.16.8` in `fabric.mod.json` is still the 1.20.6 guess for the MixinExtras `@WrapMethod`
  requirement; the development runs use loader 0.19.5.
- Iris/Sodium are `modLocalRuntime` (their intermediary class tweakers break on a named dev runtime), the EXIT
  dependency is `ml.mypals.exit:emergency_exit-fabric`, and there is no hardcoded glsl-transformer version: the
  nested libraries are extracted from the Iris/Sodium jars by the `nestedLibraries` task.
