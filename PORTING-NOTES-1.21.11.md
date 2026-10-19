# 1.21.11 port notes

Branch `port/1.21.11`, base `feat/render-delay` (Minecraft 26.3).

| | 26.3 (upstream) | 1.21.11 (this branch) |
|---|---|---|
| Minecraft | 26.3 | 1.21.11 (obfuscated) |
| Loom | `net.fabricmc.fabric-loom` 1.18-SNAPSHOT | `net.fabricmc.fabric-loom-remap` 1.18.1 |
| Mappings | none (26.3 is deobfuscated) | `loom.officialMojangMappings()` |
| Java | 25 | 21 |
| Fabric API | 0.162.0+26.3 | 0.141.6+1.21.11 |
| Litematica | 0.29.1 | 0.26.16 |
| malilib | 0.30.2 | 0.27.20 |
| EXIT | 0.1.0+26.3 | 0.1.0+1.21.11 |

`./gradlew build` is green. Nothing has been run in a client yet.

1.21.11 is much closer to 26.3 than 1.20.6 is: it already has the GPU-device renderer (`GpuBuffer`,
`RenderPass`, `SubmitNodeCollector`, submit/render states, `ChunkSectionLayer`), so the mesh code kept its
shape. What moved is where those classes live, how a draw is described, and how Litematica hands out the
frame buffer.

## Names that moved between 26.3 and 1.21.11

| 26.3 | 1.21.11 |
|---|---|
| `com.mojang.renderpearl.api.buffers.GpuBuffer(Slice)` | `com.mojang.blaze3d.buffers.GpuBuffer(Slice)` |
| `com.mojang.renderpearl.api.device.GpuDevice` | `com.mojang.blaze3d.systems.GpuDevice` |
| `com.mojang.renderpearl.api.commands.RenderPass` | `com.mojang.blaze3d.systems.RenderPass` |
| `com.mojang.renderpearl.api.pipeline.IndexType` / `PrimitiveTopology` | `VertexFormat.IndexType` / `VertexFormat.Mode` |
| `com.mojang.renderpearl.api.textures.GpuTextureView` | `com.mojang.blaze3d.textures.GpuTextureView` |
| `net.minecraft.client.renderer.DynamicGpuData.Transform` | `net.minecraft.client.renderer.DynamicUniforms.Transform` |
| `net.minecraft.client.renderer.state.level.{Level,Camera}RenderState` | `net.minecraft.client.renderer.state.{Level,Camera}RenderState` |
| `...block.dispatch.{BlockStateModel,BlockStateModelPart}` | `...block.model.{BlockStateModel,BlockModelPart}` |
| `net.minecraft.client.renderer.block.BlockAndTintGetter` | `net.minecraft.world.level.BlockAndTintGetter` |
| `fi.dy.masa.malilib.util.position.LayerRange` | `fi.dy.masa.malilib.util.LayerRange` |
| `fi.dy.masa.malilib.util.position.IntBoundingBox` | `fi.dy.masa.malilib.util.IntBoundingBox` |
| `LayerRange#getMinLayerBoundary/#getMaxLayerBoundary` | `#getLayerMin/#getLayerMax` |
| malilib `RenderUtils#mainTarget()` | malilib `RenderUtils#fb()` |
| Litematica `render.uniform.LegacyTerrainFixUniform` | malilib `render.uniform.ChunkFixUniform` |
| Litematica `compat.iris.IrisCompat` | malilib `compat.iris.IrisCompat` |
| `LevelRenderEvents.END_MAIN` (Fabric API) | `WorldRenderEvents.END_MAIN` (now in the `...rendering.v1.world` package) |
| `ChunkPos.pack/unpack`, `pos.x()/z()`, `pos.pack()` | `ChunkPos.asLong`, `new ChunkPos(long)`, `pos.x/z`, `pos.toLong()` |
| `LevelRenderer#mainCamera()` | `GameRenderer#getMainCamera()` |
| `ClientboundLevelChunkWithLightPacket#x()/z()` | `#getX()/getZ()` |
| `SchematicVerifier.BlockMismatch#stateFound()` | public field `stateFound` |

Draw description: 1.21.11's `RenderPass.Draw` has one parameter less (no `baseVertex`; the record is
`slot, vertexBuffer, indexBuffer, indexType, firstIndex, indexCount, uploaderConsumer`) and the consumer is
called with `UniformUploader#upload(String, GpuBufferSlice)` instead of `#setUniform`. `RenderSystem` has no
`getCompiledPipeline` any more, so `RenderPass#setPipeline` takes the `RenderPipeline` itself, and
`getModelViewMatrixCopy()` is gone (`new Matrix4f(RenderSystem.getModelViewMatrix())`).

## Where the frame buffer comes from now

26.3 passed a `RenderTarget` into `LitematicaRenderer#piecewiseDrawBlockLayerGroup`; 1.21.11 passes a
`ChunkSectionLayerGroup` whose internal `ChunkRenderBatchDraw#draw` asks `group.outputTarget()`. So:

- the terrain portal (`MixinLitematicaRenderer`) wraps the group call and flushes the batch after the opaque
  group, like before;
