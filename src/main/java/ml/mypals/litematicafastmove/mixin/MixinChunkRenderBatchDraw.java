package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import fi.dy.masa.litematica.render.schematic.ChunkRenderBatchDraw;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ChunkRenderBatchDraw.class, remap = false)
public class MixinChunkRenderBatchDraw {

    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;outputTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget routeBatchDrawTarget(ChunkSectionLayerGroup group, Operation<RenderTarget> original) {
        return SchematicExit.redirect(original.call(group));
    }
}
