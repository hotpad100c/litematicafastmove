# Port notes: Minecraft 1.20.1 (Litematica 0.15.4 / malilib 0.16.3)

The upstream branch targets Minecraft 26.3 with Litematica 0.29.1. This branch ports it to 1.20.1, one step
below the 1.20.4 branch (`port/1.20.4`, Litematica 0.17.4 / malilib 0.18.3) and the 1.20.6 branch
(`port/1.20.6`, Litematica 0.18.3 / malilib 0.19.2).

Litematica 0.15.4 has exactly the same renderer design as 0.17.4 that the 1.20.4 port was written against:
`ChunkRendererSchematicVbo` + `BufferBuilderCache`, `WorldRendererSchematic#renderBlockLayer(RenderType, …)`
drawing one `RenderType` at a time, `ResortTransparencyTask`-style translucent resorting, and Minecraft's
immediate-mode `RenderType` + `BufferBuilder` + `VertexBuffer` renderer. The 1.20.4 port therefore carried
over almost unchanged; the notes below list everything that did **not**.

## Build

- `net.fabricmc.fabric-loom-remap` 1.18.1, official Mojang mappings (1.20.1 is obfuscated).
- Litematica 0.15.4 (`maven.modrinth:bEpr0Arc:1ZTpW1lr`), malilib 0.16.3 (`maven.modrinth:malilib:V7yLDtJV`)
  and EXIT 0.1.0+1.20.1 are `modImplementation`, so their intermediary names are remapped to Mojang names.
- Java release 17; Fabric API 0.92.12+1.20.1; loader 0.19.5.
- EXIT 1.20.1 is Fabric-only (Iris has no NeoForge build for 1.20.1); its `fabric` jar carries the common
  classes as well, so the one artifact is enough.
- Iris/Sodium nested libraries are extracted from their own jars at build time (`nestedLibraries` task), like
  on 1.20.6: on 1.20.1 the nested set is not the same as on 1.20.4, so hardcoding
  `jcpp / antlr4 / glsl-transformer 3.0.0-pre3` would be wrong.

`./gradlew build` is green. Nothing has been run in a client yet (machine policy: one client at a time, and a
client run has to be announced).

## What needed real work beyond the 1.20.4 port

Both of these are bugs the 1.20.4 branch still carries, because mixin `@At` targets and `@Inject` handler
signatures are strings/descriptors that the compiler cannot check:

1. **`MixinBlockModelRendererSchematic`: malilib moved `LayerRange`.** The injection target still said
   `Lfi/dy/masa/malilib/util/position/LayerRange;isPositionAtRenderEdgeOnSide(…)`, which is malilib 0.30.2
   (26.3). In 0.16.3 and 0.18.3 the class is `fi.dy.masa.malilib.util.LayerRange` — verified in both jars
   with `javap`, and the call site in `BlockModelRendererSchematic#shouldRenderModelSide` invokes that owner.
   Fixed to `util/LayerRange`.
2. **`MixinWorldRendererSchematic`: `setupTerrain` has one argument less.** Litematica 0.29 added a
   `ProfilerFiller` parameter; 0.15.4 (and 0.17.4, and 0.18.3) declare
   `setupTerrain(Camera, Frustum, int, boolean)`. A Mixin `@Inject` handler may not declare more arguments
   than the target, so the handler would have thrown `InvalidInjectionException` on start. The
   `ProfilerFiller` parameter was dropped.

Everything else is the 1.20.4 port as-is (`fabric.mod.json`'s `minecraft` range is now `~1.20.1`; litematica
and malilib keep the `*` range the mod has always used).

