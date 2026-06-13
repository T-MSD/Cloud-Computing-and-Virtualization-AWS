package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Estimates the relative cost of a request based on its URL.
 *
 * This is the load balancer's view of "how heavy will this request be" — used
 * to pick which worker should handle it.
 *
 * Initial implementation supports the test WebServer's /work?n=N endpoint
 * (cost is proportional to N). When the project's real workloads are wired
 * up, extend this with paths like /fractal, /dna, /grayscott using their
 * own parameter formulas (e.g. the diagram's C = i*0.5 + bb*0.35 + m*0.15).
 *
 * Any unknown path gets DEFAULT_COST so the LB still routes it sensibly.
 */
public class RequestCostEstimator {

    /** Cost assigned to a request whose endpoint/params we don't recognize. */
    private static final long DEFAULT_COST = 100;

    /** Cost for a /test request — very light, just a sanity-check endpoint. */
    private static final long TEST_COST = 1;

    public long estimate(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        Map<String, String> params = parseQuery(uri.getRawQuery());

        switch (path) {
            case "/test":
                return TEST_COST;

            case "/work": {
                // Cost is proportional to n; this is what /work?n=N uses internally.
                long n = parseLong(params.get("n"), 7500);
                return Math.max(1, n);
            }

            // TODO: when project workloads are integrated, add cases for
            //   "/fractal":     params W,H,i,v,bb,m  ->  cost from C = i*0.5 + bb*0.35 + m*0.15
            //   "/dna":         params seq           ->  cost from len(seq)^2 or similar
            //   "/grayscott":   params iters, size   ->  cost from iters * size^2
            // The point is to give the LB an a-priori cost estimate per request.

            default:
                return DEFAULT_COST;
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return map;
        for (String kv : rawQuery.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) map.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        return map;
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) return fallback;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return fallback; }
    }
}
