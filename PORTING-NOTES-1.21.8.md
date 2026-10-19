# 1.21.8 port notes

Branch `port/1.21.8`, base `feat/render-delay` (Minecraft 26.3), started from the finished 1.21.11 port.

| | 26.3 (upstream) | 1.21.8 (this branch) |
|---|---|---|
| Minecraft | 26.3 | 1.21.8 (obfuscated) |
| Loom | `net.fabricmc.fabric-loom` 1.18-SNAPSHOT | `net.fabricmc.fabric-loom-remap` 1.18.1 |
| Mappings | none (26.3 is deobfuscated) | `loom.officialMojangMappings()` |
| Java | 25 | 21 |
| Fabric API | 0.162.0+26.3 | 0.136.1+1.21.8 |
| Litematica | 0.29.1 | 0.23.7 |
| malilib | 0.30.2 | 0.25.7 |
| EXIT | 0.1.0+26.3 | 0.1.0+1.21.8 |

`./gradlew build` is green. Nothing has been run in a client yet.

1.21.8 sits in between the two renderer generations: the **terrain** path is already the GPU-device one
(`GpuBuffer`, `RenderPass`, `ChunkSectionLayer`, chunk batch draws, `DynamicUniforms`), while **entities and
block entities** are still drawn through `MultiBufferSource` (no submit node collector, no
`LevelRenderState`). So this port is the 1.21.11 terrain/mesh half plus the 1.20.6 entity/placement half, with
its own tessellation API. `net.minecraft.resources.ResourceLocation` is still the old name here.

## Terrain half (as for 1.21.11, with 1.21.8 details)

- `LitematicaRenderer#piecewiseDrawBlockLayerGroup(ChunkSectionLayerGroup)` — the group carries the frame
  buffer (`outputTarget()`), so the opaque group is covered by EXIT's portal and the new
  `MixinChunkRenderBatchDraw` redirects the other two (translucent / weather targets).
- `WorldRendererSchematic#prepareBlockLayers` has the same locals as 1.21.11 (`startedDrawing`,
  `uniformValues`, `renderMap`, `colorVector`, `matrix4f`, `indexCount`), but `uniformValues` is called that
  instead of `transformValues`, and `DynamicUniforms.Transform` carries a `lineWidth` float (5-arg record).
- `RenderPass.Draw` is the 7-arg form with `UniformUploader#upload`; `RenderPipeline` goes straight into
  `RenderPass#setPipeline` (no `getCompiledPipeline`).
- Tessellation is Litematica's own per-layer `BufferBuilder` path:
  `BlockModelRendererSchematic(BlockColors)` + `setBakedManager(ModelManager)`,
  `getBakedModel(state).collectParts(random)`,
  `renderModel(view, parts, state, pos, matrices, builder, state.getSeed(pos))` and
  `renderLiquid(consumer, view, pos, state, fluidState)`. The matrix is translated by `(x&15, y&15, z&15)`,
  which is also what the fluid renderer writes by itself, so the mesh stays section-local.
  `ChunkSectionLayer#pipeline()` supplies the vertex format/mode, `sortOnUpload()` decides resorting and
  `MeshData.SortState#buildSortedIndexBuffer` produces the resorted indices.

## Entity / block-entity half (as for 1.20.6)

- `LitematicaRenderer#piecewiseRenderEntities` / `#piecewiseRenderBlockEntities` take `MultiBufferSource`s;
  the port wraps the calls into `WorldRendererSchematic` and hands EXIT's channel batch
  (`SchematicExit.batch`) in instead, so they render with the batch after the shader pack.
- Pending-move shifting is done on `EntityRenderDispatcher#render(Entity, …)` and
  `BlockEntityRenderDispatcher#render(BlockEntity, …)`, like the 1.20.6 port.
- Block entities of detached placements are rendered by the mod itself
  (`PlacementBlockEntities#renderDetached`, taken from the 1.20.6 port): 0.23.7's
  `ChunkRendererSchematicVbo#addBlockEntity` also only accepts a block entity that exists in the schematic
  world at the exact drawn position, which is false while a move is deferred.
- malilib's `RenderUtils#fb()` is the frame buffer hook for the overlay drawing (`MixinRenderUtils`).
- `RenderHandler#onRenderWorldLastAdvanced` gets the portal, as on 1.21.11.

