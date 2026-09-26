package dev.axymorrsen.systemdownloadaccelerator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class ContentRangeTest {
    @Test
    public void parsesStandardHeader() {
        ContentRange range = ContentRange.parse("bytes 0-255/16777216");
        assertEquals(0L, range.from);
        assertEquals(255L, range.to);
        assertEquals(16777216L, range.total);
    }

    @Test
    public void acceptsServerWithoutBytesPrefix() {
        ContentRange range = ContentRange.parse("1024-2047/4096");
        assertEquals(1024L, range.from);
        assertEquals(2047L, range.to);
        assertEquals(4096L, range.total);
    }

    @Test
    public void rejectsUnknownOrBrokenRange() {
        assertNull(ContentRange.parse("bytes */4096"));
        assertNull(ContentRange.parse("bytes 0-4096/4096"));
        assertNull(ContentRange.parse("garbage"));
    }
}
