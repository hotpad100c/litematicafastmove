package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import ml.mypals.litematicafastmove.mesh.PlacementMeshRenderer;
import ml.mypals.litematicafastmove.render.SchematicExit;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldRendererSchematic.class, remap = false)
public class MixinWorldRendererSchematic {

    /**
     * Draws the detached placement meshes for one block layer right after Litematica's own chunks, inside the same
     * EXIT portal, so both end up in the channel target.
     */
    @WrapMethod(method = "renderBlockLayer")
    private int routeBlockLayer(RenderType layer, PoseStack matrices, Camera camera, Matrix4f projMatrix,
                                Operation<Integer> original) {
        int[] count = {0};
        SchematicExit.portal(() -> {
            count[0] = original.call(layer, matrices, camera, projMatrix);
            PlacementMeshRenderer.drawLayer(layer, matrices, camera, projMatrix);
        });
        return count[0];
    }

    @WrapMethod(method = "renderBlockOverlay")
    private void routeBlockOverlay(ChunkRendererSchematicVbo.OverlayRenderType type, PoseStack matrices, Camera camera,
                                   Matrix4f projMatrix, Operation<Void> original) {
        SchematicExit.portal(() -> {
            original.call(type, matrices, camera, projMatrix);
            PlacementMeshRenderer.drawOverlays(type, matrices, camera, projMatrix);
        });
    }

    // Litematica 0.15.4 has no ProfilerFiller argument on setupTerrain (0.29 gained it).
    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void captureFrustum(Camera camera, Frustum frustum, int frameCount, boolean playerSpectator,
                                CallbackInfo ci) {
        PlacementMeshRenderer.captureFrustum(frustum);
    }

    @Inject(method = "loadRenderers", at = @At("RETURN"))
    private void onLoadRenderers(CallbackInfo ci) {
        PlacementMeshManager.rebuildAll();
    }

    /** Shifts schematic entities that still sit at their pre-move position while a placement is being moved. */
    @WrapOperation(method = "renderEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void shiftPendingEntity(EntityRenderDispatcher dispatcher, Entity entity, double x, double y, double z,
                                    float yaw, float partialTicks, PoseStack matrices, MultiBufferSource buffers,
                                    int light, Operation<Void> original) {
        if (PendingMoveTracker.hasAnyObjectShift()) {
            Vec3 shift = PendingMoveTracker.getObjectShift(entity.getX(), entity.getY(), entity.getZ());
            if (shift != null) {
                x += shift.x;
                y += shift.y;
                z += shift.z;
            }
        }
        original.call(dispatcher, entity, x, y, z, yaw, partialTicks, matrices, SchematicExit.batch(buffers), light);
    }

    /** Shifts schematic block entities that still sit at their pre-move position. */
    @WrapOperation(method = "renderEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"))
    private void shiftPendingBlockEntity(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity,
                                         float partialTicks, PoseStack matrices, MultiBufferSource buffers,
                                         Operation<Void> original) {
        Vec3 shift = null;
        if (PendingMoveTracker.hasAnyObjectShift()) {
            BlockPos pos = blockEntity.getBlockPos();
            shift = PendingMoveTracker.getObjectShift(pos.getX(), pos.getY(), pos.getZ());
        }
        if (shift != null) {
            matrices.pushPose();
            matrices.translate(shift.x, shift.y, shift.z);
        }
        original.call(dispatcher, blockEntity, partialTicks, matrices, SchematicExit.batch(buffers));
        if (shift != null) {
            matrices.popPose();
        }
    }
}
