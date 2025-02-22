package com.ishland.c2me.common.threading.chunkio;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.ishland.c2me.common.config.C2MEConfig;
import com.ishland.c2me.common.util.C2MEForkJoinWorkerThreadFactory;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;

public class ChunkIoThreadingExecutorUtils {

    public static final ForkJoinPool serializerExecutor = new ForkJoinPool(
            C2MEConfig.asyncIoConfig.serializerParallelism,
            new C2MEForkJoinWorkerThreadFactory("C2ME serializer worker #%d", Thread.NORM_PRIORITY - 1),
            null,
            true
    );

    public static final ThreadFactory ioWorkerFactory = new ThreadFactoryBuilder().setDaemon(true).setNameFormat("IOWorker-%d").setPriority(Thread.NORM_PRIORITY - 1).build();

}
