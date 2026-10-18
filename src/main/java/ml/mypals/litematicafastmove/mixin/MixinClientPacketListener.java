package ml.mypals.litematicafastmove.mixin;

import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPacketListener.class, remap = false)
public abstract class MixinClientPacketListener {

    /**
     * 1.18.2 and later send the chunk with its light in one {@code ClientboundLevelChunkWithLightPacket}; 1.17 still has
     * the chunk ({@code ClientboundLevelChunkPacket}) and the light in separate packets, so the hook moves to the
     * chunk packet's handler.
     */
    @Inject(method = "handleLevelChunk", at = @At("RETURN"))
    private void onChunkLoaded(ClientboundLevelChunkPacket packet, CallbackInfo ci) {
        PlacementMeshManager.markChunkDirty(packet.getX(), packet.getZ());
    }
}
