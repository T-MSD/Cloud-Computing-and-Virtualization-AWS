package pt.ulisboa.tecnico.cnv.loadbalancer;

import software.amazon.awssdk.regions.Region;

/**
 * Central place to read AWS configuration from environment variables
 * (which are set by sourcing config.sh).
 *
 * Calling any required() field without the corresponding env var set
 * throws immediately on first use, with a clear message — better than
 * a cryptic AWS SDK error later.
 */
public final class Config {

    private Config() {}

    public static Region region() {
        return Region.of(required("AWS_DEFAULT_REGION"));
    }

    public static String amiId() {
        return required("AWS_AMI_ID");
    }

    public static String keyPairName() {
        return required("AWS_KEYPAIR_NAME");
    }

    public static String securityGroupId() {
        return required("AWS_SECURITY_GROUP_ID");
    }

    /** Optional. Defaults to t3.micro (free-tier). Override with AWS_INSTANCE_TYPE if needed. */
    public static String instanceType() {
        String v = System.getenv("AWS_INSTANCE_TYPE");
        return (v == null || v.isEmpty()) ? "t3.micro" : v;
    }

    /**
     * Optional. Name of the IAM Instance Profile attached to launched workers
     * so they can call AWS services (DynamoDB, etc.) without shipping
     * credentials. Returns null if unset — in that case workers launch with
     * no role and any AWS SDK calls inside them will fail with
     * "Unable to load credentials".
     *
     * Create the profile in IAM:
     *   Console -> IAM -> Roles -> Create role -> AWS service -> EC2 ->
     *   attach AmazonDynamoDBFullAccess (or narrower) -> name it.
     * AWS auto-creates an Instance Profile with the same name.
     */
    public static String instanceProfileName() {
        String v = System.getenv("AWS_INSTANCE_PROFILE");
        return (v == null || v.isEmpty()) ? null : v;
    }

    /**
     * Returns true when at least one workload's Lambda function is configured.
     * Each workload has its own function env var, read in LoadBalancerServer:
     *   /fractals  -> AWS_LAMBDA_FRACTALS
     *   /dna       -> AWS_LAMBDA_DNA
     *   /grayscott -> AWS_LAMBDA_GRAYSCOTT
     * If none is set, Lambda routing is disabled and every request goes to a
     * worker EC2 instance.
     */
    public static boolean lambdaEnabled() {
        return isSet("AWS_LAMBDA_FRACTALS")
            || isSet("AWS_LAMBDA_DNA")
            || isSet("AWS_LAMBDA_GRAYSCOTT");
    }

    private static boolean isSet(String envName) {
        String v = System.getenv(envName);
        return v != null && !v.isEmpty();
    }

    /**
     * Maximum request cost eligible for Lambda offload. Heavy requests above
     * this cost stay on EC2 workers (Lambda's 15-min hard timeout makes it
     * unsuitable for long-running work, and the cost-per-ms is higher).
     * Only consulted when lambdaEnabled() is true. Default 10000.
     */
    public static long lambdaMaxCost() {
        String v = System.getenv("LAMBDA_MAX_COST");
        if (v == null || v.isEmpty()) return 10_000L;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return 10_000L; }
    }

    /**
     * Worker pool's average CPU% must be above this for Lambda to take traffic.
     * Below this threshold, the pool has capacity and there's no point paying
     * for Lambda. Default 70.0.
     */
    public static double lambdaCpuThreshold() {
        String v = System.getenv("LAMBDA_CPU_THRESHOLD");
        if (v == null || v.isEmpty()) return 70.0;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return 70.0; }
    }

    private static String required(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException(
                    "Environment variable " + name + " is not set. " +
                    "Did you `source ./config.sh` before running?");
        }
        return v;
    }
}
