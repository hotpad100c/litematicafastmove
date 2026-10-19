package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import ml.mypals.litematicafastmove.LitematicaFastMove;
import ml.mypals.litematicafastmove.config.FastMoveConfig;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 1.21.4 的半透明排序沿用 {@code SectionRenderDispatcher} 的做法：构建时留下
 * {@link MeshData.SortState}，相机移动后在新线程里 {@code buildSortedIndexBuffer} 得到只含索引的缓冲区，
 * 再在渲染线程把它上传进同一个 {@link VertexBuffer}。
 */
public class TranslucentSorter {
    private record Resorted(PlacementMesh.GpuLayer layer, ByteBufferBuilder allocator, ByteBufferBuilder.Result index,
                            MeshData.SortState state, double x, double y, double z) {}

    private static final ExecutorService SORTER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "LFMTranslucentSorter");
        thread.setDaemon(true);
        return thread;
    });
    private static final Queue<Resorted> RESORTED = new ConcurrentLinkedQueue<>();

    private int budget = FastMoveConfig.TRANSLUCENT_RESORTS_PER_FRAME.getIntegerValue();

    private TranslucentSorter() {}

    static TranslucentSorter forFrame() {
        return new TranslucentSorter();
    }

    void maybeResort(PlacementMesh.GpuLayer layer, double rx, double ry, double rz) {
        PlacementMesh.SortInfo sort = layer.sort;
        if (sort == null || sort.pending || this.budget <= 0) return;

        double dx = rx - sort.lastX, dy = ry - sort.lastY, dz = rz - sort.lastZ;
        double threshold = FastMoveConfig.TRANSLUCENT_RESORT_DISTANCE.getDoubleValue();
        if (!Double.isNaN(sort.lastX) && dx * dx + dy * dy + dz * dz <= threshold * threshold) return;

        this.budget--;
        sort.pending = true;
        MeshData.SortState state = sort.state;
        SORTER.submit(() -> {
            ByteBufferBuilder allocator = null;
            try {
                allocator = new ByteBufferBuilder(sortBufferSize(layer));
                ByteBufferBuilder.Result index = state.buildSortedIndexBuffer(allocator,
                        VertexSorting.byDistance((float) rx, (float) ry, (float) rz));
                RESORTED.add(new Resorted(layer, allocator, index, state, rx, ry, rz));
            } catch (Throwable e) {
                if (allocator != null) {
                    try {
                        allocator.close();
                    } catch (Throwable ignored) {
                    }
                }
                layer.sort.pending = false;
                LitematicaFastMove.LOGGER.debug("Failed to resort translucent section", e);
            }
        });
    }

    private static int sortBufferSize(PlacementMesh.GpuLayer layer) {
        // only the sorted index buffer is written, four bytes per quad index is a safe upper bound
        return Math.max(1024, layer.indexCount * 4 + 1024);
    }

    static void applyResorted() {
        Resorted resorted;
        while ((resorted = RESORTED.poll()) != null) {
            PlacementMesh.GpuLayer layer = resorted.layer();
            // upload replaces only the index buffer and closes the index Result
            layer.vertexBuffer.bind();
            layer.vertexBuffer.uploadIndexBuffer(resorted.index());
            VertexBuffer.unbind();
            resorted.allocator().close();
            layer.sort.state = resorted.state();
            layer.sort.lastX = resorted.x();
            layer.sort.lastY = resorted.y();
            layer.sort.lastZ = resorted.z();
            layer.sort.pending = false;
        }
    }
}
