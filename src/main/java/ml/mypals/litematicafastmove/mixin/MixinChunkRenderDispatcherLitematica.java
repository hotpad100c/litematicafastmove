package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import fi.dy.masa.litematica.render.schematic.BufferAllocatorCache;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDispatcherLitematica;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Litematica 的区块网格在 rebuildChunk 里用 plainFormat 构建成原版格式，但在 portal 之外
 * (piecewisePrepareAndUpdate → runChunkUploads) 上传；Iris 1.8 会在上传时给 VAO 换上加宽的布局，所以要包进 plainUpload
 */
@Mixin(value = ChunkRenderDispatcherLitematica.class, remap = false)
public abstract class MixinChunkRenderDispatcherLitematica {

    @WrapMethod(method = "uploadVertexBufferByLayer")
    private void plainUploadLayer(RenderType layer, BufferAllocatorCache allocators, ChunkRendererSchematicVbo renderer,
                                  ChunkRenderDataSchematic data, VertexSorting sorting, boolean resortOnly, ProfilerFiller profiler,
                                  Operation<Void> original) throws InterruptedException {
        upload(() -> original.call(layer, allocators, renderer, data, sorting, resortOnly, profiler));
    }

    @WrapMethod(method = "uploadVertexBufferByType")
    private void plainUploadOverlay(OverlayRenderType type, BufferAllocatorCache allocators, ChunkRendererSchematicVbo renderer,
                                    ChunkRenderDataSchematic data, VertexSorting sorting, boolean resortOnly, ProfilerFiller profiler,
                                    Operation<Void> original) throws InterruptedException {
        upload(() -> original.call(type, allocators, renderer, data, sorting, resortOnly, profiler));
    }

    // plainUpload 只能在渲染线程调用
    private static void upload(Runnable upload) {
        if (RenderSystem.isOnRenderThread()) {
            SchematicExit.plainUpload(upload);
        } else {
            upload.run();
        }
    }
}
