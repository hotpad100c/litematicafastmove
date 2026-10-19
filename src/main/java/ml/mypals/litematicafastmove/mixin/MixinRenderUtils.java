package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import fi.dy.masa.malilib.render.RenderUtils;
import ml.mypals.litematicafastmove.render.SchematicExit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.3 里这个方法是 {@code mainTarget()}，1.21.8 的 malilib 叫 {@code fb()}；
 * Litematica 的 overlay 绘制从它取主帧缓冲，路由时换成 EXIT 的目标。
 */
@Mixin(value = RenderUtils.class, remap = false)
public class MixinRenderUtils {

    @ModifyReturnValue(method = "fb", at = @At("RETURN"))
    private static RenderTarget routeMainTarget(RenderTarget original) {
        return SchematicExit.redirect(original);
    }
}
