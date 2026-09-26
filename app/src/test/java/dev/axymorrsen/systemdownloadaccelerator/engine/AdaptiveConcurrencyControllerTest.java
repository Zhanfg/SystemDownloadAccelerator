package dev.axymorrsen.systemdownloadaccelerator.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AdaptiveConcurrencyControllerTest {
    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    public void rampsAfterThreeHealthyBaselineSamples() {
        AdaptiveConcurrencyController controller =
                new AdaptiveConcurrencyController(2, 16);

        assertEquals(
                AdaptiveConcurrencyController.Action.WARMUP,
                controller.sample(8.0 * 1024 * 1024, 8L * GIB).action);
        assertEquals(
                AdaptiveConcurrencyController.Action.WARMUP,
                controller.sample(8.1 * 1024 * 1024, 8L * GIB).action);

        AdaptiveConcurrencyController.Decision third =
                controller.sample(8.2 * 1024 * 1024, 8L * GIB);

        assertEquals(
                AdaptiveConcurrencyController.Action.RAMP,
                third.action);
        assertEquals(4, third.workers);
    }

    @Test
    public void startupZeroDrainDoesNotDisableLaterRamp() {
        AdaptiveConcurrencyController controller =
                new AdaptiveConcurrencyController(4, 16);

        assertEquals(
                AdaptiveConcurrencyController.Action.WARMUP,
                controller.sample(0.0, 8L * GIB).action);
        assertEquals(
                AdaptiveConcurrencyController.Action.WARMUP,
                controller.sample(0.0, 8L * GIB).action);
        assertEquals(
                AdaptiveConcurrencyController.Action.WARMUP,
                controller.sample(32.0 * 1024, 8L * GIB).action);

        controller.sample(16.0 * 1024 * 1024, 8L * GIB);
        controller.sample(17.0 * 1024 * 1024, 8L * GIB);
        AdaptiveConcurrencyController.Decision ready =
                controller.sample(18.0 * 1024 * 1024, 8L * GIB);

        assertEquals(
                AdaptiveConcurrencyController.Action.RAMP,
                ready.action);
        assertEquals(8, ready.workers);
    }

    @Test
    public void transientLowPerWorkerSamplesDoNotPermanentlyStopController() {
        AdaptiveConcurrencyController controller =
                new AdaptiveConcurrencyController(4, 16);

        assertEquals(
                AdaptiveConcurrencyController.Action.WARMUP,
                controller.sample(200.0 * 1024, 8L * GIB).action);
        assertEquals(
                AdaptiveConcurrencyController.Action.WARMUP,
                controller.sample(220.0 * 1024, 8L * GIB).action);
        assertEquals(
                AdaptiveConcurrencyController.Action.HOLD,
                controller.sample(240.0 * 1024, 8L * GIB).action);

        controller.sample(16.0 * 1024 * 1024, 8L * GIB);
        controller.sample(17.0 * 1024 * 1024, 8L * GIB);
        AdaptiveConcurrencyController.Decision recovered =
                controller.sample(18.0 * 1024 * 1024, 8L * GIB);

        assertEquals(
                AdaptiveConcurrencyController.Action.RAMP,
                recovered.action);
        assertEquals(8, recovered.workers);
    }

    @Test
    public void rollsBackOnlyAfterThreePostRampSamples() {
        AdaptiveConcurrencyController controller =
                new AdaptiveConcurrencyController(2, 16);

        controller.sample(8.0 * 1024 * 1024, 8L * GIB);
        controller.sample(8.0 * 1024 * 1024, 8L * GIB);
        controller.sample(8.0 * 1024 * 1024, 8L * GIB);
        assertEquals(4, controller.workers());

        assertEquals(
                AdaptiveConcurrencyController.Action.WARMUP,
                controller.sample(8.1 * 1024 * 1024, 8L * GIB).action);
        assertEquals(
                AdaptiveConcurrencyController.Action.WARMUP,
                controller.sample(8.2 * 1024 * 1024, 8L * GIB).action);

        AdaptiveConcurrencyController.Decision evaluation =
                controller.sample(8.2 * 1024 * 1024, 8L * GIB);

        assertEquals(
                AdaptiveConcurrencyController.Action.ROLLBACK,
                evaluation.action);
        assertEquals(2, controller.workers());

        assertEquals(
                AdaptiveConcurrencyController.Action.HOLD,
                controller.sample(20.0 * 1024 * 1024, 8L * GIB).action);
    }

    @Test
    public void continuesRampingWhenGainIsStrong() {
        AdaptiveConcurrencyController controller =
                new AdaptiveConcurrencyController(2, 16);

        controller.sample(4.0 * 1024 * 1024, 8L * GIB);
        controller.sample(4.0 * 1024 * 1024, 8L * GIB);
        controller.sample(4.0 * 1024 * 1024, 8L * GIB);
        assertEquals(4, controller.workers());

        controller.sample(7.0 * 1024 * 1024, 8L * GIB);
        controller.sample(8.0 * 1024 * 1024, 8L * GIB);
        AdaptiveConcurrencyController.Decision decision =
                controller.sample(8.5 * 1024 * 1024, 8L * GIB);

        assertEquals(
                AdaptiveConcurrencyController.Action.RAMP,
                decision.action);
        assertEquals(8, decision.workers);
    }

    @Test
    public void remainingBytesCanStopFurtherRamp() {
        AdaptiveConcurrencyController controller =
                new AdaptiveConcurrencyController(4, 64);

        controller.sample(20.0 * 1024 * 1024, 8L * 1024L * 1024L);
        controller.sample(20.0 * 1024 * 1024, 8L * 1024L * 1024L);
        AdaptiveConcurrencyController.Decision decision =
                controller.sample(20.0 * 1024 * 1024, 8L * 1024L * 1024L);

        assertEquals(
                AdaptiveConcurrencyController.Action.HOLD,
                decision.action);
        assertEquals(4, controller.workers());
    }
}
