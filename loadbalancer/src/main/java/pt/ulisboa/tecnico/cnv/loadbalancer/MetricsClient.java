package pt.ulisboa.tecnico.cnv.loadbalancer;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Wraps the CloudWatch SDK. The only method the AutoScaler needs is
 * "what's the recent average CPU of this instance?".
 *
 * NOTE on freshness:
 *   - With Detailed Monitoring DISABLED (default), AWS publishes one CPU
 *     datapoint per 5 minutes. The AutoScaler will see metrics 1-5 minutes
 *     stale, so scaling reactions are slow.
 *   - With Detailed Monitoring ENABLED (Launch Template -> Advanced -> on),
 *     datapoints come every 1 minute. Much snappier scaling.
 */
public class MetricsClient {

    /** How far back to look when fetching the latest CPU datapoint. */
    private static final Duration LOOKBACK = Duration.ofMinutes(10);

    /** Period of each datapoint, in seconds. 60 works with detailed monitoring; basic monitoring rounds up to 300. */
    private static final int PERIOD_SECONDS = 60;

    private final CloudWatchClient cw;

    public MetricsClient() {
        this.cw = CloudWatchClient.builder()
                .region(Config.region())
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .build();
    }

    /**
     * Returns the most recent average CPU% datapoint for the given instance,
     * or null if CloudWatch has no data yet (typical right after launch).
     */
    public Double getAverageCpu(String instanceId) {
        GetMetricStatisticsRequest req = GetMetricStatisticsRequest.builder()
                .namespace("AWS/EC2")
                .metricName("CPUUtilization")
                .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build())
                .startTime(Instant.now().minus(LOOKBACK))
                .endTime(Instant.now())
                .period(PERIOD_SECONDS)
                .statistics(Statistic.AVERAGE)
                .build();

        List<Datapoint> dps = cw.getMetricStatistics(req).datapoints();
        if (dps.isEmpty()) return null;

        return dps.stream()
                .max(Comparator.comparing(Datapoint::timestamp))
                .map(Datapoint::average)
                .orElse(null);
    }

    public void close() { cw.close(); }
}