Every mixin target on this branch was checked by hand against the actual jars (Litematica 0.15.4, malilib
0.16.3 and the mapped 1.20.1 Minecraft jar) — target method, owner and descriptor of each `@At`, and the
handler signature of each `@Inject` / `@WrapMethod` / `@WrapOperation` (`@WrapOperation` handlers must pass
the call's receiver as the first argument, which the inherited code does).

## Litematica 0.15.4 / malilib 0.16.3 API differences from 0.17.4 / 0.18.3

Essentially none for the classes this mod touches. The 1.20.4 notes' list holds unchanged:

- `PlacementManagerTaskRebuild` does not exist; chunk rebuilds happen in
  `SchematicPlacementManager#processQueuedChunks`, where `chunkRebuildQueue` / `chunksToRebuild` are filtered
  so chunks whose schematic data is still at the pre-move position are not rebuilt. Verified present in
  0.15.4, together with `chunksPreChange`, `markChunkForRebuild`, `getPlacementPartsInChunk`.
- No `onToggleMainRendering`; a pending move is committed by the tick timer, on schematic rebuild, on
  placement removal, on `clear`, and at the end of a move that no longer defers.
- `fi.dy.masa.malilib.util.position.*` is `fi.dy.masa.malilib.util.*`; `IntBoundingBox` has public fields;
  `LayerRange` has `getLayerMin()/getLayerMax()` and `getAxis()`.
- malilib 0.16.3 has no per-option translation-key prefix: display names come from
  `config.name.<lowercase name>` and comments from `config.comment.<lowercase name>`, which the lang files
  carry.
- `Configs.loadFromFile/saveToFile` are hooked with `@WrapOperation` on the `ConfigUtils` calls, not with an
  ordinal + `@Local`, because the published Litematica jar has no local variable tables.
- `SchematicWorldRefresher` lives in `fi.dy.masa.litematica.util` (not `.world`) and has `updateAll()`.

## Dropped, because the 0.15.4 feature does not exist

Same list as 1.20.4 — intentional removals, not silent losses:

- `MixinIRISCompat` — no `fi.dy.masa.litematica.compat.iris.IrisCompat`; EXIT routes the drawing.
- `MixinLegacyTerrainFixUniform` — no `fi.dy.masa.litematica.render.uniform.LegacyTerrainFixUniform`.
- `MixinRenderUtils.mainTarget` — malilib 0.16.3 has no `mainTarget()`; EXIT's portal redirects the bound
  framebuffer.
- `MixinFluidModelRendererSchematic` — no `FluidModelRendererSchematic`; fluids go through
  `BlockRenderDispatcher#renderLiquid` without layer-range edge checks.
- `BlockModelCacheSchematicAccessor` — no `BlockModelCacheSchematic`; models come from
  `BlockRenderDispatcher`.
- `MixinPlacementManagerTaskRebuild` / `PlacementManagerTaskAccessor` — replaced by the
  `processQueuedChunks` filter (see above).
- Verifier: 0.15.4 has no `DIFF_BLOCK`, no ignored-block registry, no ignore-crop-age. The async verifier
  keeps the 0.15.4 rules only (missing / extra / wrong block / wrong state, `ignoreExistingFluids`).
- Overlay: no `ENABLE_SCHEMATIC_OVERLAY_CULLING`, no different-block grouping, no `ENABLE_SCHEMATIC_FLUIDS`
  switch; behaviour matches 0.15.4's own overlay.

## Known limitations

Inherited from the 1.20.4 port:

- Schematic block entities are registered through `ChunkRenderDataSchematic#addBlockEntity` only; the extra
  `blockEntities` set 0.15.4 uses for renderers that draw outside their bounding box cannot be populated from
  outside `rebuildChunk` without local variable tables, so those (rare) renderers may be culled at chunk edges
  for taken-over placements.
- Detached meshes are drawn per layer after Litematica's own chunks of that layer, not merged into their
  ordering. Opaque layers do not care; translucent detached sections are sorted back to front among
  themselves.

## Not verified here

`./gradlew build` is green and the remapped jar was inspected (mixin target strings are remapped to
intermediary by tiny-remapper, so no refmap is needed). Mixin application and in-game behaviour still need a
`runClient` smoke test.
