package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderTaskSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.malilib.util.IntBoundingBox;
import ml.mypals.litematicafastmove.mesh.PlacementBlockEntities;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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

    @Inject(method = "rebuildChunk", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/render/schematic/ChunkRenderDataSchematic;setTimeBuilt(J)V"))
    private void addTakenOverBlockEntities(ChunkRenderTaskSchematic task, CallbackInfo ci) {
        if (this.schematicWorldView == null) return;

        // The chunk render data was registered on the task at the start of rebuildChunk
        ChunkRenderDataSchematic data = task.getChunkRenderData();
        if (data == null) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        PlacementBlockEntities.forEachInChunk(this.position.getX() >> 4, this.position.getZ() >> 4, packed -> {
            pos.set(packed);
            BlockState state = this.schematicWorldView.getBlockState(pos);
            if (state.hasBlockEntity()) {
                BlockEntity blockEntity = this.schematicWorldView.getBlockEntity(pos);
                if (blockEntity != null && net.minecraft.client.Minecraft.getInstance()
                        .getBlockEntityRenderDispatcher().getRenderer(blockEntity) != null) {
                    data.addBlockEntity(blockEntity);
                }
            }
        });
    }

    @WrapMethod(method = "rebuildChunk")
    private void plainFormatRebuild(ChunkRenderTaskSchematic task, Operation<Void> original) {
        SchematicExit.plainFormat(() -> original.call(task));
    }

    @WrapMethod(method = "resortTransparency")
    private void plainFormatResort(ChunkRenderTaskSchematic task, Operation<Void> original) {
        SchematicExit.plainFormat(() -> original.call(task));
    }
}
