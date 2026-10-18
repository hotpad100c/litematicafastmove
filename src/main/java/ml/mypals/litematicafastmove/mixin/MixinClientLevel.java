package ml.mypals.litematicafastmove.mixin;

import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientLevel.class, remap = false)
public abstract class MixinClientLevel {

    /**
     * 1.19.3 hooks {@code setServerVerifiedBlockState}, which both the single-block and the section-blocks update
     * packet go through. 1.18.2 has no such method: {@link ClientLevel#setKnownState} serves the single-block update
     * and the section update calls {@code Level#setBlock} directly. The one hook both share is the client world's
     * {@code setBlocksDirty}, which Level leaves empty and ClientLevel overrides, and which is only reached when the
     * state really changed.
     */
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void onServerBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        PlacementMeshManager.markBlockDirty(pos);
    }
}
