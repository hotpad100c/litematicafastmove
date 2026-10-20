package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Diagnostics only: forces every vertex colour to magenta while the mesh is being built.
 *
 * <p>1.16.5 has no RenderSystem#setShaderColor, and its chunk shader is not reachable from the draw call either, so
 * the colour modulator cannot be used for the tint test the other ports use. Writing the colour into the vertices
 * works through any shader that multiplies the vertex colour, and it is opt-in via -Dlfm.tint=true.
 */
public final class DebugTintConsumer implements VertexConsumer {

    private static final int R = 255;
    private static final int G = 0;
    private static final int B = 255;
    private static final int A = 255;

    private final VertexConsumer delegate;

    public DebugTintConsumer(VertexConsumer delegate) {
        this.delegate = delegate;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        delegate.color(R, G, B, A);
        return this;
    }

    @Override
    public VertexConsumer color(float red, float green, float blue, float alpha) {
        delegate.color(R, G, B, A);
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        delegate.uv(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        delegate.overlayCoords(u, v);
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        delegate.uv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        delegate.normal(x, y, z);
        return this;
    }

    @Override
    public void endVertex() {
        delegate.endVertex();
    }
}
