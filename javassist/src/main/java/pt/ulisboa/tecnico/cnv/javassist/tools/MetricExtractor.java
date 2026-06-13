package pt.ulisboa.tecnico.cnv.javassist.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javassist.CannotCompileException;
import javassist.CtBehavior;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import pt.ulisboa.tecnico.cnv.javassist.metrics.Metrics;
import pt.ulisboa.tecnico.cnv.javassist.metrics.Workload;
import pt.ulisboa.tecnico.cnv.javassist.metrics.WorkloadMetric;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public class MetricExtractor extends CodeDumper {

    // Metric dictionaries
    private static ConcurrentHashMap<String, Long> _nBlocks;            // Number of bb's per thread
    private static ConcurrentHashMap<String, Long> _nMethods;           // Number of methods per thread
    private static ConcurrentHashMap<String, Long> _nInsts;             // Number of insts per thread
    private static ConcurrentHashMap<String, Long> _cpuTimeMs;          // Time of CPU work per thread
    private static ConcurrentHashMap<String, Long> _wallClockTimeMs;    // Thread execution time

    // CPU clock
    private static ThreadMXBean _bean;

    // DB Interaction
    private static DynamoDbClient _ddb;
    private static Region _awsRegion;
    private static Gson _gson;
    private static final String TABLE_NAME = "Workload_Metrics";
    private static final String ARGS_COLUMN_NAME = "args";
    private static final String METRICS_COLUMN_NAME = "metrics";
    private static final String COMPLEXITY_COLUMN_NAME = "C";
    private static final String VALUE_COLUMN_NAME = "V";
    private static final String WORKLOADTYPE_COLUMN_NAME = "Workload";

    public MetricExtractor(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
        // Initialize HashMaps for metric storage
        _nBlocks = new ConcurrentHashMap<>();
        _nMethods = new ConcurrentHashMap<>();
        _nInsts = new ConcurrentHashMap<>();
        _cpuTimeMs = new ConcurrentHashMap<>();
        _wallClockTimeMs = new ConcurrentHashMap<>();

        _bean = ManagementFactory.getThreadMXBean();

        _gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
        _awsRegion = Region.US_EAST_1;
        _ddb = DynamoDbClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(_awsRegion)
                .build();
    }

    // Method transform method
    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);
        behavior.insertAfter(String.format("%s.incMethods(Thread.currentThread().getName());", MetricExtractor.class.getName()));

        if (behavior.getName().equals("handleWorkload")){
            behavior.insertBefore(String.format("%s.StartTimers(Thread.currentThread().getName());", MetricExtractor.class.getName()));
            behavior.insertBefore(String.format("%s.CleanCounters(Thread.currentThread().getName());", MetricExtractor.class.getName()));
            behavior.insertAfter(String.format("%s.StopTimers(Thread.currentThread().getName());", MetricExtractor.class.getName()));
            behavior.insertAfter(String.format(
                "%s.WriteStatistics(Thread.currentThread().getName(), \"%s\", $args);", MetricExtractor.class.getName(), behavior.getDeclaringClass().getName())
            );
        }
    }

    // BasicBlock transform method
    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);
        block.behavior.insertAt(block.line, String.format("%s.incBasicBlock(Thread.currentThread().getName(), %s);", MetricExtractor.class.getName(), block.getLength()));
    }

    
    /// Write to file
    public static void WriteStatistics(String threadName, String classPath, Object[] args) {
        Workload workload;
        switch (args.length){
            case 3:
                workload = Workload.JULIA_FRACTALS;
                break;
            case 4:
                workload = Workload.DNA_ALIGNMENT;
                break;
            case 6:
                workload = Workload.GRAY_SCOTT;
                break;
            default:
                throw new IllegalArgumentException("What god forsaken workload did thou just run?!");
        }
        var item = BuildItemAttributes(threadName, args, workload);
        try{
            _ddb.putItem(
                PutItemRequest.builder().tableName(TABLE_NAME)
                        .item(item)
                        .conditionExpression("attribute_not_exists(" + WORKLOADTYPE_COLUMN_NAME + ")")
                        .build()
            );
        }catch (ConditionalCheckFailedException e){
            System.out.println("Skipped write: Metrics for these arguments already exist.");
        }catch (Exception e){
            System.err.println("AWS DynamoDB Error: " +e.getMessage());
            _ddb.close();
            throw e;
        }
    }

    public static void CleanCounters(String threadName){
        _nMethods.remove(threadName);
        _nBlocks.remove(threadName);
        _nInsts.remove(threadName);
        _cpuTimeMs.remove(threadName);
        _wallClockTimeMs.remove(threadName);
    }

    /// Increment metrics
    public static void incBasicBlock(String threadName, int length){
        _nInsts.merge(threadName, (long) length, Long::sum);
        _nBlocks.merge(threadName, 1L, Long::sum);
    }

    public static void incMethods(String threadName){
        _nMethods.merge(threadName, 1L, Long::sum);
    }

    public static void StartTimers(String threadName){
        _cpuTimeMs.put(threadName, _bean.getCurrentThreadCpuTime());
        _wallClockTimeMs.put(threadName, System.nanoTime());
    }

    public static void StopTimers(String threadName){
        // I am aware we are losing precision and truncating the value. Executions are not fast enough for this to be an issue
        var deltaTimeCPU = TimeUnit.NANOSECONDS.toMillis(_bean.getCurrentThreadCpuTime() -_cpuTimeMs.get(threadName));
        var deltaTimeWall = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() -_wallClockTimeMs.get(threadName));
        _cpuTimeMs.put(threadName, deltaTimeCPU);
        _wallClockTimeMs.put(threadName, deltaTimeWall);
    }

    private static Map<String, AttributeValue> BuildItemAttributes(String threadName, Object[] args, Workload workload) {

        String[] stringArgs = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            stringArgs[i] = args[i].toString();
        }

        var metrics = new Metrics(
                _nInsts.getOrDefault(threadName, 0L),
                _nBlocks.getOrDefault(threadName, 0L),
                _nMethods.getOrDefault(threadName, 0L),
                _cpuTimeMs.getOrDefault(threadName, 0L),
                _wallClockTimeMs.getOrDefault(threadName, 0L)
        );

        var workloadMetric = new WorkloadMetric(stringArgs, metrics, workload);

        Map<String, AttributeValue> item = new HashMap<>();

        item.put(ARGS_COLUMN_NAME, AttributeValue.builder().s(workloadMetric.getKey()).build());
        item.put(WORKLOADTYPE_COLUMN_NAME, AttributeValue.builder().s(workloadMetric.getWorkload().toString()).build());
        item.put(METRICS_COLUMN_NAME, AttributeValue.builder().s(_gson.toJson(workloadMetric.getMetrics())).build());
        item.put(COMPLEXITY_COLUMN_NAME, AttributeValue.builder().n(String.valueOf(workloadMetric.getC())).build());
        item.put(VALUE_COLUMN_NAME, AttributeValue.builder().n(String.valueOf(workloadMetric.getV())).build());

        return item;
    }
}
