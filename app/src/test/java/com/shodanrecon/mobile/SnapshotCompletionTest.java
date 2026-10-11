package com.shodanrecon.mobile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.*;

/** JVM checks for transfer completion. Android bitmap decoding remains device validation. */
public class SnapshotCompletionTest {
    // Generated with JDK ImageIO from an 8x8 RGB test pattern; no camera data.
    private static final String JPEG_FIXTURE = "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAIAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDK0PwR9391+lFFFeDisxxHtHqdGR5rivqkfeP/2Q==";
    private static final String PNG_FIXTURE = "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAbElEQVR4Xg3JQREAQAgDMZzgpEZ2cIKTOsFJ3dzlm6qiCxVTbOHiihRVTTdqptnGzTXpH6KFxIgVFieiH0MPGmbYwcMNmR9LL1pm2cXLLdkfpo3MmDU2Z+IfRx865tjDxx25H6GDwoQNDhcSHqdfTQGqIR25AAAAAElFTkSuQmCC";
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private File generatedImage(String format) throws Exception {
        File file = folder.newFile("snapshot." + format);
        Files.write(file.toPath(), Base64.getDecoder().decode(format.equals("jpeg") ? JPEG_FIXTURE : PNG_FIXTURE));
        return file;
    }

    @Test public void completeJpegAndPngHaveTheirCompletionMarkers() throws Exception {
        assertTrue(SnapshotStore.completeImage(generatedImage("jpeg"), "image/jpeg"));
        assertTrue(SnapshotStore.completeImage(generatedImage("png"), "image/png"));
    }

    @Test public void truncatedJpegAndPngAreRejectedEvenWithIntactHeaders() throws Exception {
        for (String format : new String[]{"jpeg", "png"}) {
            File valid = generatedImage(format);
            byte[] bytes = Files.readAllBytes(valid.toPath());
            File truncated = folder.newFile("truncated." + format);
            Files.write(truncated.toPath(), Arrays.copyOf(bytes, bytes.length - (format.equals("jpeg") ? 2 : 12)));
            assertFalse(SnapshotStore.completeImage(truncated, "image/" + format));
        }
    }

    @Test public void emptyTinyAndMissingFilesCannotPassCompletionChecks() throws Exception {
        File empty = folder.newFile("empty.jpg");
        File tiny = folder.newFile("tiny.png"); Files.write(tiny.toPath(), new byte[]{1, 2, 3});
        assertFalse(SnapshotStore.completeImage(empty, "image/jpeg"));
        assertFalse(SnapshotStore.completeImage(tiny, "image/png"));
        assertFalse(SnapshotStore.completeImage(new File(folder.getRoot(), "missing.jpg"), "image/jpeg"));
    }
}
