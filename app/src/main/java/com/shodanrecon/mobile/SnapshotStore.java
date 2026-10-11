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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
        String name = entry.optString("fileName");
        if (!name.matches("snapshot_[A-Za-z0-9_-]+\\.(jpg|png|webp|gif|img)")) throw new Exception("Invalid stored image filename.");
        File file = new File(directory, name);
        if (!file.isFile()) throw new Exception("Snapshot file is missing.");
        return file;
    }

    public synchronized long storageBytes() {
        long bytes = 0;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) bytes += file.length();
        return bytes;
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
            entry.remove("fileName"); entry.remove("treeCopy");
            entry.put("thumbnail", i < 100 && !"corrupt".equals(entry.optString("reason")) && !"empty".equals(entry.optString("reason"))
                    ? thumbnail(entries.getJSONObject(i).optString("fileName")) : "");
            result.put(entry);
        }
        return result;
    }

    private String thumbnail(String name) {
        if (!name.matches("snapshot_[A-Za-z0-9_-]+\\.(jpg|png|webp|gif|img)")) return "";
        Bitmap image = null;
        try {
            File file = new File(directory, name);
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
            if (cancelled.get()) throw new Exception("Capture cancelled.");
            String hash = hex(digest.digest());
            JSONObject inspection = inspect(temporary);
            String mime = inspection.getString("mimeType"), reason = inspection.getString("reason");
            String extension = mime.equals("image/png") ? "png" : mime.equals("image/webp") ? "webp" : mime.equals("image/gif") ? "gif" : mime.equals("image/jpeg") ? "jpg" : "img";
            destination = new File(directory, "snapshot_" + id + "." + extension);
            if (!temporary.renameTo(destination)) throw new Exception("Cannot save snapshot file.");
            JSONObject entry = new JSONObject().put("id", id).put("cameraId", camera.getString("id"))
                    .put("cameraName", camera.optString("name", camera.optString("host"))).put("ip", camera.optString("host"))
                    .put("channel", channel).put("capturedAt", Instant.now().toString()).put("timestamp", System.currentTimeMillis())
                    .put("sizeBytes", size).put("mimeType", mime).put("sha256", hash).put("fileName", destination.getName())
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
                JSONArray updated = new JSONArray().put(entry);
                for (int i = 0; i < previous.length(); i++) updated.put(previous.get(i));
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
        String name = target.optString("fileName");
        if (!name.matches("snapshot_[A-Za-z0-9_-]+\\.(jpg|png|webp|gif|img)")) throw new Exception("Invalid stored image filename.");
        File file = new File(directory, name);
        if (file.exists() && !file.delete()) throw new Exception("Snapshot could not be deleted.");
        String treeCopy = target.optString("treeCopy");
        if (!treeCopy.isEmpty()) try { DocumentsContract.deleteDocument(context.getContentResolver(), Uri.parse(treeCopy)); } catch (Exception ignored) { /* Optional exported copy may have been moved. */ }
        persist(updated);
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
            Bitmap verification = BitmapFactory.decodeFile(image.getAbsolutePath(), sample);
            if (verification == null) reason = "corrupt";
            else verification.recycle();
        }
        return new JSONObject().put("mimeType", mime).put("reason", reason)
                .put("width", Math.max(0, bounds.outWidth)).put("height", Math.max(0, bounds.outHeight));
    }

    static boolean completeImage(File image, String mime) {
        try (RandomAccessFile file = new RandomAccessFile(image, "r")) {
            if (mime.equals("image/jpeg")) {
                if (file.length() < 4) return false;
                file.seek(file.length() - 2);
                return file.readUnsignedByte() == 255 && file.readUnsignedByte() == 217;
            }
            if (mime.equals("image/png")) {
                if (file.length() < 12) return false;
                file.seek(file.length() - 12);
                byte[] tail = new byte[12]; file.readFully(tail);
                return tail[4] == 'I' && tail[5] == 'E' && tail[6] == 'N' && tail[7] == 'D';
            }
            return true;
        } catch (Exception e) { return false; }
    }
}
