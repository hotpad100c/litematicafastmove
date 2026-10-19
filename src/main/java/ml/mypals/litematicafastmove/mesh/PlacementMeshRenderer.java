package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.sync.MoveInterpolator;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 1.21.4 的绘制端：每个网格层是一个 {@link VertexBuffer}，
 * 由 Litematica 自己的 {@code WorldRendererSchematic#renderBlockLayer} / {@code renderBlockOverlay}
 * 返回后按同样的 RenderType / OverlayRenderType 追加绘制。
 */
public final class PlacementMeshRenderer {
    private interface SectionVisitor {
        void visit(PlacementMesh.GpuSection section, double ox, double oy, double oz);
    }

    private record TranslucentDraw(double distanceSq, PlacementMesh.GpuLayer layer, double ox, double oy, double oz) {}

    private static volatile Frustum frustum;

    private PlacementMeshRenderer() {}

    public static void captureFrustum(Frustum value) {
        frustum = value;
    }

    /**
     * 在 Litematica 画完某一层之后调用，追加所有被接管的投影在这一层的网格。
     * 顶点是区段相对坐标，绘制时用 modelView 平移到相机相对位置。
     */
    public static void renderLayer(RenderType layer, Matrix4f matrices, Matrix4f projMatrix,
                                   double cameraX, double cameraY, double cameraZ) {
        MeshUploader.processQueues();
        if (PlacementMeshManager.meshes().isEmpty()) return;
        MoveInterpolator.update();

        List<TranslucentDraw> translucentDraws = new ArrayList<>();
        TranslucentSorter sorter = TranslucentSorter.forFrame();
        boolean[] added = {false};

        forEachVisibleSection(cameraX, cameraY, cameraZ, true, (section, ox, oy, oz) -> {
            for (PlacementMesh.GpuLayer gpuLayer : section.layers()) {
                if (gpuLayer.layer != layer || gpuLayer.vertexBuffer.isInvalid()) continue;

                if (gpuLayer.sort != null) {
                    sorter.maybeResort(gpuLayer, -ox, -oy, -oz);
                    translucentDraws.add(new TranslucentDraw((ox + 8) * (ox + 8) + (oy + 8) * (oy + 8) + (oz + 8) * (oz + 8),
                            gpuLayer, ox, oy, oz));
                    continue;
                }

                added[0] = true;
            }
        });

        // 半透明：由远到近，与 Litematica 自己的反向遍历一致
        translucentDraws.sort(Comparator.comparingDouble(TranslucentDraw::distanceSq).reversed());
        added[0] |= !translucentDraws.isEmpty();

        if (!added[0]) return;

        layer.setupRenderState();
        // Litematica resets the ghost-block alpha before renderBlockLayer returns, so reapply it around our draws
        boolean ghost = Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue();
        if (ghost) {
            float alpha = (float) Configs.Visuals.GHOST_BLOCK_ALPHA.getDoubleValue();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        }
        try {
            ShaderInstance shader = RenderSystem.getShader();
            if (shader != null) {
                RenderSystem.setupShaderLights(shader);
            }
            Matrix4f modelView = new Matrix4f(matrices);

            forEachVisibleSection(cameraX, cameraY, cameraZ, false, (section, ox, oy, oz) -> {
                for (PlacementMesh.GpuLayer gpuLayer : section.layers()) {
                    if (gpuLayer.layer != layer || gpuLayer.sort != null || gpuLayer.vertexBuffer.isInvalid()) continue;
                    draw(gpuLayer.vertexBuffer, modelView, projMatrix, shader, ox, oy, oz);
                }
            });

            for (TranslucentDraw draw : translucentDraws) {
                draw(draw.layer().vertexBuffer, modelView, projMatrix, shader, draw.ox(), draw.oy(), draw.oz());
            }
        } finally {
            if (ghost) {
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
            layer.clearRenderState();
        }
    }

    private static void draw(VertexBuffer buffer, Matrix4f modelView, Matrix4f projMatrix, ShaderInstance shader,
                             double ox, double oy, double oz) {
        if (shader == null) return;
        Matrix4f translated = new Matrix4f(modelView).translate((float) ox, (float) oy, (float) oz);
        buffer.bind();
        buffer.drawWithShader(translated, projMatrix, shader);
        VertexBuffer.unbind();
    }

    public static void drawOverlays(OverlayRenderType type, Matrix4f matrices, Camera camera, Matrix4f projMatrix) {
        if (PlacementMeshManager.meshes().isEmpty()) return;

        Vec3 cameraPos = camera.getPosition();
        List<TranslucentDraw> draws = new ArrayList<>();

        forEachVisibleSection(cameraPos.x, cameraPos.y, cameraPos.z, false, (section, ox, oy, oz) -> {
            for (PlacementMesh.GpuLayer layer : section.layers()) {
                if (layer.overlay != type || layer.indexCount <= 0 || layer.vertexBuffer.isInvalid()) continue;
                draws.add(new TranslucentDraw((ox + 8) * (ox + 8) + (oy + 8) * (oy + 8) + (oz + 8) * (oz + 8),
                        layer, ox, oy, oz));
            }
        });

        if (draws.isEmpty()) return;

        // 由远到近，和 Litematica 自己的 overlay 反向遍历一致
        draws.sort(Comparator.comparingDouble(TranslucentDraw::distanceSq).reversed());

        boolean renderThrough = Configs.Visuals.SCHEMATIC_OVERLAY_RENDER_THROUGH.getBooleanValue() ||
                                Hotkeys.RENDER_OVERLAY_THROUGH_BLOCKS.getKeybind().isKeybindHeld();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (renderThrough) {
            RenderSystem.disableDepthTest();
        } else {
            RenderSystem.enableDepthTest();
        }

        ShaderInstance originalShader = RenderSystem.getShader();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        try {
            ShaderInstance shader = RenderSystem.getShader();
            Matrix4f modelView = new Matrix4f(matrices);

            // Litematica draws overlays back to front as well
            for (TranslucentDraw draw : draws) {
                Matrix4f translated = new Matrix4f(modelView).translate((float) draw.ox(), (float) draw.oy(), (float) draw.oz());
                draw.layer().vertexBuffer.bind();
                draw.layer().vertexBuffer.drawWithShader(translated, projMatrix, shader);
                VertexBuffer.unbind();
            }
        } finally {
            RenderSystem.setShader(() -> originalShader);
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
        }
    }

    private static void forEachVisibleSection(double cameraX, double cameraY, double cameraZ, boolean requestClipRebuilds, SectionVisitor visitor) {
        LayerRangeClip.Range range = LayerRangeClip.snapshot();
        Frustum fr = frustum;
        int renderDistance = (Minecraft.getInstance().options.getEffectiveRenderDistance() + 2) * 16;
        double maxDistanceSq = (double) renderDistance * renderDistance;

        for (Map.Entry<SchematicPlacement, PlacementMesh> entry : PlacementMeshManager.meshes().entrySet()) {
            SchematicPlacement placement = entry.getKey();
            PlacementMesh mesh = entry.getValue();
            if (mesh.active.isEmpty() || !placement.isEnabled() || !placement.isRenderingEnabled()) continue;

            BlockPos delta = placement.getOrigin().subtract(mesh.activeBuildOrigin);
            Vec3 lag = MoveInterpolator.getLag(placement);
            PlacementBlockAccessor accessor = mesh.targetAccessor;
            boolean sameBuild = accessor != null && mesh.activeBuildOrigin.equals(mesh.targetBuildOrigin);

            if (fr != null && sameBuild) {
                AABB box = new AABB(
                        accessor.minPos.getX() + delta.getX() + lag.x, accessor.minPos.getY() + delta.getY() + lag.y, accessor.minPos.getZ() + delta.getZ() + lag.z,
                        accessor.maxPos.getX() + delta.getX() + lag.x + 1, accessor.maxPos.getY() + delta.getY() + lag.y + 1, accessor.maxPos.getZ() + delta.getZ() + lag.z + 1);
                if (!fr.isVisible(box)) continue;
            }

            boolean canRebuild = requestClipRebuilds && sameBuild && mesh.uploadingFull == null && PendingMoveTracker.get(placement) == null;
            Set<Long> clipKeys = null;

            for (PlacementMesh.GpuSection section : mesh.active.values()) {
                if (sameBuild) {
                    PlacementMesh.Clip required = LayerRangeClip.compute(range, accessor.minPos, accessor.maxPos, section.origin(), delta);
                    if (required != null && required.isEmpty()) continue;
                    if (canRebuild && (section.hasBlocks() || !section.layers().isEmpty()) && !Objects.equals(required, section.clip()) &&
                        !mesh.clipRebuildPending.contains(section.key())) {
                        if (clipKeys == null) clipKeys = new HashSet<>();
                        clipKeys.add(section.key());
                    }
                }
                if (section.layers().isEmpty()) continue;

                double wx = section.origin().getX() + delta.getX() + lag.x;
                double wy = section.origin().getY() + delta.getY() + lag.y;
                double wz = section.origin().getZ() + delta.getZ() + lag.z;
                double cx = wx + 8.0 - cameraX;
                double cz = wz + 8.0 - cameraZ;
                if (cx * cx + cz * cz > maxDistanceSq) continue;
                if (fr != null && !fr.isVisible(new AABB(wx, wy, wz, wx + 16, wy + 16, wz + 16))) continue;

                visitor.visit(section, wx - cameraX, wy - cameraY, wz - cameraZ);
            }

            if (clipKeys != null) {
                mesh.clipRebuildPending.addAll(clipKeys);
                PlacementMeshManager.requestPartialBuild(placement, mesh, clipKeys);
            }
        }
    }
}
