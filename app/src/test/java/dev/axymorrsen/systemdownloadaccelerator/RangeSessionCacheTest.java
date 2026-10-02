package dev.axymorrsen.systemdownloadaccelerator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.axymorrsen.systemdownloadaccelerator.core.RangePart;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.Collections;

import org.junit.Test;

public final class RangeSessionCacheTest {
    @Test
    public void exactPartSurvivesSessionRestart() throws Exception {
        File root = Files.createTempDirectory("range-cache").toFile();
        URL url = new URL("https://example.com/file.bin");

        RangeSessionCache.Session first =
                RangeSessionCache.open(
                        root,
                        url,
                        "\"etag-a\"",
                        100L,
                        1000,
                        Collections.emptyMap());
        RangeSessionCache.PartBacking part =
                first.backingFor(new RangePart(0, 0L, 49L));
        writeBytes(part.file, 50);
        first.close(false);

        RangeSessionCache.Session second =
                RangeSessionCache.open(
                        root,
                        url,
                        "\"etag-a\"",
                        100L,
                        1000,
                        Collections.emptyMap());
        RangeSessionCache.PartBacking restored =
                second.backingFor(new RangePart(0, 0L, 49L));

        assertEquals(50L, restored.reusableBytes);
        assertEquals(50L, restored.file.length());
        second.close(true);
    }

    @Test
    public void resumedHeadReusesOnlyContiguousSuffix() throws Exception {
        File root = Files.createTempDirectory("range-cache-head").toFile();
        URL url = new URL("https://example.com/file.bin");

        RangeSessionCache.Session first =
                RangeSessionCache.open(
                        root,
                        url,
                        "\"etag-b\"",
                        100L,
                        1000,
                        Collections.emptyMap());
        RangeSessionCache.PartBacking oldHead =
                first.backingFor(new RangePart(0, 0L, 49L));
        writeBytes(oldHead.file, 40);
        first.close(false);

        RangeSessionCache.Session second =
                RangeSessionCache.open(
                        root,
                        url,
                        "\"etag-b\"",
                        100L,
                        1000,
                        Collections.emptyMap());
        RangeSessionCache.PartBacking resumed =
                second.backingFor(new RangePart(0, 20L, 49L));

        assertEquals(20L, resumed.reusableBytes);
        assertEquals(20L, resumed.file.length());
        assertTrue(resumed.file.getName().startsWith("r_20_49"));
        second.close(true);
    }

    @Test
    public void differentValidatorDoesNotReuseBytes() throws Exception {
        File root = Files.createTempDirectory("range-cache-etag").toFile();
        URL url = new URL("https://example.com/file.bin");

        RangeSessionCache.Session first =
                RangeSessionCache.open(
                        root,
                        url,
                        "\"etag-old\"",
                        100L,
                        1000,
                        Collections.emptyMap());
        RangeSessionCache.PartBacking part =
                first.backingFor(new RangePart(0, 0L, 49L));
        writeBytes(part.file, 50);
        first.close(false);

        RangeSessionCache.Session changed =
                RangeSessionCache.open(
                        root,
                        url,
                        "\"etag-new\"",
                        100L,
                        1000,
                        Collections.emptyMap());
        RangeSessionCache.PartBacking fresh =
                changed.backingFor(new RangePart(0, 0L, 49L));

        assertEquals(0L, fresh.reusableBytes);
        assertFalse(fresh.file.exists());
        changed.close(true);
    }

    private static void writeBytes(File file, int count)
            throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] bytes = new byte[count];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) i;
            }
            out.write(bytes);
            out.getFD().sync();
        }
    }
}
