package com.ishland.c2me.common.threading.worldgen;

import com.google.common.base.Preconditions;
import com.ibm.asyncutil.locks.AsyncLock;
import com.ibm.asyncutil.locks.AsyncNamedLock;
import com.ibm.asyncutil.util.StageSupport;
import com.ishland.c2me.common.config.C2MEConfig;
import com.ishland.c2me.common.util.AsyncCombinedLock;
import com.mojang.datafixers.util.Either;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.ishland.c2me.common.threading.worldgen.ChunkStatusUtils.ChunkStatusThreadingType.AS_IS;
import static com.ishland.c2me.common.threading.worldgen.ChunkStatusUtils.ChunkStatusThreadingType.PARALLELIZED;
import static com.ishland.c2me.common.threading.worldgen.ChunkStatusUtils.ChunkStatusThreadingType.SINGLE_THREADED;

public class ChunkStatusUtils {

    public static ChunkStatusThreadingType getThreadingType(final ChunkStatus status) {
        if (status.equals(ChunkStatus.STRUCTURE_STARTS)
                || status.equals(ChunkStatus.STRUCTURE_REFERENCES)
                || status.equals(ChunkStatus.BIOMES)
                || status.equals(ChunkStatus.NOISE)
                || status.equals(ChunkStatus.SURFACE)
                || status.equals(ChunkStatus.CARVERS)
                || status.equals(ChunkStatus.LIQUID_CARVERS)
                || status.equals(ChunkStatus.HEIGHTMAPS)) {
            return PARALLELIZED;
        } else if (status.equals(ChunkStatus.SPAWN)) {
            return SINGLE_THREADED;
        } else if (status.equals(ChunkStatus.FEATURES)) {
            return C2MEConfig.threadedWorldGenConfig.allowThreadedFeatures ? PARALLELIZED : SINGLE_THREADED;
        }
        return AS_IS;
    }

    public static <T> CompletableFuture<T> runChunkGenWithLock(ChunkPos target, int radius, AsyncNamedLock<ChunkPos> chunkLock, Supplier<CompletableFuture<T>> action) {
        return CompletableFuture.supplyAsync(() -> {
            if (radius == 0)
                return StageSupport.tryWith(chunkLock.acquireLock(target), unused -> action.get()).toCompletableFuture().thenCompose(Function.identity());

            ArrayList<ChunkPos> fetchedLocks = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
            for (int x = target.x - radius; x <= target.x + radius; x++)
                for (int z = target.z - radius; z <= target.z + radius; z++)
                    fetchedLocks.add(new ChunkPos(x, z));

            return new AsyncCombinedLock(chunkLock, new HashSet<>(fetchedLocks)).getFuture().thenCompose(lockToken -> {
                final CompletableFuture<T> future = action.get();
                future.thenRun(lockToken::releaseLock);
                return future;
            });
        }, AsyncCombinedLock.lockWorker).thenCompose(Function.identity());
    }

    public enum ChunkStatusThreadingType {

        PARALLELIZED() {
            @Override
            public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> runTask(AsyncLock lock, Supplier<CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>>> completableFuture) {
                return CompletableFuture.supplyAsync(completableFuture, WorldGenThreadingExecutorUtils.mainExecutor).thenCompose(Function.identity());
            }
        },
        SINGLE_THREADED() {
            @Override
            public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> runTask(AsyncLock lock, Supplier<CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>>> completableFuture) {
                Preconditions.checkNotNull(lock);
                return lock.acquireLock().toCompletableFuture().thenComposeAsync(lockToken -> {
                    try {
                        return completableFuture.get();
                    } finally {
                        lockToken.releaseLock();
                    }
                }, WorldGenThreadingExecutorUtils.mainExecutor);
            }
        },
        AS_IS() {
            @Override
            public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> runTask(AsyncLock lock, Supplier<CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>>> completableFuture) {
                return completableFuture.get();
            }
        };

        public abstract CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> runTask(AsyncLock lock, Supplier<CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>>> completableFuture);

    }
}
