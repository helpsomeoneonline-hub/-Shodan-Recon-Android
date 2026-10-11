package com.shodanrecon.smoke;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import android.util.Xml;
import org.xmlpull.v1.XmlSerializer;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** UI, upgrade and batch smoke uses synthetic credentials and an emulator loopback fixture only. */
public final class SmokeInstrumentation extends Instrumentation {
    private Activity dashboard;
    private WebView browser;
    private File evidence;
    private int checks;
    private String phase;

    @Override public void onCreate(Bundle args) { super.onCreate(args); phase = args == null ? "fresh" : args.getString("phase", "fresh"); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            evidence = new File(getTargetContext().getFilesDir(), "smoke-results"); evidence.mkdirs();
            dashboard = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName("com.shodanrecon.mobile", "com.shodanrecon.mobile.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> browser = findBrowser(dashboard.getWindow().getDecorView()));
            require(browser != null, "Dashboard WebView exists");
            awaitScript("document.getElementById('start-search') && window.ReconCameras && document.getElementById('cam-open')", "Dashboard and camera bridge load");
            if ("seed".equals(phase)) {
                seedInstalledVersion();
                result.putString("smoke_success", "true"); result.putInt("checks", checks);
                finish(Activity.RESULT_OK, result); return;
            }
            if ("review".equals(phase)) {
                require(eval("JSON.parse(window.ReconCameras.state()).batch.status==='cancelled' && JSON.parse(window.ReconCameras.state()).batchHistory.some(b=>b.status==='completed'&&b.completed===4&&b.snapshotsSaved===2)").equals("true"), "Completed and stopped batch reports survive process restart");
                require(eval("JSON.parse(window.ReconCameras.state()).snapshots.length===2 && JSON.parse(window.ReconCameras.state()).passwordPresets.some(p=>p.id==='preset-1'&&p.configured)").equals("true"), "Gallery and encrypted preset survive process restart");
                result.putString("smoke_success", "true"); result.putInt("checks", checks);
                finish(Activity.RESULT_OK, result); return;
            }
            if ("upgrade".equals(phase)) {
                require(eval("document.getElementById('shodan-query').value==='emulator-persisted-query'").equals("true"), "In-place update preserves saved search query");
                require(eval("JSON.parse(window.ReconNative.getState()).ipList.includes('192.168.50.2')").equals("true"), "In-place update preserves saved IP list");
                require(eval("JSON.parse(window.ReconCameras.state()).cameras.some(c=>c.id==='emulator-camera'&&c.credentialId==='emulator-legacy')").equals("true"), "In-place update preserves camera and credential assignment");
                require(eval("JSON.parse(window.ReconCameras.state()).credentials.some(c=>c.id==='emulator-legacy'&&c.hasPassword)").equals("true"), "In-place update decrypts original Keystore credential");
            }
            rotate(dashboard, UiAutomation.ROTATION_FREEZE_0, Configuration.ORIENTATION_PORTRAIT);
            navFits(); capture("01-dashboard-portrait");
            if (!"upgrade".equals(phase)) require(eval("document.getElementById('shodan-query').value.indexOf('port:80')===0").equals("true"), "Default Shodan query shown");
            eval("(()=>{let e=document.getElementById('shodan-query');e.value='emulator-persisted-query';e.dispatchEvent(new Event('input',{bubbles:true}));return true})()");
            rotate(dashboard, UiAutomation.ROTATION_FREEZE_90, Configuration.ORIENTATION_LANDSCAPE);
            navFits(); require(eval("document.getElementById('shodan-query').value==='emulator-persisted-query'").equals("true"), "Form survives orientation");
            capture("02-dashboard-landscape");
            rotate(dashboard, UiAutomation.ROTATION_FREEZE_0, Configuration.ORIENTATION_PORTRAIT);
            eval("document.querySelector('.nav button[data-view=\"cameras\"]').click()");
            awaitScript("document.getElementById('view-cameras').classList.contains('active')", "Cameras navigation works");
            navFits(); capture("03-cameras-portrait");
            eval("document.querySelector('[data-camera-tab=\"gallery\"]').click()");
            require(eval("!!document.getElementById('cam-gallery') && !!document.getElementById('cam-select-storage') && !!document.getElementById('cam-delete-flagged')").equals("true"), "Gallery controls exist");
            capture("04-gallery-empty");
            eval("document.querySelector('[data-camera-tab=\"credentials\"]').click()");
            eval("document.querySelector('.nav button[data-view=\"settings\"]').click()");
            require(eval("document.querySelectorAll('#cam-preset-section input[type=password]').length===6 && [...document.querySelectorAll('#cam-preset-section input[type=password]')].every(e=>e.value==='') && document.getElementById('cam-preset-username').value===''").equals("true"), "One shared username and six blank password preset inputs");
            require(eval("JSON.parse(window.ReconCameras.state()).passwordPresets.length===6").equals("true"), "Native state contains exactly six preset statuses");
            capture("05-six-presets-blank");
            enter("cam-preset-username", "smoke-admin"); enter("cam-preset-1", "fixture-only-initial-password");
            eval("document.getElementById('cam-save-presets').click()");
            awaitScript("!document.getElementById('cam-save-presets').disabled && document.getElementById('cam-preset-1').value==='' && JSON.parse(window.ReconCameras.state()).passwordPresets.some(p=>p.id==='preset-1'&&p.configured)", "Settings Save encrypts preset, clears password and re-enables button");
            enter("cam-preset-1", "fixture" + (char) 1 + "password"); eval("document.getElementById('cam-save-presets').click()");
            awaitScript("!document.getElementById('cam-save-presets').disabled && document.getElementById('cam-preset-1').value!==''", "Rejected native credential re-enables Save and retains edit");
            enter("cam-preset-1", "fixture-only-new-password"); eval("document.getElementById('cam-save-presets').click()");
            try {
            awaitScript("!document.getElementById('cam-save-presets').disabled && document.getElementById('cam-preset-1').value===''&& JSON.parse(window.ReconCameras.state()).passwordPresets.some(p=>p.id==='preset-1'&&p.configured)", "Second Settings Save replaces preset after failed edit");
            } catch (AssertionError failed) {
                String detail = eval("JSON.stringify({saveDisabled:document.getElementById('cam-save-presets').disabled,editedPasswordLength:document.getElementById('cam-preset-1').value.length,usernameConfigured:JSON.parse(window.ReconCameras.state()).usernameConfigured,presetConfigured:JSON.parse(window.ReconCameras.state()).passwordPresets[0].configured,notice:document.getElementById('cam-status-text').textContent})");
                throw new AssertionError("Second Settings Save failed; non-secret diagnostic: " + detail, failed);
            }
            require(eval("!window.ReconCameras.state().includes('fixture-only-new-password') && document.getElementById('cam-preset-1').value===''").equals("true"), "Saved password remains hidden from UI and bridge state");
            eval("document.querySelector('.nav button[data-view=\"cameras\"]').click()");
            require(eval("!!document.getElementById('cam-batch-start') && !!document.getElementById('cam-batch-stop') && !!document.getElementById('cam-batch-export')").equals("true"), "Automatic manager controls are available without starting connections");
            JSONObject camera = new JSONObject().put("id", "emulator-camera").put("name", "Emulator camera")
                    .put("host", "127.0.0.1").put("scheme", "http").put("port", 8089).put("authorized", true)
                    .put("httpPath", "/").put("snapshotPath", "/image/{channel}.jpg");
            if ("upgrade".equals(phase)) camera.put("credentialId", "emulator-legacy");
            eval("window.ReconCameras.command('save-camera'," + JSONObject.quote(camera.toString()) + ");true");
            awaitScript("JSON.parse(window.ReconCameras.state()).cameras.some(c=>c.id==='emulator-camera')", "Native bridge registers authorized fixture without connecting");
            ActivityMonitor monitor = addMonitor("com.shodanrecon.mobile.CameraViewerActivity", null, false);
            eval("window.ReconCameras.command('open-viewer','{\"id\":\"emulator-camera\",\"method\":\"snapshot\"}');true");
            Activity viewer = waitForMonitorWithTimeout(monitor, 8000); removeMonitor(monitor);
            require(viewer != null, "Native viewer opens for registered camera");
            waitForIdleSync(); Thread.sleep(500); capture("06-native-viewer-portrait");
            rotate(viewer, UiAutomation.ROTATION_FREEZE_90, Configuration.ORIENTATION_LANDSCAPE);
            require(!viewer.isFinishing(), "Native viewer survives orientation"); capture("07-native-viewer-landscape");
            runOnMainSync(viewer::finish); waitForIdleSync();
            require(eval("JSON.parse(window.ReconNative.getState()).settings.query==='emulator-persisted-query'").equals("true"), "Saved form persisted through native viewer");
            runBatchFixtures();
            result.putString("smoke_success", "true"); result.putInt("checks", checks);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("smoke_success", "false"); result.putString("failure", failure.toString()); result.putInt("checks", checks);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void seedInstalledVersion() throws Exception {
        require(eval("document.getElementById('shodan-query').value.indexOf('port:80')===0").equals("true"), "Installed-version defaults are available");
        eval("(()=>{let e=document.getElementById('shodan-query');e.value='emulator-persisted-query';e.dispatchEvent(new Event('input',{bubbles:true}));let s=JSON.parse(window.ReconNative.getState());s.ipList=['192.168.50.2'];window.ReconNative.saveState(JSON.stringify(s));return true})()");
        eval("window.ReconCameras.command('save-credential','{\"id\":\"emulator-legacy\",\"name\":\"Synthetic update fixture\",\"username\":\"smoke-admin\",\"password\":\"fixture-only-password\"}');true");
        awaitScript("JSON.parse(window.ReconCameras.state()).credentials.some(c=>c.id==='emulator-legacy'&&c.hasPassword)", "Installed-version Keystore credential saved");
        eval("window.ReconCameras.command('save-camera','{\"id\":\"emulator-camera\",\"name\":\"Emulator camera\",\"host\":\"127.0.0.1\",\"scheme\":\"http\",\"port\":8089,\"authorized\":true,\"credentialId\":\"emulator-legacy\",\"httpPath\":\"/\",\"snapshotPath\":\"/image/{channel}.jpg\"}');true");
        awaitScript("JSON.parse(window.ReconCameras.state()).cameras.some(c=>c.id==='emulator-camera')", "Installed-version camera saved without connecting");
    }

    private void command(String action, JSONObject args) throws Exception {
        eval("window.ReconCameras.command(" + JSONObject.quote(action) + "," + JSONObject.quote(args.toString()) + ");true");
    }

    private void enter(String id, String value) throws Exception {
        eval("(()=>{const e=document.getElementById(" + JSONObject.quote(id) + ");e.value=" + JSONObject.quote(value) + ";e.dispatchEvent(new Event('input',{bubbles:true}));return true})()");
    }

    private void registerFixture(String id, int port, String path, String snapshot) throws Exception {
        command("save-camera", new JSONObject().put("id", id).put("name", id).put("host", "127.0.0.1")
                .put("scheme", "http").put("port", port).put("authorized", true).put("credentialId", "preset-1")
                .put("httpPath", path).put("snapshotPath", snapshot).put("channels", new JSONArray().put(101)));
        awaitScript("JSON.parse(window.ReconCameras.state()).cameras.some(c=>c.id===" + JSONObject.quote(id) + ")", "Loopback camera fixture registered: " + id);
    }

    private void runBatchFixtures() throws Exception {
        try (LoopbackFixture fixture = new LoopbackFixture()) {
            int unreachable;
            try (ServerSocket vacant = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { unreachable = vacant.getLocalPort(); }
            registerFixture("fixture-success-a", fixture.port(), "/ok-a", "/picture-a/{channel}.jpg");
            registerFixture("fixture-auth-failure", fixture.port(), "/deny", "/unused/{channel}.jpg");
            registerFixture("fixture-unreachable", unreachable, "/unreachable", "/unused/{channel}.jpg");
            registerFixture("fixture-success-d", fixture.port(), "/ok-d", "/picture-d/{channel}.jpg");
            command("batch-start", new JSONObject().put("cameraIds", new JSONArray()
                    .put("fixture-success-a").put("fixture-auth-failure").put("fixture-unreachable").put("fixture-success-d")));
            awaitScript("(()=>{const s=JSON.parse(window.ReconCameras.state());return !s.busy&&s.batch.status==='completed'&&s.batch.total===4})()", "Sequential four-camera batch finishes after connection failures");
            JSONObject completed = new JSONObject(eval("JSON.parse(window.ReconCameras.state()).batch"));
            require(completed.getInt("completed") == 4 && completed.getInt("successes") == 2
                    && completed.getInt("failures") == 2 && completed.getInt("snapshotsSaved") == 2, "Batch counters record two successes, two failures and two JPEG snapshots");
            JSONArray rows = completed.getJSONArray("results");
            require(rows.getJSONObject(0).getString("status").equals("success")
                    && rows.getJSONObject(1).getString("connectionStatus").equals("authentication-failed")
                    && rows.getJSONObject(1).getInt("httpCode") == 401
                    && rows.getJSONObject(2).getString("connectionStatus").equals("unreachable")
                    && rows.getJSONObject(3).getString("status").equals("success"), "Batch records success, HTTP 401, unreachable and later success in selection order");
            require(fixture.paths().equals("/ok-a,/picture-a/101.jpg,/deny,/ok-d,/picture-d/101.jpg"), "Authenticated fixture requests run sequentially with configured snapshot channel");
            require(!fixture.unexpectedCredential.get(), "Only the assigned saved synthetic credential was submitted");
            require(eval("JSON.parse(window.ReconCameras.state()).snapshots.length===2 && JSON.parse(window.ReconCameras.state()).snapshots.every(s=>s.mimeType==='image/jpeg'&&!s.flagged&&s.width===8&&s.height===8)").equals("true"), "Compatible JPEG snapshots appear in existing gallery");
            writeEvidence("completed-batch.json", completed.toString(2));
            writeEvidence("fixture-request-order.txt", fixture.paths());
            eval("document.querySelector('.nav button[data-view=\"gallery\"]').click()"); capture("08-gallery-captured");
            registerFixture("fixture-stalled", fixture.port(), "/stall", "/unused/{channel}.jpg");
            registerFixture("fixture-after-stop", fixture.port(), "/must-not-run", "/unused/{channel}.jpg");
            JSONObject selection = new JSONObject().put("cameraIds", new JSONArray().put("fixture-stalled").put("fixture-after-stop"));
            command("batch-start", selection);
            require(fixture.stalled.await(8, TimeUnit.SECONDS), "Cancellation fixture receives current camera request");
            String firstRun = new JSONObject(eval("JSON.parse(window.ReconCameras.state()).batch")).getString("runId");
            command("batch-start", selection);
            Thread.sleep(250);
            require(new JSONObject(eval("JSON.parse(window.ReconCameras.state()).batch")).getString("runId").equals(firstRun), "Repeated Start cannot create an overlapping batch");
            long stoppedAt = System.nanoTime(); command("batch-stop", new JSONObject());
            awaitScript("(()=>{const s=JSON.parse(window.ReconCameras.state());return !s.busy&&s.batch.status==='cancelled'})()", "Stop cancels a stalled request without waiting for its network timeout");
            require(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - stoppedAt) < 8, "Stop completes promptly");
            require(!fixture.paths().contains("/must-not-run"), "Stop prevents the next selected camera from starting");
            JSONObject stopped = new JSONObject(eval("JSON.parse(window.ReconCameras.state()).batch"));
            require(stopped.getJSONArray("results").length() == 1
                    && stopped.getJSONArray("results").getJSONObject(0).getString("status").equals("cancelled"), "Stopped camera outcome remains available for review");
            writeEvidence("stopped-batch.json", stopped.toString(2));
            String reports = getTargetContext().getSharedPreferences("recon_camera_batches_v1", 0).getString("batches", "");
            require(reports.contains(firstRun) && reports.contains(completed.getString("runId")), "Completed and stopped reports are committed to persistent storage");
            require(!reports.contains("fixture-only-new-password"), "Persistent diagnostic report excludes synthetic password");
        }
    }

    private void writeEvidence(String name, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(evidence, name))) { output.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    private static final class LoopbackFixture implements AutoCloseable {
        final AtomicBoolean unexpectedCredential = new AtomicBoolean();
        final CountDownLatch stalled = new CountDownLatch(1), release = new CountDownLatch(1);
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
        final ServerSocket server;
        final byte[] jpegA, jpegD;
        final String authorization = "Basic " + android.util.Base64.encodeToString("smoke-admin:fixture-only-new-password".getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        LoopbackFixture() throws Exception {
            server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1")); jpegA = jpeg(0xffff0000); jpegD = jpeg(0xff00ff00);
            Thread accept = new Thread(() -> {
                while (!server.isClosed()) try {
                    Socket socket = server.accept(); sockets.add(socket);
                    Thread worker = new Thread(() -> serve(socket), "smoke-fixture-request"); worker.setDaemon(true); worker.start();
                } catch (Exception ignored) { break; }
            }, "smoke-fixture-loopback"); accept.setDaemon(true); accept.start();
        }
        int port() { return server.getLocalPort(); }
        String paths() { synchronized (requests) { return String.join(",", requests); } }
        static byte[] jpeg(int color) {
            Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output); bitmap.recycle(); return output.toByteArray();
        }
        void serve(Socket socket) {
            try (Socket connection = socket) {
                connection.setSoTimeout(10000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                String first = reader.readLine(), supplied = "", line;
                if (first == null) return;
                while ((line = reader.readLine()) != null && !line.isEmpty()) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("authorization:")) supplied = line.substring(line.indexOf(':') + 1).trim();
                String path = first.split(" ")[1];
                if (!supplied.isEmpty() && !supplied.equals(authorization)) unexpectedCredential.set(true);
                if (!supplied.equals(authorization)) { respond(connection, 401, "text/plain", "fixture auth".getBytes(StandardCharsets.US_ASCII), true); return; }
                requests.add(path);
                if (path.equals("/stall")) { stalled.countDown(); release.await(30, TimeUnit.SECONDS); return; }
                if (path.equals("/deny")) { respond(connection, 401, "text/plain", "fixture denied".getBytes(StandardCharsets.US_ASCII), true); return; }
                if (path.startsWith("/picture-a/")) respond(connection, 200, "image/jpeg", jpegA, false);
                else if (path.startsWith("/picture-d/")) respond(connection, 200, "image/jpeg", jpegD, false);
                else respond(connection, 200, "text/html", "<html>Authorized loopback fixture</html>".getBytes(StandardCharsets.US_ASCII), false);
            } catch (Exception ignored) { /* Expected when Stop closes the stalled request. */ }
        }
        void respond(Socket socket, int code, String type, byte[] bytes, boolean challenge) throws Exception {
            String header = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Unauthorized") + "\r\nContent-Type: " + type
                    + "\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n" + (challenge ? "WWW-Authenticate: Basic realm=\"smoke-fixture\"\r\n" : "") + "\r\n";
            socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
        }
        @Override public void close() throws Exception {
            release.countDown(); server.close(); synchronized (sockets) { for (Socket socket : sockets) try { socket.close(); } catch (Exception ignored) {} }
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++; Bundle status = new Bundle(); status.putString("check", message); sendStatus(0, status);
    }
    private WebView findBrowser(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            WebView found = findBrowser(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private String eval(String script) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<String> answer = new AtomicReference<>();
        runOnMainSync(() -> browser.evaluateJavascript(script, value -> { answer.set(value); latch.countDown(); }));
        if (!latch.await(8, TimeUnit.SECONDS)) throw new AssertionError("WebView script timed out");
        return answer.get();
    }
    private void awaitScript(String script, String message) throws Exception {
        for (int i = 0; i < 40; i++) { if ("true".equals(eval("Boolean(" + script + ")"))) { require(true, message); return; } Thread.sleep(200); }
        throw new AssertionError(message);
    }
    private void navFits() throws Exception {
        require("true".equals(eval("(()=>{const n=document.querySelector('.nav'),r=n.getBoundingClientRect();return r.top>=0 && r.bottom<=innerHeight+1 && n.querySelectorAll('button').length>=6 && [...n.querySelectorAll('button')].every(b=>b.getBoundingClientRect().height>=44)})()")), "Bottom navigation remains inside viewport with touch targets");
    }
    private void rotate(Activity activity, int rotation, int orientation) throws Exception {
        require(getUiAutomation().setRotation(rotation), "Emulator rotation accepted");
        for (int i = 0; i < 40; i++) {
            if (activity.getResources().getConfiguration().orientation == orientation) { waitForIdleSync(); Thread.sleep(600); return; }
            Thread.sleep(200);
        }
        throw new AssertionError("Orientation did not change");
    }
    private void capture(String name) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Emulator screenshot unavailable");
        try (FileOutputStream output = new FileOutputStream(new File(evidence, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
        bitmap.recycle();
        try (FileOutputStream output = new FileOutputStream(new File(evidence, name + ".xml"))) {
            XmlSerializer xml = Xml.newSerializer(); xml.setOutput(output, "UTF-8"); xml.startDocument("UTF-8", true);
            xml.startTag(null, "hierarchy"); dumpNode(xml, getUiAutomation().getRootInActiveWindow()); xml.endTag(null, "hierarchy"); xml.endDocument();
        }
        Bundle status = new Bundle(); status.putString("screenshot", name); sendStatus(0, status);
    }
    private void dumpNode(XmlSerializer xml, AccessibilityNodeInfo node) throws Exception {
        if (node == null) return;
        xml.startTag(null, "node");
        xml.attribute(null, "text", node.getText() == null ? "" : node.getText().toString());
        xml.attribute(null, "class", node.getClassName() == null ? "" : node.getClassName().toString());
        xml.attribute(null, "description", node.getContentDescription() == null ? "" : node.getContentDescription().toString());
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds); xml.attribute(null, "bounds", bounds.toShortString());
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); dumpNode(xml, child); if (child != null) child.recycle(); }
        xml.endTag(null, "node");
    }
}
