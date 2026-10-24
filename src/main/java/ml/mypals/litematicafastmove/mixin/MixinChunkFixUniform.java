package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.malilib.render.uniform.ChunkFixUniform;
import ml.mypals.litematicafastmove.render.SchematicExit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ChunkFixUniform.class, remap = false)
public class MixinChunkFixUniform {

    @WrapOperation(method = "updateBuffer", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/malilib/compat/iris/IrisCompat;isShaderActive()Z"))
    private boolean shadersOffWhenRouted(Operation<Boolean> original) {
        return original.call() && !SchematicExit.isActive();
    }
}
