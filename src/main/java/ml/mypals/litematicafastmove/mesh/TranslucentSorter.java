package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import ml.mypals.litematicafastmove.LitematicaFastMove;
import ml.mypals.litematicafastmove.config.FastMoveConfig;

/**
 * Re-sorts the translucent sections of the detached meshes.
 * <p>
 * 0.29's version sorts a copy of the index buffer on a worker thread. Minecraft 1.20.4's BufferBuilder owns the
 * sorting points, so like Litematica's own chunk renderer this re-sorts a bounded number of sections synchronously on
 * the render thread, and only when the camera moved far enough.
 */
public class TranslucentSorter {

    private int budget = FastMoveConfig.TRANSLUCENT_RESORTS_PER_FRAME.getIntegerValue();

    private TranslucentSorter() {}

    static TranslucentSorter forFrame() {
        return new TranslucentSorter();
    }

    void maybeResort(PlacementMesh.GpuLayer layer, double rx, double ry, double rz) {
        PlacementMesh.SortInfo sort = layer.sort();
        if (sort == null || sort.pending || this.budget <= 0) return;

        double dx = rx - sort.lastX, dy = ry - sort.lastY, dz = rz - sort.lastZ;
        double threshold = FastMoveConfig.TRANSLUCENT_RESORT_DISTANCE.getDoubleValue();
        if (!Double.isNaN(sort.lastX) && dx * dx + dy * dy + dz * dz <= threshold * threshold) return;

        this.budget--;
        sort.pending = true;
        try {
            BufferBuilder builder = new BufferBuilder(4096);
            builder.begin(sort.mode, sort.format);
            builder.restoreSortState(sort.state);
            builder.setQuadSorting(VertexSorting.byDistance((float) rx, (float) ry, (float) rz));
            layer.vertexBuffer().bind();
            try {
                layer.vertexBuffer().upload(builder.end());
            } finally {
                VertexBuffer.unbind();
            }
            builder.release();
            sort.lastX = rx;
            sort.lastY = ry;
            sort.lastZ = rz;
        } catch (Throwable e) {
            LitematicaFastMove.LOGGER.debug("Failed to resort translucent section", e);
        } finally {
            sort.pending = false;
        }
    }
}
