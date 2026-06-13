# Nature@Cloud

A cloud-native compute service for three CPU-intensive scientific workloads, built for the
**Cloud Computing and Virtualization (CNV) 2025/26** course at Instituto Superior Técnico.

The system runs the workloads behind a **custom load balancer and auto-scaler** written in Java
(no AWS ALB / Auto Scaling Group), which provisions EC2 workers on demand, spreads requests by
predicted cost, and offloads light requests to **AWS Lambda** while the worker fleet is scaling up.
Request complexity is learned at runtime through **bytecode instrumentation** and stored in DynamoDB,
then used to make routing decisions.

---

## Workloads

| Endpoint | Workload | Problem-size metric `V` |
|---|---|---|
| `/fractals` | Julia-set fractal generation | `width · height · iterations` |
| `/grayscott` | Gray-Scott reaction-diffusion simulation | `size² · maxIterations` |
| `/dna` | DNA sequence alignment | `len(seq1) · len(seq2)` |

Each returns its result directly over HTTP (a PNG data URI for the image workloads, HTML for DNA).

---

## Architecture

```
                 ┌────────────────────────────────────────────────────────┐
   client  ─────►│  Load Balancer (custom, Java)   [EC2]                  │
                 │   • least-loaded worker selection (by request cost)    │
                 │   • AutoScaler  — CloudWatch CPU, scale 80% / 25%      │
                 │   • HealthChecker — pings /test, replaces dead workers │
                 │   • Lambda offload — light requests while scaling up   │
                 └───────┬───────────────────────────────┬────────────────┘
                         │ forward (HTTP :8000)           │ invoke (light + hot pool)
                         ▼                                ▼
                 ┌────────────────────┐              ┌──────────────────────┐
                 │  EC2 worker pool   │              │  AWS Lambda          │
                 │  (WebServer +      │              │  one fn per workload │
                 │   Javassist agent) |              └──────────────────────┘
                 └────────┬───────────┘
                          │ write per-request metrics (V, normalized C)
                          ▼
                 ┌───────────────────────────────┐
                 │  DynamoDB  (Workload_Metrics) │  ◄── LB reads back to build
                 └───────────────────────────────┘       per-workload V→C caches
```

### How a request is routed

1. The LB estimates the request and picks the **least-loaded** worker (load = sum of in-flight
   request costs), so heavy requests don't pile onto one instance.
2. The **AutoScaler** polls CloudWatch for the pool's average CPU every 30 s and grows the fleet
   above 80 % / shrinks it below 25 % (bounded by min/max).
3. The **HealthChecker** pings each worker's `/test` endpoint; workers failing repeatedly are
   dropped and (above the minimum) replaced.
