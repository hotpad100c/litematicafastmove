# Port notes: Minecraft 1.20.2 (Litematica 0.16.1 / malilib 0.17.0)

This branch is the 1.20.4 port carried one version further down. The toolkit is the same
(`net.fabricmc.fabric-loom-remap` 1.18.1, official Mojang mappings, Java 17, EXIT `0.1.0+1.20.2`).

See `PORTING-NOTES-1.20.4.md` for the full list of render-path decisions, dropped 0.29-only mixins and known
limitations; everything there applies here.

## What was different for 1.20.2

- **Nothing had to change in the ported source.** Litematica 0.16.1 / malilib 0.17.0 expose the same names used by
  the 1.20.4 port, and every 1.20.4 mixin target still exists in 0.16.1 (verified per target against the remapped
  jar: `SchematicPlacementManager` chunk rebuild queue + `onPre/PostPlacementChange` + `markChunkForRebuild(ChunkPos)`,
  `SchematicVerifier.verifyChunks/checkChangedPositions`, `WorldRendererSchematic.renderBlockLayer/renderBlockOverlay`,
  `ChunkRendererSchematicVbo.rebuildChunk/rebuildWorldView`, `BlockModelRendererSchematic.shouldRenderModelSide` +
  `LayerRange.isPositionAtRenderEdgeOnSide`, `LitematicaRenderer.piecewiseRenderOverlay/piecewiseRenderEntities`,
  `RenderHandler.onRenderWorldLast`, `GuiConfigs.getConfigs`, `ConfigUtils.readConfigBase/writeConfigBase`).
- Versions only: `minecraft_version=1.20.2`, `fabric_api_version=0.91.6+1.20.2`, Litematica `JkhAnNEE` (0.16.1),
  malilib `wzI0QkCv` (0.17.0), EXIT `0.1.0+1.20.2`, Iris `Cjwm9s3i`, Sodium `pmgeU5yX`.

## Not verified here

`./gradlew build` is green. No client was started from this worktree (machine policy: one client at a time, and a
client run has to be announced). Mixin application and in-game behaviour still need a `runClient` smoke test.
