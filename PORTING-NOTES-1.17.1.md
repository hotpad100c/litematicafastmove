# Port notes: Minecraft 1.17.1 (Litematica 0.9.0 / malilib 0.10.0-dev.26)

The upstream branch targets Minecraft 26.3 with Litematica 0.29.1. This branch ports it to 1.17.1, the second
oldest target. Minecraft 1.17 already has the rendering stack this mod is built on (`VertexConsumer`,
`RenderType`, `PoseStack`, the `BufferBuilder` that owns its data until a `VertexBuffer` uploads it), so the
mesh code needed no third rewrite — it is the 1.18.2 code (`port/1.18.2`, Litematica 0.11.7 / malilib 0.12.1)
with the handful of API differences below.

## Build

- `net.fabricmc.fabric-loom-remap` 1.18.1, official Mojang mappings, Java release 16.
- Fabric API 0.46.1+1.17, loader 0.19.5, EXIT 0.1.0+1.17.1 (published from the matching EXIT worktree;
  `publishToMavenLocal` succeeded there without the duplicate-artifact workaround).
- Litematica 0.9.0 (`maven.modrinth:bEpr0Arc:FExyLuF1`), malilib 0.10.0-dev.26
  (`maven.modrinth:malilib:HljUtdhE`).
- Iris/Sodium nested libraries extracted from their own jars for the dev runtime (`nestedLibraries`); ids from
  the EXIT 1.17.1 branch (iris `pkGrlTNQ`, sodium `Fz37KqRh`).
- `fabric.mod.json`: `minecraft ~1.17.1`, `java >=16`, and the Fabric API dependency is `fabric`, **not**
  `fabric-api`: in 0.46.1+1.17 the aggregate mod is still called `fabric` and only gains the `fabric-api` id
  with `provides` in 0.76.0+1.18.2. Declaring `fabric-api` here would make Fabric Loader refuse to start
  (`HARD_DEP_NO_CANDIDATE`), the same trap the EXIT 1.17.1 branch hit.

`./gradlew build` is green and `remapJar` reports no unresolved mixin target. Nothing has been run in a client
yet (machine policy: one client at a time, and a client run has to be announced).

## Minecraft 1.17.1 differences (five compile errors, nothing else)

1. **Chunk sections.** `ChunkAccess` has no `getSection(int)` / `getSectionsCount()` yet (they arrive with
   `LevelHeightAccessor` in 1.18), and `LevelChunkSection` has `isEmpty()` instead of `hasOnlyAir()`.
   `ClientRegions` reads `chunk.getSections()` and indexes it from the world bottom
   (`level.getMinBuildHeight() >> 4`).
2. **`BlockRenderDispatcher#renderLiquid` has no `BlockState` argument**: `(BlockPos, BlockAndTintGetter,
   VertexConsumer, FluidState)`.
3. **`Options#getEffectiveRenderDistance()` does not exist**; `Options#renderDistance` (a public field, already
   the value the server clamped) is used instead.
4. **Chunks and light are still separate packets.** 1.17 has `ClientboundLevelChunkPacket` handled by
   `ClientPacketListener#handleLevelChunk`; `ClientboundLevelChunkWithLightPacket` only exists from 1.18.
   `MixinClientPacketListener` hooks the 1.17 handler.

This is the complete list — the other 1.17 API the mod touches (`VertexFormat#clearBufferState`,
`VertexBuffer#upload(BufferBuilder)`, `BufferBuilder#getSortState/restoreSortState/setQuadSortOrigin`,
`RenderType#setupRenderState/clearRenderState`, `BufferUploader#reset`, `ShaderInstance#CHUNK_OFFSET`,
`GameRenderer#getPositionColorShader`, `EntityRenderDispatcher#render(Entity,DDDFF…)`,
`BlockEntityRenderDispatcher#render(BlockEntity,F…)`, `ItemBlockRenderTypes#getChunkRenderType/getRenderLayer`,
`Minecraft#getMainRenderTarget`, `Camera#getPosition`) is unchanged from 1.18.2.

## Litematica 0.9.0 / malilib 0.10.0-dev.26 API differences

- **`SchematicPlacementManager` has no `chunkRebuildQueue`.** 0.9.0 keeps a single `chunksToRebuild` set that
  serves as both the rebuild queue and the pending-rebuild set, and `processQueuedChunks()` returns `boolean`
  instead of `void`. `MixinSchematicPlacementManagerI` therefore shadows only `chunksToRebuild` and filters the
  frozen chunks there. (`remapJar` flags this kind of mistake: the first build failed with
  *Cannot remap chunkRebuildQueue because it does not exist in any of the targets
  [fi/dy/masa/litematica/…/SchematicPlacementManager]*.)
