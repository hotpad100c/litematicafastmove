package ml.mypals.litematicafastmove.mixin;

import fi.dy.masa.malilib.compat.iris.IrisCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.11 的 Iris 兼容搬到了 malilib（26.3 时在 Litematica 里），做法不变：
 * 关掉它的管线注册，交给 EXIT。
 */
@Mixin(value = IrisCompat.class, remap = false)
public class MixinIRISCompat {

    @Inject(method = "registerPipelines", at = @At("HEAD"), cancellable = true)
    private static void supressLitematicaIrisCompatRegistration(CallbackInfo ci) {
        ci.cancel();
    }
}
