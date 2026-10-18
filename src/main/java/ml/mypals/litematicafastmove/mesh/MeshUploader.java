package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.mypals.litematicafastmove.LitematicaFastMove;
import ml.mypals.litematicafastmove.config.FastMoveConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;


final class MeshUploader {
    private record Completed(PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer buffer) {}

    private static final class Budget {
        long bytes = FastMoveConfig.uploadBytesPerFrame();
        final long deadline = System.nanoTime() + (long) (FastMoveConfig.UPLOAD_MILLIS_PER_FRAME.getDoubleValue() * 1_000_000L);
        boolean usedAny;

        boolean hasRemaining() {
            return !this.usedAny || (this.bytes > 0 && System.nanoTime() < this.deadline);
        }
    }

    private static final Queue<Completed> COMPLETED = new ConcurrentLinkedQueue<>();
    private static final Queue<PlacementMesh> TO_CLOSE = new ConcurrentLinkedQueue<>();

    private MeshUploader() {}

    static void enqueueBuilt(PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer buffer) {
        COMPLETED.add(new Completed(mesh, buffer));
    }

    static void enqueueClose(PlacementMesh mesh) {
        TO_CLOSE.add(mesh);
    }

    static void processQueues() {
        PlacementMesh closing;
        while ((closing = TO_CLOSE.poll()) != null) {
            closing.close();
        }

        Completed completed;
        while ((completed = COMPLETED.poll()) != null) {
            accept(completed.mesh(), completed.buffer());
        }

        Budget budget = new Budget();

        for (Map.Entry<SchematicPlacement, PlacementMesh> entry : PlacementMeshManager.meshes().entrySet()) {
            if (!budget.hasRemaining()) break;
            SchematicPlacement placement = entry.getKey();
            PlacementMesh mesh = entry.getValue();

            PlacementMesh.BuiltPlacementBuffer full = mesh.uploadingFull;
            if (full != null) {
                upload(mesh, full, budget);
                if (!full.isFullyUploaded()) continue;
                activateFull(placement, mesh, full);
                MeshDebugGizmos.fullBuild(mesh.targetAccessor, placement.getOrigin().subtract(full.buildOrigin), true);
            }

            PlacementMesh.BuiltPlacementBuffer partial;
            while ((partial = mesh.uploadingPartials.peek()) != null && budget.hasRemaining()) {
                upload(mesh, partial, budget);
                if (!partial.isFullyUploaded()) break;
                mesh.uploadingPartials.poll();
                PlacementBlockEntities.update(placement, mesh, partial);
                MeshDebugGizmos.sectionsApplied(mesh.targetAccessor, partial.keys, placement.getOrigin().subtract(partial.buildOrigin));
                applyPartial(mesh, partial);
            }
        }

        TranslucentSorter.applyResorted();
    }

    private static void accept(PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer buffer) {
        if (buffer.generation != mesh.requestedGeneration) {
            buffer.discard();
        } else if (buffer.isFull()) {
            if (mesh.uploadingFull != null) {
                mesh.uploadingFull.discard();
            }
            mesh.uploadingFull = buffer;
        } else if (mesh.uploadingFull == null && mesh.activeGeneration == buffer.generation) {
            mesh.uploadingPartials.add(buffer);
        } else {
            mesh.deferredPartials.add(buffer);
        }
    }

    private static void activateFull(SchematicPlacement placement, PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer full) {
        LitematicaFastMove.LOGGER.debug("Detached placement mesh ready: {} sections (generation {})",
                full.uploaded.size(), full.generation);
        mesh.active.values().forEach(section -> section.closeExcept(full.borrowed));
        mesh.active.clear();
        full.borrowed.clear();
        mesh.clipRebuildPending.clear();
        for (PlacementMesh.GpuSection section : full.uploaded) {
            mesh.active.put(section.key(), section);
        }
        mesh.activeGeneration = full.generation;
        mesh.activeBuildOrigin = full.buildOrigin;
        mesh.uploadingFull = null;
        PlacementBlockEntities.replace(placement, mesh, full);

        mesh.uploadingPartials.forEach(PlacementMesh.BuiltPlacementBuffer::discard);
        mesh.uploadingPartials.clear();
        for (PlacementMesh.BuiltPlacementBuffer partial : mesh.deferredPartials) {
            if (partial.generation == mesh.activeGeneration) {
                mesh.uploadingPartials.add(partial);
            } else {
                partial.discard();
            }
        }
        mesh.deferredPartials.clear();
    }

    private static void applyPartial(PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer partial) {
        for (long key : partial.keys) {
            PlacementMesh.GpuSection old = mesh.active.remove(key);
            if (old != null) old.closeExcept(partial.borrowed);
            mesh.clipRebuildPending.remove(key);
        }
        for (PlacementMesh.GpuSection section : partial.uploaded) {
            if (section.hasBlocks() || !section.layers().isEmpty()) {
                mesh.active.put(section.key(), section);
            } else {
                section.close();
            }
        }
        partial.uploaded.clear();
        partial.borrowed.clear();
    }

    private static void upload(PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer buffer, Budget budget) {
        while (budget.hasRemaining() && !buffer.isFullyUploaded()) {
            budget.usedAny = true;
            PlacementMesh.CpuSection section = buffer.pending.get(buffer.nextToUpload++);
            PlacementMesh.GpuSection old = mesh.active.get(section.key());
            List<PlacementMesh.GpuLayer> layers = new ArrayList<>(section.layers().size());
            for (PlacementMesh.CpuLayer layer : section.layers()) {
                PlacementMesh.GpuLayer same = findSameContent(old, layer);
                if (same != null) {
                    // 内容未变 (例如移动后重新对照，结果相同)：沿用旧 buffer，不重新创建
                    buffer.borrowed.add(same);
                    layers.add(same);
                    layer.free();
                    continue;
                }

                budget.bytes -= layer.vertexBytes;
                VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                // VertexBuffer#upload asserts the render thread and releases the RenderedBuffer itself.
                vertexBuffer.upload(layer.take());
                layer.releaseBuilder();
                PlacementMesh.SortInfo sort = layer.sortState != null
                        ? new PlacementMesh.SortInfo(layer.sortState, Double.NaN, Double.NaN, Double.NaN)
                        : null;
                layers.add(new PlacementMesh.GpuLayer(layer.layer, layer.overlay, vertexBuffer, layer.indexCount,
                        layer.vertexBytes, sort, layer.hash));
            }
            buffer.uploaded.add(new PlacementMesh.GpuSection(section.key(), section.origin(),
                    section.hasBlocks(), section.clientEmpty(), section.clip(), layers, section.blockEntities()));
        }
    }

    private static PlacementMesh.GpuLayer findSameContent(PlacementMesh.GpuSection old, PlacementMesh.CpuLayer layer) {
        if (old == null) return null;
        for (PlacementMesh.GpuLayer candidate : old.layers()) {
            if (candidate.sameContent(layer)) return candidate;
        }
        return null;
    }

    /** Handed back by {@link TranslucentSorter} after the sorted index buffer was uploaded. */
    static void releaseBuilder(BufferBuilder builder) {
        builder.release();
    }
}
