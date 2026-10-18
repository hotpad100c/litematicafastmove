package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo.OverlayRenderType;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import ml.mypals.litematicafastmove.LitematicaFastMove;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.config.ThreadPools;
import ml.mypals.litematicafastmove.mixin.BufferBuilderAccessor;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

public final class MeshSectionBuilder {
    private static final class MeshBuilderThread extends Thread {
        MeshBuilderThread(Runnable r) {
            super(r, "LFMMeshBuilder");
            this.setDaemon(true);
            this.setPriority(Thread.NORM_PRIORITY - 1);
        }
    }

    private static final ThreadPoolExecutor BUILDER = (ThreadPoolExecutor) Executors.newFixedThreadPool(
            FastMoveConfig.meshBuilderThreads(), MeshBuilderThread::new);

    public static void applyThreadCount() {
        ThreadPools.resize(BUILDER, FastMoveConfig.meshBuilderThreads());
    }

    private MeshSectionBuilder() {}

    public static boolean isBuilderThread() {
        return Thread.currentThread() instanceof MeshBuilderThread;
    }

    /**
     * Submits a build; keys == null builds everything. Sections are sorted nearest-camera-first and spread over all
     * builder threads in batches: partial builds hand each finished batch to onBuilt at once, full builds merge them
     * into one replacement. Builds superseded by a newer generation are dropped.
     */
    static void submit(SchematicPlacement placement, PlacementMesh mesh, int generation, PlacementBlockAccessor accessor,
                       BlockPos buildOrigin, Set<Long> keys, BlockPos clientDelta, WorldSchematic world, ClientLevel clientLevel,
                       Consumer<PlacementMesh.BuiltPlacementBuffer> onBuilt) {
        LayerRangeClip.Range range = LayerRangeClip.snapshot();
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        boolean full = keys == null;

        List<Long> sectionKeys = full ? allSections(accessor) : new ArrayList<>(keys);
        sectionKeys.sort(Comparator.comparingDouble(key -> distanceSq(key, clientDelta, camera)));
        List<List<Long>> batches = partition(sectionKeys, Math.max(1, FastMoveConfig.BUILD_BATCH_SECTIONS.getIntegerValue()));
        FullBuild collector = full ? new FullBuild(placement, generation, buildOrigin, batches.size(), onBuilt) : null;

        for (List<Long> batch : batches) {
            BUILDER.submit(() -> {
                List<PlacementMesh.CpuSection> sections = null;
                try {
                    List<List<PlacementMesh.CpuSection>> built = new ArrayList<>(1);
                    SchematicExit.plainFormat(() -> built.add(
                            build(mesh, generation, accessor, batch, !full,
                                    clientDelta, range, camera, world, clientLevel)));
                    sections = built.get(0);
                } catch (Throwable e) {
                    LitematicaFastMove.LOGGER.error("Failed to build mesh for placement '{}'", placement.getName(), e);
                }
                if (full) {
                    collector.complete(sections);
                } else if (sections != null) {
                    onBuilt.accept(new PlacementMesh.BuiltPlacementBuffer(generation, buildOrigin, new HashSet<>(batch), sections));
                }
            });
        }
    }

    private static final class FullBuild {
        private final SchematicPlacement placement;
        private final int generation;
        private final BlockPos buildOrigin;
        private final Consumer<PlacementMesh.BuiltPlacementBuffer> onBuilt;
        private final List<PlacementMesh.CpuSection> sections = new ArrayList<>();
        private final long start = System.nanoTime();
        private int remaining;
        private boolean failed;

        FullBuild(SchematicPlacement placement, int generation, BlockPos buildOrigin, int batches,
                  Consumer<PlacementMesh.BuiltPlacementBuffer> onBuilt) {
            this.placement = placement;
            this.generation = generation;
            this.buildOrigin = buildOrigin;
            this.remaining = batches;
            this.onBuilt = onBuilt;
        }

