package io.github.rareities.protonbridge;

import java.util.concurrent.BlockingQueue;

/** Publishes a job only after its terminal callback is visible to the monitor. */
public final class RcJobRegistration {
    private RcJobRegistration() { }

    public interface Registrar<T> { void register(int jobId, T handler); }

    public static <T> void publish(int jobId, T handler, Registrar<T> registrar, BlockingQueue<Integer> queue) {
        registrar.register(jobId, handler);
        queue.add(jobId);
    }
}
