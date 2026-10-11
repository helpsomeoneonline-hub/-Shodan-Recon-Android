package com.shodanrecon.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import okhttp3.Response;

/** Native commands accept only manually registered cameras, never a list of Shodan results. */
public final class CameraBridge {
    private static final int STORAGE = 6101, EXPORT_IMAGE = 6102, EXPORT_CONFIG = 6103, IMPORT_CONFIG = 6104, EXPORT_BATCH = 6105;
    private final Activity activity;
    private final BiConsumer<String, JSONObject> send;
    private final CameraRepository repository;
    private final SnapshotStore snapshots;
    private final CameraBatchStore batches;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile CameraHttp activeHttp;
    private volatile String pendingImage = "", pendingConfig = "", pendingBatch = "";

    public CameraBridge(Activity activity, BiConsumer<String, JSONObject> send) {
        this.activity = activity; this.send = send;
        repository = new CameraRepository(activity); snapshots = new SnapshotStore(activity);
        batches = new CameraBatchStore(activity);
        try { batches.interrupt(); } catch (Exception e) { notice(false, "Previous camera check report could not be restored. Existing reports were preserved."); }
        File[] previousProbes = activity.getCacheDir().listFiles();
        if (previousProbes != null) for (File probe : previousProbes) if (probe.getName().startsWith("camera_probe_") && probe.getName().endsWith(".tmp")) probe.delete();
    }

    private JSONObject currentState() throws Exception {
        JSONObject state = repository.state();
        state.put("snapshots", snapshots.state()).put("storageBytes", snapshots.storageBytes())
                .put("storageLocation", snapshots.storageLocation()).put("busy", busy.get()).put("ok", true)
                .put("batch", batches.state()).put("batchHistory", batches.history());
        return state;
    }

    @JavascriptInterface public String state() {
        try { return currentState().toString(); }
        catch (Exception e) { return error(repository.sanitize(e.getMessage())).toString(); }
    }

    @JavascriptInterface public void command(String action, String json) {
        if (closed.get()) return;
        if ("cancel".equals(action) || "batch-stop".equals(action)) {
            cancelled.set(true); CameraHttp transport = activeHttp;
            if (transport != null) transport.cancel();
            notice(true, busy.get() ? "Cancelling camera task…" : "No camera task is running.");
            return;
        }
        if (action == null || action.length() > 40 || json == null || json.length() > 65536) {
            notice(false, "Camera command is invalid or too large."); return;
        }
        boolean taskAcquired = false;
        try {
            final JSONObject args = new JSONObject(json);
            boolean cameraTask = action.equals("capture") || action.equals("test-camera") || action.equals("batch-start");
            if (cameraTask && !busy.compareAndSet(false, true)) {
                notice(false, "A camera task is already running. Stop it before starting another."); return;
            }
            taskAcquired = cameraTask;
            if (cameraTask) cancelled.set(false);
            worker.execute(() -> {
                try {
                    switch (action) {
                        case "save-camera": repository.saveCamera(args); publish(); notice(true, "Camera profile saved."); break;
                        case "delete-camera": repository.deleteCamera(args.optString("id")); publish(); notice(true, "Camera profile deleted. Saved snapshots were retained."); break;
                        case "save-credential": repository.saveCredential(args); publish(); notice(true, "Device credential saved securely."); break;
                        case "save-presets": repository.savePresets(args); publish(); notice(true, "Shared username and password presets saved securely.", action); break;
                        case "delete-credential": repository.deleteCredential(args.optString("id")); publish(); notice(true, "Credential deleted and removed from associated cameras."); break;
                        case "capture": capture(args); break;
                        case "test-camera": test(args); break;
                        case "batch-start": batch(args); break;
                        case "batch-export": exportBatch(); break;
                        case "open-viewer": openViewer(args); break;
                        case "preview-snapshot": case "preview": preview(args.optString("id")); break;
                        case "share-snapshot": case "share": share(args.optString("id")); break;
                        case "export-snapshot": exportSnapshot(args.optString("id")); break;
                        case "delete-snapshot": snapshots.delete(args.optString("id")); publish(); notice(true, "Snapshot deleted."); break;
                        case "delete-flagged": int count = snapshots.deleteFlagged(); publish(); notice(true, "Deleted " + count + " flagged images."); break;
                        case "thumbnail": thumbnail(args.optString("id")); break;
                        case "select-storage": launchFolderPicker(); break;
                        case "export-config": exportConfig(); break;
                        case "import-config": launchImport(); break;
                        case "refresh": publish(); break;
                        default: throw new Exception("Unknown camera command.");
                    }
                } catch (Exception | OutOfMemoryError e) {
                    if (action.equals("capture") || action.equals("test-camera")) {
                        try { repository.record(args.optString("id"), action, cancelled.get() ? "Cancelled" : "Failed"); } catch (Exception ignored) {}
                    }
                    notice(false, cancelled.get() && cameraTask ? "Camera task cancelled." : safeFailure(e), action);
                } finally {
                    if (cameraTask) {
                        activeHttp = null; busy.set(false); publish();
                    }
                }
            });
        } catch (Exception e) {
            if (taskAcquired) busy.set(false);
            notice(false, "Camera command is invalid.");
        }
    }

