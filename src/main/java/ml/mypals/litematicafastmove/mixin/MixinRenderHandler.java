package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fi.dy.masa.litematica.event.RenderHandler;
import ml.mypals.litematicafastmove.render.SchematicExit;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = RenderHandler.class, remap = false)
public class MixinRenderHandler {

    @WrapMethod(method = "onRenderWorldLast")
    private void routeBoxes(Matrix4f matrices, Matrix4f projMatrix, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(matrices, projMatrix));
    }
}
