package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background thread that periodically pings each worker's /test endpoint.
 *
 * A worker is considered "alive" until it fails FAILURE_THRESHOLD consecutive
 * health checks, at which point it is deregistered from the pool and (optionally)
 * terminated so the AutoScaler can launch a replacement.
 *
 * This complements the AutoScaler:
 *   - AutoScaler reacts to CPU load (slow — minutes)
 *   - HealthChecker reacts to liveness (fast — seconds)
 *
 * It does NOT terminate by default — leaving stuck instances around can be
 * useful for debugging. Set TERMINATE_DEAD_WORKERS to true once you trust it.
 */
public class HealthChecker {

    // ---- tunables -------------------------------------------------------

    private static final int CHECK_INTERVAL_SECONDS = 15;
    private static final int FAILURE_THRESHOLD = 3; // consecutive failures
    private static final int HEALTH_CHECK_TIMEOUT_S = 5;
    private static final String HEALTH_PATH = "/test";
    private static final boolean TERMINATE_DEAD_WORKERS = true;

    // ---- collaborators --------------------------------------------------

    private final EC2Manager ec2;
    private final WorkerPool pool;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(HEALTH_CHECK_TIMEOUT_S))
        .build();

    private final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "healthchecker");
            t.setDaemon(true);
            return t;
        });

    /** instanceId -> consecutive-failure count */
    private final Map<String, Integer> failureCounts =
        new ConcurrentHashMap<>();

    public HealthChecker(EC2Manager ec2, WorkerPool pool) {
        this.ec2 = ec2;
        this.pool = pool;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(
            this::checkAll,
            CHECK_INTERVAL_SECONDS,
            CHECK_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        System.out.println(
            "[health] started: ping " +
                HEALTH_PATH +
                " every " +
                CHECK_INTERVAL_SECONDS +
                "s, " +
                "drop after " +
                FAILURE_THRESHOLD +
                " consecutive failures"
        );
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    // ---- core -----------------------------------------------------------

    private void checkAll() {
        for (Worker w : pool.snapshot()) {
            try {
                URI uri = URI.create(
                    "http://" + w.getHost() + ":" + w.getPort() + HEALTH_PATH
                );
                HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(HEALTH_CHECK_TIMEOUT_S))
                    .GET()
                    .build();
                HttpResponse<Void> resp = http.send(
                    req,
                    HttpResponse.BodyHandlers.discarding()
                );
                if (resp.statusCode() / 100 == 2) {
                    onSuccess(w);
                } else {
                    onFailure(w, "HTTP " + resp.statusCode());
                }
            } catch (Exception e) {
                onFailure(
                    w,
                    e.getClass().getSimpleName() + ": " + e.getMessage()
                );
            }
        }
    }

    private void onSuccess(Worker w) {
        // Reset failure count if it was non-zero
        Integer prev = failureCounts.put(w.getInstanceId(), 0);
        if (prev != null && prev > 0) {
            System.out.println(
                "[health] " + w.getInstanceId() + " back to healthy"
            );
        }
    }

    private void onFailure(Worker w, String reason) {
        int fails = failureCounts.merge(w.getInstanceId(), 1, Integer::sum);
        System.out.println(
            "[health] " +
                w.getInstanceId() +
                " failed check " +
                fails +
                "/" +
                FAILURE_THRESHOLD +
                " (" +
                reason +
                ")"
        );
        if (fails >= FAILURE_THRESHOLD) {
            dropWorker(w);
        }
    }

    private void dropWorker(Worker w) {
        System.out.println(
            "[health] dropping unhealthy worker " + w.getInstanceId()
        );
        pool.deregister(w.getInstanceId());
        failureCounts.remove(w.getInstanceId());

        if (TERMINATE_DEAD_WORKERS) {
            // Run termination in the scheduler thread to keep this method quick
            scheduler.execute(() -> {
                try {
                    ec2.terminate(w.getInstanceId());
                    System.out.println(
                        "[health] terminated " + w.getInstanceId()
                    );
                } catch (Exception e) {
                    System.err.println(
                        "[health] failed to terminate " +
                            w.getInstanceId() +
                            ": " +
                            e.getMessage()
                    );
                }
            });
        }
    }
}
