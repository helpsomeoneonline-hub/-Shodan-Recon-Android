package com.shodanrecon.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.os.BatteryManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final String STORE = "recon_local_v1";
    private static final String KEY_ALIAS = "shodan_recon_api_v1";
    private static final int REQUEST_IMPORT = 501;
    private static final int REQUEST_EXPORT = 502;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final AtomicBoolean cancelLAN = new AtomicBoolean(false);
    private final AtomicBoolean lanActive = new AtomicBoolean(false);
    private WebView webView;
    private SharedPreferences prefs;
    private String pendingCsv = "";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences(STORE, MODE_PRIVATE);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme()) && "/android_asset/index.html".equals(uri.getPath())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }
        });
        webView.addJavascriptInterface(new Bridge(), "ReconNative");
        webView.setBackgroundColor(0xff03101e);
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void send(String kind, JSONObject payload) {
        String js = "window.receiveNative(" + JSONObject.quote(kind) + "," + payload.toString() + ")";
        runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(js, null); });
    }
    private void sendError(String kind, String message) {
        JSONObject out = new JSONObject();
        try { out.put("ok", false); out.put("error", message == null ? "Unknown error" : message); } catch(Exception ignored) {}
        send(kind, out);
    }
    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry)ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return gen.generateKey();
    }
    private void putApiKey(String input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(input.getBytes(StandardCharsets.UTF_8));
        String saved = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        prefs.edit().putString("apiKey", saved).apply();
    }
    private String readApiKey() throws Exception {
        String saved = prefs.getString("apiKey", "");
        if (saved.isEmpty()) return "";
        String[] bits = saved.split("\\.");
        if (bits.length != 2) throw new Exception("Stored key format is invalid");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, Base64.decode(bits[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(bits[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    private JSONObject shodanRequest(String endpoint, String query, int page) throws Exception {
        String key = readApiKey();
        if (key.isEmpty()) throw new Exception("Enter your Shodan API key in Settings first.");
        String url = "https://api.shodan.io/" + endpoint + "?key=" + URLEncoder.encode(key, "UTF-8");
        if (query != null) url += "&query=" + URLEncoder.encode(query, "UTF-8") + "&page=" + page;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000); conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "application/json");
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder body = new StringBuilder();
        if (stream != null) try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line; while ((line = in.readLine()) != null && body.length() < 1200000) body.append(line);
        }
        conn.disconnect();
        JSONObject json;
        try { json = new JSONObject(body.toString()); } catch(Exception e) { throw new Exception("Unexpected Shodan reply (HTTP " + status + ")"); }
        if (status >= 400) throw new Exception("Shodan HTTP " + status + ": " + json.optString("error", "Request failed"));
        return json;
    }
    private JSONObject summarizeShodan(JSONObject raw) throws Exception {
        JSONObject result = new JSONObject(); result.put("ok", true);
        result.put("total", raw.optInt("total", 0));
        JSONArray matches = new JSONArray();
        JSONArray entries = raw.optJSONArray("matches");
        if (entries != null) for (int i = 0; i < Math.min(entries.length(), 100); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item == null) continue;
            String ip = item.optString("ip_str", "");
            if (ip.isEmpty()) continue;
            JSONObject summary = new JSONObject();
            summary.put("ip", ip);
            summary.put("port", item.optInt("port", 0));
            summary.put("product", item.optString("product", "Unknown"));
            summary.put("org", item.optString("org", ""));
            JSONObject location = item.optJSONObject("location");
            summary.put("country", location == null ? "" : location.optString("country_name", ""));
            summary.put("timestamp", item.optString("timestamp", ""));
            matches.put(summary);
        }
        result.put("matches", matches);
        return result;
    }
    private long parseIPv4(String ip) throws Exception {
        String[] p = ip.trim().split("\\.", -1);
        if (p.length != 4) throw new Exception("Use an IPv4 address such as 192.168.1.1");
        long n = 0;
        for (String x : p) {
            if (!x.matches("[0-9]{1,3}")) throw new Exception("Invalid IPv4 address");
            int part = Integer.parseInt(x);
            if (part > 255) throw new Exception("Invalid IPv4 octet");
            n = (n << 8) | part;
        }
        return n;
    }
    private boolean privateIP(long ip) {
        int a = (int)((ip >>> 24) & 255), b = (int)((ip >>> 16) & 255);
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }
    private String formatIP(long value) {
        return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "." + ((value >>> 8) & 255) + "." + (value & 255);
    }
    // Bounded TCP service inventory for a single authorized private IPv4 /24 network.
    private void doLANScan(String start, String end, String portCSV, int workers) {
        if (!lanActive.compareAndSet(false, true)) {
            sendError("lan-done", "A LAN scan is already in progress"); return;
        }
        cancelLAN.set(false);
        ExecutorService workerPool = null;
        try {
            final long firstIP = parseIPv4(start), lastIP = parseIPv4(end);
            if (lastIP < firstIP || lastIP - firstIP > 255)
                throw new Exception("Range must contain at most 256 addresses");
            if (!privateIP(firstIP) || !privateIP(lastIP) || (firstIP >>> 8) != (lastIP >>> 8))
                throw new Exception("LAN scan supports one authorized private /24 network only");
            java.util.Set<Integer> portsSet = new java.util.HashSet<>();
            for (String part : portCSV.split(",")) {
                if (part.trim().isEmpty()) continue;
                int port = Integer.parseInt(part.trim());
                if (port < 1 || port > 65535) throw new Exception("Ports must be from 1 to 65535");
                portsSet.add(port);
                if (portsSet.size() > 8) throw new Exception("Use at most eight ports");
            }
            if (portsSet.isEmpty()) throw new Exception("Enter at least one port");
            final java.util.List<Integer> ports = new java.util.ArrayList<>(portsSet);
            java.util.Collections.sort(ports);
            final int total = (int)(lastIP - firstIP + 1);
            final java.util.concurrent.atomic.AtomicInteger nextIndex = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger scanned = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger found = new java.util.concurrent.atomic.AtomicInteger();
            int workerCount = Math.max(1, Math.min(8, workers));
            workerPool = Executors.newFixedThreadPool(workerCount);
            java.util.List<java.util.concurrent.Future<?>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < workerCount; i++) {
                tasks.add(workerPool.submit(() -> {
                    while (!cancelLAN.get()) {
                        int position = nextIndex.getAndIncrement();
                        if (position >= total) return;
                        String ip = formatIP(firstIP + position);
                        for (int port : ports) {
                            if (cancelLAN.get()) break;
                            try (Socket socket = new Socket()) {
                                socket.connect(new InetSocketAddress(ip, port), 450);
                                found.incrementAndGet();
                                JSONObject item = new JSONObject();
                                item.put("ok", true); item.put("ip", ip);
                                item.put("port", port); item.put("product", "TCP service");
                                item.put("country", "LAN");
                                item.put("org", "Authorized private network");
                                send("lan-match", item);
                            } catch (Exception ignored) {}
                        }
                        JSONObject progress = new JSONObject();
                        try {
                            progress.put("ok", true);
                            progress.put("scanned", scanned.incrementAndGet());
                            progress.put("found", found.get());
                            progress.put("total", total);
                            send("lan-progress", progress);
                        } catch (Exception ignored) {}
                    }
                }));
            }
            for (java.util.concurrent.Future<?> task : tasks) task.get();
            JSONObject done = new JSONObject();
            done.put("ok", true); done.put("stopped", cancelLAN.get());
            done.put("scanned", scanned.get()); done.put("found", found.get());
            send("lan-done", done);
        } catch(Exception e) { sendError("lan-done", e.getMessage()); }
        finally {
            if (workerPool != null) workerPool.shutdownNow();
            lanActive.set(false);
        }
    }

    public class Bridge {
        @JavascriptInterface public String getState() { return prefs.getString("state", "{}"); }
        @JavascriptInterface public void saveState(String json) {
            if (json != null && json.length() <= 900000) prefs.edit().putString("state", json).apply();
            else sendError("notice", "Saved history is too large. Export results or clear old sessions.");
        }
        @JavascriptInterface public boolean hasApiKey() { return !prefs.getString("apiKey", "").isEmpty(); }
        @JavascriptInterface public void setApiKey(String key) {
            if (key == null || key.length() < 6 || key.length() > 256) { sendError("key", "Enter a valid API key."); return; }
            try { putApiKey(key.trim()); JSONObject j = new JSONObject(); j.put("ok", true); send("key", j); }
            catch (Exception ex) { sendError("key", ex.getMessage()); }
        }
        @JavascriptInterface public void deleteApiKey() {
            prefs.edit().remove("apiKey").apply();
            JSONObject j = new JSONObject(); try { j.put("ok", true); } catch(Exception ignored) {} send("key", j);
        }
        @JavascriptInterface public void apiInfo() {
            pool.submit(() -> { try {
                JSONObject r = shodanRequest("api-info", null, 1);
                JSONObject o = new JSONObject(); o.put("ok", true); o.put("plan", r.optString("plan", ""));
                o.put("queryCredits", r.optInt("query_credits", -1));
                o.put("scanCredits", r.optInt("scan_credits", -1));
                send("api-info", o);
            } catch(Exception ex) { sendError("api-info", ex.getMessage()); } });
        }
        @JavascriptInterface public void searchShodan(String query, int page) {
            if (query == null || query.trim().isEmpty() || query.length() > 400 || page < 1 || page > 5) {
                sendError("search", "Enter a query and use pages 1–5."); return;
            }
            pool.submit(() -> { try { send("search", summarizeShodan(shodanRequest("shodan/host/search", query.trim(), page))); }
                catch(Exception ex) { sendError("search", ex.getMessage()); } });
        }
        @JavascriptInterface public void scanLAN(String start, String end, String ports, int workers) { pool.submit(() -> doLANScan(start, end, ports, workers)); }
        @JavascriptInterface public void stopLAN() { cancelLAN.set(true); }
        @JavascriptInterface public void importIPs() {
            runOnUiThread(() -> {
                Intent in = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                in.addCategory(Intent.CATEGORY_OPENABLE); in.setType("*/*");
                in.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "text/csv", "application/csv"});
                startActivityForResult(in, REQUEST_IMPORT);
            });
        }
        @JavascriptInterface public void exportCsv(String value) {
            if (value == null || value.length() > 2000000) { sendError("notice", "CSV is too large"); return; }
            pendingCsv = value;
            runOnUiThread(() -> {
                Intent in = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                in.addCategory(Intent.CATEGORY_OPENABLE); in.setType("text/csv");
                in.putExtra(Intent.EXTRA_TITLE, "shodan_recon_results.csv");
                startActivityForResult(in, REQUEST_EXPORT);
            });
        }
        @JavascriptInterface public void openHttpCamera(String address, boolean snapshot, String channel) {
            try {
                if (address == null || address.length() > 1024) throw new Exception("Enter a camera URL");
                Uri uri = Uri.parse(address.trim());
                String scheme = uri.getScheme();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    throw new Exception("Use an http:// or https:// camera address");
                if (uri.getUserInfo() != null) throw new Exception("Do not put passwords in the URL");
                String host = uri.getHost();
                if (host == null || !privateIP(parseIPv4(host)))
                    throw new Exception("Use an authorized private-network IPv4 camera address");
                int port = uri.getPort();
                if (port > 65535 || port == 0) throw new Exception("Invalid camera port");
                if (snapshot) {
                    if (channel == null || !channel.matches("[0-9]{3,5}")) throw new Exception("Invalid channel");
                    uri = uri.buildUpon().encodedPath("/ISAPI/Streaming/channels/" + channel + "/picture")
                            .clearQuery().fragment(null).build();
                }
                final Uri destination = uri;
                runOnUiThread(() -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, destination)); }
                    catch(Exception e) { sendError("notice", "No compatible browser installed"); }
                });
            } catch(Exception ex) { sendError("notice", ex.getMessage()); }
        }
        @JavascriptInterface public void openRTSP(String url) {
            if (url == null || !url.startsWith("rtsp://") || url.length() > 2000) { sendError("notice", "Enter a valid rtsp:// URL"); return; }
            runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception ex) { sendError("notice", "Install a compatible RTSP player, such as VLC."); } });
        }
        @JavascriptInterface public String environment() {
            JSONObject j = new JSONObject();
            try {
                ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
                Network n = cm.getActiveNetwork(); NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                j.put("online", caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                BatteryManager bm = (BatteryManager)getSystemService(BATTERY_SERVICE);
                j.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            } catch(Exception ignored) {}
            return j.toString();
        }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_IMPORT) {
            pool.submit(() -> { try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream == null) throw new Exception("Cannot read file");
                byte[] buf = new byte[1024]; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                int len; while ((len = stream.read(buf)) != -1) {
                    out.write(buf, 0, len); if (out.size() > 500000) throw new Exception("Import file too large (500 KB max)");
                }
                JSONObject j = new JSONObject(); j.put("ok", true); j.put("text", out.toString("UTF-8")); send("ip-import", j);
            } catch(Exception ex) { sendError("ip-import", ex.getMessage()); } });
        } else if (requestCode == REQUEST_EXPORT) {
            pool.submit(() -> { try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("Cannot write file");
                out.write(pendingCsv.getBytes(StandardCharsets.UTF_8));
                JSONObject j = new JSONObject(); j.put("ok", true); send("export", j);
            } catch(Exception ex) { sendError("export", ex.getMessage()); } finally { pendingCsv = ""; } });
        }
    }
    @Override protected void onDestroy() {
        cancelLAN.set(true); pool.shutdownNow();
        if (webView != null) { webView.removeJavascriptInterface("ReconNative"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