- the new `MixinChunkRenderBatchDraw` redirects `ChunkSectionLayerGroup#outputTarget()` to EXIT's target for
  the groups whose target is not the main one (`TRANSLUCENT` → `LevelRenderer#getTranslucentTarget`,
  `TRIPWIRE` → `#getWeatherTarget`); the opaque group is the main target, which EXIT's portal already moves;
- the overlay path takes the frame buffer from malilib's `RenderUtils#fb()`, so `MixinRenderUtils` now wraps
  that method instead of `mainTarget()`;
- `RenderHandler#onRenderWorldLast` is `#onRenderWorldLastAdvanced` here, with the same portal.

## Tessellation

`BlockModelCacheSchematic`, `FluidModelRendererSchematic`, `IBlockOutputSchematic` and
`BlockModelRendererSchematic#tessellateBlock` do not exist in 0.26.16. Litematica now tessellates per
section-layer into a `BufferBuilder` taken from `ChunkSectionLayer#pipeline()`:

- blocks: own `BlockModelRendererSchematic(mc.getBlockColors(), mc.getBlockRenderer())`, models from
  `BlockRenderDispatcher#getBlockModel(state).collectParts(random)`, seed via `setSeed`,
  `renderModel(view, parts, state, pos, matrices, builder, false, OverlayTexture.NO_OVERLAY)`, matrix
  translated by `(x&15, y&15, z&15)` — section-local vertices, the same coordinates the draw transform
  expects. The matrix starts at the section because these mesh sections are 16³ and 16-aligned; Litematica's
  own chunks are taller, which is what its `IBufferBuilderPatch#litematica$setOffsetY` hack is for (offset 0
  for us, so it is not used).
- fluids: `BlockRenderDispatcher#renderLiquid` writes `(x&15, y&15, z&15)` itself.
- translucent sorting is decided by `ChunkSectionLayer#sortOnUpload()` (`translucent()` is gone), and
  `MeshData.SortState` no longer writes into a caller buffer:
  `buildSortedIndexBuffer(ByteBufferBuilder, VertexSorting)` returns a `ByteBufferBuilder.Result`, which the
  sorter thread copies into its own `MemoryUtil` buffer.

## Things that could not be ported as-is (and why)

1. **`MixinFluidModelRendererSchematic` (Dropped).** There is no `FluidModelRendererSchematic` in 0.26.16;
  fluids go through vanilla `BlockRenderDispatcher#renderLiquid` / `LiquidBlockRenderer`, which never consults
  malilib's layer range, so there is no range-edge test left to patch on the builder thread. The block
  equivalent (`MixinBlockModelRendererSchematic` → `BlockModelRendererSchematic#shouldRenderModelSide`) is
  kept and retargeted to `fi.dy.masa.malilib.util.LayerRange`.
2. **`BlockModelCacheSchematicAccessor` (Dropped).** No `BlockModelCacheSchematic` / `FluidStateModelSet` in
  0.26.16; models come from `BlockRenderDispatcher#getBlockModel`.
3. **`MixinIRISCompat` (Retargeted).** Litematica's iris compat moved into malilib
  (`fi.dy.masa.malilib.compat.iris.IrisCompat`), so the mixin suppresses `registerPipelines` there.
4. **`MixinLegacyTerrainFixUniform` (Retargeted).** The shader-fix uniform is malilib's `ChunkFixUniform`;
  the "shaders on" flag it writes is wrapped the same way (`isShaderActive() && !SchematicExit.isActive()`).
5. **`MixinRenderUtils` (Retargeted).** `mainTarget()` → `fb()` (see above).

Everything else ported 1:1 — no feature of the 26.3 branch is missing on this version.

## Assumptions / things to watch in the client run

- `BlockModelRendererSchematic` is instantiated per build (as on 26.3) instead of reusing the world
  renderer's instance: the shared instance's `getProfiler()` can be null before the first rendered frame and
  Litematica's `renderBlock` would NPE, and `LitematicaRenderer#getWorldRenderer()` creates the renderer as a
  side effect. The per-instance cost is negligible; `enableCache/disableCache` are static and per-thread.
- Model-part collection and the AO brightness cache use per-thread `RandomSource`s.
- `MixinWorldRendererSchematic#appendDetachedPlacementDraws` still injects at the `startedDrawing` load in
  `prepareBlockLayers`; 0.26.16 has the same local names (`transformValues`, `renderMap`, `colorMod`,
  `texMatrix`, `indexCount`). 0.26.16 declares the first three as the concrete `ArrayList` / `EnumMap` /
  `Vector4f`, not as the `List` / `Map` / `Vector4fc` interfaces 0.29.1 uses, and MixinExtras matches a
  `@Local(name = …)` parameter against the local's type **exactly** (Mixin's
  `LocalVariableDiscriminator.findExplicitLocal` uses `Type.equals`, so no supertype is accepted) - the sugar
  parameters therefore carry the concrete types, otherwise the client dies with
  `Unable to find matching local!` as soon as `WorldRendererSchematic` is transformed.
- `fabricloader >=0.16.8` in `fabric.mod.json` follows the 1.20.6 port (the dev runs use loader 0.19.5).
- Iris/Sodium nested libraries are extracted from their jars at build time (EXIT's `nestedLibraries` task), so
  no glsl-transformer version is hardcoded.
