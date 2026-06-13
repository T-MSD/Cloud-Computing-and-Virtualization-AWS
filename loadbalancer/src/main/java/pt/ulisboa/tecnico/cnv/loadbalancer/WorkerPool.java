package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Thread-safe set of active workers.
 *
 * Selection strategy: least-loaded. The next request goes to the worker
 * whose current in-flight cost sum is lowest. Ties broken by stable order.
 *
 * If two requests arrive at almost the same instant, both threads may
 * see the same "least loaded" worker (a benign race) — the load
 * counters self-correct on the next decision.
 *
 * Periodically logs a status line showing each worker's load, so you can
 * watch the cost-aware routing in action.
 */
public class WorkerPool {

    /** Interval for the periodic status log. */
    private static final int STATUS_LOG_INTERVAL_SECONDS = 10;

    private final List<Worker> workers = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService statusLogger =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pool-status");
                t.setDaemon(true);
                return t;
            });

    public WorkerPool() {
        statusLogger.scheduleAtFixedRate(this::logStatus,
                STATUS_LOG_INTERVAL_SECONDS, STATUS_LOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Add a worker. Idempotent — same instanceId won't be added twice. */
    public void register(Worker w) {
        if (!workers.contains(w)) {
            workers.add(w);
            System.out.println("[pool] registered " + w);
        }
    }

    /** Remove a worker by instance ID. Returns the removed Worker or null. */
    public Worker deregister(String instanceId) {
        for (Worker w : workers) {
            if (w.getInstanceId().equals(instanceId)) {
                workers.remove(w);
                System.out.println("[pool] deregistered " + w);
                return w;
            }
        }
        return null;
    }

    /** Pick the worker with the lowest current load. Returns null if pool is empty. */
    public Worker pickWorker() {
        return workers.stream()
                .min(Comparator.comparingLong(Worker::getCurrentLoad))
                .orElse(null);
    }

    /** Snapshot of all currently registered workers. Safe to iterate. */
    public List<Worker> snapshot() {
        return new ArrayList<>(workers);
    }

    public int size()       { return workers.size(); }
    public boolean isEmpty() { return workers.isEmpty(); }

    /** Stop the periodic status logger. Call from shutdown. */
    public void stop() { statusLogger.shutdownNow(); }

    // ---- internal -------------------------------------------------------

    private void logStatus() {
        if (workers.isEmpty()) return;
        String line = workers.stream()
                .map(w -> w.getInstanceId() + "=" + w.getCurrentLoad())
                .collect(Collectors.joining("  "));
        System.out.println("[pool] status: " + line);
    }
}
