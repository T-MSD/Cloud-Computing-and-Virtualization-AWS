package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.concurrent.CountDownLatch;

/**
 * Entry point for the load balancer.
 *
 * On startup:
 *   1. Launches INITIAL_WORKERS EC2 instances via EC2Manager
 *   2. Registers each one in the WorkerPool
 *   3. Starts the AutoScaler (CPU-driven scale up/down)
 *   4. Starts the HealthChecker (drops unresponsive workers)
 *   5. Starts the HTTP server on LISTEN_PORT, fed by the pool
 *
 * On Ctrl+C / JVM exit:
 *   - Stops all background threads and the HTTP server
 *   - Terminates every registered worker
 */
public class LoadBalancerMain {

    private static final int LISTEN_PORT = 8080;
    private static final int WORKER_PORT = 8000;

    /** How many workers to launch at startup. AutoScaler will grow/shrink from here. */
    private static final int INITIAL_WORKERS = 1;

    /** Seconds to wait after EC2 reports "running" for rc.local + WebServer to come up. */
    private static final int WEBSERVER_WARMUP_SECONDS = 45;

    public static void main(String[] args) throws Exception {
        final EC2Manager    ec2     = new EC2Manager();
        final MetricsClient metrics = new MetricsClient();
        final WorkerPool    pool    = new WorkerPool();
        final LambdaInvoker lambdaInvoker = Config.lambdaEnabled() ? new LambdaInvoker() : null;
        final AutoScaler         scaler  = new AutoScaler(ec2, pool, metrics);
        final HealthChecker      health  = new HealthChecker(ec2, pool);
        final LoadBalancerServer server  = new LoadBalancerServer(LISTEN_PORT, pool, lambdaInvoker, scaler);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[shutdown] stopping autoscaler...");
            scaler.stop();
            System.out.println("[shutdown] stopping healthchecker...");
            health.stop();
            System.out.println("[shutdown] stopping server...");
            server.stop();
            pool.stop();

            for (Worker w : pool.snapshot()) {
                try {
                    System.out.println("[shutdown] terminating " + w.getInstanceId());
                    ec2.terminate(w.getInstanceId());
                } catch (Exception e) {
                    System.err.println("[shutdown] failed to terminate "
                            + w.getInstanceId() + ": " + e.getMessage());
                }
            }
            ec2.close();
            metrics.close();
            if (lambdaInvoker != null) lambdaInvoker.close();
            System.out.println("[shutdown] done.");
        }, "lb-shutdown"));

        // 1. Launch initial workers
        for (int i = 0; i < INITIAL_WORKERS; i++) {
            System.out.println("[startup] launching worker " + (i + 1) + "/" + INITIAL_WORKERS);
            String id = ec2.launchWorker();
            System.out.println("[startup] launched " + id + ", waiting until running...");
            ec2.waitUntilRunning(id);
            String ip = ec2.getPublicIp(id);
            System.out.println("[startup] " + id + " running at " + ip);
            pool.register(new Worker(id, ip, WORKER_PORT));
        }

        // 2. Give WebServer(s) time to bind port 8000
        System.out.println("[startup] sleeping " + WEBSERVER_WARMUP_SECONDS +
                "s for WebServer(s) to come up...");
        Thread.sleep(WEBSERVER_WARMUP_SECONDS * 1000L);

        // 3. Start background threads
        scaler.start();
        health.start();

        // 4. Start HTTP front-end
        server.start();

        System.out.println("[ready] LB up with " + pool.size() + " worker(s). Try:");
        System.out.println("  curl 'http://localhost:" + LISTEN_PORT + "/test'");
        System.out.println("  curl 'http://localhost:" + LISTEN_PORT + "/work?n=7500'");
        System.out.println("Generate load with:");
        System.out.println("  ab -c 10 -n 5000 -s 60 'http://localhost:" + LISTEN_PORT + "/work?n=20000'");
        System.out.println("Press Ctrl+C to stop and terminate workers.");

        new CountDownLatch(1).await();
    }
}
