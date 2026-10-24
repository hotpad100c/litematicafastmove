package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import fi.dy.masa.litematica.util.SchematicUtils;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SchematicUtils.class, remap = false)
public abstract class MixinSchematicUtils {

    /** Single-block edits only mark the chunk for Litematica's rebuild, which skips the detached placements. */
    @Inject(method = "setTargetedSchematicBlockState*", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/schematic/placement/SchematicPlacementManager;markChunkForRebuild"))
    private static void onSchematicBlockEdited(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true) BlockPos pos) {
        PlacementMeshManager.markSchematicBlockDirty(pos);
    }
}