    private void publish() {
        if (closed.get()) return;
        try { send.accept("camera-state", currentState()); }
        catch (Exception e) { notice(false, repository.sanitize(e.getMessage())); }
    }

    private static JSONObject error(String message) {
        JSONObject result = new JSONObject();
        try { result.put("ok", false).put("error", message); } catch (Exception ignored) {}
        return result;
    }

    private void notice(boolean ok, String message) {
        notice(ok, message, "");
    }

    private void notice(boolean ok, String message, String action) {
        if (closed.get()) return;
        try { send.accept("camera-notice", new JSONObject().put("ok", ok).put("action", action).put(ok ? "message" : "error", repository.sanitize(message))); }
        catch (Exception ignored) {}
    }

    private void progress(int completed, int total, String message) {
        if (closed.get()) return;
        try { send.accept("camera-progress", new JSONObject().put("ok", true).put("completed", completed).put("total", total)
                .put("message", message).put("busy", busy.get())); }
        catch (Exception ignored) {}
    }

    private String safeFailure(Throwable error) {
        if (error instanceof OutOfMemoryError) return "This image is too large to display safely. Export it or use a lower resolution snapshot.";
        if (error instanceof java.net.SocketTimeoutException) return "Camera response timed out. Check the address, port, path and network.";
        if (error instanceof javax.net.ssl.SSLException) return "HTTPS certificate verification failed. Install a trusted certificate matching the camera hostname.";
        String message = error.getMessage();
        if (message == null || message.isEmpty()) return "Camera operation failed. Check the device, network and available storage.";
        // Low-level errors can contain request URLs or provider paths. Known actionable messages are safe.
        if (message.contains("http://") || message.contains("https://") || message.contains("rtsp://") || message.contains("Authorization"))
            return "Camera operation failed. Check the saved configuration, network and selected credential.";
        return repository.sanitize(message);
    }

    private static String path(JSONObject camera, String method) throws Exception {
        String key;
        switch (method) {
            case "http": key = "httpPath"; break;
            case "snapshot": key = "snapshotPath"; break;
            case "mjpeg": key = "mjpegPath"; break;
            case "hls": key = "hlsPath"; break;
            default: throw new Exception("Choose HTTP, snapshot, MJPEG or HLS for a connection test.");
        }
        String path = camera.optString(key);
        if (path.isEmpty()) throw new Exception("Configure this camera's " + method.toUpperCase(Locale.ROOT) + " path first.");
        return path;
    }

    private static void checkHttp(Response response) throws Exception {
        int code = response.code();
        if (code == 401) throw new Exception("HTTP 401: Camera authentication failed. Select or edit this camera's saved credential.");
        if (code == 403) throw new Exception("HTTP 403: Camera access is denied. Check account permissions; access restrictions will not be bypassed.");
        if (code == 404) throw new Exception("HTTP 404: Camera path or channel was not found. Check the configured URL template.");
        if (!response.isSuccessful()) throw new Exception("Camera returned HTTP " + code + ". Check the camera configuration and service availability.");
        if (response.body() == null) throw new Exception("The camera returned no response body.");
    }

