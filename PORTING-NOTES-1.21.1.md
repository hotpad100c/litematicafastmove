# 1.21.1 port notes

Branch `port/1.21.1`, base `feat/render-delay` (Minecraft 26.3).

| | 26.3 (upstream) | 1.20.6 | 1.21.4 | 1.21.1 (this branch) |
|---|---|---|---|---|
| Minecraft | 26.3 | 1.20.6 | 1.21.4 | 1.21.1 (obfuscated) |
| Loom | `net.fabricmc.fabric-loom` 1.18-SNAPSHOT | `fabric-loom-remap` 1.18.1 | `fabric-loom-remap` 1.18.1 | `fabric-loom-remap` 1.18.1 |
| Mappings | none (26.3 is deobfuscated) | official Mojang | official Mojang | official Mojang |
| Java | 25 | 21 | 21 | 21 |
| Fabric API | 0.162.0+26.3 | 0.100.8+1.20.6 | 0.119.4+1.21.4 | 0.116.17+1.21.1 |
| Litematica | 0.29.1 | 0.18.3 | 0.21.7 | 0.19.61 (`o7i8oi1Z`) |
| malilib | 0.30.2 | 0.19.2 | 0.23.5 | 0.21.10 (`C99LEy6r`) |
| EXIT | 0.1.0+26.3 | 0.1.0+1.20.6 | 0.1.0+1.21.4 | 0.1.0+1.21.1 |

`./gradlew build` is green. Nothing has been run in a client yet.

## How the port is built

Litematica 0.19.61 / malilib 0.21.10 are the same generation as the 0.21.7 / 0.23.5 pair used on 1.21.4, so the
source is the `port/1.21.4` tree with the Minecraft-side differences of 1.21.1 put back. The mesh code keeps the
1.21 buffer API (`ByteBufferBuilder` / `MeshData` / `MeshData.SortState#buildSortedIndexBuffer` +
`VertexBuffer#uploadIndexBuffer`), including the `MeshData#sortQuads` at build time and the temporary allocator
on the sorter thread.

## Differences against the 1.21.4 port (all API, no behaviour change)

- `VertexBuffer(BufferUsage.STATIC_WRITE)` → `VertexBuffer(VertexBuffer.Usage.STATIC)` (the `BufferUsage` enum,
  `MeshData.SortState#centroids` and the rest of the 1.21.2 render rework only arrive in 1.21.2).
- `CompiledShaderProgram` → `ShaderInstance`; the position-color shader is still a supplier,
  `RenderSystem.setShader(GameRenderer::getPositionColorShader)` and `RenderSystem.setShader(() -> original)`
  (`CoreShaders` does not exist yet).
- `LevelHeightAccessor#getMinY` → `#getMinBuildHeight` (renamed in 1.21.2).
- `EntityRenderDispatcher#render` still has the yaw parameter (9 args, not 8), so the entity shift wrap uses the
  same descriptor as the 1.20.6 port.
- Litematica `RenderUtils` overlay helpers do **not** take a `RandomSource` yet
  (`drawBlockModelQuadOverlayBatched` / `drawBlockModelOutlinesBatched`; only `modelHasQuads` does), and
  `Block#shouldRenderFace` takes the world + position + neighbour (5 args).
- `SchematicVerifier#verifyChunks` / `#checkChangedPositions` and `RenderHandler#onRenderWorldLast` have no
  profiler argument yet, so those two mixins keep the 1.20.6 signatures.
- `WorldRendererSchematic#renderBlockLayer` / `renderBlockOverlay` / `setupTerrain` / `loadRenderers` /
  `renderBlockEntities`, the `piecewiseRender*` methods and top-level `OverlayRenderType`, the profiler-taking
  `ChunkRendererSchematicVbo#rebuildChunk` / `#resortTransparency`, and the `IgnoreBlockRegistry` /
  `MismatchType.DIFF_BLOCK` / overlay-culling / `ENABLE_SCHEMATIC_FLUIDS` features are already present in
  0.19.61, so they are ported exactly like on 1.21.4.

## Things that could not be ported as-is (and why)

The same seven drops as the 1.21.4 port, with the same reasons (see `PORTING-NOTES-1.21.4.md`):
`MixinIRISCompat` (0.19.61's `IrisCompat` has no `registerPipelines`), `MixinRenderUtils` (no
`RenderUtils#mainTarget` in malilib 0.21.10), `MixinLegacyTerrainFixUniform`, `MixinFluidModelRendererSchematic`,
`BlockModelCacheSchematicAccessor`, and `MixinPlacementManagerTaskRebuild` + `PlacementManagerTaskAccessor`
(replaced by the two `markChunk(s)ForRebuild` hooks).

## Assumptions / things to watch in the client run

- Same as 1.21.4: the shader is re-read after `RenderType#setupRenderState()` and null draws are skipped, the
  detached block entities render at the RETURN of `renderBlockEntities` into the wrapped vanilla
  `renderBuffers().bufferSource()`, and `fabric.mod.json` still says `fabricloader >=0.16.8` (the development runs
  use loader 0.19.5).
- Iris/Sodium are `modLocalRuntime`, EXIT is `ml.mypals.exit:emergency_exit-fabric`, and the Iris/Sodium nested
  libraries are extracted from their jars by the `nestedLibraries` task (no hardcoded glsl-transformer version).
