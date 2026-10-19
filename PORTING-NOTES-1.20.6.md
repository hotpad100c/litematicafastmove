# 1.20.6 port notes

Branch `port/1.20.6`, base `feat/render-delay` (Minecraft 26.3).

| | 26.3 (upstream) | 1.20.6 (this branch) |
|---|---|---|
| Minecraft | 26.3 | 1.20.6 (obfuscated) |
| Loom | `net.fabricmc.fabric-loom` 1.18-SNAPSHOT | `net.fabricmc.fabric-loom-remap` 1.18.1 |
| Mappings | none (26.3 is deobfuscated) | `loom.officialMojangMappings()` |
| Java | 25 | 21 |
| Fabric API | 0.162.0+26.3 | 0.100.8+1.20.6 |
| Litematica | 0.29.1 | 0.18.3 |
| malilib | 0.30.2 | 0.19.2 |
| EXIT | 0.1.0+26.3 | 0.1.0+1.20.6 |

`./gradlew build` is green. Nothing has been run in a client yet.

## How the port is built

The 26.3 renderer uses the new GPU-device API (`GpuBuffer`, `RenderPass`, `DynamicGpuData`, submit/render
states). None of that exists in 1.20.6, so the mesh upload/draw half was reimplemented on the primitives that
Litematica 0.18.3 itself uses on this version:

- build: `BufferBuilder` per section-layer, one `RenderedBuffer` each, built on the `LFMMeshBuilder` threads;
- upload: `VertexBuffer.upload(RenderedBuffer)` on the render thread, within the same per-frame byte/time budget;
- translucency: keep `BufferBuilder.SortState` at build time, and re-sort with
  `restoreSortState` + `setQuadSorting` + index-only upload — the exact pattern of
  `SectionRenderDispatcher.RenderSection.ResortTransparencyTask`;
- draw: Litematica 0.18.3 draws one `RenderType` at a time, so the hook moved from the 26.3
  `prepareBlockLayers` render-map to `WorldRendererSchematic#renderBlockLayer` (RETURN);
  overlays similarly hook `renderBlockOverlay`;
- EXIT integration now uses the 1.20.6 `MultiBufferSource` API (`ExitChannel#batch(MultiBufferSource)`)
  instead of the 26.3 `SubmitNodeCollector` batching.

The "fast move" logic itself (move deferral, interpolation, render delay, detached meshes, clip rebuilds,
async verifier) is unchanged apart from the API renames listed below.

## API renames that are pure porting work

- `Identifier` → `ResourceLocation`; `Identifier.fromNamespaceAndPath` → `new ResourceLocation`.
- `ChunkPos.pack/unpack`, `pos.x()/z()` → `ChunkPos.asLong`, `new ChunkPos(long)`, `pos.x/z`, `pos.toLong()`.
- `ChunkSectionLayer` → `RenderType` (1.20.6 has no chunk-layer enum).
- malilib `util.position.LayerRange` → `util.LayerRange`; `util.position.IntBoundingBox` → `util.IntBoundingBox`;
  `util.data.Color4f` → `util.Color4f`; `util.position.LayerMode` → `util.LayerMode`;
  `getMinLayerBoundary/getMaxLayerBoundary` → `getLayerMin/getLayerMax`.
- `LitematicaSchematic#getAreaSizeAsVec3i` → `getAreaSize` (returns a `BlockPos`).
- `BlockAndTintGetter` moved to `net.minecraft.world.level`; 1.20.6 also requires `getShade` and
  `getMinBuildHeight` (not `getMinY`).
- `ClientboundLevelChunkWithLightPacket#x()/z()` → `getX()/getZ()`.
- Fabric API `LevelRenderEvents.END_MAIN` → `WorldRenderEvents.END`.
- malilib 0.19.2 has no `IConfigBase#apply(translationKey)`, so the lang files use bare option names and
  `config.comment.<lowercase name>`.

## Things that could not be ported as-is (and why)

1. **`MixinIRISCompat` (Dropped).** Litematica 0.18.3 has no
   `fi.dy.masa.litematica.compat.iris.IrisCompat`. There is no Litematica pipeline registration to suppress;
   EXIT 1.20.6 handles routing by itself.
2. **`MixinLegacyTerrainFixUniform` (Dropped).** `fi.dy.masa.litematica.render.uniform.LegacyTerrainFixUniform`
   does not exist in 0.18.3 (the 26.3 shader-fix uniform was added with the new pipeline).
3. **`MixinRenderUtils` (Dropped).** The 26.3 hook replaces malilib's `RenderUtils#mainTarget()`; malilib 0.19.2
   has no such method and uses the bound framebuffer directly. EXIT's portal already redirects the framebuffer,
   so the hook has nothing to do here.
4. **`MixinPlacementManagerTaskRebuild` / `PlacementManagerTaskAccessor` (Replaced).** 0.18.3 has no
   `PlacementManagerTask(Rebuild)`; chunk rebuild requests are queued directly. The same deferral is now applied
   at the only two entry points, `SchematicPlacementManager#markChunkForRebuild(ChunkPos)` and
   `#markChunksForRebuild(Collection)`, with the same skip-and-replay bookkeeping.
5. **`onToggleMainRendering` hook (Replaced).** 0.18.3 has no such method. `commitAll()` now runs at the head of
   `SchematicWorldRefresher#updateAll`, which is what Litematica calls for every full refresh, including the
   rendering toggle.
