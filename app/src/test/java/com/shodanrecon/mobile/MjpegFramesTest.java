package com.shodanrecon.mobile;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

public class MjpegFramesTest {
    @Test public void readsConsecutiveFramesAndSkipsMultipartHeaders() throws Exception {
        byte[] first = {(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9};
        byte[] second = {(byte) 0xff, (byte) 0xd8, 3, (byte) 0xff, (byte) 0xd9};
        java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream();
        stream.write("--frame\r\nContent-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        stream.write(first); stream.write("\r\n--frame\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); stream.write(second);
        ByteArrayInputStream input = new ByteArrayInputStream(stream.toByteArray());
        assertArrayEquals(first, MjpegFrames.next(input, 1024));
        assertArrayEquals(second, MjpegFrames.next(input, 1024));
    }
    @Test public void rejectsUnboundedPreamble() throws Exception {
        try { MjpegFrames.next(new ByteArrayInputStream(new byte[65]), 64); fail("Expected bounded preamble rejection"); }
        catch (IOException expected) {}
    }
    @Test public void rejectsOversizedImage() throws Exception {
        byte[] input = new byte[70]; input[0] = (byte) 0xff; input[1] = (byte) 0xd8;
        try { MjpegFrames.next(new ByteArrayInputStream(input), 64); fail("Expected bounded image rejection"); }
        catch (IOException expected) {}
    }
    @Test public void rejectsTruncatedImage() throws Exception {
        byte[] input = {(byte) 0xff, (byte) 0xd8, 1};
        try { MjpegFrames.next(new ByteArrayInputStream(input), 64); fail("Expected truncation rejection"); }
        catch (EOFException expected) {}
    }
}
