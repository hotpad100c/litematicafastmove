package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.malilib.render.uniform.ChunkFixUniform;
import ml.mypals.litematicafastmove.render.SchematicExit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.3 的 {@code fi.dy.masa.litematica.render.uniform.LegacyTerrainFixUniform} 在 1.21.11 是
 * malilib 的 {@link ChunkFixUniform}：它把 "着色器是否开启" 写进区块修复 uniform，路由时应当算作关闭。
 */
@Mixin(value = ChunkFixUniform.class, remap = false)
public class MixinChunkFixUniform {

    @WrapOperation(method = "fillBuffer", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/malilib/compat/iris/IrisCompat;isShaderActive()Z"))
    private boolean shadersOffWhenRouted(Operation<Boolean> original) {
        return original.call() && !SchematicExit.isActive();
    }
}
