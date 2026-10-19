package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderTaskSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.malilib.util.IntBoundingBox;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = ChunkRendererSchematicVbo.class, remap = false)
public abstract class MixinChunkRendererSchematicVbo {

    @Shadow @Final protected BlockPos.MutableBlockPos position;
    @Shadow @Final protected List<IntBoundingBox> boxes;
    @Shadow protected ChunkCacheSchematic schematicWorldView;

    /**
     * Litematica 0.21.7 fills {@code boxes} from the placement manager in {@code rebuildWorldView}; the
     * detached placements must be left out so their own mesh is the only thing drawn.
     */
    @Inject(method = "rebuildWorldView", at = @At("RETURN"))
    private void excludeTakenOverPlacements(CallbackInfo ci) {
        int chunkX = this.position.getX() >> 4;
        int chunkZ = this.position.getZ() >> 4;

        synchronized (this.boxes) {
            this.boxes.clear();
            for (SchematicPlacementManager.PlacementPart part : DataManager.getSchematicPlacementManager().getPlacementPartsInChunk(chunkX, chunkZ)) {
                if (!PlacementMeshManager.isDetached(part.placement)) {
                    this.boxes.add(part.bb);
                }
            }
        }
    }

    @WrapMethod(method = "rebuildChunk")
    private void plainFormatRebuild(ChunkRenderTaskSchematic task, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.plainFormat(() -> original.call(task, profiler));
    }

    @WrapMethod(method = "resortTransparency")
    private void plainFormatResort(ChunkRenderTaskSchematic task, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.plainFormat(() -> original.call(task, profiler));
    }
}
