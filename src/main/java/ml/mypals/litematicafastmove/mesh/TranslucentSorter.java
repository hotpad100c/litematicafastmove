package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.BufferBuilder;
import ml.mypals.litematicafastmove.LitematicaFastMove;
import ml.mypals.litematicafastmove.config.FastMoveConfig;

/**
 * Re-sorts the translucent sections of the detached meshes.
 * <p>
 * Minecraft 1.16.5 reorders the quad vertices themselves (it has no index buffer): begin, restoreState, sortQuads and
 * upload is exactly what the vanilla chunk resort (ChunkRenderDispatcher.ResortTransparencyTask) does. Like
 * Litematica's own chunk renderer this re-sorts a bounded number of sections on the render thread, and only when the
 * camera moved far enough.
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
            builder.restoreState(sort.state);
            builder.sortQuads((float) rx, (float) ry, (float) rz);
            builder.end();
            layer.vertexBuffer().upload(builder);
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
