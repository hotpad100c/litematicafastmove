package ml.mypals.litematicafastmove.mesh;

import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 调试用的区段框。1.21.8 has no {@code Gizmos} API (that arrived with the 26.x renderer), so the boxes are
 * drawn as {@code DEBUG_LINES} through EXIT's portal with malilib's {@code renderAreaOutline}.
 *
 * Feature note: the 26.3 version used {@code persistForMillis(millis).fadeOut()}. There is no such facility
 * here; the boxes stay visible for the configured duration and do not fade.
 */
public final class MeshDebugGizmos {
    private static final int QUEUED = 0xFFFFB000;
    private static final int APPLIED = 0xFF40FF80;
    private static final int SKIPPED = 0xFF808080;
    private static final int FULL = 0xFF40A0FF;
    private static final int MAX_PER_FRAME = 512;
    private static final float LINE_WIDTH = 2.0f;

    private record Box(BlockPos min, BlockPos max, int color, long expiresAt) {}

    private static final Queue<Box> PENDING = new ConcurrentLinkedQueue<>();
    // render thread only
    private static final List<Box> ACTIVE = new ArrayList<>();

    private MeshDebugGizmos() {}

    public static boolean isEnabled() {
        return FastMoveConfig.DEBUG_GIZMOS.getBooleanValue();
    }

    static void fullBuild(PlacementBlockAccessor accessor, BlockPos delta, boolean applied) {
        if (!isEnabled() || accessor == null) return;
        add(accessor.minPos, accessor.maxPos, delta, applied ? APPLIED : FULL);
    }

    static void sectionsQueued(PlacementBlockAccessor accessor, Collection<Long> keys, BlockPos delta) {
        addSections(accessor, keys, delta, QUEUED);
    }

    static void sectionsApplied(PlacementBlockAccessor accessor, Collection<Long> keys, BlockPos delta) {
        addSections(accessor, keys, delta, APPLIED);
    }

    static void sectionsSkipped(PlacementBlockAccessor accessor, Collection<Long> keys, BlockPos delta) {
        addSections(accessor, keys, delta, SKIPPED);
    }

    private static void addSections(PlacementBlockAccessor accessor, Collection<Long> keys, BlockPos delta, int color) {
        if (!isEnabled() || accessor == null || keys == null) return;

        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;
        for (long key : keys) {
            int sx = SectionPos.x(key) << 4, sy = SectionPos.y(key) << 4, sz = SectionPos.z(key) << 4;
            int x0 = Math.max(sx, min.getX()), x1 = Math.min(sx + 15, max.getX());
            int y0 = Math.max(sy, min.getY()), y1 = Math.min(sy + 15, max.getY());
            int z0 = Math.max(sz, min.getZ()), z1 = Math.min(sz + 15, max.getZ());
            if (x0 > x1 || y0 > y1 || z0 > z1) continue;

            add(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1), delta, color);
        }
    }

    private static void add(BlockPos min, BlockPos max, BlockPos delta, int color) {
        long millis = FastMoveConfig.DEBUG_GIZMO_MILLIS.getIntegerValue();
        PENDING.add(new Box(min.offset(delta), max.offset(delta), color, System.currentTimeMillis() + millis));
    }

    public static void flush() {
        long now = System.currentTimeMillis();
        int count = 0;
        Box box;
        while (count++ < MAX_PER_FRAME && (box = PENDING.poll()) != null) {
            ACTIVE.add(box);
        }
        if (!isEnabled()) {
            PENDING.clear();
            ACTIVE.clear();
            return;
        }

        Iterator<Box> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expiresAt() <= now) iterator.remove();
        }
        if (ACTIVE.isEmpty()) return;

        SchematicExit.portal(() -> {
            for (Box active : ACTIVE) {
                int argb = active.color();
                Color4f color = new Color4f(
                        ((argb >> 16) & 0xFF) / 255.0f, ((argb >> 8) & 0xFF) / 255.0f, (argb & 0xFF) / 255.0f,
                        ((argb >>> 24) & 0xFF) / 255.0f);
                RenderUtils.renderAreaOutline(active.min(), active.max(), LINE_WIDTH, color, color, color);
            }
        });
    }
}
