package dev.axymorrsen.systemdownloadaccelerator;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LiveDownloadNotificationControllerTest {
    @Test
    public void compactPercentIsBoundedForStatusChip() {
        assertEquals(
                "0%",
                LiveDownloadNotificationController.compactPercent(-1.0));
        assertEquals(
                "42%",
                LiveDownloadNotificationController.compactPercent(0.42));
        assertEquals(
                "100%",
                LiveDownloadNotificationController.compactPercent(2.0));
    }
}
