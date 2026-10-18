# Port notes: Minecraft 1.16.5 (Litematica 0.0.0-dev.20210917 / malilib 0.10.0-dev.21+arne.8)

The upstream branch targets Minecraft 26.3 with Litematica 0.29.1. This branch ports it to 1.16.5, the oldest
and hardest target: Minecraft's render stack here is still the pre-1.17 one, so this is the only branch where
the mesh layer needed a real rewrite of its own (the 1.17.1 branch could reuse the 1.18.2 code as it was).
Base of the port is the 1.17.1 branch (`port/1.17.1`, Litematica 0.9.0 / malilib 0.10.0-dev.26).

Litematica's 1.16.5 build, on the other hand, is structurally the same library as its 1.17.1 build: same
`ChunkRendererSchematicVbo` / `WorldRendererSchematic` / `SchematicPlacementManager` / `SchematicVerifier`
design, only without the `Matrix4f projMatrix` parameters and with the 1.16 render calls inside. That is why
the mixin set could stay the same.

## Build

- `net.fabricmc.fabric-loom-remap` 1.18.1, official Mojang mappings, Java release 16.
- Fabric API 0.42.0+1.16, loader 0.19.5, EXIT 0.1.0+1.16.5 (published from the matching EXIT worktree;
  `publishToMavenLocal` succeeded there without the duplicate-artifact workaround).
- Litematica 0.0.0-dev.20210917 (`maven.modrinth:bEpr0Arc:odLw7Aly`, the only Fabric build for 1.16.5 on
  Modrinth), malilib 0.10.0-dev.21+arne.8 (`maven.modrinth:malilib:Lav6afIY`).
- Iris/Sodium nested libraries extracted from their own jars for the dev runtime (`nestedLibraries`); ids from
  the EXIT 1.16.5 branch (iris `1turazSM`, sodium `YAGZ1cCS`).
- `fabric.mod.json`: `minecraft ~1.16.5`, `java >=16`, and the Fabric API dependency is `fabric`, not
  `fabric-api` (0.42.0+1.16 still ships the aggregate under the old id).
- Logging is log4j, not slf4j: on 1.16.5 slf4j is not on Loom's compile classpath (the same reason the EXIT
  1.16.5 branch uses `org.apache.logging.log4j`). `LitematicaFastMove.LOGGER` is a log4j `Logger`; the `{}`
  placeholders in the existing messages work unchanged.

`./gradlew build` is green and `remapJar` reports no unresolved mixin target. Nothing has been run in a client
yet (machine policy: one client at a time, and a client run has to be announced).

## Minecraft 1.16.5 render-code work (the real part of this port)

The whole buffer/shader path is different from 1.17+:

1. **`BufferBuilder` has no `VertexFormat.Mode`** — the draw mode is the raw GL mode int, and `begin` takes
   `(int, VertexFormat)`. `RenderType#mode()` already returns that int, and Litematica's `OverlayRenderType`
   exposes `getGlMode()` where the 1.17+ releases say `getDrawMode()`. Everything that carried a
   `VertexFormat.Mode` (the per-layer builders, `PlacementMesh.SortInfo`) carries an `int` now.
2. **There is no index buffer.** `VertexBuffer(VertexFormat)` is constructed with its format, `upload` derives
   the vertex count from the byte size, and `draw(Matrix4f, int)` issues `glDrawArrays(mode, 0, vertexCount)`.
   So `GpuLayer#indexCount` became `GpuLayer#vertexCount`, the mesh uploader passes the layer's format to the
   `VertexBuffer` constructor (overlays use `DefaultVertexFormat.POSITION_COLOR`), and the draw call takes the
   GL mode instead of drawing an index buffer.
3. **No chunk-offset uniform and no projection matrix argument.** `WorldRendererSchematic#renderBlockLayer`
   is `(RenderType, PoseStack, Camera)`, `renderBlockOverlays` is `(PoseStack, Camera)`,
   `LitematicaRenderer#piecewiseRenderOverlay` is `(PoseStack)` and `RenderHandler#onRenderWorldLast` is
   `(float, PoseStack)`. `PlacementMeshRenderer` follows `LevelRenderer#renderChunkLayer` and Litematica's own
   `renderBlockLayer`: `layer.setupRenderState()`, then per section `matrices.pushPose()` /
   `translate(sectionOrigin - camera)` / `buffer.bind()` / `layer.format().setupBufferState(0L)` /
   `buffer.draw(matrices.last().pose(), glMode)` / `popPose()`, then `VertexBuffer.unbind()`,
   `RenderSystem.clearCurrentColor()`, `format().clearBufferState()` and `clearRenderState()`.
   (`RenderSystem.clearCurrentColor()` is deprecated in 1.16.5 but is exactly what vanilla and Litematica call
   there; the same goes for the format/attribute calls.)