        void complete(List<PlacementMesh.CpuSection> batch) {
            synchronized (this) {
                if (batch == null) {
                    this.failed = true;
                } else {
                    this.sections.addAll(batch);
                }
                if (--this.remaining > 0) return;
            }

            if (this.failed) {
                this.sections.forEach(section -> section.layers().forEach(PlacementMesh.CpuLayer::free));
                return;
            }
            LitematicaFastMove.LOGGER.debug("Built detached mesh for '{}' in {} ms ({} sections)",
                    this.placement.getName(), (System.nanoTime() - this.start) / 1_000_000, this.sections.size());
            this.onBuilt.accept(new PlacementMesh.BuiltPlacementBuffer(this.generation, this.buildOrigin, null, this.sections));
        }
    }

    private static List<Long> allSections(PlacementBlockAccessor accessor) {
        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;
        List<Long> keys = new ArrayList<>();
        for (int sy = min.getY() >> 4; sy <= max.getY() >> 4; sy++) {
            for (int sz = min.getZ() >> 4; sz <= max.getZ() >> 4; sz++) {
                for (int sx = min.getX() >> 4; sx <= max.getX() >> 4; sx++) {
                    keys.add(SectionPos.asLong(sx, sy, sz));
                }
            }
        }
        return keys;
    }

    private static double distanceSq(long key, BlockPos delta, Vec3 camera) {
        double dx = (SectionPos.x(key) << 4) + 8 + delta.getX() - camera.x;
        double dy = (SectionPos.y(key) << 4) + 8 + delta.getY() - camera.y;
        double dz = (SectionPos.z(key) << 4) + 8 + delta.getZ() - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static List<List<Long>> partition(List<Long> keys, int size) {
        List<List<Long>> batches = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += size) {
            batches.add(new ArrayList<>(keys.subList(i, Math.min(keys.size(), i + size))));
        }
        return batches;
    }

    // emitEmpty: a partial build also returns empty sections, so their old meshes are cleared
    private static List<PlacementMesh.CpuSection> build(PlacementMesh mesh, int generation, PlacementBlockAccessor accessor,
                                                        List<Long> sectionKeys, boolean emitEmpty, BlockPos clientDelta,
                                                        LayerRangeClip.Range range, Vec3 camera,
                                                        WorldSchematic world, ClientLevel clientLevel) {
        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;

        List<PlacementMesh.CpuSection> sections = new ArrayList<>();
        DetachedBlockView view = new DetachedBlockView(accessor, world, clientLevel);

        Direction.Axis clipAxis = range != null ? range.axis() : null;
        int clipMin = Integer.MIN_VALUE, clipMax = Integer.MAX_VALUE;
        if (range != null) {
            int d = clipAxis.choose(clientDelta.getX(), clientDelta.getY(), clientDelta.getZ());
            clipMin = range.min() - d;
            clipMax = range.max() - d;
            view.setClip(clipAxis, clipMin, clipMax);
        }

        BlockModelRendererSchematic blockRenderer = new BlockModelRendererSchematic(Minecraft.getInstance().getBlockColors());

        boolean translucent = Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue();
        boolean renderColliding = Configs.Visuals.RENDER_COLLIDING_SCHEMATIC_BLOCKS.getBooleanValue();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos clientPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos overlayClientPos = new BlockPos.MutableBlockPos();

        try {
            for (long key : sectionKeys) {
                if (mesh.requestedGeneration != generation) {
                    sections.forEach(s -> s.layers().forEach(PlacementMesh.CpuLayer::free));
                    return null;
                }

                int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
                BlockPos sectionOrigin = new BlockPos(sx << 4, sy << 4, sz << 4);
                int x0 = Math.max(sx << 4, min.getX()), x1 = Math.min((sx << 4) + 15, max.getX());
                int y0 = Math.max(sy << 4, min.getY()), y1 = Math.min((sy << 4) + 15, max.getY());
                int z0 = Math.max(sz << 4, min.getZ()), z1 = Math.min((sz << 4) + 15, max.getZ());
                PlacementMesh.Clip clip = LayerRangeClip.compute(range, min, max, sectionOrigin, clientDelta);
                LongArrayList blockEntities = new LongArrayList();

                boolean hasSchematicBlocks = view.loadSection(sx, sy, sz);
                boolean hasBlocks = view.hasUnclippedBlocks();
                boolean clippedAway = clip != null && clip.isEmpty();
                boolean clientEmpty = ClientRegions.isSectionEmpty(clientLevel, min, max, key, clientDelta);
                // With only air in the schematic there can still be overlay "extra blocks", but not if the client area is air too
                boolean scan = !clippedAway && (hasSchematicBlocks || !clientEmpty);
                boolean overlayEnabled = OverlayMeshBuilder.isEnabled();

                Map<RenderType, BufferBuilder> builders = new java.util.HashMap<>();
                Map<OverlayRenderType, BufferBuilder> overlayBuilders = new EnumMap<>(OverlayRenderType.class);

                if (scan) {
                    OverlayMeshBuilder overlay = overlayEnabled
                            ? new OverlayMeshBuilder(view,
                                    p -> clientLevel.getBlockState(overlayClientPos.setWithOffset(p, clientDelta)),
                                    type -> overlayBuilders.computeIfAbsent(type, t -> newBuffer(t.getDrawMode(), com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR, 256 * 1024)))
                            : null;

                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int x = x0; x <= x1; x++) {
                                if (clipAxis != null) {
                                    int v = clipAxis.choose(x, y, z);
                                    if (v < clipMin || v > clipMax) continue;
                                }

                                pos.set(x, y, z);
                                BlockState state = view.getBlockState(pos);
                                clientPos.setWithOffset(pos, clientDelta);
                                BlockState clientState = clientLevel.getBlockState(clientPos);
                                boolean schematicAir = state.isAir();
                                boolean clientAir = clientState.isAir();

                                if (schematicAir && (clientAir || !accessor.isInAnyRegion(x, y, z))) continue;

                                boolean missing = false;
                                if (!schematicAir && (clientAir || (renderColliding && clientState != state))) {
                                    tessellate(view, state, pos, blockRenderer,
                                            layer -> builders.computeIfAbsent(translucent ? RenderType.translucent() : layer,
                                                    l -> newBuffer(l.mode(), l.format(), l.bufferSize())));
                                    if (state.hasBlockEntity()) {
                                        blockEntities.add(BlockPos.asLong(x, y, z));
                                    }
                                    missing = clientAir && state.getRenderShape() == RenderShape.MODEL;
                                }

                                if (overlay != null) {
                                    try {
                                        overlay.addOverlay(pos, state, clientState, missing);
                                    } catch (Throwable e) {
                                        LitematicaFastMove.LOGGER.debug("Failed to build overlay at {}", pos.toShortString(), e);
                                    }
                                }
                            }
                        }
                    }
                }

