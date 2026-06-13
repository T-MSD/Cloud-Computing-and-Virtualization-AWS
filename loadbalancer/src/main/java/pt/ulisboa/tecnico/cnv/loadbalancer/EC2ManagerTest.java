package pt.ulisboa.tecnico.cnv.loadbalancer;

/**
 * Manual smoke test for EC2Manager.
 *
 * Launches one worker, waits for it to be running, prints its public DNS,
 * sleeps 30 seconds so you can verify it in the AWS Console, then terminates it.
 *
 * Run with:
 *   mvn -pl loadbalancer exec:java \
 *       -Dexec.mainClass=pt.ulisboa.tecnico.cnv.loadbalancer.EC2ManagerTest
 *
 * Will actually spend ~30 seconds of EC2 time (cents, not dollars).
 */
public class EC2ManagerTest {

    public static void main(String[] args) throws Exception {
        EC2Manager manager = new EC2Manager();
        String instanceId = null;
        try {
            System.out.println("Listing running instances before launch:");
            manager.listRunningInstances().forEach(i ->
                    System.out.println("  " + i.instanceId() + " @ " + i.publicIpAddress()));

            System.out.println("Launching a new worker...");
            instanceId = manager.launchWorker();
            System.out.println("  launched: " + instanceId);

            System.out.println("Waiting until running (this takes 30-90s)...");
            String dns = manager.waitUntilRunning(instanceId);
            System.out.println("  running, DNS: " + dns);
            System.out.println("  public IP:   " + manager.getPublicIp(instanceId));

            System.out.println("Sleeping 30s — go look at EC2 -> Instances in the AWS Console.");
            Thread.sleep(30_000);
        } finally {
            if (instanceId != null) {
                System.out.println("Terminating " + instanceId);
                manager.terminate(instanceId);
            }
            manager.close();
            System.out.println("Done.");
        }
    }
}
