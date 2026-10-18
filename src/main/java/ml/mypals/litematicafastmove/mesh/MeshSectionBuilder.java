package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
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
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
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
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
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
     * 提交构建；keys == null 为完整构建。区段按离相机由近到远分批，分散到所有构建线程并行处理：
     * 局部构建每批完成即交给 onBuilt，逐批生效；完整构建等所有批次完成后合并为一次整体替换。
     * 被新一代构建取代时直接丢弃
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

        FullBuild(SchematicPlacement placement, int generation, BlockPos buildOrigin, int batches, Consumer<PlacementMesh.BuiltPlacementBuffer> onBuilt) {
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

    // emitEmpty：局部构建时空区段也要返回，用于清除旧网格
    private static List<PlacementMesh.CpuSection> build(PlacementMesh mesh, int generation, PlacementBlockAccessor accessor,
                                                        List<Long> sectionKeys, boolean emitEmpty, BlockPos clientDelta,
                                                        LayerRangeClip.Range range, Vec3 camera,
                                                        WorldSchematic world, ClientLevel clientLevel) {
        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;

        List<PlacementMesh.CpuSection> sections = new ArrayList<>();
        DetachedBlockView view = new DetachedBlockView(accessor, world, clientLevel);
        BlockModelRendererSchematic blockRenderer = new BlockModelRendererSchematic(Minecraft.getInstance().getBlockColors());
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();

        Direction.Axis clipAxis = range != null ? range.axis() : null;
        int clipMin = Integer.MIN_VALUE, clipMax = Integer.MAX_VALUE;
        if (range != null) {
            int d = clipAxis.choose(clientDelta.getX(), clientDelta.getY(), clientDelta.getZ());
            clipMin = range.min() - d;
            clipMax = range.max() - d;
            view.setClip(clipAxis, clipMin, clipMax);
        }

        boolean translucent = Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue();
        boolean renderColliding = Configs.Visuals.RENDER_COLLIDING_SCHEMATIC_BLOCKS.getBooleanValue();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos clientPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos overlayClientPos = new BlockPos.MutableBlockPos();

        PoseStack matrices = new PoseStack();

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
                List<PlacementMesh.CpuLayer> layers = new ArrayList<>();
                LongArrayList blockEntities = new LongArrayList();

                // 每个区段独立的 builder；builder 与其 RenderedBuffer 一起交给上传线程后释放
                IdentityHashMap<RenderType, BufferBuilder> builders = new IdentityHashMap<>();
                EnumMap<OverlayRenderType, BufferBuilder> overlayBuilders = new EnumMap<>(OverlayRenderType.class);

                boolean hasSchematicBlocks = view.loadSection(sx, sy, sz);
                boolean hasBlocks = view.hasUnclippedBlocks();
                boolean clippedAway = clip != null && clip.isEmpty();
                boolean clientEmpty = ClientRegions.isSectionEmpty(clientLevel, min, max, key, clientDelta);
                // 投影这里全是空气时只可能出现 overlay 的"多余方块"，客户端对应区域也为空就跳过
                boolean scan = !clippedAway && (hasSchematicBlocks || !clientEmpty);

                OverlayMeshBuilder overlay = OverlayMeshBuilder.isEnabled()
                        ? new OverlayMeshBuilder(view,
                                p -> clientLevel.getBlockState(overlayClientPos.setWithOffset(p, clientDelta)),
                                type -> overlayBuilder(overlayBuilders, type))
                        : null;

                if (scan) {
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
                                    tessellate(view, state, pos, sectionOrigin, matrices, blockRenderer, dispatcher,
                                            builders, translucent);
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

                    for (Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet()) {
                        PlacementMesh.CpuLayer layer = finishLayer(entry.getKey(), null, entry.getValue(),
                                entry.getKey() == RenderType.translucent(), camera, sectionOrigin, clientDelta);
                        if (layer != null) layers.add(layer);
                    }
                    for (Map.Entry<OverlayRenderType, BufferBuilder> entry : overlayBuilders.entrySet()) {
                        PlacementMesh.CpuLayer layer = finishLayer(null, entry.getKey(), entry.getValue(),
                                entry.getKey() == OverlayRenderType.QUAD, camera, sectionOrigin, clientDelta);
                        if (layer != null) layers.add(layer);
                    }
                }

                // 局部构建时空区段也要返回，用于清除旧网格；有方块但被裁空的区段保留记录
                if (hasBlocks || !layers.isEmpty() || emitEmpty) {
                    sections.add(new PlacementMesh.CpuSection(key, sectionOrigin, hasBlocks, clientEmpty, clip, layers, blockEntities.toLongArray()));
                }
            }

        return sections;
    }

    private static void tessellate(DetachedBlockView view, BlockState state, BlockPos pos, BlockPos sectionOrigin,
                                   PoseStack matrices, BlockModelRendererSchematic blockRenderer,
                                   BlockRenderDispatcher dispatcher, Map<RenderType, BufferBuilder> builders,
                                   boolean translucent) {
        try {
            FluidState fluidState = state.getFluidState();
            if (!fluidState.isEmpty()) {
                RenderType fluidLayer = ItemBlockRenderTypes.getRenderLayer(fluidState);
                // 流体渲染直接写 pos & 15，本身就是区段相对坐标
                dispatcher.renderLiquid(pos, view, builder(builders, fluidLayer), state, fluidState);
            }

            if (state.getRenderShape() == RenderShape.MODEL) {
                RenderType layer = translucent ? RenderType.translucent() : ItemBlockRenderTypes.getChunkRenderType(state);
                BakedModel model = dispatcher.getBlockModel(state);
                matrices.pushPose();
                matrices.translate(pos.getX() - sectionOrigin.getX(), pos.getY() - sectionOrigin.getY(), pos.getZ() - sectionOrigin.getZ());
                blockRenderer.renderModel(view, model, state, pos, matrices, builder(builders, layer), state.getSeed(pos));
                matrices.popPose();
            }
        } catch (Throwable e) {
            LitematicaFastMove.LOGGER.debug("Failed to tessellate {} at {}", state, pos.toShortString(), e);
        }
    }

    private static BufferBuilder builder(Map<RenderType, BufferBuilder> builders, RenderType layer) {
        return builders.computeIfAbsent(layer, l -> newBuffer(l.mode(), l.format()));
    }

    private static BufferBuilder overlayBuilder(Map<OverlayRenderType, BufferBuilder> builders, OverlayRenderType type) {
        return builders.computeIfAbsent(type, t -> newBuffer(t.getDrawMode(), DefaultVertexFormat.POSITION_COLOR));
    }

    private static BufferBuilder newBuffer(com.mojang.blaze3d.vertex.VertexFormat.Mode mode,
                                           com.mojang.blaze3d.vertex.VertexFormat format) {
        BufferBuilder builder = new BufferBuilder(16 * 1024);
        builder.begin(mode, format);
        return builder;
    }

    /** Ends the buffer and turns it into a CPU layer; returns null for an empty buffer. */
    private static PlacementMesh.CpuLayer finishLayer(RenderType layer, OverlayRenderType overlay, BufferBuilder builder,
                                                       boolean sortQuads, Vec3 camera, BlockPos sectionOrigin, BlockPos clientDelta) {
        BufferBuilder.SortState sortState = null;
        if (sortQuads) {
            builder.setQuadSorting(VertexSorting.byDistance(
                    (float) (camera.x - sectionOrigin.getX() - clientDelta.getX()),
                    (float) (camera.y - sectionOrigin.getY() - clientDelta.getY()),
                    (float) (camera.z - sectionOrigin.getZ() - clientDelta.getZ())));
            sortState = builder.getSortState();
        }

        BufferBuilder.RenderedBuffer renderedBuffer = builder.endOrDiscardIfEmpty();
        if (renderedBuffer == null) {
            builder.release();
            return null;
        }

        BufferBuilder.DrawState drawState = renderedBuffer.drawState();
        ByteBuffer vertices = renderedBuffer.vertexBuffer();
        long hash = vertices != null ? hash(vertices) : 0L;
        int vertexBytes = drawState.vertexBufferSize();
        return new PlacementMesh.CpuLayer(layer, overlay, builder, renderedBuffer, drawState.indexCount(),
                vertexBytes, sortState, hash);
    }

    // 64 位内容哈希，用于判断重建后的网格是否与旧网格相同
    private static long hash(ByteBuffer buffer) {
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
