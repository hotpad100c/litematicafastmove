package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo.OverlayRenderType;
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

public final class PlacementMeshRenderer {
    private record DrawSection(PlacementMesh.GpuLayer layer, double x, double y, double z, double distanceSq) {}

    private static volatile Frustum frustum;

    private PlacementMeshRenderer() {}

    public static void captureFrustum(Frustum value) {
        frustum = value;
    }

    /**
     * Appends the detached placement meshes of one block layer, right after Litematica drew its own chunks for it.
     * Uses the same shader, vertex format and chunk offset uniform as {@code WorldRendererSchematic#renderBlockLayer}.
     */
    public static void drawLayer(RenderType layer, PoseStack matrices, Camera camera, Matrix4f projMatrix) {
        // diagnostics: -Dlfm.nodraw=true skips our draws (the reference mod's own chunks stay visible)
        if (Boolean.getBoolean("lfm.nodraw")) return;
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

        layer.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        BufferUploader.reset();

        boolean renderAsTranslucent = Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue();
        if (renderAsTranslucent) {
            float alpha = (float) Configs.Visuals.GHOST_BLOCK_ALPHA.getDoubleValue();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        }

        // Same uniforms WorldRendererSchematic#initShader sets for Litematica's own chunks
        if (Boolean.getBoolean("lfm.tint")) {
            RenderSystem.setShaderColor(1.0f, 0.0f, 1.0f, 1.0f);
        }
        initShader(shader, matrices, projMatrix);
        RenderSystem.setupShaderLights(shader);
        shader.apply();

        Uniform chunkOffset = shader.CHUNK_OFFSET;
        boolean startedDrawing = false;

        for (DrawSection draw : draws) {
            if (chunkOffset != null) {
                chunkOffset.set((float) draw.x() + Integer.getInteger("lfm.shift", 0), (float) draw.y(), (float) draw.z());
                chunkOffset.upload();
            }
            VertexBuffer buffer = draw.layer().vertexBuffer();
            // VertexBuffer#draw only issues the GL call: the vertex format (VAO + attribute pointers) has to be
            // bound around it, otherwise the driver reads garbage and the geometry lands right in front of the camera
            layer.format().setupBufferState();
            buffer.bind();
            // VertexBuffer#draw() re-applies whatever shader RenderSystem currently holds; after
            // renderBlockLayer that is Litematica's restored shader, whose vertex layout does not match this
            // buffer, so parts of the mesh come out scattered. Draw with the layer's own shader instead.
            buffer.drawWithShader(new Matrix4f(matrices.last().pose()), projMatrix, shader);
            VertexBuffer.unbind();
            layer.format().clearBufferState();
            startedDrawing = true;
        }

        if (renderAsTranslucent) {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
        if (chunkOffset != null) {
            chunkOffset.set(0.0f, 0.0f, 0.0f);
        }

        shader.clear();
        if (startedDrawing) {
            layer.format().clearBufferState();
        }
        VertexBuffer.unbind();
        layer.clearRenderState();
    }

    /** Draws the detached placements' overlays, after Litematica drew its own for the same overlay type. */
    public static void drawOverlays(OverlayRenderType type, Camera camera, Matrix4f projMatrix) {
        MeshUploader.processQueues();
        if (PlacementMeshManager.meshes().isEmpty()) return;
        MoveInterpolator.update();

        Vec3 cameraPos = camera.getPosition();
        List<DrawSection> draws = new ArrayList<>();

        forEachVisibleSection(cameraPos.x, cameraPos.y, cameraPos.z, false, (section, ox, oy, oz) -> {
            for (PlacementMesh.GpuLayer layer : section.layers()) {
                if (layer.overlay() != type || layer.indexCount() <= 0) continue;
                double dx = ox + 8.0, dy = oy + 8.0, dz = oz + 8.0;
                draws.add(new DrawSection(layer, ox, oy, oz, dx * dx + dy * dy + dz * dz));
            }
        });

        if (draws.isEmpty()) return;

        boolean renderThrough = Configs.Visuals.SCHEMATIC_OVERLAY_RENDER_THROUGH.getBooleanValue() ||
                                Hotkeys.RENDER_OVERLAY_THROUGH_BLOCKS.getKeybind().isKeybindHeld();

        RenderType renderLayer = RenderType.translucent();
        renderLayer.setupRenderState();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (renderThrough) {
            RenderSystem.disableDepthTest();
        } else {
            RenderSystem.enableDepthTest();
        }

        ShaderInstance originalShader = RenderSystem.getShader();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        ShaderInstance shader = RenderSystem.getShader();
        BufferUploader.reset();

        for (DrawSection draw : draws) {
            PoseStack matrices = new PoseStack();
            matrices.translate(draw.x(), draw.y(), draw.z());
            VertexBuffer buffer = draw.layer().vertexBuffer();
            buffer.bind();
            buffer.drawWithShader(matrices.last().pose(), projMatrix, shader);
            VertexBuffer.unbind();
        }

        renderLayer.clearRenderState();
        RenderSystem.setShader(() -> originalShader);
        RenderSystem.disableBlend();
    }

    private static void initShader(ShaderInstance shader, PoseStack matrices, Matrix4f projMatrix) {
        for (int i = 0; i < 12; ++i) {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }
        if (shader.MODEL_VIEW_MATRIX != null) shader.MODEL_VIEW_MATRIX.set(matrices.last().pose());
        if (shader.PROJECTION_MATRIX != null) shader.PROJECTION_MATRIX.set(projMatrix);
        if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        if (shader.FOG_START != null) shader.FOG_START.set(RenderSystem.getShaderFogStart());
        if (shader.FOG_END != null) shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        if (shader.FOG_COLOR != null) shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        if (shader.TEXTURE_MATRIX != null) shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        if (shader.GAME_TIME != null) shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
    }

    private static void forEachVisibleSection(double cameraX, double cameraY, double cameraZ, boolean requestClipRebuilds,
                                              SectionVisitor visitor) {
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

            boolean canRebuild = requestClipRebuilds && sameBuild && mesh.uploadingFull == null && PendingMoveTracker.get(placement) == null;
            Set<Long> clipKeys = null;

            for (PlacementMesh.GpuSection section : mesh.active.values()) {
                if (sameBuild) {
                    PlacementMesh.Clip required = LayerRangeClip.compute(range, accessor.minPos, accessor.maxPos, section.origin(), delta);
                    // NB: do not skip on an "empty" clip - with the layer-range clip stale the section would be
                    // dropped for cameras far from where the mesh was first built (empty west/east/north views)
                    // while the geometry itself is fine.

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