6. **`MixinFluidModelRendererSchematic` (Dropped).** `FluidModelRendererSchematic` does not exist in 0.18.3;
   fluids go through vanilla `BlockRenderDispatcher#renderLiquid`, which has no Litematica layer-range edge test
   to patch (its vertices are already section-relative, so no `OmegaHackfix` is needed either).
7. **`BlockModelCacheSchematicAccessor` (Dropped).** No `BlockModelCacheSchematic` / `FluidStateModelSet` in
   0.18.3; models come from `BlockRenderDispatcher#getBlockModel`.
8. **Overlay features that only exist in 0.29.** 0.18.3 has no `IgnoreBlockRegistry`, no
   `ENABLE_SCHEMATIC_OVERLAY_CULLING`, no `IGNORE_CROP_AGE`, no `ENABLE_DIFFERENT_BLOCKS`, and no
   `OverlayType`/`MismatchType#DIFF_BLOCK`. Those branches of `OverlayMeshBuilder` and the async verifier are
   therefore absent, not silently disabled by us. Everything 0.18.3 does support is a 1:1 port.
9. **`ENABLE_SCHEMATIC_FLUIDS` toggle (Not applicable).** 0.18.3 always renders a block's fluid; the port does
   the same (equivalent to the toggle being on).
10. **Block entities of detached placements (Reimplemented).** 0.18.3's
    `ChunkRendererSchematicVbo#addBlockEntity` only accepts a `BlockEntity` that already exists in the schematic
    world at the exact drawn position, which is false while a move is deferred. The mod renders them itself,
    with the same origin + lag translation as the block mesh, into EXIT's batch.
11. **Debug gizmos (Reimplemented).** 1.20.6 has no `Gizmos` API. Boxes are drawn as `DEBUG_LINES` through the
    EXIT portal. `persistForMillis(...).fadeOut()` has no equivalent, so the boxes stay fully opaque for the
    configured duration and then disappear.
12. **Translucent resorting (Reimplemented).** `SortState#writeSortedIndexBuffer` does not exist in 1.20.6; the
    vanilla index-only re-upload pattern is used instead (see above).

## Mixin injections that only fail at client start (found by the static audit)

This branch was run in a client, but the run was only checked for "did the game start" - and it did start
before it died. `Y:/Temp/User/opencode/lfm-1.20.6-client.log` ends with

```
Mixin apply for mod litematicafastmove failed ... MixinSchematicPlacementManagerI ... Invalid descriptor ...
@Inject::skipFrozenChunks ... Expected (SchematicPlacement;CallbackInfo)V but found (Collection;CallbackInfo)V
```

`./gradlew build` compiles the handlers but never checks that they fit the target method, so two injections
were broken - the same two the 1.21.4 and 1.21.1 branches carried, both inherited from this port:

1. **`MixinSchematicPlacementManagerI.skipFrozenChunks` (Ambiguous target).** Litematica 0.18.3 has two
   `markChunksForRebuild` overloads: `(SchematicPlacement)V` (public, only forwards to the other one) and
   `(Collection<ChunkPos>)V` (the one every entry point funnels through, and the one the handler matches).
   With a bare method name Mixin resolves the *name* first and then validates the handler against the first
   overload, so it failed with `Expected (SchematicPlacement;CallbackInfo)V but found (Collection;CallbackInfo)V`.
   The injection now pins the descriptor: `method = "markChunksForRebuild(Ljava/util/Collection;)V"`. The
   descriptor only contains JDK types, so it is identical in development and production.
2. **`MixinWorldRendererSchematic.shiftPendingBlockEntity` (Wrong class in the `@At` target).** The target
   named `Lnet/minecraft/world/level/blockentity/BlockEntity;`, but `BlockEntity` lives in
   `net/minecraft/world/level/block/entity/` - `blockentity` is the package of `BlockEntityRenderDispatcher`,
   not of `BlockEntity`. The invoke could never match; it is now
   `Lnet/minecraft/world/level/block/entity/BlockEntity;`. The built jar shows the defect plainly: the owner and
   the other Minecraft types of that target string were remapped to intermediary, while
   `net/minecraft/world/level/blockentity/BlockEntity` stayed named, because no such class exists.

Everything else was verified mechanically, on the named development classpath (handler descriptors from
`build/classes/java/main`, target descriptors from the Loom-remapped mod jars plus the named Minecraft jar),
59 checks: 19 `@Inject`, 9 `@WrapMethod`, 7 `@WrapOperation`, 1 `@ModifyReturnValue` and 23 `@Shadow` members.
The checker is `Y:/Temp/User/opencode/mixin-audit.py`, run as
`python Y:/Temp/User/opencode/mixin-audit.py <worktree> <merged Minecraft jar>`.

## Assumptions / things to watch in the client run

- `DetachedBlockView#getShade` delegates to the client level; 26.3 did not have to implement it.
- Overlay QUAD layers are sorted at build time per section and drawn back-to-front per section; they are not
  re-sorted while the camera moves (0.18.3 re-sorts its chunk overlays; our sections are much smaller).
- Block entities use the schematic world's light, like Litematica's own block-entity path.
- `fabricloader >=0.16.8` in `fabric.mod.json` is a guess for the MixinExtras `@WrapMethod` requirement.
  The development runs use loader 0.19.5.
- The Iris/Sodium nested libraries are extracted from their jars at build time (EXIT's `nestedLibraries` task).
  For 1.20.6 that yields `jcpp 1.4.14`, `antlr4-runtime 4.13.1` and `glsl-transformer 2.0.1` (the 26.3 branch
  hardcoded `glsl-transformer 3.0.0-pre3`, which is wrong here).
