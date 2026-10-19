package ml.mypals.litematicafastmove.mixin;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.util.SchematicWorldRefresher;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import ml.mypals.litematicafastmove.sync.IFastMoveManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SchematicWorldRefresher.class, remap = false)
public abstract class MixinSchematicWorldRefresher {

    /**
     * Litematica 0.23.7 has no {@code onToggleMainRendering} hook; toggling rendering (and every other
     * full refresh) goes through here, so pending moves are committed first.
     */
    @Inject(method = "updateAll", at = @At("HEAD"))
    private void onUpdateAll(CallbackInfo ci) {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        if (manager != null) {
            ((IFastMoveManager) manager).litematicafastmove$commitAll();
        }
        PlacementMeshManager.rebuildAll();
    }
}
