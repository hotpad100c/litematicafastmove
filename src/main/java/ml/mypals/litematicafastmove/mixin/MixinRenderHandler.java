package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fi.dy.masa.litematica.event.RenderHandler;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Litematica 0.21.7 calls into malilib's advanced render event (the plain {@code onRenderWorldLast} of 1.20.6
 * no longer exists), which is where the placement boxes are drawn.
 */
@Mixin(value = RenderHandler.class, remap = false)
public class MixinRenderHandler {

    @WrapMethod(method = "onRenderWorldLastAdvanced")
    private void routeBoxes(Matrix4f matrices, Matrix4f projMatrix, Frustum frustum, Camera camera,
                            FogParameters fog, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix, frustum, camera, fog, profiler));
    }
}
