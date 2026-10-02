package dev.axymorrsen.systemdownloadaccelerator;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DownloadProgressControllerTest {
    @Test
    public void percentIsExplicitAndBounded() {
        assertEquals(
                "0.0%",
                ProgressFormat.percent(-1.0));
        assertEquals(
                "50.0%",
                ProgressFormat.percent(0.5));
        assertEquals(
                "100.0%",
                ProgressFormat.percent(2.0));
    }

    @Test
    public void bytesUseBinaryUnits() {
        assertEquals(
                "0 B",
                ProgressFormat.bytes(0L));
        assertEquals(
                "1.0 KiB",
                ProgressFormat.bytes(1024L));
        assertEquals(
                "1.0 MiB",
                ProgressFormat.bytes(
                        1024L * 1024L));
    }
}
