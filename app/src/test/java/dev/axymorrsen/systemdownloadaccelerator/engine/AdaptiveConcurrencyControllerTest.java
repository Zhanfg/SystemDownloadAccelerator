package dev.axymorrsen.systemdownloadaccelerator.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AdaptiveConcurrencyControllerTest {
    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    public void rampsAfterTwoHealthyBaselineSamples() {
        AdaptiveConcurrencyController controller =
                new AdaptiveConcurrencyController(2, 16);

        assertEquals(
                AdaptiveConcurrencyController.Action.WARMUP,
                controller.sample(8.0 * 1024 * 1024, 8L * GIB).action);

        AdaptiveConcurrencyController.Decision second =
                controller.sample(8.2 * 1024 * 1024, 8L * GIB);

        assertEquals(
                AdaptiveConcurrencyController.Action.RAMP,
                second.action);
        assertEquals(4, second.workers);
    }

    @Test
    public void stopsRampingWhenMarginalGainIsPoor() {
        AdaptiveConcurrencyController controller =
                new AdaptiveConcurrencyController(2, 16);

        controller.sample(8.0 * 1024 * 1024, 8L * GIB);
        controller.sample(8.0 * 1024 * 1024, 8L * GIB);

        assertEquals(4, controller.workers());

        controller.sample(8.1 * 1024 * 1024, 8L * GIB);
        AdaptiveConcurrencyController.Decision evaluation =
                controller.sample(8.2 * 1024 * 1024, 8L * GIB);

        assertEquals(
                AdaptiveConcurrencyController.Action.HOLD,
                evaluation.action);
        assertEquals(4, controller.workers());

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
        assertEquals(4, controller.workers());

        controller.sample(7.0 * 1024 * 1024, 8L * GIB);
        AdaptiveConcurrencyController.Decision decision =
                controller.sample(8.0 * 1024 * 1024, 8L * GIB);

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
        AdaptiveConcurrencyController.Decision decision =
                controller.sample(20.0 * 1024 * 1024, 8L * 1024L * 1024L);

        assertEquals(
                AdaptiveConcurrencyController.Action.HOLD,
                decision.action);
        assertEquals(4, controller.workers());
    }
}
