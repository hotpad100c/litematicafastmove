package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.event.RenderHandler;
import ml.mypals.litematicafastmove.render.SchematicExit;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = RenderHandler.class, remap = false)
public class MixinRenderHandler {

    @WrapMethod(method = "onRenderWorldLast")
    private void routeBoxes(float partialTicks, PoseStack matrices, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(partialTicks, matrices));
    }
}