## Renames / differences against 1.21.11

| 1.21.11 | 1.21.8 |
|---|---|
| `net.minecraft.resources.Identifier` | `net.minecraft.resources.ResourceLocation` |
| `SubmitNodeCollector` batching | `MultiBufferSource` batching (EXIT `batch(MultiBufferSource)`) |
| `IWorldSchematicRenderer` | concrete `WorldRendererSchematic` (no interface) |
| `DynamicUniforms.Transform` (4 args) | `+ float lineWidth` (5 args) |
| `RenderPass#bindTexture` + sampler | `RenderPass#bindSampler` |
| `ChunkRendererSchematicVbo#rebuildChunk(task, pack)` | `#rebuildChunk(task, profiler)` |
| `ChunkMeshDataSchematic` / `ChunkRenderDispatcherBuffers` | `ChunkRenderDataSchematic` / `BufferAllocatorCache` |
| `BlockModelRendererSchematic(BlockColors, BlockRenderDispatcher)` | `(BlockColors)`, `renderModel(…, long seed)` |
| `shouldRenderModelSide(…, boolean cull, BlockPos)` (static) | `(…, BlockPos)` (instance) |
| `Fabric API …rendering.v1.world.WorldRenderEvents.END_MAIN` | `…rendering.v1.WorldRenderEvents.END` |
| `RenderUtils` overlay helpers take a `lineWidth` | they do not |
| `LayerRange#getLayerMin/#getLayerMax` | same (both versions moved them out of `util.position`) |

`fi.dy.masa.malilib.util.IntBoundingBox` is a record with accessors in 1.21.11 and a plain class with public
fields (`box.minX` …) in 0.25.7.

## Things that could not be ported as-is (and why)

1. **`MixinChunkFixUniform` (Dropped).** 0.23.7 and malilib 0.25.7 have no terrain-fix uniform at all
   (`LegacyTerrainFixUniform` / `ChunkFixUniform`), so there is no "shaders are on" flag to patch. The
   equivalent of the 26.3 hook is simply absent on this version.
2. **`MixinPlacementManagerTaskRebuild` / `PlacementManagerTaskAccessor` (Replaced).** 0.23.7 has no rebuild
   task class; chunk rebuild requests are queued directly. As in the 1.20.6 port the same deferral is applied
   at the only two entry points, `SchematicPlacementManager#markChunkForRebuild(ChunkPos)` and
   `#markChunksForRebuild(Collection)`.
3. **`onToggleMainRendering` hook (Replaced).** 0.23.7 has no such method; `commitAll()` runs at the head of
   `SchematicWorldRefresher#updateAll` instead, which covers the rendering toggle and every other full refresh.
4. **Debug gizmos (Reimplemented).** 1.21.8 has no `Gizmos` API. The boxes are drawn as `DEBUG_LINES`
   through EXIT's portal with malilib's `RenderUtils#renderAreaOutline`. `persistForMillis(...).fadeOut()`
   has no equivalent, so boxes stay fully opaque for the configured duration and then disappear.
5. **"Ignore crop age" (Dropped).** 0.23.7 has no `IGNORE_CROP_AGE` option and no
   `BlockUtils#areStatesEqualIgnoringAge`, so the overlay builder and the async verifier do not consult it.
   Everything else of the verifier (including `DIFF_BLOCK` / `IgnoreBlockRegistry`) exists in 0.23.7 and is
   ported.
6. **`BlockModelCacheSchematicAccessor` / `MixinFluidModelRendererSchematic` (Dropped).** Neither class
   exists in 0.23.7 (same as 1.21.11): models come from the baked model manager, fluids from
   `BlockModelRendererSchematic#renderLiquid`, and the vanilla liquid renderer never consults malilib's layer
   range, so there is no range-edge test to patch on the builder thread. The block equivalent
   (`MixinBlockModelRendererSchematic`) is kept.

## Mixin injections that only fail at client start (found by the static audit)

`./gradlew build` compiles the handlers but never checks that they fit the target method, and this branch has
never been run in a client. Six injections were broken; the client would have died at startup the moment
Litematica's placement manager / renderer was transformed:

