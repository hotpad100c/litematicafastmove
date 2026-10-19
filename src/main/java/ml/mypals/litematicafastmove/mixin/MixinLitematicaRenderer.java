package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Litematica 0.21.7 splits the schematic rendering into one method per render layer, and each of them now
 * takes the profiler as well, so every layer gets its own portal.
 */
@Mixin(value = LitematicaRenderer.class, remap = false)
public class MixinLitematicaRenderer {

    @WrapMethod(method = "piecewiseRenderSolid")
    private void routeSolid(Matrix4f matrices, Matrix4f projMatrix, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix, profiler));
    }

    @WrapMethod(method = "piecewiseRenderCutoutMipped")
    private void routeCutoutMipped(Matrix4f matrices, Matrix4f projMatrix, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix, profiler));
    }

    @WrapMethod(method = "piecewiseRenderCutout")
    private void routeCutout(Matrix4f matrices, Matrix4f projMatrix, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix, profiler));
    }

    @WrapMethod(method = "piecewiseRenderTripwire")
    private void routeTripwire(Matrix4f matrices, Matrix4f projMatrix, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix, profiler));
    }

    @WrapMethod(method = "piecewiseRenderTranslucent")
    private void routeTranslucent(Matrix4f matrices, Matrix4f projMatrix, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix, profiler));
    }

    @WrapMethod(method = "piecewiseRenderOverlay")
    private void routeOverlay(Matrix4f matrices, Matrix4f projMatrix, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix, profiler));
    }

    @WrapMethod(method = "renderSchematicOverlay")
    private void routePlacementBoxes(Matrix4f matrices, Matrix4f projMatrix, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix, profiler));
    }
}
