package pt.ulisboa.tecnico.cnv.javassist.metrics;

public class WorkloadMetric {

    private float C; // Complexity
    private long V; // Value from metrics
    private String[] args;
    private Metrics metrics;
    private Workload workload;


    public WorkloadMetric(String[] args, Metrics metrics, Workload workload) {
        this.metrics = metrics;
        this.args = args;
        this.workload = workload;
        setC();
        setV();
    }

    // v_f = W * H * I
    // v_GS = S^2 * I_max
    // v_DNA = len(Sequence 1) * len(Sequence 2)
    private void setV(){
        switch (workload){
            case JULIA_FRACTALS:
                V = Long.parseLong(args[0]) * Long.parseLong(args[1]) * Long.parseLong(args[2]);
                break;
            case GRAY_SCOTT:
                var s = Long.parseLong(args[0]);
                V = s*s * Long.parseLong(args[1]);
                break;
            case DNA_ALIGNMENT:
                int seq1Len = args[0].substring(args[0].lastIndexOf('\n') + 1).length();
                int seq2Len = args[1].substring(args[1].lastIndexOf('\n') + 1).length();
                V = (long) seq1Len * seq2Len;
            break;
            default:
                throw new IllegalArgumentException("Unknown workload: " + workload);
        }
    }

    // C = i * 0.5 + bb * 0.35 + m * 0.15
    private void setC() {
        var realC = (long)
            (metrics.getInstructionCount() * 0.5f
                + metrics.getBlockCount() * 0.35f
                + metrics.getMethodCount() * 0.15f);
        switch (workload) {
            case JULIA_FRACTALS:
                C = calculateDirectRuleOfThree(1104710656, 0.8F, realC);
                break;
            case GRAY_SCOTT:
                C = calculateDirectRuleOfThree(938766144, 0.8F, realC);
                break;
            case DNA_ALIGNMENT:
                C = calculateDirectRuleOfThree(200000, 0.8F, realC);
                break;
            default:
                throw new IllegalArgumentException("Unknown workload: " + workload);
        }
    }

    /**
     * Calculates the unknown value 'x' in a direct proportion.
     * Expression: A -> B, as C -> x  (x = (B * C) / A)
     * * @param a The primary known independent variable (cannot be 0)
     * @param b The primary known dependent variable
     * @param c The secondary known independent variable
     * @return The calculated unknown value x
     * @throws IllegalArgumentException if 'a' is zero to prevent division by zero
     */
    private float calculateDirectRuleOfThree(long a, float b, long c) {
        if (a == 0) {
            throw new IllegalArgumentException("Argument 'a' cannot be zero as it results in division by zero.");
        }
        return Math.min((b * c) / a, 1.0f);
    }



    // ===== Getters =====
    public String getKey(){
        return String.join(",", args);
    }

    public String[] getArgs() {
        return args;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public long getV() {
        return V;
    }

    public float getC() {
        return C;
    }

    public Workload getWorkload() {
        return workload;
    }
}
