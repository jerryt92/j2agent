package io.github.jerryt92.j2agent.service.file.oss.reconcile;

public final class ObjectUploadReconcileDelayCalculator {
    private ObjectUploadReconcileDelayCalculator() {
    }

    public static int delaySeconds(int attempt, int initialDelaySeconds, int maxDelaySeconds) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        if (initialDelaySeconds < 1) {
            throw new IllegalArgumentException("initialDelaySeconds must be >= 1");
        }
        if (maxDelaySeconds < initialDelaySeconds) {
            throw new IllegalArgumentException("maxDelaySeconds must be >= initialDelaySeconds");
        }
        long scaled = (long) initialDelaySeconds * (1L << (attempt - 1));
        return (int) Math.min(scaled, maxDelaySeconds);
    }
}