    private void batchProgress(JSONObject batch) throws Exception {
        if (closed.get()) return;
        batches.update(batch);
        send.accept("batch-progress", new JSONObject(batch.toString()).put("ok", true));
    }

    private void batchCheck(JSONObject row) {
        if (closed.get()) return;
        try {
            boolean ok = row.optString("connectionStatus").equals("success");
            JSONObject event = new JSONObject().put("ok", ok).put("status", row.optString("connectionStatus"))
                    .put("httpCode", row.optInt("httpCode")).put("elapsedMs", row.optLong("elapsedMs"))
                    .put(ok ? "message" : "error", repository.sanitize(row.optString("message")));
            send.accept("batch-check", event);
        } catch (Exception ignored) {}
    }

    private void batchSnapshotCheck(JSONObject outcome) {
        if (closed.get()) return;
        try {
            boolean ok = outcome.optString("status").equals("saved") || outcome.optString("status").equals("duplicate");
            send.accept("batch-snapshot-check", new JSONObject().put("ok", ok)
                    .put("status", outcome.optString("status")).put("httpCode", outcome.optInt("httpCode"))
                    .put(ok ? "message" : "error", repository.sanitize(outcome.optString("message"))));
        } catch (Exception ignored) {}
    }

    /** Selection is resolved only from persisted, authorized profiles; discovery results cannot become requests. */
    private java.util.List<JSONObject> batchSelection(JSONObject args) throws Exception {
        return CameraBatchReport.selection(args.optJSONArray("cameraIds"), repository::camera);
    }

    private void batch(JSONObject args) throws Exception {
        java.util.List<JSONObject> selected = batchSelection(args);
        if (cancelled.get() || closed.get()) throw new Exception("Camera task cancelled.");
        JSONObject batch = batches.start(selected.size());
        String endStatus = "completed";
        try {
            batchProgress(batch);
            for (int index = 0; index < selected.size(); index++) {
                if (cancelled.get() || closed.get()) { endStatus = "cancelled"; break; }
                JSONObject camera = selected.get(index);
                JSONObject row = CameraBatchReport.begin(camera, batch.getString("runId"));
                row.put("cameraName", repository.sanitize(row.optString("cameraName")));
                batch.put("currentCamera", new JSONObject().put("id", camera.getString("id"))
                                .put("name", repository.sanitize(camera.optString("name"))).put("host", camera.optString("host"))
                                .put("scheme", camera.optString("scheme")).put("port", camera.optInt("port")))
                        .put("index", index + 1).put("currentResult", row)
                        .put("message", "Testing camera " + (index + 1) + " of " + selected.size() + "…");
                batchProgress(batch);
                String message;
                int responseCode = 0;
                boolean connected = false;
                try {
                    activeHttp = new CameraHttp(repository, camera);
                    if (cancelled.get() || closed.get()) { activeHttp.cancel(); throw new Exception("Camera task cancelled."); }
                    int firstChannel = camera.getJSONArray("channels").optInt(0, 101);
                    try (Response response = activeHttp.get(CameraUrls.url(camera, path(camera, "http"), firstChannel))) {
                        responseCode = response.code(); row.put("httpCode", responseCode);
                        checkHttp(response);
                        if (response.body().byteStream().read() == -1) throw new Exception("The camera returned an empty HTTP interface response. Check its configured path.");
                    }
                    if (cancelled.get() || closed.get()) throw new Exception("Camera task cancelled.");
                    connected = true; row.put("connectionStatus", "success");
                    message = "HTTP " + responseCode + ": Connection successful.";
                    row.put("message", message);
                    batch.put("message", message + " Capturing configured channels…"); batchProgress(batch);
                    batchSnapshots(camera, row, batch);
                    String status = cancelled.get() || closed.get() ? "cancelled" : row.optInt("snapshotFailures") > 0 ? "snapshot-failed" : "success";
                    message += " " + row.optInt("snapshotsSaved") + " snapshots saved; " + row.optInt("snapshotFailures") + " snapshot failures; " + row.optInt("snapshotsFlagged") + " flagged for review.";
                    CameraBatchReport.finishRow(row, status, message);
                } catch (Exception | OutOfMemoryError e) {
                    if (e instanceof CameraHttp.StatusException) {
                        responseCode = ((CameraHttp.StatusException) e).statusCode; row.put("httpCode", responseCode);
                    }
                    String status = CameraBatchReport.failureStatus(e, responseCode, cancelled.get() || closed.get());
                    if (!connected) row.put("connectionStatus", status);
                    else if (!cancelled.get() && !closed.get()) row.put("snapshotFailures", row.optInt("snapshotFailures") + 1);
                    message = safeFailure(e);
                    CameraBatchReport.finishRow(row, status, message);
                } finally { activeHttp = null; }
                batch.remove("currentResult");
                batch.getJSONArray("results").put(row);
                CameraBatchReport.totals(batch);
                batch.put("message", row.optString("message"));
                batchProgress(batch); batchCheck(row);
                repository.record(camera.getString("id"), "batch-check", row.optString("message"));
                if (cancelled.get() || closed.get()) { endStatus = "cancelled"; break; }
            }
        } catch (Exception | OutOfMemoryError e) {
            endStatus = cancelled.get() || closed.get() ? "cancelled" : "interrupted";
            throw e;
        } finally {
            CameraBatchReport.finish(batch, endStatus);
            batchProgress(batch);
            if (!closed.get()) { publish(); notice(endStatus.equals("completed"), batch.optString("message")); }
        }
    }

