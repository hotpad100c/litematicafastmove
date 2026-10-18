package ml.mypals.litematicafastmove.mixin;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import ml.mypals.litematicafastmove.access.PlacementAccessorRegistry;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import ml.mypals.litematicafastmove.sync.IFastMoveManager;
import ml.mypals.litematicafastmove.sync.MoveInterpolator;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import ml.mypals.litematicafastmove.sync.PlacementSnapshot;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

@Mixin(value = SchematicPlacementManager.class, remap = false)
public abstract class MixinSchematicPlacementManagerI implements IFastMoveManager {

    @Shadow @Final protected Set<ChunkPos> chunksPreChange;
    @Shadow @Final protected Set<ChunkPos> chunksToRebuild;

    @Shadow abstract void onPostPlacementChange(SchematicPlacement placement);

    @Shadow public abstract void markChunkForRebuild(ChunkPos pos);

    @Unique private PlacementSnapshot litematicafastmove$preChange;
    @Unique private boolean litematicafastmove$committing;

    @Inject(method = "onPrePlacementChange", at = @At("HEAD"))
    private void onPrePlacementChangeHead(SchematicPlacement placement, CallbackInfo ci) {
        this.litematicafastmove$preChange = placement != null ? PlacementSnapshot.of(placement) : null;
    }

    @Inject(method = "onPostPlacementChange", at = @At("HEAD"), cancellable = true)
    private void onPostPlacementChangeHead(SchematicPlacement placement, CallbackInfo ci) {
        PlacementSnapshot pre = this.litematicafastmove$preChange;
        this.litematicafastmove$preChange = null;

        if (placement == null || this.litematicafastmove$committing) return;

        // Invalidate the old accessor cache, relative coordinates are recomputed from the new origin
        PlacementAccessorRegistry.invalidate(placement);

        PendingMoveTracker.MoveState state = PendingMoveTracker.get(placement);
        boolean pureTranslation = pre != null && pre.isPureTranslationTo(placement);

        // Pure translation of a large placement: the detached mesh only needs a new transform (see
        // PlacementMeshManager), nothing is rebuilt; the WorldSchematic rewrite is postponed until movement stops.
        // First load (addSchematicPlacement) does not pass through here; toggling, rotation, mirroring and sub-region
        // edits take the vanilla path and rebuild the detached mesh.
        if (FastMoveConfig.isFastMoveEnabled() && pureTranslation && FastMoveConfig.shouldDeferCommit(placement)) {
            // chunksPreChange now = the chunks occupied before the move, i.e. where the WorldSchematic data really is
            PendingMoveTracker.onMoved(placement, pre.origin(), new HashSet<>(this.chunksPreChange));
            ci.cancel();
            return;
        }

        if (state != null) {
            this.chunksPreChange.addAll(state.baseChunks);
            PendingMoveTracker.remove(placement);
            this.litematicafastmove$rebuildSkipped();
        }

        if (!pureTranslation) {
            PlacementMeshManager.requestBuild(placement);
        } else {
            PlacementMeshManager.markPlacementDirty(placement);
        }
    }

    @Inject(method = "addSchematicPlacement", at = @At("RETURN"))
    private void onAddSchematicPlacement(SchematicPlacement placement, boolean printMessages, CallbackInfo ci) {
        PlacementMeshManager.requestBuild(placement);
    }

    @Inject(method = "removeSchematicPlacement(Lfi/dy/masa/litematica/schematic/placement/SchematicPlacement;Z)Z", at = @At("HEAD"))
    private void onRemoveSchematicPlacement(SchematicPlacement placement, boolean update, CallbackInfoReturnable<Boolean> cir) {
        if (placement != null) {
            // Put the chunk index back at the current origin first, only then can the vanilla removal unload the right chunks
            this.litematicafastmove$commit(placement);
            PlacementAccessorRegistry.invalidate(placement);
            PlacementMeshManager.dispose(placement);
        }
    }

    @Inject(method = "markAllPlacementsOfSchematicForRebuild", at = @At("HEAD"))
    private void onMarkAllPlacementsOfSchematicForRebuild(LitematicaSchematic schematic, CallbackInfo ci) {
        this.litematicafastmove$commitAll();
        PlacementMeshManager.rebuildAllOf(schematic);
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void onClear(CallbackInfo ci) {
        PendingMoveTracker.clear();
        PlacementAccessorRegistry.clear();
        PlacementMeshManager.disposeAll();
        MoveInterpolator.clear();
    }

    /**
     * Litematica 0.9.0 rebuilds the schematic chunks directly in processQueuedChunks and keeps a single set,
     * chunksToRebuild, that serves as the rebuild queue, so that is where the frozen chunks are kept out while a move
     * is pending. They are put back one by one when the move is committed.
     */
    @Inject(method = "processQueuedChunks", at = @At("HEAD"))
    private void skipFrozenChunks(CallbackInfoReturnable<Boolean> cir) {
        if (!PendingMoveTracker.hasAnyPending()) return;

        Iterator<ChunkPos> iterator = this.chunksToRebuild.iterator();
        while (iterator.hasNext()) {
            ChunkPos pos = iterator.next();
            if (PendingMoveTracker.isFrozenChunk(pos.x, pos.z)) {
                iterator.remove();
                PendingMoveTracker.markSkippedRebuild(pos.x, pos.z);
            }
        }
    }

    @Override
    public void litematicafastmove$commit(SchematicPlacement placement) {
        PendingMoveTracker.MoveState state = PendingMoveTracker.remove(placement);
        if (state == null) return;

        if (!placement.getOrigin().equals(state.baseOrigin)) {
            this.chunksPreChange.clear();
            this.chunksPreChange.addAll(state.baseChunks);
            this.litematicafastmove$committing = true;
            try {
                this.onPostPlacementChange(placement);
            } finally {
                this.litematicafastmove$committing = false;
                this.chunksPreChange.clear();
            }
        }

        this.litematicafastmove$rebuildSkipped();
        PlacementMeshManager.markPlacementDirty(placement);
    }

    @Override
    public void litematicafastmove$commitAll() {
        for (SchematicPlacement placement : PendingMoveTracker.getPending()) {
            this.litematicafastmove$commit(placement);
        }
    }

    @Override
    public void litematicafastmove$commitSettled(long nowMs) {
        if (!PendingMoveTracker.hasAnyPending()) return;
        for (SchematicPlacement placement : PendingMoveTracker.getDueForCommit(nowMs, FastMoveConfig.COMMIT_DELAY_MS.getIntegerValue())) {
            this.litematicafastmove$commit(placement);
        }
    }

    @Unique
    private void litematicafastmove$rebuildSkipped() {
        for (ChunkPos pos : PendingMoveTracker.drainUnfrozenSkipped()) {
            this.markChunkForRebuild(pos);
        }
    }
}
