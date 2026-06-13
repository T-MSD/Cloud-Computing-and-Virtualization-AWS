# Load Balancer + Auto Scaler

Custom Java load balancer and auto-scaler for the CNV project. Replaces the
AWS-managed Classic Load Balancer and Auto Scaling Group with our own logic
running in a single Java process.

## Architecture

```
                Client (curl, ab, browser)
                          |
                          v
        +------------------------------------------+
        |          LoadBalancerServer              |
        |    (HTTP listener on port 8080)          |
        |                                          |
        |  per-request:                            |
        |    cost  = RequestCostEstimator.est()    |
        |    target = WorkerPool.pickWorker()      |
        |    target.addLoad(cost)                  |
        |    forward request -> wait response      |
        |    target.removeLoad(cost)               |
        +-----+----------+----------+--------------+
              |          |          |
              v          v          v
        +-------+   +-------+   +-------+
        |Worker |   |Worker |   |Worker |     EC2 instances running
        |  EC2  |   |  EC2  |   |  EC2  |     WebServer on port 8000
        +-------+   +-------+   +-------+
                          ^
        ------------------+------------------
        |                                   |
   +---------+                       +-------------+
   |AutoScale|                       |HealthChecker|
   | (30s)   | <- CloudWatch CPU     | (15s) -> /test
   +---------+                       +-------------+
        |                                   |
        | launchWorker /                    | terminate
        | terminate                         | if unresponsive
        v                                   v
              +--------------------+
              |     EC2Manager     |
              |  (AWS EC2 SDK)     |
              +--------------------+
```

## Classes

| Class | Role |
|---|---|
| `LoadBalancerMain` | Entry point. Launches the initial worker(s), starts the background threads, blocks until Ctrl+C, terminates everything on shutdown. |
| `LoadBalancerServer` | HTTP server. Receives client requests, picks a worker, forwards the request, returns the response. |
| `WorkerPool` | Thread-safe registry of healthy workers. `pickWorker()` returns the least-loaded one. Logs status every 10 s. |
| `Worker` | One worker's identity (instance ID, host, port) and current in-flight cost (`addLoad`/`removeLoad`). |
| `RequestCostEstimator` | Maps an incoming request URI to a numeric cost. `/work?n=N` -> `N`; `/test` -> 1; unknown -> 100. |
| `EC2Manager` | Thin wrapper around the AWS EC2 SDK. `launchWorker()`, `terminate()`, `waitUntilRunning()`, `getPublicIp()`. |
| `MetricsClient` | Thin wrapper around the AWS CloudWatch SDK. `getAverageCpu(instanceId)` returns the most recent avg CPU% or null. |
| `AutoScaler` | Background thread. Every 30 s, polls CloudWatch for all workers' CPU and decides whether to add or remove one. Cooldown 120 s between actions. |
| `HealthChecker` | Background thread. Every 15 s, GETs `/test` on each worker. After 3 consecutive failures, deregisters and terminates the worker. |

## Request flow

1. Client sends `GET /work?n=20000` to the LB's port 8080.
2. `LoadBalancerServer.ForwardingHandler` parses the URI.
3. `RequestCostEstimator.estimate(uri)` returns the cost (`20000` for this URL).
4. `WorkerPool.pickWorker()` returns the worker with the lowest current load.
5. `worker.addLoad(cost)` increments the worker's in-flight cost counter.
6. The handler builds a matching outbound `HttpRequest` and sends it to the worker on port 8000 via `java.net.http.HttpClient`.
7. When the worker's response arrives, the handler copies status code + body back to the client.
8. `worker.removeLoad(cost)` decrements the counter.

If the pool is empty -> 503. If forwarding fails (worker unreachable, timeout) -> 502.

## Auto-scaling behavior

Configured in `AutoScaler.java`:

| Tunable | Value | Meaning |
|---|---|---|
| `MIN_WORKERS` | 1 | Never go below this. AutoScaler force-launches if pool ever falls below. |
| `MAX_WORKERS` | 3 | Never go above this. |
| `SCALE_UP_THRESHOLD` | 80 % | Average CPU above this -> add 1 worker |
| `SCALE_DOWN_THRESHOLD` | 25 % | Average CPU below this -> remove 1 worker |
| `CHECK_INTERVAL_SECONDS` | 30 | How often we evaluate |
| `COOLDOWN_SECONDS` | 120 | After any scaling action, wait this long before next |
| `DRAIN_SECONDS` | 10 | Wait this long after deregistering before terminating |

The AutoScaler:
- Polls `MetricsClient.getAverageCpu(instanceId)` for each worker
- Averages the values (workers without a CloudWatch datapoint yet are skipped)
- Triggers scale-up or scale-down asynchronously (so the eval thread is never blocked on EC2 API calls)
- On scale-up: `EC2Manager.launchWorker()`, wait until running, sleep `WEBSERVER_WARMUP_S` (45 s) for `/etc/rc.local` to bring up the WebServer, then `pool.register()`
- On scale-down: pick least-loaded worker, `pool.deregister()`, sleep `DRAIN_SECONDS` for in-flight requests to finish, `EC2Manager.terminate()`
- **Force-launches** if `pool.size() < MIN_WORKERS` regardless of CPU (covers the case where HealthChecker dropped every worker)