    private void batchSnapshots(JSONObject camera, JSONObject row, JSONObject batch) throws Exception {
        JSONArray configured = camera.optJSONArray("channels");
        if (configured == null || configured.length() < 1 || configured.length() > 64) throw new Exception("Configure 1–64 snapshot channels for this camera.");
        java.util.LinkedHashSet<Integer> channels = new java.util.LinkedHashSet<>();
        for (int i = 0; i < configured.length(); i++) {
            int channel = configured.optInt(i, -1);
            if (channel < 1 || channel > 65535) throw new Exception("Snapshot channels must be 1–65535.");
            channels.add(channel);
        }
        for (int channel : channels) {
            if (cancelled.get() || closed.get()) return;
            JSONObject outcome = new JSONObject().put("channel", channel).put("httpCode", 0)
                    .put("startedAt", java.time.Instant.now().toString());
            batch.put("message", "Camera " + batch.optInt("index") + "/" + batch.optInt("total") + ": downloading channel " + channel + "…");
            batchProgress(batch);
            int code = 0;
            try (Response response = activeHttp.get(CameraUrls.url(camera, path(camera, "snapshot"), channel))) {
                code = response.code(); outcome.put("httpCode", code); checkHttp(response);
                if (response.body().contentLength() > SnapshotStore.MAX_IMAGE_BYTES) throw new Exception("Snapshot exceeds the 20 MB download limit.");
                JSONObject image = snapshots.save(response.body().byteStream(), camera, channel, cancelled);
                String reason = image.optString("reason");
                outcome.put("snapshotId", image.getString("id")).put("reason", reason);
                if (image.optBoolean("flagged")) row.put("snapshotsFlagged", row.optInt("snapshotsFlagged") + 1);
                if (reason.isEmpty() || reason.equals("duplicate")) {
                    row.put("snapshotsSaved", row.optInt("snapshotsSaved") + 1);
                    outcome.put("status", reason.equals("duplicate") ? "duplicate" : "saved").put("message", "Snapshot saved.");
                } else {
                    row.put("snapshotFailures", row.optInt("snapshotFailures") + 1);
                    outcome.put("status", "invalid-image").put("message", "Snapshot flagged: " + reason + ". Review it in the gallery.");
                }
                if (!image.optString("storageWarning").isEmpty()) outcome.put("storageWarning", image.optString("storageWarning"));
            } catch (Exception | OutOfMemoryError e) {
                if (cancelled.get() || closed.get()) return;
                if (e instanceof CameraHttp.StatusException) {
                    code = ((CameraHttp.StatusException) e).statusCode; outcome.put("httpCode", code);
                }
                row.put("snapshotFailures", row.optInt("snapshotFailures") + 1);
                outcome.put("status", CameraBatchReport.failureStatus(e, code, false)).put("message", safeFailure(e));
            }
            outcome.put("finishedAt", java.time.Instant.now().toString());
            row.getJSONArray("snapshotResults").put(outcome);
            CameraBatchReport.totals(batch); batchProgress(batch);
            batchSnapshotCheck(outcome);
            // Do not retry one rejected assigned credential on every snapshot channel.
            if (code == 401 || code == 403) return;
        }
    }

