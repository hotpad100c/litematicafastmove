package ml.mypals.litematicafastmove.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Minecraft 1.18.2 has no public handle on a finished BufferBuilder: the RenderedBuffer that 1.19.3 hands out does not
 * exist yet, the data stays inside the builder until VertexBuffer#upload pops it. The mesh builder needs the draw
 * state and the raw vertex bytes (for the upload budget and the content hash) while it is still on the builder thread,
 * so it reads them through these accessors.
 */
@Mixin(BufferBuilder.class)
public interface BufferBuilderAccessor {

    @Accessor("drawStates")
    List<BufferBuilder.DrawState> lfm$drawStates();

    @Accessor("totalRenderedBytes")
    int lfm$totalRenderedBytes();

    @Accessor("buffer")
    ByteBuffer lfm$buffer();
}
