package com.shodanrecon.mobile;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Bounded JPEG-marker reader supporting camera multipart streams without buffering an entire stream. */
final class MjpegFrames {
    private MjpegFrames() {}
    static byte[] next(InputStream input, int limit) throws IOException {
        int previous = -1, current, searched = 0;
        while ((current = input.read()) != -1) {
            if (previous == 0xff && current == 0xd8) break;
            if (++searched > limit) throw new IOException("No JPEG frame within bounded stream input");
            previous = current;
        }
        if (current == -1) throw new EOFException("MJPEG stream ended");
        ByteArrayOutputStream frame = new ByteArrayOutputStream(65536);
        frame.write(0xff); frame.write(0xd8); previous = 0xd8;
        while ((current = input.read()) != -1) {
            if (frame.size() >= limit) throw new IOException("MJPEG frame too large");
            frame.write(current);
            if (previous == 0xff && current == 0xd9) return frame.toByteArray();
            previous = current;
        }
        throw new EOFException("Incomplete MJPEG frame");
    }
}
