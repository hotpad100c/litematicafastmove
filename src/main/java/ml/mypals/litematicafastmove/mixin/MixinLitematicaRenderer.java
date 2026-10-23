package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import ml.mypals.litematicafastmove.render.SchematicExit;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 0.18.3 splits the 26.3 {@code piecewiseDrawBlockLayerGroup} into one method per render layer, so each of them
 * gets its own portal.
 */
@Mixin(value = LitematicaRenderer.class, remap = false)
public class MixinLitematicaRenderer {

    @WrapMethod(method = "piecewiseRenderSolid")
    private void routeSolid(Matrix4f matrices, Matrix4f projMatrix, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix));
    }

    @WrapMethod(method = "piecewiseRenderCutoutMipped")
    private void routeCutoutMipped(Matrix4f matrices, Matrix4f projMatrix, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix));
    }

    @WrapMethod(method = "piecewiseRenderCutout")
    private void routeCutout(Matrix4f matrices, Matrix4f projMatrix, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix));
    }

    @WrapMethod(method = "piecewiseRenderTranslucent")
    private void routeTranslucent(Matrix4f matrices, Matrix4f projMatrix, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix));
    }

    @WrapMethod(method = "piecewiseRenderOverlay")
    private void routeOverlay(Matrix4f matrices, Matrix4f projMatrix, Operation<Void> original) {
        SchematicExit.flushBatch();
        SchematicExit.portal(() -> original.call(matrices, projMatrix));
    }

    @WrapMethod(method = "renderSchematicOverlay")
    private void routePlacementBoxes(Matrix4f matrices, Matrix4f projMatrix, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix));
    }
}
