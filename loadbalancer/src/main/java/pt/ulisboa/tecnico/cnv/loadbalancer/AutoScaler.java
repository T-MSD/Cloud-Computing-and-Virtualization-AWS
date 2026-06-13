package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background thread that periodically polls CloudWatch for each worker's
 * CPU and decides whether to scale the pool up or down.
 *
 * Decision logic each tick (every CHECK_INTERVAL_SECONDS):
 *   - Compute average CPU across all workers in the pool
 *   - If avg >= SCALE_UP_THRESHOLD and pool.size() < MAX_WORKERS:
 *         launch one new worker (in a background task; the eval loop
 *         doesn't block waiting for it to come up)
 *   - If avg <= SCALE_DOWN_THRESHOLD and pool.size() > MIN_WORKERS:
 *         pick the worker with the lowest current load, deregister it,
 *         drain a few seconds for in-flight requests, then terminate
 *
 * A COOLDOWN_SECONDS window blocks further scaling actions after each one,
 * so we don't fire 10 scale-ups in 30 seconds while a single new worker
 * is still booting.
 */
public class AutoScaler {

    // ---- tunables -------------------------------------------------------

    private static final int    MIN_WORKERS            = 1;
    private static final int    MAX_WORKERS            = 3;
    private static final double SCALE_UP_THRESHOLD     = 80.0;   // % CPU
    private static final double SCALE_DOWN_THRESHOLD   = 25.0;   // % CPU
    private static final int    CHECK_INTERVAL_SECONDS = 30;
    private static final int    COOLDOWN_SECONDS       = 120;
    private static final int    DRAIN_SECONDS          = 10;
    private static final int    WORKER_PORT            = 8000;
    private static final int    WEBSERVER_WARMUP_S     = 45;

    // ---- collaborators --------------------------------------------------

    private final EC2Manager    ec2;
    private final WorkerPool    pool;
    private final MetricsClient metrics;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "autoscaler");
                t.setDaemon(true);
                return t;
            });

    private volatile long lastActionAt = 0;

    /**
     * Most recent average CPU% observed across the pool, or -1 if no data
     * has been collected yet. Read by LoadBalancerServer to decide whether
     * workers are overloaded enough to spill light traffic to Lambda.
     */
    private volatile double lastAvgCpu = -1;

    public AutoScaler(EC2Manager ec2, WorkerPool pool, MetricsClient metrics) {
        this.ec2 = ec2;
        this.pool = pool;
        this.metrics = metrics;
    }

    public double getLastAvgCpu() { return lastAvgCpu; }

    public void start() {
        scheduler.scheduleAtFixedRate(this::evaluate,
                CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        System.out.println("[autoscaler] started: every " + CHECK_INTERVAL_SECONDS + "s, "
                + "min=" + MIN_WORKERS + ", max=" + MAX_WORKERS + ", "
                + "up>" + SCALE_UP_THRESHOLD + "%, down<" + SCALE_DOWN_THRESHOLD + "%");
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    // ---- evaluation -----------------------------------------------------

    private void evaluate() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastActionAt < COOLDOWN_SECONDS * 1000L) {
                return; // still cooling down from the last scaling action
            }

            List<Worker> workers = pool.snapshot();

            // Enforce MIN_WORKERS even if pool got emptied (e.g. all workers killed by HealthChecker)
            if (workers.size() < MIN_WORKERS) {
                System.out.println("[autoscaler] below MIN_WORKERS, launching replacement");
                lastActionAt = now;
                scheduler.execute(this::scaleUp);
                return;
            }
            if (workers.isEmpty()) return;

            double sum = 0;
            int count = 0;
            for (Worker w : workers) {
                Double cpu = metrics.getAverageCpu(w.getInstanceId());
                if (cpu != null) {
                    sum += cpu;
                    count++;
                }
            }
            if (count == 0) {
                System.out.println("[autoscaler] no CPU data yet");
                return;
            }
            double avg = sum / count;
            this.lastAvgCpu = avg;
            System.out.printf("[autoscaler] avg CPU=%.1f%% across %d worker(s), pool=%d%n",
                    avg, count, pool.size());

            if (avg >= SCALE_UP_THRESHOLD && pool.size() < MAX_WORKERS) {
                lastActionAt = now;
                scheduler.execute(this::scaleUp);
            } else if (avg <= SCALE_DOWN_THRESHOLD && pool.size() > MIN_WORKERS) {
                lastActionAt = now;
                scheduler.execute(this::scaleDown);
            }
        } catch (Exception e) {
            System.err.println("[autoscaler] eval error: " + e.getMessage());
        }
    }

    private void scaleUp() {
        try {
            System.out.println("[autoscaler] SCALE UP: launching new worker...");
            String id = ec2.launchWorker();
            ec2.waitUntilRunning(id);
            String ip = ec2.getPublicIp(id);
            System.out.println("[autoscaler] " + id + " running at " + ip
                    + ", warming up " + WEBSERVER_WARMUP_S + "s...");
            Thread.sleep(WEBSERVER_WARMUP_S * 1000L);
            pool.register(new Worker(id, ip, WORKER_PORT));
            System.out.println("[autoscaler] SCALE UP complete; pool=" + pool.size());
        } catch (Exception e) {
            System.err.println("[autoscaler] scaleUp failed: " + e.getMessage());
        }
    }

    private void scaleDown() {
        try {
            // Pick the least-loaded worker (likely safe to drop)
            Worker victim = pool.snapshot().stream()
                    .min(Comparator.comparingLong(Worker::getCurrentLoad))
                    .orElse(null);
            if (victim == null) return;

            System.out.println("[autoscaler] SCALE DOWN: removing " + victim.getInstanceId()
                    + " (load=" + victim.getCurrentLoad() + ")");
            pool.deregister(victim.getInstanceId());

            // Let any in-flight requests on this worker finish
            Thread.sleep(DRAIN_SECONDS * 1000L);

            ec2.terminate(victim.getInstanceId());
            System.out.println("[autoscaler] SCALE DOWN complete; pool=" + pool.size());
        } catch (Exception e) {
            System.err.println("[autoscaler] scaleDown failed: " + e.getMessage());
        }
    }
}
