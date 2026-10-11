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

    @Test public void jpegEndMarkerCannotHideAnIncompleteHeaderOrMissingScan() throws Exception {
        File valid = generatedImage("jpeg");
        byte[] bytes = Files.readAllBytes(valid.toPath());
        File cut = folder.newFile("cut-with-end.jpg");
        byte[] truncated = Arrays.copyOf(bytes, 40); // Inside the quantization table segment.
        truncated[38] = (byte) 0xff; truncated[39] = (byte) 0xd9;
        Files.write(cut.toPath(), truncated);
        assertFalse(SnapshotStore.completeImage(cut, "image/jpeg"));
        File markersOnly = folder.newFile("markers-only.jpg");
        Files.write(markersOnly.toPath(), new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
        assertFalse(SnapshotStore.completeImage(markersOnly, "image/jpeg"));
        bytes[0] = 0; Files.write(valid.toPath(), bytes);
        assertFalse(SnapshotStore.completeImage(valid, "image/jpeg"));
    }

    @Test public void pngIntactEndMarkerCannotHideACorruptChunk() throws Exception {
        File valid = generatedImage("png");
        byte[] bytes = Files.readAllBytes(valid.toPath());
        bytes[50] ^= 1; // Alter compressed IDAT bytes without updating their checksum.
        Files.write(valid.toPath(), bytes);
        assertFalse(SnapshotStore.completeImage(valid, "image/png"));
    }

    @Test public void unknownContentAndExtraDataAfterEndMarkersAreRejected() throws Exception {
        assertFalse(SnapshotStore.completeImage(generatedImage("jpeg"), "application/octet-stream"));
        File valid = generatedImage("png");
        byte[] bytes = Files.readAllBytes(valid.toPath());
        Files.write(valid.toPath(), Arrays.copyOf(bytes, bytes.length + 1));
        assertFalse(SnapshotStore.completeImage(valid, "image/png"));
    }

    @Test public void gifAndWebpSupportRemainsAvailableWithTruncationDetection() throws Exception {
        String[] formats = {"gif", "webp"};
        String[] fixtures = {
                "R0lGODdhCAAIAIEAANxGWgAAAAAAAAAAACwAAAAACAAIAAAIDwABCBxIsKDBgwgTKkwYEAA7",
                "UklGRjgAAABXRUJQVlA4ICwAAACQAQCdASoIAAgAAUAmJaACdLoAA5gA/uC5evKfE23/20P/1of/rQ/7IAAAAA=="
        };
        for (int i = 0; i < formats.length; i++) {
            File image = folder.newFile("snapshot." + formats[i]);
            byte[] bytes = Base64.getDecoder().decode(fixtures[i]);
            Files.write(image.toPath(), bytes);
            assertTrue(SnapshotStore.completeImage(image, "image/" + formats[i]));
            Files.write(image.toPath(), Arrays.copyOf(bytes, bytes.length - 1));
            assertFalse(SnapshotStore.completeImage(image, "image/" + formats[i]));
        }
    }

    @Test public void resolvesLegacyAndOrganizedGalleryPathsWithoutMovingExistingImages() throws Exception {
        File root = folder.newFolder("snapshots");
        File legacy = new File(root, "snapshot_existing.jpg");
        assertEquals(legacy, SnapshotStore.resolveStoredFile(root, "snapshot_existing.jpg"));
        File organized = new File(root, "camera_registered-1/2026-10-11/snapshot_new.jpg");
        assertEquals(organized, SnapshotStore.resolveStoredFile(root, "camera_registered-1/2026-10-11/snapshot_new.jpg"));
    }

    @Test public void rejectsStoredPathsThatCouldEscapeThePrivateGallery() throws Exception {
        File root = folder.newFolder("snapshots");
        for (String name : new String[]{"../snapshot_secret.jpg", "/snapshot_secret.jpg", "camera_x/../../snapshot_secret.jpg", "camera_x/2026-10-11/../snapshot_secret.jpg"}) {
            try { SnapshotStore.resolveStoredFile(root, name); fail("Unsafe stored path accepted"); }
            catch (Exception expected) { assertFalse(expected.getMessage().isEmpty()); }
        }
        File outside = folder.newFolder("outside");
        Files.createSymbolicLink(new File(root, "camera_link").toPath(), outside.toPath());
        try { SnapshotStore.resolveStoredFile(root, "camera_link/2026-10-11/snapshot_secret.jpg"); fail("Symlink escaped gallery"); }
        catch (Exception expected) { assertFalse(expected.getMessage().isEmpty()); }
    }
}
