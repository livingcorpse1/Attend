package com.veriqra.app.config;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs independent database reads at the same time.
 *
 * <p>A dashboard is assembled from five separate queries. Against a database that is a
 * few hundred milliseconds away, asking for them one after another costs a few seconds
 * and the screen appears to hang. The queries do not depend on each other, so they are
 * sent together and the slowest one decides how long the wait is.
 */
@Component
public class ParallelQueries {

    private final ExecutorService executor;

    public ParallelQueries() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = (runnable) -> {
            Thread thread = new Thread(runnable, "veriqra-query-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(8, factory);
    }

    /**
     * Runs one task and waits for it.
     */
    public <T> T run(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor).join();
    }

    /**
     * Runs several tasks at the same time and returns their results in order.
     */
    @SafeVarargs
    public final <T> List<T> runAll(Supplier<T>... tasks) {
        List<CompletableFuture<T>> futures = new ArrayList<>(tasks.length);
        for (Supplier<T> task : tasks) {
            futures.add(CompletableFuture.supplyAsync(task, executor));
        }
        List<T> results = new ArrayList<>(tasks.length);
        long started = System.nanoTime();
        for (CompletableFuture<T> future : futures) {
            results.add(future.join());
        }
        log.debug("{} queries finished in {} ms", tasks.length, (System.nanoTime() - started) / 1_000_000);
        return results;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ParallelQueries.class);

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