                List<PlacementMesh.CpuLayer> layers = new ArrayList<>();
                float cameraX = (float) (camera.x - sectionOrigin.getX() - clientDelta.getX());
                float cameraY = (float) (camera.y - sectionOrigin.getY() - clientDelta.getY());
                float cameraZ = (float) (camera.z - sectionOrigin.getZ() - clientDelta.getZ());

                for (Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet()) {
                    BufferBuilder builder = entry.getValue();
                    boolean isTranslucent = entry.getKey() == RenderType.translucent();
                    BufferBuilder.SortState sortState = null;
                    if (isTranslucent) {
                        // 1.20 replaced the sort origin with a VertexSorting; here the BufferBuilder still sorts the
                        // quads around setQuadSortOrigin, which is exactly what the vanilla chunk resort does.
                        builder.setQuadSortOrigin(cameraX, cameraY, cameraZ);
                        sortState = builder.getSortState();
                    }
                    builder.end();
                    int indexCount = indexCount(builder);
                    if (indexCount == 0) {
                        continue;
                    }
                    int vertexBytes = bufferBytes(builder);
                    layers.add(new PlacementMesh.CpuLayer(entry.getKey(), null, builder,
                            indexCount, sortState, vertexBytes, contentHash(builder)));
                }
                for (Map.Entry<OverlayRenderType, BufferBuilder> entry : overlayBuilders.entrySet()) {
                    BufferBuilder builder = entry.getValue();
                    builder.end();
                    int indexCount = indexCount(builder);
                    if (indexCount == 0) {
                        continue;
                    }
                    int vertexBytes = bufferBytes(builder);
                    layers.add(new PlacementMesh.CpuLayer(null, entry.getKey(), builder,
                            indexCount, null, vertexBytes, contentHash(builder)));
                }

