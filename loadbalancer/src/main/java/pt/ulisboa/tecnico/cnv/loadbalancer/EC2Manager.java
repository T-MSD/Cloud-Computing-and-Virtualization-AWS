package pt.ulisboa.tecnico.cnv.loadbalancer;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Wraps all EC2-related operations the load balancer needs:
 * launching, terminating, describing, and listing worker instances.
 *
 * All AWS configuration (region, AMI, instance type, key pair, security
 * group, credentials) is read from environment variables via {@link Config},
 * which are populated by sourcing config.sh before launching the program.
 */
public class EC2Manager {

    private final Ec2Client ec2;

    public EC2Manager() {
        this.ec2 = Ec2Client.builder()
                .region(Config.region())
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .build();
    }

    // ---- public API -----------------------------------------------------

    /** Launch one worker instance from the configured AMI. Returns its instance ID. */
    public String launchWorker() {
        RunInstancesRequest.Builder builder = RunInstancesRequest.builder()
                .imageId(Config.amiId())
                .instanceType(Config.instanceType())
                .minCount(1).maxCount(1)
                .keyName(Config.keyPairName())
                .securityGroupIds(Config.securityGroupId());

        // Optional: attach IAM Instance Profile so the worker can call AWS APIs.
        String profile = Config.instanceProfileName();
        if (profile != null) {
            builder.iamInstanceProfile(b -> b.name(profile));
        }

        RunInstancesResponse response = ec2.runInstances(builder.build());
        return response.instances().get(0).instanceId();
    }

    /**
     * Block until the given instance reaches the "running" state.
     * The SDK waiter polls describeInstances internally; typical wait is 30-90 seconds.
     * Returns the public DNS name once running.
     */
    public String waitUntilRunning(String instanceId) {
        ec2.waiter().waitUntilInstanceRunning(b -> b.instanceIds(instanceId));
        return getPublicDns(instanceId);
    }

    /** Returns the public DNS name (e.g. ec2-13-51-159-78.eu-north-1.compute.amazonaws.com). */
    public String getPublicDns(String instanceId) {
        return describeOne(instanceId).publicDnsName();
    }

    /** Returns the public IPv4 address. */
    public String getPublicIp(String instanceId) {
        return describeOne(instanceId).publicIpAddress();
    }

    /** Terminate one instance (fire-and-forget; AWS handles the shutdown). */
    public void terminate(String instanceId) {
        ec2.terminateInstances(b -> b.instanceIds(instanceId));
    }

    /** All instances currently in state "running" across the account. */
    public List<Instance> listRunningInstances() {
        return ec2.describeInstances().reservations().stream()
                .flatMap(r -> r.instances().stream())
                .filter(i -> "running".equals(i.state().nameAsString()))
                .collect(Collectors.toList());
    }

    public void close() {
        ec2.close();
    }

    // ---- helpers --------------------------------------------------------

    private Instance describeOne(String instanceId) {
        DescribeInstancesResponse resp = ec2.describeInstances(b -> b.instanceIds(instanceId));
        return resp.reservations().get(0).instances().get(0);
    }
}