CloudWatch publishes EC2 CPU metrics every 1 minute when **Detailed Monitoring**
is enabled on the Launch Template; every 5 minutes otherwise. With basic monitoring,
the AutoScaler reacts slowly (3-7 min from load start to first scale-up).

## Health checking

Configured in `HealthChecker.java`:

| Tunable | Value | Meaning |
|---|---|---|
| `CHECK_INTERVAL_SECONDS` | 15 | How often to ping each worker |
| `FAILURE_THRESHOLD` | 3 | Consecutive failures before dropping |
| `HEALTH_CHECK_TIMEOUT_S` | 5 | Per-ping timeout |
| `HEALTH_PATH` | `/test` | Endpoint to ping |
| `TERMINATE_DEAD_WORKERS` | true | Also call EC2 terminate, so the worker is replaced |

A worker that fails 3 consecutive `/test` probes (about 45 s of being dead) is:
- Deregistered from the pool (no new requests routed to it)
- Terminated via `EC2Manager.terminate()` (the AutoScaler will launch a replacement to satisfy `MIN_WORKERS`)

## Cost-aware routing

`WorkerPool.pickWorker()` picks the worker with the **lowest current load**.
"Load" is the sum of in-flight request costs (computed at dispatch by
`RequestCostEstimator`). When a request completes, the worker's load is
decremented by the same amount.

This means a worker chewing on one heavy `/work?n=50000` request won't be
picked again for new requests until lighter requests finish on other
workers. The LB doesn't need to know in advance how fast each worker is —
the load counters self-balance as requests complete.

`RequestCostEstimator.estimate()` currently understands `/test` and `/work?n=N`.
For real project workloads (fractals / DNA / Gray-Scott), add cases that
parse those endpoints' parameters and return a representative cost.

## Configuration

AWS credentials and resource IDs are pulled from environment variables loaded
by `config.sh` at the project root:

```bash
source config.sh
mvn -pl loadbalancer compile
mvn -pl loadbalancer exec:java -Dexec.mainClass=pt.ulisboa.tecnico.cnv.loadbalancer.LoadBalancerMain
```

Required env vars:
- `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
- `AWS_DEFAULT_REGION` (set on the SDK clients, but the SDK also reads it implicitly)

AMI, key pair, security group, instance type, and region are currently hardcoded
in `EC2Manager.java` constants. Update them there when AWS resources change.

## Running

```bash
cd ~/cnv-shared/cnv26-g39
source ./config.sh

# Build everything
mvn -pl loadbalancer compile

# Start the load balancer (blocks; Ctrl+C to stop)
mvn -pl loadbalancer exec:java \
    -Dexec.mainClass=pt.ulisboa.tecnico.cnv.loadbalancer.LoadBalancerMain

# In another terminal — sanity check
curl -i 'http://localhost:8080/test'
curl -i 'http://localhost:8080/work?n=7500'

# Generate load to trigger scale-up
ab -c 10 -n 5000 -s 60 'http://localhost:8080/work?n=20000'
```

Stop with Ctrl+C in the LB terminal. The shutdown hook terminates the
AutoScaler, HealthChecker, HTTP server, then every registered worker EC2
instance.

## Observability

While running, the LB logs to stdout:

- `[startup] ...` — initial launch sequence
- `GET /work?... -> i-xxx (cost=N load=M) -> 200` — every forwarded request, with the picked worker, its cost, and its load at dispatch time
- `[pool] status: i-aaa=L1 i-bbb=L2 ...` — pool snapshot every 10 s
- `[autoscaler] avg CPU=X.X% across N worker(s), pool=K` — every 30 s
- `[autoscaler] SCALE UP / SCALE DOWN` — scaling actions
- `[health] i-xxx failed check 1/3 (reason)` — health check failures
- `[health] dropping unhealthy worker i-xxx`
- `[shutdown] ...` — cleanup sequence

## Files

```
loadbalancer/
+-- pom.xml                       Maven config; AWS SDK 2.x deps
+-- README.md                     This file
+-- src/main/java/pt/ulisboa/tecnico/cnv/
    +-- EC2LaunchWaitTerminate.java   Lab demo (kept for reference)
    +-- EC2MeasureCPU.java            Lab demo (kept for reference)
    +-- loadbalancer/
        +-- LoadBalancerMain.java         entry point
        +-- LoadBalancerServer.java       HTTP listener + forwarder
        +-- WorkerPool.java               thread-safe pool, least-loaded picker
        +-- Worker.java                   one worker + its in-flight cost
        +-- RequestCostEstimator.java     URI -> cost
        +-- EC2Manager.java               AWS EC2 SDK wrapper
        +-- EC2ManagerTest.java           manual launch/terminate smoke test
        +-- MetricsClient.java            AWS CloudWatch SDK wrapper
        +-- AutoScaler.java               CPU-driven scale up/down
        +-- HealthChecker.java            /test pings, deregister + terminate dead workers
```
