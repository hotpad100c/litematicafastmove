package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import ml.mypals.litematicafastmove.sync.MoveInterpolator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 独立网格里的方块实体。
 *
 * 26.3 registered the detached block entity positions with Litematica's chunk renderer. 0.23.7's
 * {@code ChunkRendererSchematicVbo#addBlockEntity} can only take a {@link BlockEntity} that already exists in the
 * schematic world at the very position it is drawn, which is not true while a move is still deferred (the
 * schematic data is still at the base origin). So the block entities of a detached placement are rendered here
 * instead, with the same origin/lag translation as the block mesh.
 */
public class PlacementBlockEntities {

    /**
     * {@code PlacementMeshManager#meshes()} 是包内可见的，调用方（mixin 在别的包里）用这个判断
     * 有没有需要自绘的方块实体，免得白开一次 EXIT 传送门。
     */
    public static boolean hasAny() {
        return !PlacementMeshManager.meshes().isEmpty();
    }

    /**
     * 在 EXIT 的 portal 内、Litematica 自己的方块实体之后调用。
     */
    public static void renderDetached(Camera camera, float partialTicks, PoseStack matrices, MultiBufferSource buffers) {
        if (PlacementMeshManager.meshes().isEmpty()) return;

        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        if (world == null) return;

        Vec3 cameraPos = camera.getPosition();
        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();

        for (Map.Entry<SchematicPlacement, PlacementMesh> entry : PlacementMeshManager.meshes().entrySet()) {
            SchematicPlacement placement = entry.getKey();
            PlacementMesh mesh = entry.getValue();
            BlockPos buildOrigin = mesh.blockEntityBuildOrigin;
            if (buildOrigin == null || mesh.blockEntities.isEmpty() || !placement.isRenderingEnabled()) continue;

            Vec3 lag = MoveInterpolator.getLag(placement);
            // The schematic world still holds this placement's data at the build origin until the move is
            // committed, so translate from the build origin to the current (displayed) origin, plus the
            // interpolation lag — exactly what the block mesh does.
            BlockPos delta = placement.getOrigin().subtract(buildOrigin);

            for (long[] positions : mesh.blockEntities.values()) {
                for (long packed : positions) {
                    BlockPos buildPos = BlockPos.of(packed);
                    BlockEntity blockEntity = world.getBlockEntity(buildPos);
                    if (blockEntity == null) continue;

                    matrices.pushPose();
                    try {
                        matrices.translate(
                                buildPos.getX() + delta.getX() + lag.x - cameraPos.x,
                                buildPos.getY() + delta.getY() + lag.y - cameraPos.y,
                                buildPos.getZ() + delta.getZ() + lag.z - cameraPos.z);
                        dispatcher.render(blockEntity, partialTicks, matrices, buffers);
                    } finally {
                        matrices.popPose();
                    }
                }
            }
        }
    }

    static void replace(SchematicPlacement placement, PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer full) {
        mesh.blockEntities.clear();
        mesh.blockEntityBuildOrigin = full.buildOrigin;
        for (PlacementMesh.GpuSection section : full.uploaded) {
            if (section.blockEntities().length > 0) {
                mesh.blockEntities.put(section.key(), section.blockEntities());
            }
        }
    }

    static void update(SchematicPlacement placement, PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer partial) {
        Map<Long, long[]> updated = new HashMap<>();
        for (PlacementMesh.GpuSection section : partial.uploaded) {
            updated.put(section.key(), section.blockEntities());
        }

        for (long key : partial.keys) {
            long[] before = mesh.blockEntities.get(key);
            long[] after = updated.get(key);
            if (after != null && after.length == 0) after = null;
            if (Arrays.equals(before, after)) continue;

            if (after != null) {
                mesh.blockEntities.put(key, after);
            } else {
                mesh.blockEntities.remove(key);
            }
        }
    }
}
