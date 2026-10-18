package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.BufferBuilder;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.render.RenderUtils;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo.OverlayRenderType;
import fi.dy.masa.litematica.util.OverlayType;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.Color4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Function;

/**
 * Builds the overlay (missing / extra / wrong block hints) into the detached placement's own buffers,
 * following Litematica 0.17.4's ChunkRendererSchematicVbo#renderOverlay.
 * <p>
 * Coordinates: {@code pos} is a build coordinate; the matching client world position is
 * {@code pos + clientDelta}. Vertices are written relative to the section origin ({@code x & 15}, ...).
 * <p>
 * Differences to the 0.29 code this was ported from: 0.17.4 has no overlay culling option, no different-block
 * grouping and no separate "render schematic fluids" switch, so those behave like their 0.17.4 defaults.
 */
final class OverlayMeshBuilder {
    interface ClientView {
        BlockState get(BlockPos buildPos);
    }

    private final DetachedBlockView schematic;
    private final ClientView client;
    private final Function<OverlayRenderType, BufferBuilder> buffers;

    private final boolean enableSides = Configs.Visuals.SCHEMATIC_OVERLAY_ENABLE_SIDES.getBooleanValue();
    private final boolean enableOutlines = Configs.Visuals.SCHEMATIC_OVERLAY_ENABLE_OUTLINES.getBooleanValue();
    private final boolean reducedInnerSides = Configs.Visuals.OVERLAY_REDUCED_INNER_SIDES.getBooleanValue();
    private final boolean modelSides = Configs.Visuals.SCHEMATIC_OVERLAY_MODEL_SIDES.getBooleanValue();
    private final boolean modelOutline = Configs.Visuals.SCHEMATIC_OVERLAY_MODEL_OUTLINE.getBooleanValue();
    private final boolean translucentInnerSides = Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue() &&
                                                  Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES.getBooleanValue();
    private final boolean ignoreClientFluids = Configs.Visuals.IGNORE_EXISTING_FLUIDS.getBooleanValue();

    private final BlockPos.MutableBlockPos relPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos();

    OverlayMeshBuilder(DetachedBlockView schematic, ClientView client, Function<OverlayRenderType, BufferBuilder> buffers) {
        this.schematic = schematic;
        this.client = client;
        this.buffers = buffers;
    }

    static boolean isEnabled() {
        return Configs.Visuals.ENABLE_SCHEMATIC_OVERLAY.getBooleanValue();
    }

    void addOverlay(BlockPos pos, BlockState stateSchematic, BlockState stateClient, boolean missing) {
        OverlayType type = this.getOverlayType(stateSchematic, stateClient);
        Color4f color = getOverlayColor(type);
        if (color == null) return;

        this.relPos.set(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);

        if (this.enableSides) {
            this.renderSides(type, color, pos, stateSchematic, missing);
        }
        if (this.enableOutlines) {
            this.renderOutlines(type, color, pos, stateSchematic, missing);
        }
    }

    private void renderSides(OverlayType type, Color4f color, BlockPos pos, BlockState stateSchematic, boolean missing) {
        BufferBuilder quads = this.buffers.apply(OverlayRenderType.QUAD);
        boolean useDefault = false;

        if (this.reducedInnerSides) {
            VoxelShape shape = stateSchematic.getCollisionShape(this.schematic, pos);

            for (Direction side : fi.dy.masa.malilib.util.PositionUtils.ALL_DIRECTIONS) {
                this.posMutable.set(pos.getX() + side.getStepX(), pos.getY() + side.getStepY(), pos.getZ() + side.getStepZ());
                OverlayType typeAdj = this.getOverlayType(this.schematic.getBlockState(this.posMutable), this.client.get(this.posMutable));
                boolean fullSquareSide = Block.isFaceFull(shape, side);

                if (missing && this.modelSides) {
                    if (type.getRenderPriority() > typeAdj.getRenderPriority() || !fullSquareSide) {
                        RenderUtils.drawBlockModelQuadOverlayBatched(getModel(stateSchematic), stateSchematic,
                                this.relPos, side, color, 0, quads);
                    } else {
                        useDefault = true;
                    }
                } else if (type.getRenderPriority() > typeAdj.getRenderPriority()) {
                    RenderUtils.drawBlockBoxSideBatchedQuads(this.relPos, side, color, 0, quads);
                } else {
                    useDefault = true;
                }
            }
        } else if (missing && this.modelSides) {
            RenderUtils.drawBlockModelQuadOverlayBatched(getModel(stateSchematic), stateSchematic, this.relPos, color, 0, quads);
        } else {
            useDefault = true;
        }

        if (useDefault) {
            try {
                fi.dy.masa.malilib.render.RenderUtils.drawBlockBoundingBoxSidesBatchedQuads(this.relPos, color, 0, quads);
            } catch (Exception ignored) {
            }
        }
    }

