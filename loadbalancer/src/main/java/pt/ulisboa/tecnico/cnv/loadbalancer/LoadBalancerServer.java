package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import pt.ulisboa.tecnico.cnv.loadbalancer.cache.ConcurrentProximityCache;
import pt.ulisboa.tecnico.cnv.loadbalancer.cache.DynamoDBMetricRepository;
import pt.ulisboa.tecnico.cnv.loadbalancer.cache.Workload;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HTTP front-end. Listens on a port, then for each request decides whether
 * to forward it to a worker EC2 (default) or invoke a Lambda function.
 *
 * Lambda is used as an overflow valve for LIGHT requests when the worker
 * pool is overloaded. A request goes to Lambda when ALL are true:
 *   - a Lambda function is configured for this request's path (one function
 *     per workload: AWS_LAMBDA_FRACTALS / _DNA / _GRAYSCOTT)
 *   - request cost <= LAMBDA_MAX_COST    (light enough for Lambda's 15-min timeout)
 *   - workers' average CPU > LAMBDA_CPU_THRESHOLD (workers are too busy)
 *
 * Heavy requests always stay on EC2 — Lambda is the wrong tool for long jobs.
 * Light requests stay on EC2 when the pool has spare capacity — Lambda is
 * paid per invocation; only worth it when workers are saturated.
 *
 * Worker selection within the pool is least-loaded (see WorkerPool).
 */
public class LoadBalancerServer {

    private final int listenPort;
    private final WorkerPool pool;
    private final LambdaInvoker lambdaInvoker;            // nullable
    private final AutoScaler autoScaler;                  // nullable; used to read avg CPU
    private final long   lambdaMaxCost;
    private final double lambdaCpuThreshold;
    private final ConcurrentProximityCache juliaCache;
    private final ConcurrentProximityCache grayScottCache;
    private final ConcurrentProximityCache dnaCache;
    private final RequestCostEstimator costEstimator = new RequestCostEstimator();
    private final ScheduledExecutorService scheduler;
    private final long defaultProximityThreshold = 3000L;
    private DynamoDBMetricRepository repository;

    /**
     * Maps a request path to the Lambda function that handles that workload.
     * Only paths whose function env var is set are present; a path absent from
     * this map is never sent to Lambda (it stays on the worker pool).
     */
    private final Map<String, String> pathLambdaFunction = new HashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMinutes(30))
            .build();

    private HttpServer server;

    public LoadBalancerServer(int listenPort, WorkerPool pool,
                              LambdaInvoker lambdaInvoker, AutoScaler autoScaler) {
        this.listenPort = listenPort;
        this.pool = pool;
        this.lambdaInvoker = lambdaInvoker;
        this.autoScaler = autoScaler;
        this.lambdaMaxCost      = (lambdaInvoker == null) ? 0L  : Config.lambdaMaxCost();
        this.lambdaCpuThreshold = (lambdaInvoker == null) ? 101 : Config.lambdaCpuThreshold();
        
        putIfSet("/fractals",  System.getenv("AWS_LAMBDA_FRACTALS"));
        putIfSet("/dna",       System.getenv("AWS_LAMBDA_DNA"));
        putIfSet("/grayscott", System.getenv("AWS_LAMBDA_GRAYSCOTT"));

        juliaCache = new ConcurrentProximityCache(Optional.ofNullable(System.getenv("JULIA_FRACTALS_PROXIMITY"))
                .map(LoadBalancerServer::safeParseLong)
                .orElse(defaultProximityThreshold));
        grayScottCache = new ConcurrentProximityCache(Optional.ofNullable(System.getenv("GRAY_SCOTT_PROXIMITY"))
                .map(LoadBalancerServer::safeParseLong)
                .orElse(defaultProximityThreshold));
        dnaCache = new ConcurrentProximityCache(Optional.ofNullable(System.getenv("DNA_PROXIMITY"))
                .map(LoadBalancerServer::safeParseLong)
                .orElse(defaultProximityThreshold));

        var ddb = DynamoDbClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.US_EAST_1)
                .build();
        repository = new DynamoDBMetricRepository(ddb, "Workload_Metrics", "Workload");

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                this::updateCaches,
                0,
                45,
                TimeUnit.SECONDS
        );
    }

    private static Long safeParseLong(String value) {
        try {
            return Long.parseLong(value.trim()); // .trim() handles accidental spaces too!
        } catch (NumberFormatException e) {
            return null; // Returning null tells Optional to use the .orElse fallback
        }
    }

    private void updateCaches() {
        try {
            juliaCache.renewCacheValues(repository.performEvenDistributionQuery(Workload.JULIA_FRACTALS));
            grayScottCache.renewCacheValues(repository.performEvenDistributionQuery(Workload.GRAY_SCOTT));
            dnaCache.renewCacheValues(repository.performEvenDistributionQuery(Workload.DNA_ALIGNMENT));
        } catch (Exception e) {
            System.err.println("[cache] refresh failed: " + e.getMessage());
        }
    }

    private void putIfSet(String path, String fn) {
        if (fn != null && !fn.isEmpty()) {
            pathLambdaFunction.put(path, fn);
        }
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(listenPort), 0);
        server.createContext("/", new ForwardingHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("LoadBalancer listening on :" + listenPort
                + (lambdaInvoker == null || pathLambdaFunction.isEmpty()
                    ? " (Lambda disabled)"
                    : " (Lambda enabled, functions=" + pathLambdaFunction
                            + ", offload requests with cost<=" + lambdaMaxCost
                            + " when avg CPU>" + lambdaCpuThreshold + "%)"));
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            System.out.println("LoadBalancer stopped.");
        }
    }

    /** A request whose predicted complexity C is below this goes to Lambda. */
    private static final float LAMBDA_COMPLEXITY_THRESHOLD = 0.8f;

    /**
     * Uses the per-workload proximity cache to predict this request's complexity
     * C and decide Lambda vs EC2:
     *   - 0 cache entries near this request's V  -> EC2 (no data, play safe)
     *   - 1 entry                                -> use its C
     *   - 2 entries (floor + ceiling)            -> linearly interpolate C at V
     * Then: C < LAMBDA_COMPLEXITY_THRESHOLD -> Lambda (light), else EC2 (heavy).
     *
     * V is computed exactly like WorkloadMetric.getV() in the javassist module.
     */
    private boolean lambdaComplexity(String path, String rawQuery) {
        Workload workload = workloadForPath(path);
        if (workload == null) return false;
        ConcurrentProximityCache cache = cacheForWorkload(workload);
        if (cache == null) return false;

        Long v = computeV(workload, parseParams(rawQuery));
        System.out.println("Computed V: " + v);
        if (v == null) return false;

        List<Map.Entry<Long, Float>> hits = cache.getExactOrClosestValues(v);
        if (hits.isEmpty()) return false;

        float c;
        if (hits.size() == 1) {
            if (!hits.get(0).getKey().equals(v)){
                System.out.println("Single hit, different from V. Skipping");
                return false;
            }
            c = hits.get(0).getValue();
            System.out.println("Single hit, exact value: " + c);
        } else {
            // 2 entries found. They could be [floor, ceiling], [floor2, floor1], or [ceiling1, ceiling2]
            Map.Entry<Long, Float> entryA = hits.get(0);
            Map.Entry<Long, Float> entryB = hits.get(1);

            // CRITICAL FIX: Ensure x0 is always the smaller key and x1 is the larger key
            // This guarantees that (x1 - x0) is always positive, keeping the slope math stable.
            long x0, x1;
            float y0, y1;

            if (entryA.getKey() < entryB.getKey()) {
                x0 = entryA.getKey(); y0 = entryA.getValue();
                x1 = entryB.getKey(); y1 = entryB.getValue();
            } else {
                x0 = entryB.getKey(); y0 = entryB.getValue();
                x1 = entryA.getKey(); y1 = entryA.getValue();
            }

            if (x1 == x0) {
                c = y0;
            } else {
                // Standard linear equation: y = y0 + ((y1 - y0) / (x1 - x0)) * (x - x0)
                double m = (double) (y1 - y0) / (double) (x1 - x0);
                c = (float) (y0 + m * (v - x0));
            }
            System.out.println("Two hits. Approximation calculated: " + c);
        }
        return c < LAMBDA_COMPLEXITY_THRESHOLD;
    }

    private static Workload workloadForPath(String path) {
        switch (path) {
            case "/fractals":  return Workload.JULIA_FRACTALS;
            case "/dna":       return Workload.DNA_ALIGNMENT;
            case "/grayscott": return Workload.GRAY_SCOTT;
            default:           return null;
        }
    }

    private ConcurrentProximityCache cacheForWorkload(Workload w) {
        switch (w) {
            case JULIA_FRACTALS: return juliaCache;
            case GRAY_SCOTT:     return grayScottCache;
            case DNA_ALIGNMENT:  return dnaCache;
            default:             return null;
        }
    }

    /* Mirror of WorkloadMetric.setV() (javassist module) */
    private static Long computeV(Workload workload, Map<String, String> p) {
        try {
            switch (workload) {
                case JULIA_FRACTALS: {
                    long w  = Long.parseLong(p.get("w"));
                    long h  = Long.parseLong(p.get("h"));
                    long it = Long.parseLong(p.get("iterations"));
                    return w * h * it;
                }
                case GRAY_SCOTT: {
                    long s    = Long.parseLong(p.get("size"));
                    long imax = Long.parseLong(p.get("maxIterations"));
                    return s * s * imax;
                }
                case DNA_ALIGNMENT: {
                    String seq1 = p.get("seq1");
                    String seq2 = p.get("seq2");
                    if (seq1 == null || seq2 == null) return null;
                    int l1 = seq1.substring(seq1.lastIndexOf('\n') + 1).length();
                    int l2 = seq2.substring(seq2.lastIndexOf('\n') + 1).length();
                    return (long) l1 * l2;
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Parse a raw query string into URL-decoded key/value pairs. */
    private static Map<String, String> parseParams(String rawQuery) {
        Map<String, String> m = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return m;
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && !kv[0].isEmpty()) {
                m.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private class ForwardingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // CORS headers on every response so browser fetch() calls from
            // file:// or other-origin pages succeed.
            addCorsHeaders(exchange);

            String method = exchange.getRequestMethod();

            // Preflight: respond 204 immediately with the CORS headers above.
            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String rawPath  = exchange.getRequestURI().getRawPath();
            String rawQuery = exchange.getRequestURI().getRawQuery();
            String pathAndQuery = (rawQuery == null) ? rawPath : (rawPath + "?" + rawQuery);

            long cost = costEstimator.estimate(exchange.getRequestURI());

            if (shouldUseLambda(cost, rawPath, rawQuery)) {
                System.out.println("Using lambda for " + method);
                handleViaLambda(exchange, method, rawPath, rawQuery, pathAndQuery, cost);
            } else {
                handleViaWorker(exchange, method, pathAndQuery, cost);
            }
        }

        private void addCorsHeaders(HttpExchange exchange) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "*");
            exchange.getResponseHeaders().add("Access-Control-Max-Age",       "3600");
        }

        /**
         * Lambda gets light requests only when the worker pool is hot AND a
         * Lambda function is configured for this request's path. Heavy requests
         * never go to Lambda (15-min timeout, higher cost per ms). Light requests
         * prefer workers when the pool has capacity.
         */
        private boolean shouldUseLambda(long cost, String path, String rawQuery) {
            // Fail conditions
            if (lambdaInvoker == null) return false;
            if (pathLambdaFunction.get(path) == null) return false;
            // if (cost > lambdaMaxCost) return false;
            // ======================================
            // Only offload while we are scaling up: avgCPU stays above the
            // threshold until the new EC2 worker is up and absorbing load.
            double avgCpu = (autoScaler == null) ? -1 : autoScaler.getLastAvgCpu();
            if (avgCpu <= lambdaCpuThreshold) return false;
            // And only offload requests the caches predict to be light (C < 0.8).
            var comp = lambdaComplexity(path, rawQuery);
            System.out.println("LambdaComplexity outputed -> " +comp);
            return comp;
        }

        // ---- Lambda branch ----------------------------------------------

        private void handleViaLambda(HttpExchange exchange, String method,
                                     String path, String query, String pathAndQuery, long cost)
                throws IOException {

            // Parse once, URL-decoded, into the flat param map the per-workload
            // Lambda handlers expect (e.g. {"w":"800","h":"600","iterations":"100"}).
            Map<String, String> params = parseParams(query);
            String payload = JsonUtil.objectOf(params);

            String functionName = pathLambdaFunction.get(path);

            try {
                String responseJson = lambdaInvoker.invoke(functionName, payload);

                // Parse {"statusCode": 200, "body": "..."} if present; else use raw payload.
                Integer status = JsonUtil.extractInt(responseJson, "statusCode");
                String respBody = JsonUtil.extractString(responseJson, "body");
                if (status == null) status = 200;
                if (respBody == null) respBody = responseJson;

                byte[] respBytes = respBody.getBytes();
                exchange.sendResponseHeaders(status, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }
                System.out.println(method + " " + pathAndQuery +
                        " -> LAMBDA:" + functionName + " (cost=" + cost + ") -> " + status);
            } catch (Exception e) {
                writeResponse(exchange, 502, "Lambda error: " + e.getMessage());
                System.err.println(method + " " + pathAndQuery +
                        " -> LAMBDA:" + functionName + " (cost=" + cost + ") -> 502 (" + e.getMessage() + ")");
            }
        }

        // ---- Worker branch ----------------------------------------------

        private void handleViaWorker(HttpExchange exchange, String method,
                                     String pathAndQuery, long cost) throws IOException {
            Worker worker = pool.pickWorker();
            if (worker == null) {
                writeResponse(exchange, 503, "No workers available");
                System.err.println(method + " " + pathAndQuery + " -> 503 (no workers)");
                return;
            }

            URI targetUri = URI.create("http://" + worker.getHost() + ":" + worker.getPort() + pathAndQuery);

            worker.addLoad(cost);
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri)
                        .timeout(Duration.ofMinutes(30));

                if ("GET".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
                }
                for (Map.Entry<String, List<String>> h : exchange.getRequestHeaders().entrySet()) {
                    if (isForwardableHeader(h.getKey())) {
                        for (String v : h.getValue()) {
                            builder.header(h.getKey(), v);
                        }
                    }
                }

                HttpResponse<byte[]> resp = httpClient.send(builder.build(),
                        HttpResponse.BodyHandlers.ofByteArray());

                exchange.sendResponseHeaders(resp.statusCode(), resp.body().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp.body());
                }
                System.out.println(method + " " + pathAndQuery +
                        " -> " + worker.getInstanceId() +
                        " (cost=" + cost + " load=" + worker.getCurrentLoad() + ")" +
                        " -> " + resp.statusCode());
            } catch (Exception e) {
                writeResponse(exchange, 502, "Bad gateway: " + e.getMessage());
                System.err.println(method + " " + pathAndQuery +
                        " -> " + worker.getInstanceId() +
                        " (cost=" + cost + " load=" + worker.getCurrentLoad() + ")" +
                        " -> 502 (" + e.getMessage() + ")");
            } finally {
                worker.removeLoad(cost);
            }
        }
    }

    private static void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static boolean isForwardableHeader(String name) {
        String n = name.toLowerCase();
        switch (n) {
            case "host":
            case "connection":
            case "content-length":
            case "transfer-encoding":
            case "expect":
            case "upgrade":
                return false;
            default:
                return true;
        }
    }
}
