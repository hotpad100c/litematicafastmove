package ml.mypals.litematicafastmove.verify;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.malilib.util.IntBoundingBox;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import ml.mypals.litematicafastmove.LitematicaFastMove;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.config.ThreadPools;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步的投影校验。
 *
 * 与 26.3 版本的差异 (都是 Litematica 0.18.3 缺失的能力，不是本模组丢功能):
 * 0.18.3 has no {@code IgnoreBlockRegistry}, no "ignore crop age" option and no DIFF_BLOCK mismatch category,
 * so the work threads do not consult them; everything else mirrors {@code SchematicVerifier#checkBlockStates}.
 */
public final class AsyncSchematicVerifier {
    private static final ThreadPoolExecutor POOL = (ThreadPoolExecutor) Executors.newFixedThreadPool(
            FastMoveConfig.verifierThreads(),
            runnable -> {
                Thread thread = new Thread(runnable, "LFMVerifier");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }
    );

    public static void applyThreadCount() {
        ThreadPools.resize(POOL, FastMoveConfig.verifierThreads());
    }

    // 同一组 (类型, 期望 -> 实际) 的全部位置；mismatch 由组内位置共用
    public record MismatchGroup(BlockMismatch mismatch, List<BlockPos> positions) {}

    // 已按 SchematicVerifier#checkBlockStates 的规则判定完毕，主线程只需批量写入
    public record ChunkResult(ChunkPos pos,
                              Map<MismatchType, Map<Pair<BlockState, BlockState>, MismatchGroup>> mismatches,
                              Object2IntOpenHashMap<BlockState> correctCounts, int correctNonAir,
                              int schematicBlocks, int clientBlocks,
                              Set<BlockState> schematicMismatchStates) {}

    // 判定规则所需的配置与忽略列表，在主线程拷贝快照供工作线程读取
    private record Rules(boolean ignoreExistingFluids,
                         Set<Pair<BlockState, BlockState>> ignoredMismatches) {}

    public static final class Session {
        public final SchematicPlacement placement;
        private final PlacementBlockAccessor accessor;
        private final Rules rules;
        private final Set<ChunkPos> submitted = new HashSet<>();
        private final Queue<ChunkResult> completed = new ConcurrentLinkedQueue<>();
        private final AtomicInteger running = new AtomicInteger();
        private volatile boolean cancelled;

        public Session(SchematicPlacement placement, Set<Pair<BlockState, BlockState>> ignoredMismatches) {
            this.placement = placement;
            this.accessor = new PlacementBlockAccessor(placement);
            this.rules = new Rules(
                    Configs.Visuals.IGNORE_EXISTING_FLUIDS.getBooleanValue(),
                    new HashSet<>(ignoredMismatches));
        }

        public boolean isSubmitted(ChunkPos pos) {
            return this.submitted.contains(pos);
        }

        public void submit(ChunkPos pos, ChunkAccess clientChunk, List<IntBoundingBox> boxes) {
            this.submitted.add(pos);
            this.running.incrementAndGet();
            POOL.submit(() -> {
                try {
                    if (!this.cancelled) {
                        this.completed.add(scan(pos, clientChunk, boxes, this.accessor, this.rules));
                    }
                } catch (Exception e) {
                    LitematicaFastMove.LOGGER.error("Failed to verify chunk {}", pos, e);
                } finally {
                    this.running.decrementAndGet();
                }
            });
        }

        public ChunkResult pollCompleted() {
            return this.cancelled ? null : this.completed.poll();
        }

        public boolean isIdle() {
            return this.running.get() == 0 && this.completed.isEmpty();
        }

        public void cancel() {
            this.cancelled = true;
            this.completed.clear();
        }
    }

    private static ChunkResult scan(ChunkPos pos, ChunkAccess clientChunk, List<IntBoundingBox> boxes,
                                    PlacementBlockAccessor accessor, Rules rules) {
        Map<MismatchType, Map<Pair<BlockState, BlockState>, MismatchGroup>> mismatches = new EnumMap<>(MismatchType.class);
        Object2IntOpenHashMap<BlockState> correct = new Object2IntOpenHashMap<>();
        Set<BlockState> schematicMismatchStates = new HashSet<>();
        int correctNonAir = 0, schematicBlocks = 0, clientBlocks = 0;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (IntBoundingBox box : boxes) {
            for (int y = box.minY; y <= box.maxY; ++y) {
                for (int z = box.minZ; z <= box.maxZ; ++z) {
                    for (int x = box.minX; x <= box.maxX; ++x) {
                        BlockState stateSchematic = accessor.getBlockStateAt(x, y, z);
                        BlockState stateClient = clientChunk.getBlockState(mutable.set(x, y, z));
                        boolean schematicAir = stateSchematic.isAir();
                        boolean clientAir = stateClient.isAir();

                        if (!schematicAir) schematicBlocks++;
                        if (!clientAir) clientBlocks++;

                        boolean differs = stateClient != stateSchematic && (!clientAir || !schematicAir);
                        if (!differs) {
                            correct.addTo(stateClient, 1);
                            if (!schematicAir) correctNonAir++;
                            continue;
                        }

                        Pair<BlockState, BlockState> pair = Pair.of(stateSchematic, stateClient);
                        if (rules.ignoredMismatches().contains(pair)) continue;

                        MismatchType type = classify(stateSchematic, stateClient, schematicAir, clientAir, rules);
                        if (type == null) continue;

                        mismatches.computeIfAbsent(type, t -> new HashMap<>())
                                  .computeIfAbsent(pair, p -> new MismatchGroup(new BlockMismatch(type, stateSchematic, stateClient, 1), new ArrayList<>()))
                                  .positions().add(new BlockPos(x, y, z));
                        schematicMismatchStates.add(stateSchematic);
                    }
                }
            }
        }

        return new ChunkResult(pos, mismatches, correct, correctNonAir, schematicBlocks, clientBlocks, schematicMismatchStates);
    }

    @SuppressWarnings("deprecation")
    private static MismatchType classify(BlockState stateSchematic, BlockState stateClient, boolean schematicAir, boolean clientAir, Rules rules) {
        if (!schematicAir) {
            if (clientAir) return MismatchType.MISSING;
            if (stateSchematic.getBlock() != stateClient.getBlock()) {
                return MismatchType.WRONG_BLOCK;
            }
            return MismatchType.WRONG_STATE;
        }

        if (!rules.ignoreExistingFluids() || stateClient.getFluidState().isEmpty()) {
            return MismatchType.EXTRA;
        }
        return null;
    }
}
