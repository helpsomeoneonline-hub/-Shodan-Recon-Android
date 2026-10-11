package com.shodanrecon.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/** Images remain in app storage; an optional user-selected folder receives additional copies. */
public final class SnapshotStore {
    public static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    private final Context context;
    private final SharedPreferences prefs;
    private final File directory;

    public SnapshotStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("recon_snapshots_v1", Context.MODE_PRIVATE);
        directory = new File(this.context.getFilesDir(), "snapshots");
        directory.mkdirs();
        cleanupTemporaryFiles();
    }

    private JSONArray records() throws Exception {
        try { return new JSONArray(prefs.getString("images", "[]")); }
        catch (Exception e) { throw new Exception("Saved snapshot metadata cannot be read. Existing images were preserved."); }
    }

    private void persist(JSONArray records) throws Exception {
        if (!prefs.edit().putString("images", records.toString()).commit()) throw new Exception("Cannot save image metadata. Check free storage.");
    }

    public synchronized JSONObject entry(String id) throws Exception {
        JSONArray entries = records();
        for (int i = 0; i < entries.length(); i++) if (entries.getJSONObject(i).optString("id").equals(id)) return new JSONObject(entries.getJSONObject(i).toString());
        throw new Exception("Snapshot no longer exists.");
    }

    public synchronized File file(String id) throws Exception {
        JSONObject entry = entry(id);
        File file = resolveStoredFile(directory, entry.optString("fileName"));
        if (!file.isFile()) throw new Exception("Snapshot file is missing.");
        return file;
    }

    public synchronized long storageBytes() {
        return directoryBytes(directory);
    }

    private static long directoryBytes(File directory) {
        long bytes = 0;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (file.isFile()) bytes += file.length();
            else if (file.isDirectory()) bytes += directoryBytes(file);
        }
        return bytes;
    }

    /** Flat legacy filenames remain valid so an ordinary app update preserves gallery history. */
    static File resolveStoredFile(File directory, String name) throws Exception {
        if (name == null || !name.matches("(?:camera_[A-Za-z0-9_-]{1,80}/[0-9]{4}-[0-9]{2}-[0-9]{2}/)?snapshot_[A-Za-z0-9_-]+\\.(jpg|png|webp|gif|img)"))
            throw new Exception("Invalid stored image filename.");
        File file = new File(directory, name);
        if (!file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator))
            throw new Exception("Invalid stored image location.");
        return file;
    }

    public synchronized String storageLocation() {
        String tree = prefs.getString("tree", "");
        return tree.isEmpty() ? "App storage (private)" : "App storage + selected folder: " + tree;
    }

    public synchronized void selectStorage(Uri tree) throws Exception {
        if (tree == null || !"content".equals(tree.getScheme()) || !DocumentsContract.isTreeUri(tree))
            throw new Exception("Select a writable folder using the Android folder picker.");
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (android.database.Cursor cursor = context.getContentResolver().query(document, new String[]{DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new Exception("Selected folder is unavailable.");
            if ((cursor.getInt(0) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) throw new Exception("Selected folder is read-only. Choose another folder.");
        }
        if (!prefs.edit().putString("tree", tree.toString()).commit()) throw new Exception("Cannot save the selected folder.");
    }

    public synchronized JSONArray state() throws Exception {
        JSONArray result = new JSONArray(), entries = records();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = new JSONObject(entries.getJSONObject(i).toString());
            entry.put("status", status(entry));
            if (!entry.has("captureDate")) entry.put("captureDate", captureDate(entry));
            entry.remove("fileName"); entry.remove("treeCopy");
            entry.put("thumbnail", i < 100 && !"corrupt".equals(entry.optString("reason")) && !"empty".equals(entry.optString("reason"))
                    ? thumbnail(entries.getJSONObject(i).optString("fileName")) : "");
            result.put(entry);
        }
        return result;
    }

    /** Counts include retained invalid captures, which remain reviewable and deletable in the gallery. */
    public synchronized JSONObject counts() throws Exception {
        JSONObject result = new JSONObject().put("total", 0).put("valid", 0).put("duplicate", 0)
                .put("empty", 0).put("corrupt", 0).put("oversized", 0).put("flagged", 0);
        JSONArray entries = records();
        result.put("total", entries.length());
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String status = status(entry);
            if (result.has(status) && !status.equals("flagged")) result.put(status, result.getInt(status) + 1);
            if (!status.equals("valid")) result.put("flagged", result.getInt("flagged") + 1);
        }
        return result;
    }

    private static String status(JSONObject entry) {
        String reason = entry.optString("reason");
        return reason.isEmpty() ? "valid" : reason;
    }

    private static String captureDate(JSONObject entry) {
        try { return Instant.parse(entry.optString("capturedAt")).atZone(ZoneOffset.UTC).toLocalDate().toString(); }
        catch (Exception ignored) {
            long timestamp = entry.optLong("timestamp", 0);
            return timestamp > 0 ? Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString() : "";
        }
    }

    private String thumbnail(String name) {
        Bitmap image = null;
        try {
            File file = resolveStoredFile(directory, name);
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1 || (long) bounds.outWidth * bounds.outHeight > 100000000) return "";
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > 160 || bounds.outHeight / options.inSampleSize > 160) options.inSampleSize *= 2;
            image = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (image == null) return "";
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            image.compress(Bitmap.CompressFormat.JPEG, 65, output);
            return "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } catch (Exception | OutOfMemoryError e) { return ""; }
        finally { if (image != null) image.recycle(); }
    }

    public synchronized String thumbnailFor(String id) throws Exception {
        return thumbnail(entry(id).optString("fileName"));
    }

    /** A cancelled/failed download never adds an incomplete image to the gallery. */
    public JSONObject save(InputStream source, JSONObject camera, int channel, AtomicBoolean cancelled) throws Exception {
        return save(source, camera, channel, cancelled, bytes -> {});
    }

    public JSONObject save(InputStream source, JSONObject camera, int channel, AtomicBoolean cancelled, java.util.function.LongConsumer progress) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new Exception("Cannot create snapshot storage.");
        String id = UUID.randomUUID().toString();
        File temporary = new File(directory, "download_" + id + ".tmp");
        File destination = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            long nextProgress = 256 * 1024;
            try (OutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new Exception("Capture cancelled.");
                    size += count;
                    if (size > MAX_IMAGE_BYTES) throw new Exception("Snapshot exceeds the 20 MB download limit.");
                    output.write(buffer, 0, count); digest.update(buffer, 0, count);
                    if (size >= nextProgress) { progress.accept(size); nextProgress = size + 256 * 1024; }
                }
                output.flush();
            }
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new Exception("Capture cancelled.");
            String hash = hex(digest.digest());
            JSONObject inspection = inspect(temporary);
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new Exception("Capture cancelled.");
            String mime = inspection.getString("mimeType"), reason = inspection.getString("reason");
            String extension = mime.equals("image/png") ? "png" : mime.equals("image/webp") ? "webp" : mime.equals("image/gif") ? "gif" : mime.equals("image/jpeg") ? "jpg" : "img";
            Instant capturedAt = Instant.now();
            String captureDate = capturedAt.atZone(ZoneOffset.UTC).toLocalDate().toString();
            String cameraId = camera.getString("id");
            String storageId = cameraId.matches("[A-Za-z0-9_-]{1,80}") ? cameraId
                    : hex(MessageDigest.getInstance("SHA-256").digest(cameraId.getBytes(StandardCharsets.UTF_8)));
            String relativeName = "camera_" + storageId + "/" + captureDate + "/snapshot_" + id + "." + extension;
            destination = resolveStoredFile(directory, relativeName);
            if (!destination.getParentFile().isDirectory() && !destination.getParentFile().mkdirs())
                throw new Exception("Cannot create camera snapshot folder.");
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new Exception("Capture cancelled.");
            if (!temporary.renameTo(destination)) throw new Exception("Cannot save snapshot file.");
            JSONObject entry = new JSONObject().put("id", id).put("cameraId", cameraId)
                    .put("cameraName", camera.optString("name", camera.optString("host"))).put("ip", camera.optString("host"))
                    .put("channel", channel).put("capturedAt", capturedAt.toString()).put("timestamp", capturedAt.toEpochMilli()).put("captureDate", captureDate)
                    .put("sizeBytes", size).put("mimeType", mime).put("sha256", hash).put("fileName", relativeName)
                    .put("width", inspection.getInt("width")).put("height", inspection.getInt("height"));
            synchronized (this) {
                JSONArray previous = records();
                if (previous.length() >= 5000) throw new Exception("Gallery limit reached (5,000 images). Export or delete older snapshots.");
                for (int i = 0; i < previous.length(); i++) {
                    JSONObject old = previous.getJSONObject(i);
                    if (hash.equals(old.optString("sha256"))) {
                        entry.put("duplicateOf", old.optString("id"));
                        if (reason.isEmpty()) reason = "duplicate";
                        break;
                    }
                }
                entry.put("flagged", !reason.isEmpty()).put("reason", reason);
                entry.put("status", status(entry));
                JSONArray updated = new JSONArray().put(entry);
                for (int i = 0; i < previous.length(); i++) updated.put(previous.get(i));
                if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new Exception("Capture cancelled.");
                persist(updated);
                // Once metadata commits, preserve the image even if the optional folder copy fails.
                destination = null;
            }
            if (!cancelled.get()) copyToSelectedFolder(entry, file(id), cancelled);
            return entry;
        } finally {
            temporary.delete();
            if (destination != null) destination.delete();
        }
    }

    private void copyToSelectedFolder(JSONObject entry, File file, AtomicBoolean cancelled) {
        String treeValue = prefs.getString("tree", "");
        if (treeValue.isEmpty()) return;
        Uri created = null;
        try {
            Uri tree = Uri.parse(treeValue);
            Uri directoryUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            created = DocumentsContract.createDocument(context.getContentResolver(), directoryUri, entry.optString("mimeType", "application/octet-stream"), file.getName());
            if (created == null) throw new Exception("Folder does not permit new files.");
            try (InputStream input = new FileInputStream(file); OutputStream output = context.getContentResolver().openOutputStream(created, "w")) {
                if (output == null) throw new Exception("Folder is unavailable.");
                byte[] buffer = new byte[16384]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled.get()) throw new Exception("Capture cancelled.");
                    output.write(buffer, 0, count);
                }
            }
            entry.put("treeCopy", created.toString());
            replace(entry);
        } catch (Exception e) {
            if (created != null) try { DocumentsContract.deleteDocument(context.getContentResolver(), created); } catch (Exception ignored) {}
            try { entry.put("storageWarning", "Saved in app storage. The selected folder copy failed; select the folder again or export this image."); replace(entry); } catch (Exception ignored) {}
        }
    }

    private synchronized void replace(JSONObject replacement) throws Exception {
        JSONArray entries = records();
        for (int i = 0; i < entries.length(); i++) if (entries.getJSONObject(i).optString("id").equals(replacement.optString("id"))) entries.put(i, replacement);
        persist(entries);
    }

    public synchronized void delete(String id) throws Exception {
        JSONArray previous = records(), updated = new JSONArray();
        JSONObject target = null;
        for (int i = 0; i < previous.length(); i++) {
            JSONObject item = previous.getJSONObject(i);
            if (item.optString("id").equals(id)) target = item;
            else updated.put(item);
        }
        if (target == null) throw new Exception("Snapshot no longer exists.");
        File file = resolveStoredFile(directory, target.optString("fileName"));
        if (file.exists() && !file.delete()) throw new Exception("Snapshot could not be deleted.");
        String treeCopy = target.optString("treeCopy");
        if (!treeCopy.isEmpty()) try { DocumentsContract.deleteDocument(context.getContentResolver(), Uri.parse(treeCopy)); } catch (Exception ignored) { /* Optional exported copy may have been moved. */ }
        persist(updated);
        removeEmptyCameraFolders(file.getParentFile());
    }

    private void removeEmptyCameraFolders(File folder) {
        while (folder != null && !folder.equals(directory)) {
            File[] contents = folder.listFiles();
            if (contents == null || contents.length != 0 || !folder.delete()) return;
            folder = folder.getParentFile();
        }
    }

    public synchronized int deleteFlagged() throws Exception {
        JSONArray entries = records();
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < entries.length(); i++) if (entries.getJSONObject(i).optBoolean("flagged")) ids.add(entries.getJSONObject(i).optString("id"));
        for (String id : ids) delete(id);
        return ids.size();
    }

    public void cleanupTemporaryFiles() {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (file.getName().startsWith("download_") && file.getName().endsWith(".tmp")) file.delete();
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    /** Shared by saved captures and connection tests, so a valid header alone never means success. */
    static JSONObject inspect(File image) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
        String mime = bounds.outMimeType == null ? "application/octet-stream" : bounds.outMimeType;
        String reason = image.length() == 0 ? "empty" : bounds.outWidth < 1 || bounds.outHeight < 1 ? "corrupt" : "";
        if (reason.isEmpty() && (long) bounds.outWidth * bounds.outHeight > 100000000) reason = "oversized";
        if (reason.isEmpty() && !completeImage(image, mime)) reason = "corrupt";
        if (reason.isEmpty()) {
            BitmapFactory.Options sample = new BitmapFactory.Options(); sample.inSampleSize = 1;
            while (bounds.outWidth / sample.inSampleSize > 512 || bounds.outHeight / sample.inSampleSize > 512) sample.inSampleSize *= 2;
            try {
                Bitmap verification = BitmapFactory.decodeFile(image.getAbsolutePath(), sample);
                if (verification == null || verification.getWidth() < 1 || verification.getHeight() < 1) reason = "corrupt";
                if (verification != null) verification.recycle();
            } catch (OutOfMemoryError memory) { reason = "oversized"; }
        }
        return new JSONObject().put("mimeType", mime).put("reason", reason)
                .put("width", Math.max(0, bounds.outWidth)).put("height", Math.max(0, bounds.outHeight));
    }

    static boolean completeImage(File image, String mime) {
        try (RandomAccessFile file = new RandomAccessFile(image, "r")) {
            if (mime.equals("image/jpeg")) return completeJpeg(file);
            if (mime.equals("image/png")) return completePng(file);
            if (mime.equals("image/gif")) {
                if (file.length() < 14) return false;
                byte[] signature = new byte[6]; file.readFully(signature);
                String header = new String(signature, StandardCharsets.US_ASCII);
                file.seek(file.length() - 1);
                return (header.equals("GIF87a") || header.equals("GIF89a")) && file.readUnsignedByte() == 0x3b;
            }
            if (mime.equals("image/webp")) {
                if (file.length() < 20 || file.readInt() != 0x52494646) return false; // RIFF
                long length = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
                return file.readInt() == 0x57454250 && length + 8 == file.length(); // WEBP
            }
            return false;
        } catch (Exception e) { return false; }
    }

    private static boolean completeJpeg(RandomAccessFile file) throws Exception {
        if (file.length() < 4 || file.readUnsignedShort() != 0xffd8) return false;
        boolean frame = false, scan = false;
        int pendingMarker = -1;
        while (pendingMarker >= 0 || file.getFilePointer() < file.length()) {
            int marker;
            if (pendingMarker >= 0) { marker = pendingMarker; pendingMarker = -1; }
            else {
                if (file.readUnsignedByte() != 0xff) return false;
                do { marker = file.readUnsignedByte(); } while (marker == 0xff);
            }
            if (marker == 0xd9) return frame && scan && file.getFilePointer() == file.length();
            if (marker == 0 || marker == 0xd8 || marker >= 0xd0 && marker <= 0xd7) return false;
            if (marker == 0x01) continue; // Standalone TEM marker.
            int length = file.readUnsignedShort();
            long end = file.getFilePointer() + length - 2L;
            if (length < 2 || end > file.length()) return false;
            if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                if (length < 11) return false;
                file.readUnsignedByte(); // precision; actual supported decoding is checked by BitmapFactory.
                int height = file.readUnsignedShort(), width = file.readUnsignedShort(), components = file.readUnsignedByte();
                if (height == 0 || width == 0 || components < 1 || components > 4 || length != 8 + 3 * components) return false;
                frame = true;
            }
            if (marker == 0xda) {
                if (!frame || length < 8) return false;
                int components = file.readUnsignedByte();
                if (components < 1 || components > 4 || length != 6 + 2 * components) return false;
                scan = true;
            }
            file.seek(end);
            if (marker == 0xda) {
                // Entropy bytes may contain escaped FF bytes and restart markers. Other markers end this scan.
                boolean entropy = false;
                while (file.getFilePointer() < file.length()) {
                    if (file.readUnsignedByte() != 0xff) { entropy = true; continue; }
                    int next;
                    do { next = file.readUnsignedByte(); } while (next == 0xff);
                    if (next == 0) { entropy = true; continue; }
                    if (next >= 0xd0 && next <= 0xd7) continue;
                    pendingMarker = next;
                    break;
                }
                if (pendingMarker < 0 || !entropy) return false;
            }
        }
        return false;
    }

    private static boolean completePng(RandomAccessFile file) throws Exception {
        if (file.length() < 45 || file.readLong() != 0x89504e470d0a1a0aL) return false;
        boolean header = false, pixels = false;
        byte[] buffer = new byte[16384];
        while (file.getFilePointer() + 12 <= file.length()) {
            long length = Integer.toUnsignedLong(file.readInt());
            byte[] type = new byte[4]; file.readFully(type);
            String chunk = new String(type, StandardCharsets.US_ASCII);
            if (length > file.length() - file.getFilePointer() - 4) return false;
            if (!header && (!chunk.equals("IHDR") || length != 13)) return false;
            if (header && chunk.equals("IHDR")) return false;
            CRC32 crc = new CRC32(); crc.update(type);
            long remaining = length;
            while (remaining > 0) {
                int read = (int) Math.min(buffer.length, remaining);
                file.readFully(buffer, 0, read); crc.update(buffer, 0, read); remaining -= read;
            }
            if (crc.getValue() != Integer.toUnsignedLong(file.readInt())) return false;
            if (chunk.equals("IHDR")) header = true;
            if (chunk.equals("IDAT") && length > 0) pixels = true;
            if (chunk.equals("IEND")) return header && pixels && length == 0 && file.getFilePointer() == file.length();
        }
        return false;
    }
}
