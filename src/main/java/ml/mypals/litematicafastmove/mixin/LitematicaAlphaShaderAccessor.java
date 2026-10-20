package ml.mypals.litematicafastmove.mixin;

import fi.dy.masa.litematica.render.LitematicaRenderer;
import fi.dy.masa.malilib.render.shader.ShaderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = LitematicaRenderer.class, remap = false)
public interface LitematicaAlphaShaderAccessor {
    @Accessor("SHADER_ALPHA")
    static ShaderProgram fastmove$getAlphaShader() {
        throw new AssertionError();
    }
}
