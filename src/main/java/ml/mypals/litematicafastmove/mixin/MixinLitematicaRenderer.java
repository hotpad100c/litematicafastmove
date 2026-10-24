package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import ml.mypals.litematicafastmove.render.SchematicExit;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = LitematicaRenderer.class, remap = false)
public class MixinLitematicaRenderer {

    // The block layers themselves are routed inside WorldRendererSchematic#renderBlockLayer, which is where the
    // detached placement meshes are appended as well.
    @WrapMethod(method = "piecewiseRenderOverlay")
    private void routeOverlay(PoseStack matrices, Operation<Void> original) {
        SchematicExit.flushBatch();
        SchematicExit.portal(() -> original.call(matrices));
    }

    @WrapMethod(method = "piecewiseRenderEntities")
    private void routeEntities(PoseStack matrices, float partialTicks, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, partialTicks));
    }
}
