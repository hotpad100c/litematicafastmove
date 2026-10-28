package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.BufferBuilder;
import ml.mypals.litematicafastmove.mixin.BufferBuilderAccessor;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo.OverlayRenderType;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


final class PlacementMesh {
    // Layer range clipping: along axis only the section-local coordinates [lo, hi] survive; null = no clipping,
    // lo > hi = the whole section is clipped away
    record Clip(Direction.Axis axis, int lo, int hi) {
        boolean isEmpty() {
            return this.lo > this.hi;
        }
    }

    /**
     * Built on a worker thread. The RenderedBuffer keeps its BufferBuilder alive, so the data can still be uploaded
     * on the render thread later. hash: content hash of the vertex data, used to keep the old GPU buffer when a rebuild
     * produced the same geometry.
     */
    record CpuLayer(RenderType layer, OverlayRenderType overlay, BufferBuilder.RenderedBuffer buffer, BufferBuilder builder,
                    int indexCount, BufferBuilder.SortState sortState, int vertexBytes, long hash) {
        void free() {
            this.buffer.release();
            freeNative(this.builder);
        }

        void releaseUploaded() {
            freeNative(this.builder);
        }
    }
    // clientEmpty: the matching area of the real world (grown by one block) is all air
    // blockEntities: block entity positions to show (build coordinates, BlockPos.asLong), registered with Litematica
    record CpuSection(long key, BlockPos origin, boolean hasBlocks, boolean clientEmpty, Clip clip,
                      List<CpuLayer> layers, long[] blockEntities) {}

    static final class SortInfo {
        final BufferBuilder.SortState state;
        final VertexFormat.Mode mode;
        final VertexFormat format;
        double lastX, lastY, lastZ;
        boolean pending;

        SortInfo(BufferBuilder.SortState state, double x, double y, double z, VertexFormat.Mode mode, VertexFormat format) {
            this.state = state;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.mode = mode;
            this.format = format;
        }
    }

    record GpuLayer(RenderType layer, OverlayRenderType overlay, VertexBuffer vertexBuffer, int indexCount,
                    SortInfo sort, int vertexBytes, long hash) {
        boolean sameContent(CpuLayer cpu) {
            return this.layer == cpu.layer() && this.overlay == cpu.overlay() && this.indexCount == cpu.indexCount() &&
                   this.vertexBytes == cpu.vertexBytes() && this.hash == cpu.hash();
        }

        void close() {
            this.vertexBuffer.close();
        }
    }
    // hasBlocks: the section contains schematic blocks, so it is kept even when the layer range clips it away
    record GpuSection(long key, BlockPos origin, boolean hasBlocks, boolean clientEmpty, Clip clip,
                      List<GpuLayer> layers, long[] blockEntities) {
        void close() {
            for (GpuLayer layer : this.layers) {
                layer.close();
            }
        }

        // Layers carried over into a new build are owned by the new section and must not be closed here
        void closeExcept(Set<GpuLayer> keep) {
            for (GpuLayer layer : this.layers) {
                if (!keep.contains(layer)) layer.close();
            }
        }
    }


    static final class BuiltPlacementBuffer {
        final int generation;
        final BlockPos buildOrigin;
        final Set<Long> keys;
        final List<CpuSection> pending;
        final List<GpuSection> uploaded = new ArrayList<>();
        // Layers carried over from the current mesh (identity comparison); they still belong to the old section
        final Set<GpuLayer> borrowed = Collections.newSetFromMap(new IdentityHashMap<>());
        int nextToUpload;

        BuiltPlacementBuffer(int generation, BlockPos buildOrigin, Set<Long> keys, List<CpuSection> pending) {
            this.generation = generation;
            this.buildOrigin = buildOrigin;
            this.keys = keys;
            this.pending = pending;
        }

        boolean isFull() {
            return this.keys == null;
        }

        boolean isFullyUploaded() {
            return this.nextToUpload >= this.pending.size();
        }

        void discard() {
            for (int i = this.nextToUpload; i < this.pending.size(); i++) {
                this.pending.get(i).layers().forEach(CpuLayer::free);
            }
            this.nextToUpload = this.pending.size();
            this.uploaded.forEach(section -> section.closeExcept(this.borrowed));
            this.uploaded.clear();
            this.borrowed.clear();
        }
    }

    volatile int requestedGeneration;
    volatile BlockPos targetBuildOrigin;
    volatile PlacementBlockAccessor targetAccessor;
    // Block entities of the sections currently in effect (build coordinates), for Litematica's chunk rebuilds
    final Map<Long, long[]> blockEntities = new ConcurrentHashMap<>();
    volatile BlockPos blockEntityBuildOrigin;

    //RenderThread only
    final Map<Long, GpuSection> active = new HashMap<>();
    int activeGeneration;
    BlockPos activeBuildOrigin;
    BuiltPlacementBuffer uploadingFull;
    final List<BuiltPlacementBuffer> deferredPartials = new ArrayList<>();
    final ArrayDeque<BuiltPlacementBuffer> uploadingPartials = new ArrayDeque<>();
    final Set<Long> clipRebuildPending = new HashSet<>();

    void close() {
        this.active.values().forEach(GpuSection::close);
        this.active.clear();
        if (this.uploadingFull != null) {
            this.uploadingFull.discard();
            this.uploadingFull = null;
        }
        this.deferredPartials.forEach(BuiltPlacementBuffer::discard);
        this.deferredPartials.clear();
        this.uploadingPartials.forEach(BuiltPlacementBuffer::discard);
        this.uploadingPartials.clear();
        this.clipRebuildPending.clear();
        this.blockEntities.clear();
    }

    static void freeNative(BufferBuilder builder) {
        MemoryUtil.getAllocator(false).free(MemoryUtil.memAddress0(((BufferBuilderAccessor) builder).lfm$buffer()));
    }
}
