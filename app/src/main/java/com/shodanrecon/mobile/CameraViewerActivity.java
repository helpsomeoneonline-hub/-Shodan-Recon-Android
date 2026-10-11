package com.shodanrecon.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.HttpAuthHandler;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.Log;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;

/** Camera-only Activity. No dashboard JavaScript bridge is ever exposed to device pages. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class CameraViewerActivity extends Activity {
    private static final int BG = 0xff03101e, PANEL = 0xff0b2232, CYAN = 0xff40def4;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final String[] METHODS = {"http", "snapshot", "mjpeg", "hls", "rtsp"};
    private static final String[] LABELS = {"HTTP / HTTPS interface", "JPEG snapshot", "MJPEG stream", "HLS stream", "RTSP stream"};
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicBoolean framePending = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Long> positions = new HashMap<>();
    private CameraRepository repository;
    private JSONObject camera;
    private String method = "http", diagnostic = "Choose a viewing method and tap Connect.";
    private int channel = 101;
    private boolean expanded, active, destroyed, browserFailed;
    private volatile CameraHttp transport;
    private volatile Call streamCall;
    private volatile Response streamResponse;
    private Future<?> task;
    private ExoPlayer player;
    private PlayerView playerView;
    private WebView browser;
    private volatile HttpUrl browserOrigin;
    private Runnable testTimeout;
    private Bitmap latestImage;
    private FrameLayout viewer;
    private ImageView image;
    private TextView status;
    private Button external;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        // Media3 errors can contain the source URI. Authenticated RTSP URIs stay in memory only.
        Log.setLogLevel(Log.LOG_LEVEL_OFF);
        repository = new CameraRepository(this);
        try {
            camera = repository.camera(getIntent().getStringExtra("CAMERA_ID"));
            if (camera == null || !camera.optBoolean("authorized", false))
                throw new Exception("Select an explicitly authorized camera first.");
            String requested = saved == null ? getIntent().getStringExtra("METHOD") : saved.getString("method");
            for (String option : METHODS) if (option.equalsIgnoreCase(requested)) method = option;
            if (saved != null) {
                channel = saved.getInt("channel", 101);
                expanded = saved.getBoolean("expanded");
                for (String option : METHODS) positions.put(option, saved.getLong("position_" + option, 0));
                diagnostic = "Viewing configuration restored. Tap Connect to resume.";
            }
            buildLayout();
        } catch (Exception e) {
            TextView error = label("Camera configuration unavailable. Return to Cameras and select an authorized profile.", 18);
            error.setPadding(dp(24), dp(24), dp(24), dp(24));
            error.setBackgroundColor(BG);
            setContentView(error);
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(Color.WHITE);
        return view;
    }
    private Button button(String text, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(text); button.setAllCaps(false); button.setTextColor(CYAN);
        button.setMinHeight(dp(48)); button.setOnClickListener(action);
        return button;
    }
    private void detach(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    private void buildLayout() {
        if (playerView != null) playerView.setPlayer(null);
        detach(browser);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); root.setFitsSystemWindows(true);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL); heading.setPadding(dp(8), dp(4), dp(8), dp(4));
        heading.addView(button("Back", v -> finish()), new LinearLayout.LayoutParams(dp(80), dp(48)));
        TextView title = label(camera.optString("name", "Camera") + " · " + camera.optString("host", ""), 17);
        title.setMaxLines(2); title.setPadding(dp(8), 0, dp(8), 0);
        heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        heading.addView(button(expanded ? "Controls" : "Expand", v -> { expanded = !expanded; buildLayout(); }),
                new LinearLayout.LayoutParams(dp(100), dp(48)));
        root.addView(heading);

        LinearLayout content = new LinearLayout(this);
        boolean wide = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                || getResources().getConfiguration().screenWidthDp >= 600;
        content.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        ScrollView controls = new ScrollView(this);
        controls.setBackgroundColor(PANEL);
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(12), dp(8), dp(12), dp(8)); controls.addView(form);
        form.addView(label("Viewing method", 14));
        Spinner methods = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        methods.setAdapter(adapter);
        int chosen = 0; for (int i = 0; i < METHODS.length; i++) if (METHODS[i].equals(method)) chosen = i;
        methods.setSelection(chosen);
        form.addView(methods, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        form.addView(label("Snapshot / stream channel", 14));
        Spinner channels = new Spinner(this);
        JSONArray configured = camera.optJSONArray("channels");
        java.util.ArrayList<Integer> values = new java.util.ArrayList<>();
        if (configured != null) for (int i = 0; i < configured.length(); i++) {
            int value = configured.optInt(i, 0); if (value > 0 && !values.contains(value)) values.add(value);
        }
        if (values.isEmpty()) for (int i = 1; i <= 16; i++) values.add(i * 100 + 1);
        if (!values.contains(channel)) channel = values.get(0);
        ArrayAdapter<Integer> channelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        channelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        channels.setAdapter(channelAdapter); channels.setSelection(values.indexOf(channel));
        form.addView(channels, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        channels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int next = values.get(position);
                if (next != channel) { stopTransport(); channel = next; setStatus("Channel selected. Tap Connect to open it."); }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        LinearLayout actions = new LinearLayout(this);
        actions.addView(button("Connect", v -> connect(false)), new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("Test", v -> connect(true)), new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("Stop", v -> { stopTransport(); setStatus("Stopped. Saved camera settings are retained."); }),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        form.addView(actions);
        external = button(method.equals("http") ? "Open interface in external browser" : "Open RTSP in external player", v -> externalPlayer()); form.addView(external);
        external.setVisibility(method.equals("rtsp") || method.equals("http") ? View.VISIBLE : View.GONE);
        status = label(diagnostic, 14); status.setTextColor(CYAN); status.setPadding(0, dp(10), 0, dp(8));
        form.addView(status);
        if (!expanded) {
            content.addView(controls, wide
                    ? new LinearLayout.LayoutParams(dp(290), ViewGroup.LayoutParams.MATCH_PARENT)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(255)));
        }
        viewer = new FrameLayout(this); viewer.setBackgroundColor(Color.BLACK);
        content.addView(viewer, wide
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        methods.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String next = METHODS[position];
                if (!next.equals(method)) {
                    stopTransport(); method = next;
                    external.setVisibility(method.equals("rtsp") || method.equals("http") ? View.VISIBLE : View.GONE);
                    external.setText(method.equals("http") ? "Open interface in external browser" : "Open RTSP in external player");
                    setStatus("Method selected. Tap Connect; saved paths and credentials are retained.");
                    showSurface();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        showSurface();
    }

    private void showSurface() {
        viewer.removeAllViews();
        if (method.equals("http")) {
            if (browser == null) browser = createBrowser();
            detach(browser); viewer.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        } else if (method.equals("rtsp") || method.equals("hls")) {
            playerView = new PlayerView(this); playerView.setKeepScreenOn(active);
            playerView.setPlayer(player); viewer.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        } else {
            image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription("Camera image"); image.setImageBitmap(latestImage);
            viewer.addView(image, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private void setStatus(String message) {
        if (message != null && message.matches("(?is).*(?:failed|failure|timed out|timeout|blocked|denied|error|HTTP [45][0-9]{2}|certificate|unavailable).*")) {
            new DiagnosticLog(this).add("viewer", "status", DiagnosticLog.errorCode(message));
        }
        diagnostic = message;
        if (status != null) status.setText(message);
    }
    private void postStatus(int token, String message) {
        runOnUiThread(() -> { if (!destroyed && generation.get() == token) setStatus(message); });
    }
    private String path() {
        switch (method) {
            case "snapshot": return camera.optString("snapshotPath", "/ISAPI/Streaming/channels/{channel}/picture");
            case "mjpeg": return camera.optString("mjpegPath", "");
            case "hls": return camera.optString("hlsPath", "");
            case "rtsp": return camera.optString("rtspPath", "/Streaming/Channels/{channel}");
            default: return camera.optString("httpPath", "/");
        }
    }

    private void connect(boolean test) {
        new DiagnosticLog(this).add("viewer", test ? "test" : "connect", DiagnosticLog.atom(method));
        stopTransport();
        if (path().trim().isEmpty()) { setStatus("Set a " + method.toUpperCase() + " path in the camera profile first."); return; }
        active = true;
        int token = generation.get();
        setStatus(test ? "Testing selected method…" : "Connecting…");
        if (test) {
            testTimeout = () -> {
                if (generation.get() != token || destroyed) return;
                stopTransport(); setStatus("Connection test timed out after 30 seconds. Check network access, service path and supported media.");
            };
            main.postDelayed(testTimeout, 30000);
        }
        try {
            if (method.equals("rtsp") || method.equals("hls")) { startPlayer(test, token); return; }
            String url = CameraUrls.url(camera, path(), channel);
            transport = new CameraHttp(repository, camera);
            CameraHttp current = transport;
            if (method.equals("http") && !test) {
                browserOrigin = HttpUrl.get(url); browserFailed = false;
                browser.onResume(); browser.loadUrl(url); return;
            }
            if (method.equals("mjpeg") && !test) {
                startMjpeg(current, url, token); return;
            }
            final boolean snapshot = method.equals("snapshot");
            task = worker.submit(() -> {
                try (Response response = current.get(url)) {
                    if (!response.isSuccessful()) throw new CameraStatusException(response.code());
                    if (response.body() == null) throw new IOException("Empty reply");
                    if (snapshot) {
                        byte[] bytes = readBounded(response.body().byteStream(), MAX_IMAGE_BYTES);
                        Bitmap bitmap = decodeImage(bytes);
                        showImage(token, bitmap);
                        postStatus(token, test ? "Snapshot connection passed: a valid image was decoded."
                                : "Snapshot displayed. Use Cameras → Capture to save images to the gallery.");
                    } else {
                        // Verify an authenticated response without downloading an unbounded live stream.
                        response.body().byteStream().read(new byte[1024]);
                        String type = response.header("Content-Type", "unknown").split(";")[0];
                        postStatus(token, "Connection passed: HTTP " + response.code() + ", " + safeContentType(type) + ".");
                    }
                } catch (Exception e) { postStatus(token, failure(e)); }
                finally {
                    if (test) runOnUiThread(() -> { if (generation.get() == token) { cancelTestTimeout(); active = false; } });
                }
            });
        } catch (Exception e) { stopTransport(); setStatus(failure(e)); }
    }

    private WebView createBrowser() {
        WebView web = new WebView(this); web.setBackgroundColor(BG);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true); settings.setSaveFormData(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (sameOrigin(request.getUrl().toString())) return false;
                setStatus("Camera page link blocked: only this camera's HTTP / HTTPS origin is allowed.");
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                final int token = generation.get();
                String url = request.getUrl().toString();
                if (!sameOrigin(url)) return blockedResource("Cross-origin camera resource blocked", 403);
                if (!"GET".equals(request.getMethod())) {
                    browserError(token, "Camera browser supports read-only GET pages. Open the external browser for camera login forms or configuration changes; saved credentials are never passed externally.");
                    return blockedResource("Use the external browser for this camera form", 405);
                }
                CameraHttp current = transport;
                if (current == null) return blockedResource("Camera viewing stopped", 403);
                try {
                    // Intercept every GET: WebView does not expose a redirected subresource's new URL.
                    // Scoped OkHttp disables redirects, so an origin-changing redirect receives no credential.
                    Request.Builder outgoing = new Request.Builder().url(url).get();
                    for (String header : new String[]{"Accept", "Accept-Language", "User-Agent", "Range"}) {
                        String value = request.getRequestHeaders().get(header);
                        if (value != null) outgoing.header(header, value);
                    }
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null && !cookies.isEmpty()) outgoing.header("Cookie", cookies);
                    Response response = current.client().newCall(outgoing.build()).execute();
                    if (response.body() == null) { response.close(); return blockedResource("Empty camera reply", 502); }
                    if (!response.isSuccessful()) browserError(token, httpFailure(response.code()));
                    Map<String, String> headers = new HashMap<>();
                    for (String name : response.headers().names()) {
                        if (!name.equalsIgnoreCase("Set-Cookie")) headers.put(name, response.header(name, ""));
                    }
                    // A device page's JavaScript cannot start remote connections or load external frames.
                    headers.put("Content-Security-Policy", "default-src 'self' data: blob: 'unsafe-inline' 'unsafe-eval'; connect-src 'self'; frame-src 'self'; form-action 'none'; object-src 'none'");
                    for (String cookie : response.headers("Set-Cookie")) CookieManager.getInstance().setCookie(url, cookie);
                    MediaType type = response.body().contentType();
                    String mime = type == null ? "application/octet-stream" : type.type() + "/" + type.subtype();
                    String charset = type == null || type.charset() == null ? "UTF-8" : type.charset().name();
                    InputStream body = new FilterInputStream(response.body().byteStream()) {
                        private long count;
                        @Override public int read() throws IOException {
                            int value = super.read(); if (value != -1) limit(1); return value;
                        }
                        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                            int count = in.read(bytes, offset, length); if (count > 0) limit(count); return count;
                        }
                        private void limit(int read) throws IOException {
                            count += read;
                            if (count > 16L * 1024 * 1024) {
                                close(); throw new IOException("Camera web resource exceeds 16 MiB");
                            }
                        }
                        @Override public void close() throws IOException { try { super.close(); } finally { response.close(); } }
                    };
                    return new WebResourceResponse(mime, charset, response.code(), response.message().isEmpty() ? "Camera reply" : response.message(), headers, body);
                } catch (Exception e) {
                    browserError(token, failure(e));
                    return blockedResource("Camera request failed; review the connection diagnostic", 502);
                }
            }
            @Override public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                handler.cancel(); browserFailed = true;
                setStatus("Camera authentication must use the scoped connection. Check the selected saved credential and configured origin.");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                browserFailed = true; handler.cancel(); setStatus("TLS certificate validation failed. Use a camera certificate trusted by Android.");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) { browserFailed = true; setStatus("Camera interface failed to load (WebView error " + error.getErrorCode() + "). Check its address, path, network and certificate."); }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) { browserFailed = true; setStatus(httpFailure(response.getStatusCode())); }
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!browserFailed && sameOrigin(url)) setStatus("Read-only camera page loaded with scoped Basic / Digest authentication. Use the external browser for POST forms. Camera web plugins may require JPEG, MJPEG, HLS or RTSP viewing.");
            }
        });
        return web;
    }
    private static WebResourceResponse blockedResource(String text, int code) {
        return new WebResourceResponse("text/plain", "UTF-8", code, "Camera resource blocked", java.util.Collections.emptyMap(),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    private void browserError(int token, String text) {
        runOnUiThread(() -> {
            if (!destroyed && generation.get() == token) { browserFailed = true; setStatus(text); }
        });
    }

    private boolean sameOrigin(String url) {
        try {
            HttpUrl candidate = HttpUrl.parse(url);
            return browserOrigin != null && candidate != null && candidate.username().isEmpty() && candidate.password().isEmpty()
                    && browserOrigin.scheme().equals(candidate.scheme()) && browserOrigin.host().equals(candidate.host())
                    && browserOrigin.port() == candidate.port();
        } catch (Exception e) { return false; }
    }

    private void startPlayer(boolean test, int token) throws Exception {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player); playerView.setKeepScreenOn(!test);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (generation.get() != token || destroyed) return;
                if (state == Player.STATE_READY) {
                    setStatus(test ? "Stream connection passed: Media3 prepared compatible media. Tap Connect to play."
                            : "Stream ready. Playback depends on the camera's codec and Android device decoder.");
                    if (test) stopTransport();
                } else if (state == Player.STATE_BUFFERING) setStatus("Connecting / buffering stream…");
                else if (state == Player.STATE_ENDED) setStatus("Stream ended.");
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (generation.get() != token || destroyed) return;
                setStatus("Stream failed (" + error.getErrorCodeName() + "). Check the selected credential, stream path, network and codec. RTSP also offers an external player.");
                stopTransport();
            }
        });
        if (method.equals("rtsp")) {
            String url = rtspUrl(true);
            Uri rtsp = Uri.parse(url);
            player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
                    .setSocketFactory(new ScopedRtspSocketFactory(rtsp.getHost(), rtsp.getPort()))
                    .setTimeoutMs(15000).setDebugLoggingEnabled(false).createMediaSource(MediaItem.fromUri(url)));
        } else {
            String url = CameraUrls.url(camera, path(), channel);
            transport = new CameraHttp(repository, camera);
            OkHttpDataSource.Factory source = new OkHttpDataSource.Factory(transport.client());
            player.setMediaSource(new HlsMediaSource.Factory(source).createMediaSource(MediaItem.fromUri(url)));
            long position = positions.containsKey(method) ? positions.get(method) : 0;
            if (position > 0) player.seekTo(position);
        }
        player.setPlayWhenReady(!test); player.prepare();
    }

    private String rtspUrl(boolean includeCredential) throws Exception {
        // Normalize a relative path or an absolute RTSP URL to the registered host and RTSP port.
        int port = camera.optInt("rtspPort", 554);
        if (port < 1 || port > 65535) throw new IOException("Invalid RTSP port");
        JSONObject origin = new JSONObject(camera.toString()).put("scheme", "http").put("port", port);
        String configured = camera.optString("rtspPath", "/Streaming/Channels/{channel}");
        if (configured.startsWith("rtsp://")) configured = "http://" + configured.substring(7);
        HttpUrl normalized = HttpUrl.get(CameraUrls.url(origin, configured, channel));
        String host = normalized.host();
        String authority = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        if (includeCredential) {
            JSONObject credential = repository.credential(camera.optString("credentialId", ""));
            if (credential != null) {
                CameraUrls.rejectCredentialInUrl(normalized, credential.optString("username", ""), credential.optString("password", ""));
                // Media3 parses user information at the first colon; colons in usernames are unsupported.
                if (credential.optString("username", "").contains(":")) throw new IOException("RTSP username contains unsupported colon");
                authority = Uri.encode(credential.optString("username", "")) + ":"
                        + Uri.encode(credential.optString("password", "")) + "@" + authority;
            }
        }
        return "rtsp://" + authority + normalized.encodedPath()
                + (normalized.encodedQuery() == null ? "" : "?" + normalized.encodedQuery());
    }

    private void externalPlayer() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(method.equals("http") ? CameraUrls.url(camera, path(), channel) : rtspUrl(false)));
            startActivity(intent);
            setStatus("Opened external application without credentials. Enter the authorized camera credential there if requested.");
        } catch (android.content.ActivityNotFoundException e) {
            setStatus(method.equals("http") ? "No external browser is installed. Use the read-only built-in camera interface."
                    : "No external RTSP player is installed. Use the built-in viewer or install a compatible player such as VLC.");
        } catch (Exception e) { setStatus("Check the camera's RTSP host, port and relative stream path."); }
    }

    private void startMjpeg(CameraHttp current, String url, int token) {
        task = worker.submit(() -> {
            try {
                Call call = current.client().newBuilder().readTimeout(15, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS)
                        .build().newCall(new Request.Builder().url(url).get().build());
                streamCall = call;
                if (generation.get() != token) { call.cancel(); return; }
                try (Response response = call.execute()) {
                    streamResponse = response;
                    if (!response.isSuccessful()) throw new CameraStatusException(response.code());
                    if (response.body() == null) throw new IOException("Empty stream");
                    String type = response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT);
                    if (!type.startsWith("multipart/") && !type.startsWith("image/jpeg"))
                        throw new IOException("Expected MJPEG content type");
                    InputStream input = new BufferedInputStream(response.body().byteStream(), 32768);
                    long last = 0; int frames = 0;
                    while (generation.get() == token && !Thread.currentThread().isInterrupted()) {
                        byte[] frame = MjpegFrames.next(input, MAX_IMAGE_BYTES);
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - last < 200 || framePending.get()) continue;
                        Bitmap bitmap = decodeImage(frame); last = now; frames++;
                        showImage(token, bitmap);
                        if (frames == 1) postStatus(token, "MJPEG receiving valid images (display limited to 5 frames/second).");
                    }
                }
            } catch (Exception e) { postStatus(token, failure(e)); }
            finally { streamResponse = null; }
        });
    }

    private void showImage(int token, Bitmap bitmap) {
        framePending.set(true);
        runOnUiThread(() -> {
            try {
                if (generation.get() != token || destroyed) { bitmap.recycle(); return; }
                Bitmap previous = latestImage;
                latestImage = bitmap;
                if (image != null) image.setImageBitmap(bitmap);
                if (previous != null && previous != bitmap && image != null) image.postOnAnimation(() -> {
                    if (previous != latestImage && !previous.isRecycled()) previous.recycle();
                });
            } finally { framePending.set(false); }
        });
    }
    static byte[] readBounded(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384]; int n;
        while ((n = stream.read(buffer)) != -1) {
            if (output.size() + n > limit) throw new IOException("Image exceeds supported size");
            output.write(buffer, 0, n);
        }
        if (output.size() == 0) throw new IOException("Empty image");
        return output.toByteArray();
    }
    private static Bitmap decodeImage(byte[] bytes) throws IOException {
        BitmapFactory.Options dimensions = new BitmapFactory.Options(); dimensions.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, dimensions);
        if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0 || dimensions.outWidth > 32768 || dimensions.outHeight > 32768)
            throw new IOException("Corrupt or unsupported image");
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (Math.max(dimensions.outWidth, dimensions.outHeight) / sample > 1920) sample *= 2;
        options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("Image could not be decoded");
        return bitmap;
    }
    private static String safeContentType(String type) {
        return type.matches("[A-Za-z0-9.+_-]+/[A-Za-z0-9.+_-]+") ? type : "unspecified content type";
    }
    private static String failure(Exception error) {
        if (error instanceof CameraStatusException) return httpFailure(((CameraStatusException) error).status);
        Throwable cause = error;
        for (int i = 0; cause != null && i < 8; i++, cause = cause.getCause()) {
            if (cause instanceof SSLException) return "TLS certificate validation failed. Use a camera certificate trusted by Android.";
            if (cause instanceof SocketTimeoutException) return "Camera connection timed out. Check network access, address, port and stream path.";
            if (cause instanceof UnknownHostException) return "Camera address could not be resolved. Check the configured host and network.";
            if (cause instanceof ConnectException) return "Camera refused the connection. Check the configured service port and local network.";
        }
        String message = error.getMessage();
        if (message != null && (message.startsWith("Camera connection ") || message.startsWith("Camera hostname ")
                || message.startsWith("Camera network ") || message.startsWith("Camera redirected ")
                || message.startsWith("HTTPS certificate verification ") || message.startsWith("The camera ")
                || message.startsWith("The camera's ") || message.startsWith("Requests to a different camera ")
                || message.startsWith("The selected credential was deleted.") || message.startsWith("HTTP Basic authentication ")))
            return message;
        return "Camera request failed. Check the selected credential, service path, connection and supported image format. Reply may be empty, corrupt or exceed 8 MiB.";
    }
    private static String httpFailure(int status) {
        switch (status) {
            case 401: return "HTTP 401: camera authentication failed. Check the manually selected saved credential.";
            case 403: return "HTTP 403: the camera denied access to this resource.";
            case 404: return "HTTP 404: this stream or snapshot path was not found. Edit the camera profile.";
            default: return "Camera returned HTTP " + status + ". Check the camera's service and configured path.";
        }
    }
    private static final class CameraStatusException extends IOException {
        final int status;
        CameraStatusException(int status) { super("Camera HTTP error"); this.status = status; }
    }

    private void stopTransport() {
        generation.incrementAndGet(); active = false;
        cancelTestTimeout();
        if (transport != null) { transport.cancel(); transport = null; }
        if (streamCall != null) { streamCall.cancel(); streamCall = null; }
        if (streamResponse != null) { streamResponse.close(); streamResponse = null; }
        if (task != null) { task.cancel(true); task = null; }
        if (player != null) {
            positions.put(method, player.getCurrentPosition());
            if (playerView != null) { playerView.setPlayer(null); playerView.setKeepScreenOn(false); }
            player.release(); player = null;
        }
        if (browser != null) {
            browserOrigin = null; browserFailed = true;
            browser.stopLoading(); browser.loadUrl("about:blank");
        }
    }
    private void cancelTestTimeout() {
        if (testTimeout != null) { main.removeCallbacks(testTimeout); testTimeout = null; }
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config); if (camera != null) buildLayout();
    }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (player != null) positions.put(method, player.getCurrentPosition());
        out.putString("method", method); out.putInt("channel", channel); out.putBoolean("expanded", expanded);
        for (String option : METHODS) out.putLong("position_" + option, positions.containsKey(option) ? positions.get(option) : 0);
    }
    @Override public void onStop() {
        boolean wasActive = active;
        stopTransport();
        if (browser != null) browser.onPause();
        if (wasActive) setStatus("Paused while in background. Tap Connect to resume the selected method.");
        super.onStop();
    }
    @Override public void onDestroy() {
        destroyed = true; stopTransport(); worker.shutdownNow();
        if (browser != null) { detach(browser); browser.destroy(); browser = null; }
        super.onDestroy();
    }
}