1. **`MixinLitematicaRenderer.routeEntities` (Wrong parameter types in the `@At` target).** The target named
   `WorldRendererSchematic;renderEntities(...MultiBufferSource;F...)V`, but 0.23.7's `renderEntities` (and the
   invoke in `piecewiseRenderEntities`, which is where the injection points) take
   `MultiBufferSource$BufferSource`. The handler parameter and the target now both use
   `MultiBufferSource.BufferSource`.
2. **`MixinLitematicaRenderer.routeBlockEntities` (Same, for both buffers).** `piecewiseRenderBlockEntities`
   invokes `renderBlockEntities(Camera, Frustum, PoseStack, MultiBufferSource$BufferSource,
   MultiBufferSource$BufferSource, float, ProfilerFiller)V`; both `MultiBufferSource` in the target string and
   in the handler were too wide.
3. **`MixinWorldRendererSchematic.renderDetachedBlockEntities` (Handler parameters too wide).** Mixin compares
   a handler's parameter types with the target's for equality (only `@Coerce` primitives are allowed to
   differ), so `(MultiBufferSource, MultiBufferSource)` for `renderBlockEntities`'s two
   `MultiBufferSource$BufferSource` parameters is "Invalid descriptor". Now `MultiBufferSource.BufferSource`.
4. **`MixinWorldRendererSchematic.shiftPendingEntity` (Stale parameter list).** The target had the 1.20.6
   signature `render(Entity, double, double, double, float, float, PoseStack, MultiBufferSource, int)V`, but
   in 1.21.8 `EntityRenderDispatcher#render` has a single `float`; the extra `yaw` parameter is gone from the
   target string, the handler and `original.call`.
5. **`MixinWorldRendererSchematic.shiftPendingBlockEntity` (Wrong class in the `@At` target).** The target
   named `Lnet/minecraft/world/level/blockentity/BlockEntity;`, but `BlockEntity` lives in
   `net/minecraft/world/level/block/entity/` - `blockentity` is the package of `BlockEntityRenderDispatcher`,
   not of `BlockEntity`. The invoke could never match. The built jar shows the defect plainly: the owner and
   the other Minecraft types of that target string were remapped to intermediary, while
   `net/minecraft/world/level/blockentity/BlockEntity` stayed named, because no such class exists.
6. **`MixinSchematicPlacementManagerI.skipFrozenChunks` (Ambiguous target).** 0.23.7 has two
   `markChunksForRebuild` overloads: `(SchematicPlacement)V` (public, only forwards to the other one) and
   `(Collection<ChunkPos>)V` (the one the handler matches). The injection now pins the descriptor:
   `method = "markChunksForRebuild(Ljava/util/Collection;)V"`.

Everything else was verified mechanically, on the named development classpath (handler descriptors from
`build/classes/java/main`, target descriptors from the Loom-remapped mod jars plus the named Minecraft jar),
62 checks: 21 `@Inject`, 5 `@WrapMethod`, 8 `@WrapOperation`, 2 `@ModifyReturnValue`, 1 `@ModifyVariable` and
25 `@Shadow` members. For the two `@ModifyVariable` / `@Inject` handlers that read locals, every
`@Local(name = ...)` was also checked against 0.23.7's `LocalVariableTable`; they all resolve, and the
`@Local` parameter types are assignable from the local's type. The checker is
`Y:/Temp/User/opencode/mixin-audit.py`, run as
`python Y:/Temp/User/opencode/mixin-audit.py <worktree> <merged Minecraft jar>`.

## Assumptions / things to watch in the client run

- The terrain mixes onto Litematica's `renderMap`/uniform list exactly like 1.21.11; the local names above
  come from 0.23.7's own source, so the MixinExtras `@Local(name = …)` sugar should resolve.
- `PlacementBlockEntities#renderDetached` draws into `noCullBuffers` (the second `MultiBufferSource` of
  `piecewiseRenderBlockEntities`), matching the 1.20.6 port's choice.
- Debug boxes are assembled from `BlockPos` pairs, so they are integer-aligned (the mod's section boxes are
  too).
- `fabricloader >=0.16.8` in `fabric.mod.json` follows the 1.20.6/1.21.11 ports (the dev runs use 0.19.5).
- Iris/Sodium nested libraries are extracted from their jars at build time (EXIT's `nestedLibraries` task),
  so no glsl-transformer version is hardcoded.