4. **Lambda offload**: while the pool is hot (avg CPU over the threshold) *and* the request is
   predicted to be light, the LB invokes the workload's Lambda function instead of a worker.
   Heavy requests always stay on EC2 (Lambda's 15-minute limit makes it unsuitable for long jobs).

### How complexity is learned

The `javassist` agent instruments the workload bytecode and, for every request, records:

- **`V`** — the problem-size metric (deterministic from the request parameters, see table above).
- **`C`** — a normalized complexity in `[0, 1]`, derived from instruction / basic-block / method
  counts and scaled per workload (via a rule-of-three against a reference run) so the reference
  workload maps to `0.8`.

Each `(args, V, C, workload)` sample is written once to the **`Workload_Metrics`** DynamoDB table
(deduplicated by the request args).

### The proximity cache

The load balancer keeps **one in-memory cache per workload** (`ConcurrentProximityCache`) that maps
`V → C`, so it can predict a new request's complexity *before* running it and decide where to send it.

- **Refresh** — a background task (`updateCaches`, every 45 s) calls `DynamoDBMetricRepository`,
  which samples the table **evenly across complexity buckets** (a handful of items per `C`-band)
  so the cache holds a representative spread of `(V, C)` points rather than a biased clump.
- **Lookup** — `getExactOrClosestValues(V)` returns the exact match, or the nearest sample below
  and/or above `V`, but only if within a configurable **proximity threshold** (per-workload, set by
  `*_PROXIMITY` env vars). This yields **0, 1, or 2** points.
- **Decision** (`LoadBalancerServer.lambdaComplexity`):
  - **0 points** → no nearby data → keep on EC2 (play safe).
  - **1 point** → use its `C`.
  - **2 points** → linearly interpolate `C` at the request's `V` (`y = m·x + b`).
  - Then `C < 0.8` ⇒ the request is predicted **light** and is Lambda-eligible; otherwise EC2.

This complexity gate is combined with the CPU gate: a request is sent to Lambda only when the pool
is hot (avg CPU above the threshold) **and** the cache predicts it light — so Lambda absorbs cheap
overflow while the fleet scales, and heavy jobs always stay on EC2.

---

## Modules

| Module | Description |
|---|---|
| `fractals` | Julia-set fractal workload (HTTP handler + Lambda handler) |
| `dna` | DNA alignment workload (HTTP handler + Lambda handler) |
| `grayscott` | Gray-Scott reaction-diffusion workload (HTTP handler + Lambda handler) |
| `webserver` | HTTP server exposing the three workloads on port 8000 |
| `javassist` | Bytecode-instrumentation agent that extracts `V`/`C` metrics and writes them to DynamoDB |
| `loadbalancer` | Custom load balancer + auto-scaler + health checker + Lambda router, plus the `cache` package (`ConcurrentProximityCache`, `DynamoDBMetricRepository`) backing complexity-aware routing |

Each module has its own `README.md` with further detail.

---

## Building

Requires **Java 11+** and **Maven**.

```bash
mvn clean package
```

This produces a fat jar per module (e.g. `webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar`).

### Running a worker locally (with the metrics agent)

```bash
java -cp webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     -Xbootclasspath/a:javassist/target/javassist-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     -javaagent:javassist/target/javassist-1.0.0-SNAPSHOT-jar-with-dependencies.jar=MetricExtractor:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output \
     pt.ulisboa.tecnico.cnv.webserver.WebServer
```

The server listens on port `8000`. Example requests:

```bash
curl 'http://localhost:8000/fractals?w=800&h=600&iterations=100'
curl 'http://localhost:8000/grayscott?size=256&maxIterations=5000&f=0.030&k=0.062'
curl 'http://localhost:8000/dna?seq1=NAME:ATGC&seq2=NAME:ATGT&minLength=2&stopOnFirst=false'
```

### Running the load balancer

The load balancer reads all AWS configuration from environment variables. Copy the template,
fill in your values, and source it before launching:

```bash
cp config.sh.example config.sh   # then edit config.sh (gitignored)
source config.sh
mvn -pl loadbalancer exec:java \
    -Dexec.mainClass=pt.ulisboa.tecnico.cnv.loadbalancer.LoadBalancerMain
```

See [`loadbalancer/README.md`](loadbalancer/README.md) for the load-balancer details and
[`config.sh.example`](config.sh.example) for every configuration option (AMI, security group,
IAM instance profile, Lambda function names, scaling thresholds, cache proximity values).

---

## AWS deployment overview

- **Workers** are launched from a custom **AMI** (the WebServer + metrics agent, started at boot)
  and run the instrumented server on port 8000.
- The load balancer launches/terminates workers through the **EC2 SDK**, reads CPU from
  **CloudWatch**, and persists/reads request metrics in **DynamoDB**.
- **Lambda** functions (one per workload) provide elastic overflow capacity for light requests.
- Workers receive AWS permissions through an **IAM instance profile** (no credentials are baked
  into the AMI).
- The **`Workload_Metrics`** DynamoDB table stores the learned `(V, C)` samples. The load balancer
  reads it to populate the proximity caches; the workers' instrumentation agent writes to it. The
  per-workload proximity thresholds are configured by the `*_PROXIMITY` env vars in `config.sh`.

> **Cost note:** EC2 instances and load balancers accrue charges while running. Terminate all
> instances and stop the load balancer when finished.
