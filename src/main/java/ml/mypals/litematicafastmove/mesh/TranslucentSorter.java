package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import ml.mypals.litematicafastmove.LitematicaFastMove;
import ml.mypals.litematicafastmove.config.FastMoveConfig;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 1.20.6 的半透明排序沿用原版 {@code SectionRenderDispatcher.RenderSection.ResortTransparencyTask} 的做法：
 * 构建时保存 {@link BufferBuilder.SortState}，相机移动后在新线程里 restoreSortState + setQuadSorting，
 * 得到只含索引的 RenderedBuffer，再在渲染线程把它上传进同一个 VertexBuffer。
 */
public class TranslucentSorter {
    private record Resorted(PlacementMesh.GpuLayer layer, BufferBuilder builder, BufferBuilder.RenderedBuffer buffer,
                            BufferBuilder.SortState state, double x, double y, double z) {}

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
        BufferBuilder.SortState state = sort.state;
        SORTER.submit(() -> {
            BufferBuilder builder = null;
            try {
                builder = new BufferBuilder(sortBufferSize(layer));
                builder.begin(layer.layer.mode(), layer.layer.format());
                builder.restoreSortState(state);
                builder.setQuadSorting(VertexSorting.byDistance((float) rx, (float) ry, (float) rz));
                BufferBuilder.SortState newState = builder.getSortState();
                BufferBuilder.RenderedBuffer buffer = builder.end();
                RESORTED.add(new Resorted(layer, builder, buffer, newState, rx, ry, rz));
            } catch (Throwable e) {
                if (builder != null) {
                    try {
                        builder.release();
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
            // upload replaces only the index buffer (the RenderedBuffer is index-only)
            layer.vertexBuffer.upload(resorted.buffer());
            resorted.builder().release();
            layer.sort.state = resorted.state();
            layer.sort.lastX = resorted.x();
            layer.sort.lastY = resorted.y();
            layer.sort.lastZ = resorted.z();
            layer.sort.pending = false;
        }
    }
}
