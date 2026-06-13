package pt.ulisboa.tecnico.cnv.loadbalancer.cache;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

public class DynamoDBMetricRepository {

    private final float[][] buckets;
    private final int itemsPerBucket;
    private final String tableName;
    private final String partitionKey;
    private final DynamoDbClient dynamoDbClient;


    public DynamoDBMetricRepository(DynamoDbClient ddb, String tableName, String partitionKey){
        dynamoDbClient = ddb;
        this.partitionKey = partitionKey;
        this.tableName = tableName;
        // 5 buckets -> From 0.58 to 0.98
        buckets = new float[][]{
                {0.20F, 0.40F},
                {0.40F, 0.55F},
                {0.55F, 0.74F},
                {0.74F, 0.80F},
                {0.80F, 0.99F}
        };
        itemsPerBucket = 6; // 5 buckets * 6 items = 30 items total
    }

    public Map<Long, Float> performEvenDistributionQuery(Workload workload) {
        // 1. Changed return container to a Map where Key is V (Long) and Value is C (Float)
        Map<Long, Float> combinedResults = new HashMap<>();

        for (float[] bucket : buckets) {
            String lower = String.valueOf(bucket[0]);
            String upper = String.valueOf(bucket[1]);

            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(tableName)
                    .keyConditionExpression("#pk = :pkVal AND #c BETWEEN :low AND :high")
                    .projectionExpression("#c, #v")
                    .expressionAttributeNames(Map.of(
                            "#pk", partitionKey,
                            "#c", "C",
                            "#v", "V"
                    ))
                    .expressionAttributeValues(Map.of(
                            ":pkVal", AttributeValue.builder().s(workload.toString()).build(),
                            ":low", AttributeValue.builder().n(lower).build(),
                            ":high", AttributeValue.builder().n(upper).build()
                    ))
                    .limit(itemsPerBucket)
                    .build();

            QueryResponse response = dynamoDbClient.query(queryRequest);
            for (Map<String, AttributeValue> item : response.items()) {
                Float cVal = item.containsKey("C") ? Float.parseFloat(item.get("C").n()) : null;
                Long vVal = item.containsKey("V") ? Long.parseLong(item.get("V").n()) : null;

                if (cVal == null || vVal == null) {
                    throw new IllegalStateException("Either C or V retrieved from DB are null");
                }

                System.out.println("Query returned V = " + vVal + " and C = " + cVal);
                // 2. Put V as the key and C as the value into the map
                combinedResults.put(vVal, cVal);
            }
        }

        return combinedResults;
    }
}