    private void test(JSONObject args) throws Exception {
        if (cancelled.get()) throw new Exception("Camera task cancelled.");
        JSONObject camera = repository.camera(args.optString("id"));
        String method = args.optString("method", "snapshot").toLowerCase(Locale.ROOT);
        int channel = camera.getJSONArray("channels").optInt(0, 101);
        String url = CameraUrls.url(camera, path(camera, method), channel);
        long started = System.nanoTime();
        activeHttp = new CameraHttp(repository, camera);
        if (cancelled.get()) { activeHttp.cancel(); throw new Exception("Camera task cancelled."); }
        progress(0, 1, "Testing " + method.toUpperCase(Locale.ROOT) + " connection…");
        String diagnostic;
        try (Response response = activeHttp.get(url)) {
            checkHttp(response);
            String mime = response.body().contentType() == null ? "unspecified content type" : response.body().contentType().type() + "/" + response.body().contentType().subtype();
            if (method.equals("snapshot")) {
                File probe = File.createTempFile("camera_probe_", ".tmp", activity.getCacheDir());
                try {
                    byte[] buffer = new byte[16384]; int count; long size = 0;
                    try (InputStream input = response.body().byteStream(); OutputStream output = new FileOutputStream(probe)) {
                        while ((count = input.read(buffer)) != -1) {
                            if (cancelled.get()) throw new Exception("Camera task cancelled.");
                            size += count;
                            if (size > SnapshotStore.MAX_IMAGE_BYTES) throw new Exception("Snapshot exceeds the 20 MB download limit.");
                            output.write(buffer, 0, count);
                        }
                    }
                    JSONObject image = SnapshotStore.inspect(probe);
                    if (!image.optString("reason").isEmpty()) throw new Exception("The snapshot endpoint returned an empty, corrupt or oversized image. Check its path and channel.");
                    diagnostic = "HTTP " + response.code() + "; " + image.getString("mimeType") + "; " + image.getInt("width") + "×" + image.getInt("height");
                } finally { probe.delete(); }
            } else {
                InputStream content = response.body().byteStream();
                if (method.equals("hls")) {
                    ByteArrayOutputStream firstLine = new ByteArrayOutputStream();
                    int next;
                    while (firstLine.size() < 4096 && (next = content.read()) != -1) {
                        if (cancelled.get()) throw new Exception("Camera task cancelled.");
                        firstLine.write(next); if (next == '\n') break;
                    }
                    String header = firstLine.toString("UTF-8").replace("\uFEFF", "").trim();
                    if (!header.equals("#EXTM3U")) throw new Exception("The HLS path did not return an M3U8 playlist. Configure a compatible camera HLS endpoint.");
                } else if (content.read() == -1) throw new Exception("The camera returned an empty response. Check its path.");
                if (method.equals("mjpeg") && !mime.equals("multipart/x-mixed-replace") && !mime.startsWith("image/"))
                    throw new Exception("The MJPEG path returned " + mime + ". Configure a multipart MJPEG stream endpoint.");
                diagnostic = "HTTP " + response.code() + "; " + mime;
            }
        } finally { activeHttp = null; }
        if (cancelled.get()) throw new Exception("Camera task cancelled.");
        String result = "Connection test passed: " + diagnostic + "; " + ((System.nanoTime() - started) / 1000000) + " ms. Playback compatibility still depends on the camera.";
        repository.record(camera.getString("id"), "test-" + method, result);
        progress(1, 1, result); notice(true, result);
    }