                // Section is only kept when there is something in it, or when a partial build must clear it
                if (hasBlocks || !layers.isEmpty() || emitEmpty) {
                    sections.add(new PlacementMesh.CpuSection(key, sectionOrigin, hasBlocks, clientEmpty, clip, layers, blockEntities.toLongArray()));
                } else {
                    layers.forEach(PlacementMesh.CpuLayer::free);
                }
            }
        } finally {
            // nothing to close: every builder's data is owned by the BufferBuilder we kept or dropped
        }

        return sections;
    }

    /** Number of indices the just-ended builder wrote (0 means it holds no geometry at all). */
    private static int indexCount(BufferBuilder builder) {
        List<BufferBuilder.DrawState> states = ((BufferBuilderAccessor) builder).lfm$drawStates();
        return states.isEmpty() ? 0 : states.get(states.size() - 1).indexCount();
    }

    /** Bytes the upload of the just-ended builder will move (vertices plus indices). */
    private static int bufferBytes(BufferBuilder builder) {
        List<BufferBuilder.DrawState> states = ((BufferBuilderAccessor) builder).lfm$drawStates();
        return states.isEmpty() ? 0 : states.get(states.size() - 1).bufferSize();
    }

    /**
     * Content hash of the finished buffer. Unlike 1.19.3's RenderedBuffer there is no public handle on the data before
     * the upload pops it, so the accessor mixin reads the builder's own ByteBuffer here on the builder thread.
     */
    private static long contentHash(BufferBuilder builder) {
        BufferBuilderAccessor access = (BufferBuilderAccessor) builder;
        int total = access.lfm$totalRenderedBytes();
        ByteBuffer buffer = access.lfm$buffer();
        if (total <= 0 || buffer == null) return 0L;
        ByteBuffer bytes = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        bytes.position(0);
        bytes.limit(Math.min(total, bytes.capacity()));
        return hash(bytes);
    }

    private static BufferBuilder newBuffer(VertexFormat.Mode mode, VertexFormat format, int capacity) {
        BufferBuilder builder = new BufferBuilder(capacity);
        builder.begin(mode, format);
        return builder;
    }

    private static void tessellate(DetachedBlockView view, BlockState state, BlockPos pos,
                                   BlockModelRendererSchematic blockRenderer,
                                   java.util.function.Function<RenderType, BufferBuilder> buffers) {
        try {
            FluidState fluidState = state.getFluidState();
            if (fluidState.isEmpty() == false) {
                RenderType layer = net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderLayer(fluidState);
                Minecraft.getInstance().getBlockRenderer().renderLiquid(pos, view, buffers.apply(layer), fluidState);
            }

            if (state.getRenderShape() == RenderShape.MODEL) {
                BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
                if (model != null) {
                    PoseStack matrices = new PoseStack();
                    matrices.translate(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                    RenderType layer = net.minecraft.client.renderer.ItemBlockRenderTypes.getChunkRenderType(state);
                    blockRenderer.renderModel(view, model, state, pos, matrices, buffers.apply(layer), state.getSeed(pos));
                }
            }
        } catch (Throwable e) {
            LitematicaFastMove.LOGGER.debug("Failed to tessellate {} at {}", state, pos.toShortString(), e);
        }
    }

    // 64 bit content hash, used to tell whether a rebuilt section is identical to the old one
    private static long hash(ByteBuffer buffer) {
        if (buffer == null) return 0L;
        ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int end = b.limit();
        long h = 0x9E3779B97F4A7C15L ^ (b.remaining() * 0xC2B2AE3D27D4EB4FL);
        int i = b.position();
        for (; i + 8 <= end; i += 8) {
            h ^= Long.rotateLeft(b.getLong(i) * 0xC2B2AE3D27D4EB4FL, 31) * 0x9E3779B97F4A7C15L;
            h = Long.rotateLeft(h, 27) * 0x9E3779B97F4A7C15L + 0x85EBCA77C2B2AE63L;
        }
        for (; i < end; i++) {
            h ^= (b.get(i) & 0xFFL) * 0x27D4EB2F165667C5L;
            h = Long.rotateLeft(h, 11) * 0x9E3779B97F4A7C15L;
        }
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }
}
