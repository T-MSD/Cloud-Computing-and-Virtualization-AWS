package pt.ulisboa.tecnico.cnv.loadbalancer;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Wraps the AWS Lambda SDK. Synchronously invokes a named function and
 * returns its raw response payload as a UTF-8 string.
 *
 * The function name is chosen per request by LoadBalancerServer (one Lambda
 * per workload), so it is passed to invoke() rather than held here.
 *
 * Payload format the LB sends (the parsed query params for the workload):
 *   { "w": "800", "h": "600", "iterations": "100" }
 *
 * Response: the per-workload handlers return a plain String. If it parses as
 * {"statusCode":..,"body":".."} the LB uses those; otherwise it treats the
 * whole response as the body with HTTP 200.
 */
public class LambdaInvoker {

    private final LambdaClient lambda;

    public LambdaInvoker() {
        this.lambda = LambdaClient.builder()
                .region(Config.region())
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .build();
    }

    /**
     * Synchronous invocation of the named function. Returns the function's
     * response payload as a UTF-8 string (typically JSON). Throws if AWS
     * rejects the call or the function reports an error.
     */
    public String invoke(String functionName, String jsonPayload) {
        InvokeRequest req = InvokeRequest.builder()
                .functionName(functionName)
                .invocationType(InvocationType.REQUEST_RESPONSE)
                .payload(SdkBytes.fromUtf8String(jsonPayload))
                .build();
        InvokeResponse resp = lambda.invoke(req);
        if (resp.functionError() != null) {
            throw new RuntimeException("Lambda function error: " + resp.functionError() +
                    " — payload: " + resp.payload().asUtf8String());
        }
        return resp.payload().asUtf8String();
    }

    public void close() { lambda.close(); }
}
