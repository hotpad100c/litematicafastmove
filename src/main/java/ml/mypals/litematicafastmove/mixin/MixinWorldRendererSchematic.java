package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import ml.mypals.litematicafastmove.mesh.PlacementBlockEntities;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import ml.mypals.litematicafastmove.mesh.PlacementMeshRenderer;
import ml.mypals.litematicafastmove.render.SchematicExit;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WorldRendererSchematic.class, remap = false)
public class MixinWorldRendererSchematic {

    /**
     * Litematica 0.21.7 draws one {@link RenderType} at a time instead of collecting a render map, so the
     * detached placement meshes are appended right after Litematica finished that same layer.
     */
    @Inject(method = "renderBlockLayer", at = @At("RETURN"))
    private void appendDetachedDraws(RenderType layer, Matrix4f matrices, Camera camera, Matrix4f projMatrix,
                                     ProfilerFiller profiler, ShaderInstance shader,
                                     CallbackInfoReturnable<Integer> cir) {
        Vec3 cameraPos = camera.getPosition();
        PlacementMeshRenderer.renderLayer(layer, matrices, projMatrix, cameraPos.x, cameraPos.y, cameraPos.z);
    }

    @Inject(method = "renderBlockOverlay", at = @At("RETURN"))
    private void drawDetachedPlacementOverlays(OverlayRenderType type, Matrix4f matrices, Camera camera, Matrix4f projMatrix,
                                               ProfilerFiller profiler, CallbackInfo ci) {
        PlacementMeshRenderer.drawOverlays(type, matrices, camera, projMatrix);
    }

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void captureFrustum(Camera camera, Frustum frustum, int frameCount, boolean playerSpectator,
                                ProfilerFiller profiler, CallbackInfo ci) {
        PlacementMeshRenderer.captureFrustum(frustum);
    }

    @Inject(method = "loadRenderers", at = @At("RETURN"))
    private void onLoadRenderers(ProfilerFiller profiler, CallbackInfo ci) {
        PlacementMeshManager.rebuildAll();
    }

    /**
     * 被接管的投影不会进入 Litematica 的区块网格，所以它们的方块实体在这里自己画：
     * 位置按 origin/lag 平移，绘制结果进入 EXIT 的 batch。
     *
     * 0.21.7 renders the block entities in {@code renderBlockEntities} (0.18.3 did it in {@code renderEntities}).
     */
    @Inject(method = "renderBlockEntities", at = @At("RETURN"))
    private void renderDetachedBlockEntities(Camera camera, Frustum frustum, PoseStack matrices,
                                             MultiBufferSource.BufferSource buffers,
                                             MultiBufferSource.BufferSource buffers2, float partialTicks,
                                             ProfilerFiller profiler, CallbackInfo ci) {
        if (!PlacementMeshManager.hasMeshes()) return;

        SchematicExit.portal(() -> {
            PoseStack poseStack = new PoseStack();
            MultiBufferSource.BufferSource source = Minecraft.getInstance().renderBuffers().bufferSource();
            PlacementBlockEntities.renderDetached(camera, partialTicks, poseStack, SchematicExit.batch(source));
        });
    }

    /**
     * 移动尚未提交时，WorldSchematic 里的实体还在旧位置，这里按差量平移渲染，
     * 同时把它们的 buffer source 交给 EXIT 的 batch。
     */
    @WrapOperation(method = "renderEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void shiftPendingEntity(EntityRenderDispatcher dispatcher, Entity entity, double x, double y, double z,
                                    float yaw, float partialTicks, PoseStack matrices, MultiBufferSource buffers, int light,
                                    Operation<Void> original) {
        Vec3 shift = PendingMoveTracker.hasAnyObjectShift()
                ? PendingMoveTracker.getObjectShift(entity.getX(), entity.getY(), entity.getZ())
                : null;
        original.call(dispatcher, entity,
                x + (shift != null ? shift.x : 0.0),
                y + (shift != null ? shift.y : 0.0),
                z + (shift != null ? shift.z : 0.0),
                yaw, partialTicks, matrices, SchematicExit.batch(buffers), light);
    }

    /**
     * 方块实体同样按差量平移；0.21.7 在 {@code renderBlockEntities} 里画它们。
     */
    @WrapOperation(method = "renderBlockEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"))
    private void shiftPendingBlockEntity(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity, float partialTicks,
                                         PoseStack matrices, MultiBufferSource buffers, Operation<Void> original) {
        BlockPos pos = blockEntity.getBlockPos();
        Vec3 shift = PendingMoveTracker.hasAnyObjectShift()
                ? PendingMoveTracker.getObjectShift(pos.getX(), pos.getY(), pos.getZ())
                : null;
        if (shift == null) {
            original.call(dispatcher, blockEntity, partialTicks, matrices, SchematicExit.batch(buffers));
            return;
        }

        matrices.pushPose();
        try {
            matrices.translate(shift.x, shift.y, shift.z);
            original.call(dispatcher, blockEntity, partialTicks, matrices, SchematicExit.batch(buffers));
        } finally {
            matrices.popPose();
        }
    }
}