4. **The ghost-block alpha is inherited, not set.** 1.17+ has `RenderSystem.setShaderColor` feeding the block
   shader's colour modulator; 1.16.5 has no shader-colour API at all (no `RenderSystem#getShader`/`setShader`,
   no `ShaderInstance`), Litematica instead swaps in its own alpha shader program
   (`setupAlphaShader()`/`enableAlphaShader()` → `_glUseProgram` + `alpha_multiplier` uniform →
   `disableAlphaShader()`) around each `renderBlockLayer` call. This mod draws inside that call (its
   `@WrapMethod` calls the original first and then draws), so the detached meshes get the same ghost alpha for
   free and the renderer must **not** touch the shader program. Same for the overlays: Litematica's
   `renderSchematicOverlay` sets up blend/alpha test/line width/polygon offset and the depth test for "render
   through" around `renderBlockOverlays`, so `drawOverlays` only re-enables blend (which the overlay pass turns
   off again) and does not change the depth test — the 1.17+ branch's `SCHEMATIC_OVERLAY_RENDER_THROUGH`
   /`RENDER_OVERLAY_THROUGH_BLOCKS` handling is therefore not needed on this branch.
5. **Quad (re-)sorting is `sortQuads` + `BufferBuilder.State`.** `setQuadSortOrigin`/`getSortState`/
   `restoreSortState` do not exist yet: the build path is `builder.sortQuads(camX, camY, camZ)` then
   `state = builder.getState()` (which must happen *before* `end()`), and the re-sort is
   `new BufferBuilder(cap)` → `begin(mode, format)` → `restoreState(state)` → `sortQuads(rx, ry, rz)` → `end()`
   → `vertexBuffer.upload(builder)`, exactly the vanilla `ChunkRenderDispatcher.ResortTransparencyTask`. The
   re-sorted state is not captured again (vanilla refreshes it; sorting always starts from the quad centres, so
   re-sorting from the previous state gives the same order — it only saves a buffer copy per re-sort).
6. **The accessor mixin reads `vertexCounts`, not `drawStates`.** `BufferBuilder$DrawState` exposes only
   `format()`, `vertexCount()` and `mode()` on this version, and the private list is `vertexCounts`; the byte
   size is `vertexCount * format.getVertexSize()`. Everything else about the accessor is unchanged.
7. **Smaller API differences**: `BlockState#hasBlockEntity()` does not exist yet (use
   `state.getBlock() instanceof EntityBlock`, which is what 1.17 implements `hasBlockEntity()` as);
   `Minecraft#getBlockEntityRenderDispatcher()` does not exist (use the public
   `BlockEntityRenderDispatcher.instance`); `BlockPos.MutableBlockPos#setWithOffset` takes
   `(Vec3i, int, int, int)`; `Level`/`ClientLevel`/`ChunkAccess` have no `getMinBuildHeight()` or
   `getSectionsCount()`/`getSection(int)` (the section array starts at y = 0, so the section Y is the index) and
   `LevelChunkSection#hasOnlyAir()` is `isEmpty()`; `BlockGetter` has no `getHeight()` either, only
   `getMaxBuildHeight()`, which is what `DetachedBlockView` overrides now.
8. **Chunks and light are separate packets**, and the single/section block updates still meet in
   `ClientLevel#setBlocksDirty` — the same two changes as on 1.17.1 (`handleLevelChunk` /
   `ClientboundLevelChunkPacket`, `setBlocksDirty`).

## Litematica 0.0.0-dev.20210917 / malilib 0.10.0-dev.21+arne.8 API differences

- The `Matrix4f projMatrix` parameters the 1.17+ releases pass around do not exist: `renderBlockLayer(RenderType,
  PoseStack, Camera)`, `renderBlockOverlays(PoseStack, Camera)`, `piecewiseRenderOverlay(PoseStack)`,
  `onRenderWorldLast(float, PoseStack)`. Everything else about the renderer is the 0.9.0 shape, including the
  private `rebuildWorldView`, the private per-type `renderBlockOverlay` behind the public `renderBlockOverlays`,
  and `resortTransparency`/`rebuildChunk`.
