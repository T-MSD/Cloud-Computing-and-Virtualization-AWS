package pt.ulisboa.tecnico.cnv.javassist.metrics;

public class Metrics {

    private long instructionCount;
    private long blockCount;
    private long methodCount;
    private long cpuTimeMs;
    private long wallClockTimeMs;

    public Metrics(long instructionCount
    , long blockCount
    , long methodCount
    , long cpuTimeMs
    , long wallClockTimeMs) {
        this.instructionCount = instructionCount;
        this.blockCount = blockCount;
        this.methodCount = methodCount;
        this.cpuTimeMs = cpuTimeMs;
        this.wallClockTimeMs = wallClockTimeMs;
    }

    // ===== Getters =====
    public long getInstructionCount() {
        return instructionCount;
    }

    public long getBlockCount() {
        return blockCount;
    }

    public long getMethodCount() {
        return methodCount;
    }

    public long getCpuTimeMs() {
        return cpuTimeMs;
    }

    public long getWallClockTimeMs() {
        return wallClockTimeMs;
    }
}
