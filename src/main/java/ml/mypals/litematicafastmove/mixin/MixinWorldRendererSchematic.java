package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
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
import net.minecraft.client.renderer.DynamicUniforms.Transform;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

@Mixin(value = WorldRendererSchematic.class, remap = false)
public class MixinWorldRendererSchematic {


    // MixinExtras resolves @Local(name=...) only when the handler parameter type is EXACTLY the type the local
    // carries in the target's LocalVariableTable: Mixin's LocalVariableDiscriminator.findExplicitLocal compares
    // Local.type to the context's return type with Type.equals, so a supertype (List for an ArrayList local) is
    // NOT accepted and the sugar fails with "Unable to find matching local!". This Litematica build declares
    // these locals as ArrayList / EnumMap / Vector4f - hence the concrete types here.
    @ModifyVariable(method = "prepareBlockLayers", at = @At("LOAD"), name = "startedDrawing")
    private boolean appendDetachedPlacementDraws(boolean startedDrawing,
                                                 @Local(argsOnly = true) Matrix4fc modelView,
                                                 @Local(argsOnly = true, ordinal = 0) double cameraX,
                                                 @Local(argsOnly = true, ordinal = 1) double cameraY,
                                                 @Local(argsOnly = true, ordinal = 2) double cameraZ,
                                                 @Local(name = "uniformValues") ArrayList<Transform> transformValues,
                                                 @Local(name = "renderMap") EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> renderMap,
                                                 @Local(name = "colorVector") Vector4f colorMod,
                                                 @Local(name = "matrix4f") Matrix4f texMatrix,
                                                 @Local(name = "indexCount") LocalIntRef indexCount) {
        boolean added = PlacementMeshRenderer.appendDraws(renderMap, transformValues, modelView, colorMod, texMatrix,
                cameraX, cameraY, cameraZ, indexCount);
        return startedDrawing || added;
    }

    @Inject(method = "renderBlockOverlay", at = @At("RETURN"))
    private void drawDetachedPlacementOverlays(OverlayRenderType type, Camera camera, float lineWidth, ProfilerFiller profiler, CallbackInfo ci) {
        PlacementMeshRenderer.drawOverlays(type, camera);
    }

    /**
     * 移动尚未提交时，WorldSchematic 里的实体还在旧位置，这里按差量平移渲染，
     * 同时把它们的 buffer source 交给 EXIT 的 batch。
     */
    @WrapOperation(method = "renderEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void shiftPendingEntity(EntityRenderDispatcher dispatcher, Entity entity, double x, double y, double z,
                                    float partialTicks, PoseStack matrices, MultiBufferSource buffers, int light,
                                    Operation<Void> original) {
        Vec3 shift = PendingMoveTracker.hasAnyObjectShift()
                ? PendingMoveTracker.getObjectShift(entity.getX(), entity.getY(), entity.getZ())
                : null;
        original.call(dispatcher, entity,
                x + (shift != null ? shift.x : 0.0),
                y + (shift != null ? shift.y : 0.0),
                z + (shift != null ? shift.z : 0.0),
                partialTicks, matrices, SchematicExit.batch(buffers), light);
    }

    /**
     * 被接管的投影不会进入 Litematica 的区块网格，所以它们的方块实体在这里自己画：
     * 位置按 origin/lag 平移，绘制结果进入 EXIT 的 batch。
     */
    @Inject(method = "renderBlockEntities", at = @At("RETURN"))
    private void renderDetachedBlockEntities(Camera camera, Frustum frustum, PoseStack matrices,
                                             MultiBufferSource.BufferSource buffers,
                                             MultiBufferSource.BufferSource noCullBuffers, float partialTicks,
                                             ProfilerFiller profiler, CallbackInfo ci) {
        if (!PlacementBlockEntities.hasAny()) return;

        SchematicExit.portal(() -> {
            PoseStack poseStack = new PoseStack();
            PlacementBlockEntities.renderDetached(camera, partialTicks, poseStack, SchematicExit.batch(noCullBuffers));
        });
    }

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

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void captureFrustum(Camera camera, Frustum frustum, int frameCount, boolean playerSpectator, ProfilerFiller profiler, CallbackInfo ci) {
        PlacementMeshRenderer.captureFrustum(frustum);
    }

    @Inject(method = "loadRenderers", at = @At("RETURN"))
    private void onLoadRenderers(ProfilerFiller profiler, CallbackInfo ci) {
        PlacementMeshManager.rebuildAll();
    }
}