- `SchematicPlacementManager` behaves like 0.9.0: no `chunkRebuildQueue`, a single `chunksToRebuild` set that is
  both the queue and the pending-rebuild set, and `processQueuedChunks()` returning `boolean`.
- `WorldSchematic#scheduleChunkRenders(chunkX, chunkZ)` schedules the whole column, which is what
  `PlacementBlockEntities` uses.
- All other mixin targets exist unchanged (`ChunkRenderDataSchematic#addBlockEntity`/`setTimeBuilt`,
  `LitematicaRenderer#piecewiseRenderEntities`, `util.SchematicWorldRefresher#updateAll`,
  `BlockModelRendererSchematic#shouldRenderModelSide`, the `SchematicVerifier` fields and
  `verifyChunks`/`reset`/`checkChangedPositions`, `GuiConfigs#getConfigs`, `Configs#loadFromFile/saveToFile`,
  `SchematicPlacementManager#getAllPlacementsTouchingSubChunk` and its placement-change hooks, and malilib's
  `LayerRange#isPositionAtRenderEdgeOnSide` / `ConfigUtils#readConfigBase-writeConfigBase`). All of them were
  verified in the remapped 1.16.5 jars with `javap -p`, the `@At` injection points in the decompiled mod, and
  the mixin `@Shadow`/`@Inject`/`@WrapMethod`/`@Accessor` names resolve without a `remapJar` warning.
- The config GUI appendix is the Generic tab, as on the other old branches.

## Dropped, because the 0.0.0-dev.20210917 / 0.10.0-dev.21 feature does not exist

Same list as 1.20.4 / 1.20.1 / 1.19.3 / 1.18.2 / 1.17.1 — intentional removals, not silent losses:

- `MixinIRISCompat` — no `fi.dy.masa.litematica.compat.iris.IrisCompat`; EXIT routes the drawing.
- `MixinLegacyTerrainFixUniform` — no `render.uniform.LegacyTerrainFixUniform`.
- `MixinRenderUtils.mainTarget` — malilib 0.10.0-dev.21+arne.8 has no `mainTarget()`.
- `MixinFluidModelRendererSchematic` — no `FluidModelRendererSchematic`.
- `BlockModelCacheSchematicAccessor` — no `BlockModelCacheSchematic`.
- `MixinPlacementManagerTaskRebuild` / `PlacementManagerTaskAccessor` — chunk rebuilds happen in
  `processQueuedChunks`; the pending-move filter is applied there.
- Verifier: no `DIFF_BLOCK`, no ignored-block registry, no ignore-crop-age. The async verifier keeps the rules
  this release has (missing / extra / wrong block / wrong state, `ignoreExistingFluids`).
- Overlay: no `ENABLE_SCHEMATIC_OVERLAY_CULLING`, no different-block grouping, no `ENABLE_SCHEMATIC_FLUIDS`
  switch, and (see above) the render-through depth state is Litematica's, not ours.
- No `onToggleMainRendering`; a pending move is committed by the tick timer, on schematic rebuild, on placement
  removal, on `clear`, and at the end of a move that no longer defers.
- Config GUI: the FastMove options are appended to Litematica's Generic tab; there is no "All" tab.

## Known limitations

Inherited from the newer branches, except where 1.16.5 changes the picture:

- Schematic block entities are registered through `ChunkRenderDataSchematic#addBlockEntity` only; the extra
  `setBlockEntities` set this release uses for renderers that draw outside their bounding box cannot be
  populated from outside `rebuildChunk` without local variable tables.
- Detached meshes are drawn per layer after Litematica's own chunks of that layer (and, for overlays, after both
  of Litematica's overlay passes); translucent detached sections are sorted back to front among themselves and
  re-sorted with the vanilla `sortQuads` path.
- Uploaded vertex data lives in `BufferBuilder`-owned direct buffers until the render thread pops them, so the
  upload budget is the only back-pressure on native buffer memory; 1.16.5 has no allocator to return freed
  buffers to.

## Not verified here

`./gradlew build` is green. Mixin application and in-game behaviour still need a `runClient` smoke test. This
project declares no Java toolchain (unlike the EXIT branches), so a dev client would be launched with whatever
JVM Gradle runs on; Minecraft 1.16.5 wants a Java 8–16 runtime, and the mod's own classes are Java 16.
