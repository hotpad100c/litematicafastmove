package ml.mypals.litematicafastmove.sync;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;

public final class PendingMoveTracker {
    public static final class MoveState {
        public final BlockPos baseOrigin;
        public final Set<ChunkPos> baseChunks;
        public volatile long lastMoveMs;

        MoveState(BlockPos baseOrigin, Set<ChunkPos> baseChunks) {
            this.baseOrigin = baseOrigin;
            this.baseChunks = baseChunks;
        }
    }

    private static final Map<SchematicPlacement, MoveState> STATES = new ConcurrentHashMap<>();
    private static final Map<SchematicPlacement, Map<Long, BlockPos>> TRANSIT = new ConcurrentHashMap<>();
    private static final Set<Long> SKIPPED_REBUILDS = ConcurrentHashMap.newKeySet();
    private static volatile Set<Long> frozenChunks = Set.of();
    private static volatile Map<SchematicPlacement, List<Region>> regions = Map.of();

    private record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    public static boolean hasAnyPending() {
        return !STATES.isEmpty();
    }

    public static MoveState get(SchematicPlacement placement) {
        return STATES.get(placement);
    }

    public static void onMoved(SchematicPlacement placement, BlockPos baseOrigin, Set<ChunkPos> baseChunks) {
        MoveState state = STATES.computeIfAbsent(placement, p -> new MoveState(baseOrigin, baseChunks));
        state.lastMoveMs = System.currentTimeMillis();
        rebuildFrozen();
    }

    public static MoveState remove(SchematicPlacement placement) {
        MoveState state = STATES.remove(placement);
        if (state != null) {
            rebuildFrozen();
        }
        return state;
    }

    /** The schematic world keeps the data at {@code dataOrigin} in {@code dataChunks} until Litematica rebuilds them. */
    public static void onCommitted(SchematicPlacement placement, BlockPos dataOrigin, Set<ChunkPos> dataChunks) {
        Map<Long, BlockPos> chunks = TRANSIT.computeIfAbsent(placement, p -> new ConcurrentHashMap<>());
        for (ChunkPos pos : dataChunks) {
            chunks.putIfAbsent(pos.toLong(), dataOrigin);
        }
        rebuildFrozen();
    }

    public static void pruneTransit() {
        if (TRANSIT.isEmpty()) return;
        LongPredicate pending = pendingRebuild();
        boolean changed = false;
        for (Map.Entry<SchematicPlacement, Map<Long, BlockPos>> entry : TRANSIT.entrySet()) {
            changed |= entry.getValue().keySet().removeIf(chunk -> !pending.test(chunk));
            if (entry.getValue().isEmpty()) {
                TRANSIT.remove(entry.getKey());
                changed = true;
            }
        }
        if (changed) {
            rebuildFrozen();
        }
    }

    public static void forget(SchematicPlacement placement) {
        if (TRANSIT.remove(placement) != null) {
            rebuildFrozen();
        }
    }

    public static List<SchematicPlacement> getPending() {
        return new ArrayList<>(STATES.keySet());
    }

    public static List<SchematicPlacement> getDueForCommit(long now, long settleMs) {
        List<SchematicPlacement> due = new ArrayList<>();
        STATES.forEach((placement, state) -> {
            if (now - state.lastMoveMs >= settleMs) due.add(placement);
        });
        return due;
    }

    public static void clear() {
        STATES.clear();
        TRANSIT.clear();
        SKIPPED_REBUILDS.clear();
        frozenChunks = Set.of();
        regions = Map.of();
    }

    public static boolean hasAnyObjectShift() {
        return !regions.isEmpty() || MoveInterpolator.hasLagging();
    }

    /** The shift of an object the schematic world holds at {@code (x, y, z)}: from where its data is to where it is drawn. */
    public static Vec3 getObjectShift(double x, double y, double z) {
        Map<SchematicPlacement, List<Region>> current = regions;
        if (!current.isEmpty()) {
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            long chunk = ChunkPos.asLong(bx >> 4, bz >> 4);
            LongPredicate pending = null;
            for (Map.Entry<SchematicPlacement, List<Region>> entry : current.entrySet()) {
                SchematicPlacement placement = entry.getKey();
                BlockPos dataOrigin = null;
                Map<Long, BlockPos> transit = TRANSIT.get(placement);
                if (transit != null && transit.containsKey(chunk)) {
                    if (pending == null) pending = pendingRebuild();
                    if (pending.test(chunk)) dataOrigin = transit.get(chunk);
                }
                if (dataOrigin == null) {
                    MoveState state = STATES.get(placement);
                    dataOrigin = state != null ? state.baseOrigin : placement.getOrigin();
                }
                for (Region region : entry.getValue()) {
                    if (region.contains(bx - dataOrigin.getX(), by - dataOrigin.getY(), bz - dataOrigin.getZ())) {
                        BlockPos delta = placement.getOrigin().subtract(dataOrigin);
                        return MoveInterpolator.getLag(placement).add(delta.getX(), delta.getY(), delta.getZ());
                    }
                }
            }
        }
        return MoveInterpolator.getLagShiftAt(x, y, z);
    }

    private static LongPredicate pendingRebuild() {
        Object manager = DataManager.getSchematicPlacementManager();
        LongPredicate queued = manager instanceof IFastMoveManager fastMove ? fastMove::litematicafastmove$isRebuildPending : chunk -> false;
        return chunk -> SKIPPED_REBUILDS.contains(chunk) || queued.test(chunk);
    }

    public static boolean isFrozenChunk(int chunkX, int chunkZ) {
        Set<Long> frozen = frozenChunks;
        return !frozen.isEmpty() && frozen.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static void markSkippedRebuild(int chunkX, int chunkZ) {
        SKIPPED_REBUILDS.add(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static List<ChunkPos> drainUnfrozenSkipped() {
        List<ChunkPos> result = new ArrayList<>();
        for (Long key : SKIPPED_REBUILDS) {
            ChunkPos pos = new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (!isFrozenChunk(pos.x, pos.z) && SKIPPED_REBUILDS.remove(key)) {
                result.add(pos);
            }
        }
        return result;
    }

    private static void rebuildFrozen() {
        Set<Long> frozen = new HashSet<>();
        Map<SchematicPlacement, List<Region>> placementRegions = new ConcurrentHashMap<>();

        Set<SchematicPlacement> shifting = new HashSet<>(STATES.keySet());
        shifting.addAll(TRANSIT.keySet());
        for (SchematicPlacement placement : shifting) {
            BlockPos origin = placement.getOrigin();
            List<Region> relative = new ArrayList<>();
            for (var box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values()) {
                BlockPos a = box.getPos1(), b = box.getPos2();
                if (a == null || b == null) continue;
                relative.add(new Region(Math.min(a.getX(), b.getX()) - origin.getX(), Math.min(a.getY(), b.getY()) - origin.getY(),
                        Math.min(a.getZ(), b.getZ()) - origin.getZ(), Math.max(a.getX(), b.getX()) - origin.getX(),
                        Math.max(a.getY(), b.getY()) - origin.getY(), Math.max(a.getZ(), b.getZ()) - origin.getZ()));
            }
            placementRegions.put(placement, List.copyOf(relative));
        }

        STATES.forEach((placement, state) -> {
            for (ChunkPos pos : state.baseChunks) {
                frozen.add(pos.toLong());
            }
        });

        frozenChunks = Set.copyOf(frozen);
        regions = placementRegions.isEmpty() ? Map.of() : placementRegions;
    }
}
