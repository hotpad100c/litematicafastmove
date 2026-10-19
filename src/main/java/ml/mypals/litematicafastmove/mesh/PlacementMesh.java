package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

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
    // 层范围裁剪：沿 axis 只保留区段内相对坐标 [lo, hi]；null = 不裁剪，lo > hi = 整段裁掉
    record Clip(Direction.Axis axis, int lo, int hi) {
        boolean isEmpty() {
            return this.lo > this.hi;
        }
    }

    /**
     * 一个待上传的网格层。1.21.4 builds into a {@link ByteBufferBuilder} and finishes with a {@link MeshData}
     * (the 1.21.2 buffer API), which {@link MeshUploader} uploads on the render thread.
     * The allocation is owned by this instance until it is uploaded (VertexBuffer#upload closes the MeshData)
     * or freed.
     */
    static final class CpuLayer {
        final RenderType layer;
        final OverlayRenderType overlay;
        final int indexCount;
        final int vertexBytes;
        // 半透明层的排序状态：用于相机移动后只重建索引缓冲
        final MeshData.SortState sortState;
        final long hash;
        // The allocator owns the memory the MeshData points into; both are released together.
        private final ByteBufferBuilder allocator;
        private MeshData mesh;

        CpuLayer(RenderType layer, OverlayRenderType overlay, ByteBufferBuilder allocator, MeshData mesh,
                 int indexCount, int vertexBytes, MeshData.SortState sortState, long hash) {
            this.layer = layer;
            this.overlay = overlay;
            this.allocator = allocator;
            this.mesh = mesh;
            this.indexCount = indexCount;
            this.vertexBytes = vertexBytes;
            this.sortState = sortState;
            this.hash = hash;
        }

        MeshData take() {
            MeshData taken = this.mesh;
            this.mesh = null;
            return taken;
        }

        /** VertexBuffer#upload closed the MeshData, so only the buffer memory is left to free. */
        void releaseAllocator() {
            this.allocator.close();
        }

        void free() {
            if (this.mesh != null) {
                this.mesh.close();
                this.mesh = null;
            }
            this.allocator.close();
        }
    }
    // clientEmpty：构建时对应的真实世界区域 (外扩 1 格) 全是空气
    // blockEntities：需要显示的方块实体位置 (构建坐标，BlockPos.asLong)
    record CpuSection(long key, BlockPos origin, boolean hasBlocks, boolean clientEmpty, Clip clip, List<CpuLayer> layers, long[] blockEntities) {}

    static final class SortInfo {
        MeshData.SortState state;
        double lastX, lastY, lastZ;
        boolean pending;

        SortInfo(MeshData.SortState state, double x, double y, double z) {
            this.state = state;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
        }
    }

    static final class GpuLayer {
        final RenderType layer;
        final OverlayRenderType overlay;
        final VertexBuffer vertexBuffer;
        final int indexCount;
        final int vertexBytes;
        final SortInfo sort;
        final long hash;

        GpuLayer(RenderType layer, OverlayRenderType overlay, VertexBuffer vertexBuffer,
                 int indexCount, int vertexBytes, SortInfo sort, long hash) {
            this.layer = layer;
            this.overlay = overlay;
            this.vertexBuffer = vertexBuffer;
            this.indexCount = indexCount;
            this.vertexBytes = vertexBytes;
            this.sort = sort;
            this.hash = hash;
        }

        boolean sameContent(CpuLayer cpu) {
            return this.layer == cpu.layer && this.overlay == cpu.overlay && this.indexCount == cpu.indexCount &&
                   this.vertexBytes == cpu.vertexBytes && this.hash == cpu.hash;
        }

        void close() {
            this.vertexBuffer.close();
        }
    }
    // hasBlocks：区段内有投影方块，即使被层范围裁空也保留，范围变化时据此判断是否重建
    record GpuSection(long key, BlockPos origin, boolean hasBlocks, boolean clientEmpty, Clip clip, List<GpuLayer> layers, long[] blockEntities) {
        void close() {
            for (GpuLayer layer : this.layers) {
                layer.close();
            }
        }

        // 被新构建沿用的layer已转交给新区段，不能关闭
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
        // 从当前网格沿用的 layer (按引用比较)；应用前它们仍归旧区段所有
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
    // 当前生效网格中各区段的方块实体 (构建坐标)，供 Litematica 区块重建时登记
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
}
