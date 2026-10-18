package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo.OverlayRenderType;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.sync.MoveInterpolator;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PlacementMeshRenderer {
    private record DrawSection(PlacementMesh.GpuLayer layer, double x, double y, double z, double distanceSq) {}

    private static volatile Frustum frustum;

    private PlacementMeshRenderer() {}

    public static void captureFrustum(Frustum value) {
        frustum = value;
    }

    /**
     * Appends the detached placement meshes of one block layer, right after Litematica drew its own chunks for it.
     * 1.16.5 has no chunk-offset uniform and no projection-matrix argument: the section offset goes into the pose
     * stack and {@code VertexBuffer#draw(Matrix4f, int)} draws with the mode given to it, exactly like
     * {@code WorldRendererSchematic#renderBlockLayer} does.
     */
    public static void drawLayer(RenderType layer, PoseStack matrices, Camera camera) {
        MeshUploader.processQueues();
        if (PlacementMeshManager.meshes().isEmpty()) return;
        MoveInterpolator.update();

        Vec3 cameraPos = camera.getPosition();
        TranslucentSorter sorter = TranslucentSorter.forFrame();
        boolean translucent = layer == RenderType.translucent();
        List<DrawSection> draws = new ArrayList<>();

        forEachVisibleSection(cameraPos.x, cameraPos.y, cameraPos.z, true, (section, ox, oy, oz) -> {
            for (PlacementMesh.GpuLayer gpuLayer : section.layers()) {
                if (gpuLayer.layer() != layer) continue;
                if (translucent && gpuLayer.sort() != null) {
                    sorter.maybeResort(gpuLayer, -ox, -oy, -oz);
                }
                double dx = ox + 8.0, dy = oy + 8.0, dz = oz + 8.0;
                draws.add(new DrawSection(gpuLayer, ox, oy, oz, dx * dx + dy * dy + dz * dz));
            }
        });

        if (draws.isEmpty()) return;

        // Litematica draws translucent chunks back to front
        draws.sort(translucent
                ? Comparator.comparingDouble(DrawSection::distanceSq).reversed()
                : Comparator.comparingDouble(DrawSection::distanceSq));

        // The ghost-block alpha comes from Litematica's own alpha shader: it calls setupAlphaShader()/enableAlphaShader()
        // around renderBlockLayer and this wrapper draws inside that call, so the shader program must not be replaced
        // here (there is no colour uniform to set either; 1.16.5 uses a custom shader program for that).
        layer.setupRenderState();
        int glMode = layer.mode();

        for (DrawSection draw : draws) {
            matrices.pushPose();
            matrices.translate(draw.x(), draw.y(), draw.z());
            VertexBuffer buffer = draw.layer().vertexBuffer();
            buffer.bind();
            layer.format().setupBufferState(0L);
            buffer.draw(matrices.last().pose(), glMode);
            matrices.popPose();
        }

        VertexBuffer.unbind();
        RenderSystem.clearCurrentColor();
        layer.format().clearBufferState();
        layer.clearRenderState();
    }

    /**
     * Draws the detached placements' overlays after Litematica drew its own for the same overlay type. The render
     * state around this (blend, alpha test, line width, polygon offset and the depth test for "render through") is set
     * up by Litematica's renderSchematicOverlay, so only what renderBlockOverlays turned off again is restored here.
     */
    public static void drawOverlays(OverlayRenderType type, Camera camera) {
        MeshUploader.processQueues();
        if (PlacementMeshManager.meshes().isEmpty()) return;
        MoveInterpolator.update();

        Vec3 cameraPos = camera.getPosition();
        List<DrawSection> draws = new ArrayList<>();

        forEachVisibleSection(cameraPos.x, cameraPos.y, cameraPos.z, false, (section, ox, oy, oz) -> {
            for (PlacementMesh.GpuLayer layer : section.layers()) {
                if (layer.overlay() != type || layer.vertexCount() <= 0) continue;
                double dx = ox + 8.0, dy = oy + 8.0, dz = oz + 8.0;
                draws.add(new DrawSection(layer, ox, oy, oz, dx * dx + dy * dy + dz * dz));
            }
        });

        if (draws.isEmpty()) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        for (DrawSection draw : draws) {
            PoseStack matrices = new PoseStack();
            matrices.translate(draw.x(), draw.y(), draw.z());
            VertexBuffer buffer = draw.layer().vertexBuffer();
            buffer.bind();
            DefaultVertexFormat.POSITION_COLOR.setupBufferState(0L);
            buffer.draw(matrices.last().pose(), type.getGlMode());
        }

        VertexBuffer.unbind();
        RenderSystem.clearCurrentColor();
        DefaultVertexFormat.POSITION_COLOR.clearBufferState();
        RenderSystem.disableBlend();
    }

    private static void forEachVisibleSection(double cameraX, double cameraY, double cameraZ, boolean requestClipRebuilds,
                                              SectionVisitor visitor) {
        LayerRangeClip.Range range = LayerRangeClip.snapshot();
        Frustum fr = frustum;
        int renderDistance = (Minecraft.getInstance().options.renderDistance + 2) * 16;
        double maxDistanceSq = (double) renderDistance * renderDistance;

        for (Map.Entry<SchematicPlacement, PlacementMesh> entry : PlacementMeshManager.meshes().entrySet()) {
            SchematicPlacement placement = entry.getKey();
            PlacementMesh mesh = entry.getValue();
            if (mesh.active.isEmpty() || !placement.isEnabled() || !placement.isRenderingEnabled()) continue;

            BlockPos delta = placement.getOrigin().subtract(mesh.activeBuildOrigin);
            Vec3 lag = MoveInterpolator.getLag(placement);
            PlacementBlockAccessor accessor = mesh.targetAccessor;
            boolean sameBuild = accessor != null && mesh.activeBuildOrigin.equals(mesh.targetBuildOrigin);

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

    private interface SectionVisitor {
        void visit(PlacementMesh.GpuSection section, double ox, double oy, double oz);
    }
}
