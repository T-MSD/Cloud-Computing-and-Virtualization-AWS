package pt.ulisboa.tecnico.cnv;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

public class EC2LaunchWaitTerminate {

    // TODO - fill fields with correct values.
    private static final Region AWS_REGION = Region.EU_NORTH_1;
    private static final String AMI_ID = "ami-02064d903b8066432"; // TODO: replace with your current AMI ID (EC2 -> AMIs)
    private static final String INSTANCE_TYPE = "t3.micro";
    private static final String KEY_NAME = "mykeypair";
    private static final String SEC_GROUP_ID = "sg-046bb567335e1c0b1"; // TODO: replace with your current SG ID (EC2 -> Security Groups)

    // Time to wait until the instance is terminated (in milliseconds).
    private static final long WAIT_TIME = 1000L * 60 * 10;

    public static void main(String[] args) throws Exception {
        System.out.println("===========================================");
        System.out.println("Welcome to the AWS Java SDK 2!");
        System.out.println("===========================================");

        /*
         * Amazon EC2
         *
         * The AWS EC2 client allows you to create, delete, and administer
         * instances programmatically.
         *
         * In this sample, we use an EC2 client to get a list of all the
         * availability zones, and all instances sorted by reservation id, then
         * create an instance, list existing instances again, wait a minute and
         * the terminate the started instance.
         */
        Ec2Client ec2 = Ec2Client.builder()
            .region(AWS_REGION)
            .credentialsProvider(
                EnvironmentVariableCredentialsProvider.create()
            )
            .build();

        try {
            System.out.println(
                "You have access to " +
                    ec2.describeAvailabilityZones().availabilityZones().size() +
                    " Availability Zones."
            );
            System.out.println(
                "You have " +
                    ec2.describeInstances().reservations().size() +
                    " Amazon EC2 instance(s)."
            );

            System.out.println("Starting a new instance.");
            RunInstancesRequest runInstancesRequest =
                RunInstancesRequest.builder()
                    .imageId(AMI_ID)
                    .instanceType(INSTANCE_TYPE)
                    .minCount(1)
                    .maxCount(1)
                    .keyName(KEY_NAME)
                    .securityGroupIds(SEC_GROUP_ID)
                    .build();

            RunInstancesResponse runInstancesResponse = ec2.runInstances(
                runInstancesRequest
            );
            String newInstanceId = runInstancesResponse
                .instances()
                .get(0)
                .instanceId();

            System.out.println(
                "You now have " +
                    ec2.describeInstances().reservations().size() +
                    " Amazon EC2 instance(s)."
            );

            System.out.println(
                "Waiting 10 minutes. See your instance in the AWS console..."
            );
            Thread.sleep(WAIT_TIME);

            System.out.println("Terminating the instance.");
            TerminateInstancesRequest terminateRequest =
                TerminateInstancesRequest.builder()
                    .instanceIds(newInstanceId)
                    .build();
            ec2.terminateInstances(terminateRequest);
        } catch (AwsServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Response Status Code: " + ase.statusCode());
            System.out.println(
                "Error Code: " + ase.awsErrorDetails().errorCode()
            );
            System.out.println("Request ID: " + ase.requestId());
        } finally {
            ec2.close();
        }
    }
}
