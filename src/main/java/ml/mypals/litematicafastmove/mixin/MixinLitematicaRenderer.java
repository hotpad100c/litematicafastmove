package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.render.IWorldSchematicRenderer;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = LitematicaRenderer.class, remap = false)
public class MixinLitematicaRenderer {

    /**
     * 1.21.11 的 Litematica 不接收帧缓冲，只接收 {@link ChunkSectionLayerGroup}，目标帧缓冲由它内部的
     * {@code outputTarget()} 决定。传送门会把落在主目标上的 render pass 转到 EXIT 的目标；不是主目标的那两种
     * （半透明 / 天气）由 {@link MixinChunkRenderBatchDraw} 显式重定向。
     */
    @WrapMethod(method = "piecewiseDrawBlockLayerGroup")
    private void routeTerrain(ChunkSectionLayerGroup group, GpuSampler sampler, Operation<Void> original) {
        SchematicExit.portal(() -> {
            original.call(group, sampler);
            if (group == ChunkSectionLayerGroup.OPAQUE) {
                SchematicExit.flushBatch();
            }
        });
    }

    @ModifyVariable(method = "piecewiseRenderEntities", at = @At("HEAD"), argsOnly = true)
    private SubmitNodeCollector routeEntities(SubmitNodeCollector queue) {
        return SchematicExit.batch(queue);
    }

    @WrapOperation(method = "piecewiseRenderBlockEntities", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/render/IWorldSchematicRenderer;renderBlockEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/util/profiling/ProfilerFiller;)V"))
    private void routeBlockEntities(IWorldSchematicRenderer renderer, Camera camera, Frustum frustum, PoseStack matrices,
                                    LevelRenderState renderStates, SubmitNodeCollector queue, ProfilerFiller profiler,
                                    Operation<Void> original) {
        original.call(renderer, camera, frustum, matrices, renderStates, SchematicExit.batch(queue), profiler);
    }

    @WrapMethod(method = "piecewiseRenderOverlay")
    private void routeOverlay(Matrix4f posMatrix, Matrix4f projMatrix, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(posMatrix, projMatrix, profiler));
    }
}
