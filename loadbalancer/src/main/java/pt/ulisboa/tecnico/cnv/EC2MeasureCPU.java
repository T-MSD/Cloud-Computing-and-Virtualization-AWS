package pt.ulisboa.tecnico.cnv;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;

public class EC2MeasureCPU {

    // TODO - fill fields with correct values.
    private static final Region AWS_REGION = Region.EU_NORTH_1;

    // Total observation time in milliseconds.
    private static final long OBS_TIME = 1000L * 60 * 20;

    private static Set<Instance> getInstances(Ec2Client ec2) {
        Set<Instance> instances = new HashSet<>();
        for (Reservation reservation : ec2.describeInstances().reservations()) {
            instances.addAll(reservation.instances());
        }

        return instances;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("===========================================");
        System.out.println("Welcome to the AWS Java SDK 2!");
        System.out.println("===========================================");

        Ec2Client ec2 = Ec2Client.builder().region(AWS_REGION).credentialsProvider(EnvironmentVariableCredentialsProvider.create()).build();
        CloudWatchClient cloudWatch = CloudWatchClient.builder().region(AWS_REGION).credentialsProvider(EnvironmentVariableCredentialsProvider.create()).build();

        try {
            Set<Instance> instances = getInstances(ec2);
            System.out.println("total instances = " + instances.size());

            for (Instance instance : instances) {
                String iid = instance.instanceId();
                String state = instance.state().nameAsString();

                if (state.equals("running")) {
                    System.out.println("running instance id = " + iid);

                    List<Dimension> instanceDimension = new ArrayList<>();
                    instanceDimension.add(Dimension.builder().name("InstanceId").value(iid).build());

                    GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                            .startTime(Instant.ofEpochMilli(System.currentTimeMillis() - OBS_TIME))
                            .namespace("AWS/EC2")
                            .period(60)
                            .metricName("CPUUtilization")
                            .statistics(Statistic.AVERAGE)
                            .dimensions(instanceDimension)
                            .endTime(Instant.now())
                            .build();

                    for (Datapoint dp : cloudWatch.getMetricStatistics(request).datapoints()) {
                        System.out.println(" CPU utilization for instance " + iid + " = " + dp.average());
                    }
                } else {
                    System.out.println("instance id = " + iid);
                }

                System.out.println("Instance State : " + state + ".");
            }

        } catch (AwsServiceException ase) {
            System.out.println("Caught EC2 Exception: " + ase.getMessage());
            System.out.println("Response Status Code: " + ase.statusCode());
            System.out.println("Error Code: " + ase.awsErrorDetails().errorCode());
            System.out.println("Request ID: " + ase.requestId());
        } finally {
            cloudWatch.close();
            ec2.close();
        }
    }
}
