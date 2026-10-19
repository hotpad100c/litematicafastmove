package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = LitematicaRenderer.class, remap = false)
public class MixinLitematicaRenderer {

    /**
     * 1.21.8 的 Litematica 同样不接收帧缓冲，只接收 {@link ChunkSectionLayerGroup}，目标帧缓冲由它内部的
     * {@code outputTarget()} 决定。传送门会把落在主目标上的 render pass 转到 EXIT 的目标；不是主目标的那两种
     * （半透明 / 天气）由 {@link MixinChunkRenderBatchDraw} 显式重定向。
     */
    @WrapMethod(method = "piecewiseDrawBlockLayerGroup")
    private void routeTerrain(ChunkSectionLayerGroup group, Operation<Void> original) {
        SchematicExit.portal(() -> {
            original.call(group);
            if (group == ChunkSectionLayerGroup.OPAQUE) {
                SchematicExit.flushBatch();
            }
        });
    }

    /**
     * 1.21.8 的实体 / 方块实体还是 {@code MultiBufferSource}：把 Litematica 自己的两个 immediate buffer
     * 换成 EXIT 通道的 batch，它们就会在关卡渲染末尾（着色器包之后）一起画。
     */
    @WrapOperation(method = "piecewiseRenderEntities", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/render/schematic/WorldRendererSchematic;renderEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;FLnet/minecraft/util/profiling/ProfilerFiller;)V"))
    private void routeEntities(WorldRendererSchematic renderer, Camera camera, Frustum frustum, PoseStack matrices,
                               MultiBufferSource buffers, float partialTicks, ProfilerFiller profiler, Operation<Void> original) {
        original.call(renderer, camera, frustum, matrices, SchematicExit.batch(buffers), partialTicks, profiler);
    }

    @WrapOperation(method = "piecewiseRenderBlockEntities", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/render/schematic/WorldRendererSchematic;renderBlockEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/MultiBufferSource;FLnet/minecraft/util/profiling/ProfilerFiller;)V"))
    private void routeBlockEntities(WorldRendererSchematic renderer, Camera camera, Frustum frustum, PoseStack matrices,
                                    MultiBufferSource buffers, MultiBufferSource noCullBuffers, float partialTicks,
                                    ProfilerFiller profiler, Operation<Void> original) {
        original.call(renderer, camera, frustum, matrices, SchematicExit.batch(buffers),
                SchematicExit.batch(noCullBuffers), partialTicks, profiler);
    }

    @WrapMethod(method = "piecewiseRenderOverlay")
    private void routeOverlay(Matrix4f posMatrix, Matrix4f projMatrix, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(posMatrix, projMatrix, profiler));
    }
}
