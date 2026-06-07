package io.github.jerryt92.j2agent.service.file.oss.reconcile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectUploadReconcileDelayCalculatorTest {
    @Test
    void shouldCalculateExponentialBackoffWithCap() {
        assertEquals(10, ObjectUploadReconcileDelayCalculator.delaySeconds(1, 10, 300));
        assertEquals(20, ObjectUploadReconcileDelayCalculator.delaySeconds(2, 10, 300));
        assertEquals(40, ObjectUploadReconcileDelayCalculator.delaySeconds(3, 10, 300));
        assertEquals(80, ObjectUploadReconcileDelayCalculator.delaySeconds(4, 10, 300));
        assertEquals(300, ObjectUploadReconcileDelayCalculator.delaySeconds(10, 10, 300));
        assertEquals(300, ObjectUploadReconcileDelayCalculator.delaySeconds(20, 10, 300));
    }

    @Test
    void shouldRejectInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () ->
                ObjectUploadReconcileDelayCalculator.delaySeconds(0, 10, 300));
        assertThrows(IllegalArgumentException.class, () ->
                ObjectUploadReconcileDelayCalculator.delaySeconds(1, 0, 300));
        assertThrows(IllegalArgumentException.class, () ->
                ObjectUploadReconcileDelayCalculator.delaySeconds(1, 10, 5));
    }
}