package ml.mypals.litematicafastmove;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import ml.mypals.litematicafastmove.mesh.MeshDebugGizmos;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import ml.mypals.litematicafastmove.sync.IFastMoveManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

public class LitematicaFastMoveClient implements ClientModInitializer {
    private static int ticks;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null) return;

            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
            if (manager == null) return;
            ((IFastMoveManager) manager).litematicafastmove$commitSettled(System.currentTimeMillis());

            PlacementMeshManager.processDirty();

            if (++ticks % 10 == 0) {
                PlacementMeshManager.tick();
            }
        });

        // 1.21.8 的 Fabric API 还没有 LevelRenderEvents；世界渲染结束是同一时刻
        WorldRenderEvents.END.register(context -> MeshDebugGizmos.flush());
    }
}
