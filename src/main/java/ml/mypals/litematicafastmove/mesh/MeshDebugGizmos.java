package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 调试用的区段框。The 26.x renderer has a {@code Gizmos} API; there is none in 1.21.4, so the same boxes
 * are drawn as {@code DEBUG_LINES} through EXIT's portal.
 *
 * Feature note: the 26.3 version used {@code persistForMillis(millis).fadeOut()}. Vanilla 1.21.4 has no such
 * facility; the boxes here simply stay visible for the configured duration and do not fade.
 */
public final class MeshDebugGizmos {
    private static final int QUEUED = 0xFFFFB000;
    private static final int APPLIED = 0xFF40FF80;
    private static final int SKIPPED = 0xFF808080;
    private static final int FULL = 0xFF40A0FF;
    private static final int MAX_PER_FRAME = 512;

    private record Box(AABB aabb, int color, long expiresAt) {}

    private static final Queue<Box> PENDING = new ConcurrentLinkedQueue<>();
    // render thread only
    private static final List<Box> ACTIVE = new ArrayList<>();

    private MeshDebugGizmos() {}

    public static boolean isEnabled() {
        return FastMoveConfig.DEBUG_GIZMOS.getBooleanValue();
    }

    static void fullBuild(PlacementBlockAccessor accessor, BlockPos delta, boolean applied) {
        if (!isEnabled() || accessor == null) return;
        add(new AABB(
                accessor.minPos.getX() + delta.getX(), accessor.minPos.getY() + delta.getY(), accessor.minPos.getZ() + delta.getZ(),
                accessor.maxPos.getX() + delta.getX() + 1, accessor.maxPos.getY() + delta.getY() + 1, accessor.maxPos.getZ() + delta.getZ() + 1),
                applied ? APPLIED : FULL);
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

            add(new AABB(x0 + delta.getX(), y0 + delta.getY(), z0 + delta.getZ(),
                    x1 + delta.getX() + 1, y1 + delta.getY() + 1, z1 + delta.getZ() + 1), color);
        }
    }

    private static void add(AABB aabb, int color) {
        long millis = FastMoveConfig.DEBUG_GIZMO_MILLIS.getIntegerValue();
        PENDING.add(new Box(aabb, color, System.currentTimeMillis() + millis));
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

        SchematicExit.portal(() -> draw(now));
    }

    private static void draw(long now) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.0f);

        try {
            for (Box box : ACTIVE) {
                drawBox(buffer, box, cameraPos);
            }
            MeshData mesh = buffer.build();
            if (mesh != null) {
                BufferUploader.drawWithShader(mesh);
            }
        } finally {
            RenderSystem.lineWidth(1.0f);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
        }
    }

    private static void drawBox(BufferBuilder buffer, Box box, Vec3 cameraPos) {
        AABB a = box.aabb();
        float r = ((box.color() >> 16) & 0xFF) / 255.0f;
        float g = ((box.color() >> 8) & 0xFF) / 255.0f;
        float b = (box.color() & 0xFF) / 255.0f;
        float alpha = ((box.color() >>> 24) & 0xFF) / 255.0f;

        double x0 = a.minX - cameraPos.x, y0 = a.minY - cameraPos.y, z0 = a.minZ - cameraPos.z;
        double x1 = a.maxX - cameraPos.x, y1 = a.maxY - cameraPos.y, z1 = a.maxZ - cameraPos.z;

        // bottom
        edge(buffer, x0, y0, z0, x1, y0, z0, r, g, b, alpha);
        edge(buffer, x1, y0, z0, x1, y0, z1, r, g, b, alpha);
        edge(buffer, x1, y0, z1, x0, y0, z1, r, g, b, alpha);
        edge(buffer, x0, y0, z1, x0, y0, z0, r, g, b, alpha);
        // top
        edge(buffer, x0, y1, z0, x1, y1, z0, r, g, b, alpha);
        edge(buffer, x1, y1, z0, x1, y1, z1, r, g, b, alpha);
        edge(buffer, x1, y1, z1, x0, y1, z1, r, g, b, alpha);
        edge(buffer, x0, y1, z1, x0, y1, z0, r, g, b, alpha);
        // sides
        edge(buffer, x0, y0, z0, x0, y1, z0, r, g, b, alpha);
        edge(buffer, x1, y0, z0, x1, y1, z0, r, g, b, alpha);
        edge(buffer, x1, y0, z1, x1, y1, z1, r, g, b, alpha);
        edge(buffer, x0, y0, z1, x0, y1, z1, r, g, b, alpha);
    }

    private static void edge(BufferBuilder buffer, double x0, double y0, double z0,
                             double x1, double y1, double z1, float r, float g, float b, float a) {
        buffer.addVertex((float) x0, (float) y0, (float) z0).setColor(r, g, b, a);
        buffer.addVertex((float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
    }
}
