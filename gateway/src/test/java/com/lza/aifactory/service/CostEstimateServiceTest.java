package com.lza.aifactory.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostEstimateServiceTest {

    private final CostEstimateService svc = new CostEstimateService();

    @Test
    void rangeIsOrderedAndPositive() {
        var r = svc.estimate(3, 1500);
        assertTrue(r.lowTokens() > 0);
        assertTrue(r.highTokens() >= r.lowTokens());
    }

    @Test
    void moreAgentsEstimateHigher() {
        var one = svc.estimate(1, 1000);
        var five = svc.estimate(5, 1000);
        assertTrue(five.lowTokens() > one.lowTokens());
        assertTrue(five.highTokens() > one.highTokens());
    }

    @Test
    void longerPlanEstimatesHigher() {
        var small = svc.estimate(3, 200);
        var big = svc.estimate(3, 20_000);
        assertTrue(big.lowTokens() > small.lowTokens());
        assertTrue(big.highTokens() > small.highTokens());
    }

    @Test
    void agentCountIsClampedToPipelineRange() {
        // Mirrors run-task.sh's clamp: <1 -> 1, >10 -> 10. The estimate must not
        // promise a cheaper/dearer run than the pipeline will actually do.
        assertEquals(svc.estimate(1, 1000), svc.estimate(0, 1000));
        assertEquals(svc.estimate(1, 1000), svc.estimate(-7, 1000));
        assertEquals(svc.estimate(10, 1000), svc.estimate(99, 1000));
    }

    @Test
    void negativePlanLengthTreatedAsEmpty() {
        assertEquals(svc.estimate(3, 0), svc.estimate(3, -50));
    }

    @Test
    void wanUnitsNeverUnderstateTheRange() {
        var r = svc.estimate(1, 0);
        assertTrue(r.lowWan() >= 1);
        assertTrue(r.highWan() >= r.lowWan());
        // ceil on the high side: 10_001 tokens must display as 2 萬, not 1.
        var tiny = new CostEstimateService.TokenRange(10_000, 10_001);
        assertEquals(1, tiny.lowWan());
        assertEquals(2, tiny.highWan());
    }
}