    private void capture(JSONObject args) throws Exception {
        if (cancelled.get()) throw new Exception("Camera task cancelled.");
        JSONObject camera = repository.camera(args.optString("id"));
        JSONArray channels = args.optJSONArray("channels");
        if (channels == null || channels.length() == 0 || channels.length() > 64) throw new Exception("Select 1–64 snapshot channels.");
        java.util.LinkedHashSet<Integer> selected = new java.util.LinkedHashSet<>();
        for (int i = 0; i < channels.length(); i++) {
            int channel = channels.optInt(i, -1);
            if (channel < 1 || channel > 65535) throw new Exception("Snapshot channels must be 1–65535.");
            selected.add(channel);
        }
        activeHttp = new CameraHttp(repository, camera);
        if (cancelled.get()) { activeHttp.cancel(); throw new Exception("Camera task cancelled."); }
        int completed = 0, saved = 0, failed = 0, flagged = 0;
        String lastFailure = "";
        try {
            for (int channel : selected) {
                if (cancelled.get()) break;
                final int before = completed;
                progress(completed, selected.size(), "Downloading channel " + channel + "…");
                if (cancelled.get()) break;
                boolean authenticationRejected = false;
                try (Response response = activeHttp.get(CameraUrls.url(camera, path(camera, "snapshot"), channel))) {
                    checkHttp(response);
                    if (response.body().contentLength() > SnapshotStore.MAX_IMAGE_BYTES) throw new Exception("Snapshot exceeds the 20 MB download limit.");
                    JSONObject image = snapshots.save(response.body().byteStream(), camera, channel, cancelled, bytes ->
                            progress(before, selected.size(), "Downloading channel " + channel + " (" + String.format(Locale.ROOT, "%.1f", bytes / 1048576.0) + " MB)…"));
                    saved++;
                    if (image.optBoolean("flagged")) flagged++;
                    if (!image.optString("storageWarning").isEmpty()) notice(false, image.getString("storageWarning"));
                } catch (Exception e) {
                    if (cancelled.get()) break;
                    failed++; lastFailure = safeFailure(e);
                    notice(false, "Channel " + channel + ": " + lastFailure);
                    // One rejected selected credential must not trigger authentication attempts on every channel.
                    authenticationRejected = lastFailure.startsWith("HTTP 401") || lastFailure.startsWith("HTTP 403");
                }
                completed++;
                progress(completed, selected.size(), "Saved " + saved + ", failed " + failed + ", flagged " + flagged + ".");
                if (authenticationRejected) break;
            }
        } finally { activeHttp = null; }
        String result = (cancelled.get() ? "Capture cancelled. " : "Capture finished. ") + saved + " saved, " + failed + " failed, " + flagged + " flagged for review.";
        repository.record(camera.getString("id"), "capture", result);
        progress(completed, selected.size(), result);
        notice(failed == 0, result + (failed > 0 ? " " + lastFailure : ""));
    }

    private void openViewer(JSONObject args) throws Exception {
        JSONObject camera = repository.camera(args.optString("id"));
        String method = args.optString("method", "http").toLowerCase(Locale.ROOT);
        if (!method.matches("http|snapshot|mjpeg|rtsp|hls")) throw new Exception("Choose a supported camera viewing method.");
        if (!method.equals("rtsp")) path(camera, method);
        else if (camera.optString("rtspPath").isEmpty()) throw new Exception("Configure the camera's RTSP path first.");
        repository.record(camera.getString("id"), "viewer", "Opened " + method.toUpperCase(Locale.ROOT) + " viewer.");
        activity.runOnUiThread(() -> {
            try { activity.startActivity(new Intent(activity, CameraViewerActivity.class).putExtra("CAMERA_ID", camera.optString("id")).putExtra("METHOD", method)); }
            catch (Exception e) { notice(false, "The camera viewer could not be opened."); }
        });
    }

