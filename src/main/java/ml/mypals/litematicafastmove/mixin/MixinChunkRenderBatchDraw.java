package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import fi.dy.masa.litematica.render.schematic.ChunkRenderBatchDraw;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 1.21.11 的区块批量绘制自己去 {@code group.outputTarget()} 拿目标，其中半透明与天气两组不是主目标，
 * EXIT 的传送门不会自动接管它们，这里按 26.3 的做法显式重定向到 EXIT 的目标。
 */
@Mixin(value = ChunkRenderBatchDraw.class, remap = false)
public class MixinChunkRenderBatchDraw {

    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;outputTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget routeBatchDrawTarget(ChunkSectionLayerGroup group, Operation<RenderTarget> original) {
        return SchematicExit.redirect(original.call(group));
    }
}