- Everything else is already the 0.11.7 shape: `ChunkRendererSchematicVbo` with `boxes`/`position`/
  `schematicWorldView` and a private `rebuildWorldView` (which also fills its boxes from the 16^3 sub-chunk),
  `getTouchedBoxesInSubChunk` / `getAllPlacementsTouchingSubChunk`, the private per-type `renderBlockOverlay`
  behind the public `renderBlockOverlays(PoseStack, Camera, Matrix4f)`,
  `WorldRendererSchematic#renderBlockLayer` + `renderEntities(Camera, Frustum, PoseStack, float)` +
  `setupTerrain` + `loadRenderers`, `ChunkRenderDataSchematic#addBlockEntity`/`setTimeBuilt`,
  `LitematicaRenderer#piecewiseRenderOverlay/Entities`, `event.RenderHandler#onRenderWorldLast`,
  `util.SchematicWorldRefresher#updateAll`, `WorldSchematic#scheduleChunkRenders(chunkX, chunkZ)`, the
  `SchematicVerifier` fields and `verifyChunks`/`reset`/`checkChangedPositions`, `GuiConfigs#getConfigs`,
  `Configs#loadFromFile/saveToFile`, `Configs.Visuals.*` options the overlay uses, and malilib's
  `LayerRange#isPositionAtRenderEdgeOnSide` / `ConfigUtils#readConfigBase-writeConfigBase`. All of them were
  verified in the remapped 0.9.0 / 0.10.0-dev.26 jars, and the mixin `@Shadow`/`@Inject`/`@WrapMethod` targets
  resolve without a single `remapJar` warning.
- `WorldRendererSchematic#initShader` sets exactly the uniforms this mod's own `PlacementMeshRenderer#initShader`
  copy sets, so the detached meshes are shaded like Litematica's own chunks here too.

## Dropped, because the 0.9.0 / 0.10.0-dev.26 feature does not exist

Same list as 1.20.4 / 1.20.1 / 1.19.3 / 1.18.2 — intentional removals, not silent losses:

- `MixinIRISCompat` — no `fi.dy.masa.litematica.compat.iris.IrisCompat`; EXIT routes the drawing.
- `MixinLegacyTerrainFixUniform` — no `render.uniform.LegacyTerrainFixUniform`.
- `MixinRenderUtils.mainTarget` — malilib 0.10.0-dev.26 has no `mainTarget()`.
- `MixinFluidModelRendererSchematic` — no `FluidModelRendererSchematic`.
- `BlockModelCacheSchematicAccessor` — no `BlockModelCacheSchematic`.
- `MixinPlacementManagerTaskRebuild` / `PlacementManagerTaskAccessor` — 0.9.0 rebuilds schematic chunks in
  `processQueuedChunks`; the pending-move filter is applied there.
- Verifier: 0.9.0 has no `DIFF_BLOCK`, no ignored-block registry, no ignore-crop-age. The async verifier keeps
  the 0.9.0 rules (missing / extra / wrong block / wrong state, `ignoreExistingFluids`).
- Overlay: no `ENABLE_SCHEMATIC_OVERLAY_CULLING`, no different-block grouping, no `ENABLE_SCHEMATIC_FLUIDS`
  switch.
- No `onToggleMainRendering`; a pending move is committed by the tick timer, on schematic rebuild, on placement
  removal, on `clear`, and at the end of a move that no longer defers.
- Config GUI: the FastMove options are appended to Litematica's Generic tab; there is no "All" tab.

## Known limitations

Inherited from the 1.20.4 / 1.20.1 / 1.19.3 / 1.18.2 ports:

- Schematic block entities are registered through `ChunkRenderDataSchematic#addBlockEntity` only; the extra
  `setBlockEntities` set 0.9.0 uses for renderers that draw outside their bounding box cannot be populated from
  outside `rebuildChunk` without local variable tables.
- Detached meshes are drawn per layer after Litematica's own chunks of that layer (and, for overlays, after
  both of Litematica's overlay passes); translucent detached sections are sorted back to front among
  themselves.
- Uploaded vertex data lives in `BufferBuilder`-owned direct buffers until the render thread pops them, so the
  upload budget is the only back-pressure on native buffer memory; there is no allocator to return freed
  buffers to.

## Not verified here

`./gradlew build` is green. Mixin application and in-game behaviour still need a `runClient` smoke test. Note
that this project declares no Java toolchain (unlike the EXIT branches), so a dev client would be launched with
whatever JVM Gradle runs on; Minecraft 1.17.1 wants a Java 16 runtime.