    private void preview(String id) throws Exception {
        JSONObject image = snapshots.entry(id); File file = snapshots.file(id);
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) throw new Exception("This image is empty or corrupt. Export it for review or delete it.");
        if ((long) bounds.outWidth * bounds.outHeight > 100000000) throw new Exception("This image is too large for safe preview. Export it for review.");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 2048 || bounds.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new Exception("This image cannot be decoded. Export it for review or delete it.");
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || closed.get()) { bitmap.recycle(); return; }
            LinearLayout container = new LinearLayout(activity); container.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (16 * activity.getResources().getDisplayMetrics().density); container.setPadding(padding, padding, padding, padding);
            TextView details = new TextView(activity); details.setText(image.optString("ip") + " · channel " + image.optInt("channel") + "\n" + image.optString("capturedAt"));
            container.addView(details);
            ImageView view = new ImageView(activity); view.setImageBitmap(bitmap); view.setAdjustViewBounds(true); view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            container.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.55)));
            AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(image.optString("cameraName", "Snapshot")).setView(container)
                    .setPositiveButton("Close", null).setNeutralButton("Share", (d, which) -> command("share", "{\"id\":" + JSONObject.quote(id) + "}" )).create();
            dialog.setOnDismissListener(d -> { view.setImageDrawable(null); bitmap.recycle(); }); dialog.show();
        });
    }

    private void share(String id) throws Exception {
        JSONObject image = snapshots.entry(id);
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", snapshots.file(id));
        activity.runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_SEND).setType(image.optString("mimeType", "image/jpeg"))
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("Camera snapshot", uri));
                activity.startActivity(Intent.createChooser(intent, "Share snapshot"));
            } catch (Exception e) { notice(false, "No compatible sharing app is installed."); }
        });
    }

    private void exportSnapshot(String id) throws Exception {
        JSONObject image = snapshots.entry(id); File file = snapshots.file(id);
        if (!pendingImage.isEmpty()) throw new Exception("Finish or cancel the current image export first.");
        pendingImage = id;
        activity.runOnUiThread(() -> {
            try { activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(image.optString("mimeType", "image/jpeg")).putExtra(Intent.EXTRA_TITLE, file.getName()), EXPORT_IMAGE); }
            catch (Exception e) { pendingImage = ""; notice(false, "The Android file picker could not be opened."); }
        });
    }

    private void thumbnail(String id) throws Exception {
        JSONObject image = snapshots.entry(id);
        send.accept("camera-thumbnail", new JSONObject().put("ok", true).put("id", id).put("thumbnail", snapshots.thumbnailFor(id)));
    }

    private void launchFolderPicker() {
        activity.runOnUiThread(() -> {
            try { activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), STORAGE); }
            catch (Exception e) { notice(false, "The Android folder picker could not be opened."); }
        });
    }

    private void exportConfig() throws Exception {
        if (!pendingConfig.isEmpty()) throw new Exception("Finish or cancel the current configuration export first.");
        pendingConfig = repository.exportConfig().toString(2);
        activity.runOnUiThread(() -> {
            try { activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "shodan_recon_camera_config.json"), EXPORT_CONFIG); }
            catch (Exception e) { pendingConfig = ""; notice(false, "The Android file picker could not be opened."); }
        });
    }

    private void exportBatch() throws Exception {
        if (!pendingBatch.isEmpty()) throw new Exception("Finish or cancel the current camera check export first.");
        pendingBatch = batches.csv();
        activity.runOnUiThread(() -> {
            try { activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "shodan_recon_camera_checks.csv"), EXPORT_BATCH); }
            catch (Exception e) { pendingBatch = ""; notice(false, "The Android file picker could not be opened."); }
        });
    }

    private void launchImport() {
        activity.runOnUiThread(() -> {
            try { activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain"}), IMPORT_CONFIG); }
            catch (Exception e) { notice(false, "The Android file picker could not be opened."); }
        });
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode < STORAGE || requestCode > EXPORT_BATCH) return false;
        if (closed.get()) return true;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == EXPORT_IMAGE) pendingImage = "";
            if (requestCode == EXPORT_CONFIG) pendingConfig = "";
            if (requestCode == EXPORT_BATCH) pendingBatch = "";
            notice(true, "File selection cancelled."); return true;
        }
        Uri uri = data.getData();
        worker.execute(() -> {
            try {
                if (requestCode == STORAGE) {
                    int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new Exception("The folder picker did not grant write access.");
                    if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
                        activity.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    else activity.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    snapshots.selectStorage(uri); publish(); notice(true, "New snapshots will also be copied to the selected folder. Existing images remain in app storage.");
                } else if (requestCode == EXPORT_IMAGE) {
                    String id = pendingImage;
                    try (InputStream input = new FileInputStream(snapshots.file(id)); OutputStream output = activity.getContentResolver().openOutputStream(uri, "w")) {
                        if (output == null) throw new Exception("The selected file cannot be written.");
                        byte[] buffer = new byte[16384]; int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    } finally { pendingImage = ""; }
                    notice(true, "Snapshot exported.");
                } else if (requestCode == EXPORT_CONFIG) {
                    try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "w")) {
                        if (output == null) throw new Exception("The selected file cannot be written.");
                        output.write(pendingConfig.getBytes(StandardCharsets.UTF_8));
                    } finally { pendingConfig = ""; }
                    notice(true, "Camera configuration exported without credentials. Imported devices require authorization confirmation.");
                } else if (requestCode == EXPORT_BATCH) {
                    try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "w")) {
                        if (output == null) throw new Exception("The selected file cannot be written.");
                        output.write(pendingBatch.getBytes(StandardCharsets.UTF_8));
                    } finally { pendingBatch = ""; }
                    notice(true, "Camera check CSV exported without usernames or passwords.");
                } else readImport(uri);
            } catch (Exception e) { notice(false, safeFailure(e)); }
        });
        return true;
    }

    private void readImport(Uri uri) throws Exception {
        JSONObject config;
        try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("The configuration file cannot be read.");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) { bytes.write(buffer, 0, count); if (bytes.size() > 1048576) throw new Exception("Configuration file exceeds the 1 MB import limit."); }
            config = new JSONObject(bytes.toString("UTF-8"));
        }
        if (!"shodan-recon-camera-config".equals(config.optString("format")) || config.optInt("version") != 1)
            throw new Exception("Select a Shodan Recon camera configuration JSON file.");
        JSONArray cameras = config.optJSONArray("cameras");
        if (cameras == null || cameras.length() == 0 || cameras.length() > 500) throw new Exception("Configuration must contain 1–500 camera profiles.");
        activity.runOnUiThread(() -> {
            if (closed.get() || activity.isFinishing()) return;
            new AlertDialog.Builder(activity).setTitle("Register imported cameras?")
                    .setMessage("Import " + cameras.length() + " camera profiles? Confirm that you own or are authorized to administer every camera in this file. No passwords will be imported and no connections will start automatically.")
                    .setNegativeButton("Cancel", null).setPositiveButton("I am authorized — import", (dialog, which) -> {
                        if (closed.get()) return;
                        worker.execute(() -> {
                            try { repository.importConfig(config); publish(); notice(true, "Camera profiles imported. Select saved credentials manually before connecting."); }
                            catch (Exception e) { notice(false, safeFailure(e)); }
                        });
                    }).show();
        });
    }

    public void close() {
        closed.set(true); cancelled.set(true);
        CameraHttp transport = activeHttp; if (transport != null) transport.cancel();
        try { batches.interrupt(); } catch (Exception ignored) { /* Startup will also recover an unfinished report. */ }
        worker.shutdownNow();
    }
}
