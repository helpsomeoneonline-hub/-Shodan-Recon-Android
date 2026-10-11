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
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Instrumentation-only UI smoke; no account key, credential or camera connection is used. */
public final class SmokeInstrumentation extends Instrumentation {
    private Activity dashboard;
    private WebView browser;
    private File evidence;
    private int checks;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
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
            rotate(dashboard, UiAutomation.ROTATION_FREEZE_0, Configuration.ORIENTATION_PORTRAIT);
            navFits(); capture("01-dashboard-portrait");
            require(eval("document.getElementById('shodan-query').value.indexOf('port:80')===0").equals("true"), "Default Shodan query shown");
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
            require(eval("document.getElementById('cam-credential-password').value==='' && document.getElementById('cam-credential-user').value===''").equals("true"), "Credential form stays blank");
            capture("05-credentials-blank");
            JSONObject camera = new JSONObject().put("id", "emulator-camera").put("name", "Emulator camera")
                    .put("host", "127.0.0.1").put("scheme", "http").put("port", 8089).put("authorized", true)
                    .put("httpPath", "/").put("snapshotPath", "/image/{channel}.jpg");
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
            result.putString("smoke_success", "true"); result.putInt("checks", checks);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("smoke_success", "false"); result.putString("failure", failure.toString()); result.putInt("checks", checks);
            finish(Activity.RESULT_CANCELED, result);
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
        require("true".equals(eval("(()=>{const n=document.querySelector('.nav'),r=n.getBoundingClientRect();return r.top>=0 && r.bottom<=innerHeight+1 && n.querySelectorAll('button').length===6 && [...n.querySelectorAll('button')].every(b=>b.getBoundingClientRect().height>=44)})()")), "Bottom navigation remains inside viewport with touch targets");
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
