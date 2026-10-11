package de.agentcodi.performance;

public final class PerformanceMain {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("Expected suite, warmups and samples");
        }
        int warmups = Integer.parseInt(arguments[1]);
        int samples = Integer.parseInt(arguments[2]);
        if (warmups < 1 || samples < 1) {
            throw new IllegalArgumentException("Warmups and samples must be positive");
        }
        switch (arguments[0]) {
            case "terminal": TerminalBenchmarks.run(warmups, samples); break;
            case "chat": ChatBenchmarks.run(warmups, samples); break;
            case "protocol": ProtocolBenchmarks.run(warmups, samples); break;
            default: throw new IllegalArgumentException("Unknown performance suite");
        }
    }

    private PerformanceMain() { }
}