    private void renderOutlines(OverlayType type, Color4f baseColor, BlockPos pos, BlockState stateSchematic, boolean missing) {
        BufferBuilder lines = this.buffers.apply(OverlayRenderType.OUTLINE);
        Color4f color = new Color4f(baseColor.r, baseColor.g, baseColor.b, 1f);
        boolean useDefault = false;

        if (this.reducedInnerSides) {
            OverlayType[][][] adjTypes = new OverlayType[3][3][3];

            for (int y = 0; y <= 2; ++y) {
                for (int z = 0; z <= 2; ++z) {
                    for (int x = 0; x <= 2; ++x) {
                        if (x != 1 || y != 1 || z != 1) {
                            this.posMutable.set(pos.getX() + x - 1, pos.getY() + y - 1, pos.getZ() + z - 1);
                            adjTypes[x][y][z] = this.getOverlayType(this.schematic.getBlockState(this.posMutable), this.client.get(this.posMutable));
                        } else {
                            adjTypes[x][y][z] = type;
                        }
                    }
                }
            }

            if (missing && this.modelOutline) {
                if (stateSchematic.canOcclude()) {
                    useDefault = true;
                } else {
                    RenderUtils.drawBlockModelOutlinesBatched(getModel(stateSchematic), stateSchematic, this.relPos, color, 0, lines);
                }
            } else {
                this.renderReducedEdges(pos, adjTypes, color, lines);
            }
        } else if (missing && this.modelOutline) {
            RenderUtils.drawBlockModelOutlinesBatched(getModel(stateSchematic), stateSchematic, this.relPos, color, 0, lines);
        } else {
            useDefault = true;
        }

        if (useDefault) {
            try {
                fi.dy.masa.malilib.render.RenderUtils.drawBlockBoundingBoxOutlinesBatchedLines(this.relPos, color, 0, lines);
            } catch (Exception ignored) {
            }
        }
    }

    private void renderReducedEdges(BlockPos pos, OverlayType[][][] adjTypes, Color4f color, BufferBuilder lines) {
        OverlayType[] neighborTypes = new OverlayType[4];
        Vec3i[] neighborPositions = new Vec3i[4];

        for (Direction.Axis axis : PositionUtils.AXES_ALL) {
            for (int corner = 0; corner < 4; ++corner) {
                Vec3i[] offsets = PositionUtils.getEdgeNeighborOffsets(axis, corner);
                if (offsets == null) continue;

                int index = -1;
                boolean hasCurrent = false;

                for (int i = 0; i < 4; ++i) {
                    Vec3i offset = offsets[i];
                    OverlayType type = adjTypes[offset.getX() + 1][offset.getY() + 1][offset.getZ() + 1];
                    if (type == OverlayType.NONE) continue;

                    if (index == -1 || type.getRenderPriority() >= neighborTypes[index - 1].getRenderPriority()) {
                        if (index < 0 || type.getRenderPriority() > neighborTypes[index - 1].getRenderPriority()) {
                            index = 0;
                        }
                        neighborPositions[index] = new Vec3i(pos.getX() + offset.getX(), pos.getY() + offset.getY(), pos.getZ() + offset.getZ());
                        neighborTypes[index] = type;
                        hasCurrent |= (i == 0);
                        ++index;
                    }
                }

                if (index > 0 && hasCurrent) {
                    Vec3i posTmp = new Vec3i(pos.getX(), pos.getY(), pos.getZ());
                    for (int i = 0; i < index; ++i) {
                        Vec3i tmp = neighborPositions[i];
                        if (tmp.getX() <= posTmp.getX() && tmp.getY() <= posTmp.getY() && tmp.getZ() <= posTmp.getZ()) {
                            posTmp = tmp;
                        }
                    }

                    if (posTmp.getX() == pos.getX() && posTmp.getY() == pos.getY() && posTmp.getZ() == pos.getZ()) {
                        try {
                            RenderUtils.drawBlockBoxEdgeBatchedLines(this.relPos, axis, corner, color, lines);
                        } catch (IllegalStateException ignored) {
                            return;
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private OverlayType getOverlayType(BlockState stateSchematic, BlockState stateClient) {
        if (stateSchematic == stateClient) {
            return OverlayType.NONE;
        }

        boolean clientHasAir = stateClient.isAir();

        if (stateSchematic.isAir()) {
            if (clientHasAir || (this.ignoreClientFluids && stateClient.getFluidState().isEmpty() == false)) {
                return OverlayType.NONE;
            }
            return OverlayType.EXTRA;
        }

        if (clientHasAir || (this.ignoreClientFluids && stateClient.getFluidState().isEmpty() == false)) {
            return OverlayType.MISSING;
        }
        if (stateSchematic.getBlock() != stateClient.getBlock()) {
            return OverlayType.WRONG_BLOCK;
        }
        return OverlayType.WRONG_STATE;
    }

    private static Color4f getOverlayColor(OverlayType type) {
        return switch (type) {
            case MISSING -> Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_MISSING.getBooleanValue() ? Configs.Colors.SCHEMATIC_OVERLAY_COLOR_MISSING.getColor() : null;
            case EXTRA -> Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_EXTRA.getBooleanValue() ? Configs.Colors.SCHEMATIC_OVERLAY_COLOR_EXTRA.getColor() : null;
            case WRONG_BLOCK -> Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_WRONG_BLOCK.getBooleanValue() ? Configs.Colors.SCHEMATIC_OVERLAY_COLOR_WRONG_BLOCK.getColor() : null;
            case WRONG_STATE -> Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_WRONG_STATE.getBooleanValue() ? Configs.Colors.SCHEMATIC_OVERLAY_COLOR_WRONG_STATE.getColor() : null;
            default -> null;
        };
    }

    private static BakedModel getModel(BlockState state) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
    }
}
