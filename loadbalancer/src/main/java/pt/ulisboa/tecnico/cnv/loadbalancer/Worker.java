package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One backend worker EC2 instance.
 *
 * Identity is the AWS instance ID (immutable for the worker's lifetime).
 * Public IP and DNS are looked up once at registration time.
 *
 * `currentLoad` is the SUM of estimated request costs currently in flight on
 * this worker. The LoadBalancerServer adds a request's cost when dispatching
 * and subtracts it when the response comes back. WorkerPool.pickWorker()
 * uses this to send the next request to the least-loaded worker.
 *
 * Costs are stored as longs (cost values are small integers from RequestCostEstimator).
 */
public final class Worker {

    private final String instanceId;
    private final String host;
    private final int port;

    private final AtomicLong currentLoad = new AtomicLong(0);

    public Worker(String instanceId, String host, int port) {
        this.instanceId = instanceId;
        this.host = host;
        this.port = port;
    }

    public String getInstanceId() { return instanceId; }
    public String getHost()       { return host; }
    public int    getPort()       { return port; }

    public long getCurrentLoad()  { return currentLoad.get(); }

    /** Called when dispatching a request to this worker. */
    public void addLoad(long cost) { currentLoad.addAndGet(cost); }

    /** Called when the response comes back (or the request fails). */
    public void removeLoad(long cost) { currentLoad.addAndGet(-cost); }

    @Override
    public boolean equals(Object o) {
        return o instanceof Worker && ((Worker) o).instanceId.equals(instanceId);
    }

    @Override
    public int hashCode() { return instanceId.hashCode(); }

    @Override
    public String toString() {
        return "Worker[" + instanceId + " @ " + host + ":" + port + ", load=" + currentLoad.get() + "]";
    }
}
