package com.shodanrecon.mobile;

import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLException;

import okhttp3.Call;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/** Scoped HTTP transport. Selected credentials are sent only after an authentication challenge. */
public final class CameraHttp {
    private final JSONObject camera;
    private final HttpUrl origin;
    private final String username;
    private final String password;
    private final Set<Call> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final OkHttpClient client;

    public CameraHttp(CameraRepository repository, JSONObject camera) throws Exception {
        this(camera, resolveCredential(repository, camera));
    }

    /** Also permits JVM verification without Android storage or a physical camera. */
    public CameraHttp(JSONObject camera, JSONObject credential) throws Exception {
        if (camera == null) throw new IllegalArgumentException("Select a registered camera first.");
        this.camera = new JSONObject(camera.toString());
        origin = HttpUrl.get(CameraUrls.url(this.camera, "/", 101));
        username = credential == null ? "" : credential.optString("username", "");
        password = credential == null ? "" : credential.optString("password", "");
        rejectSecretControls(username);
        rejectSecretControls(password);
        client = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .authenticator((route, response) -> {
                    if (cancelled.get() || !CameraUrls.sameOrigin(origin, response.request().url())) return null;
                    if (username.isEmpty() && password.isEmpty()) return null;
                    int attempts = responseCount(response);
                    if (attempts >= 3) return null;
                    Challenge challenge = preferredChallenge(response.headers("WWW-Authenticate"));
                    if (challenge == null)
                        throw new IOException("The camera's authentication method is unsupported. Use HTTP Basic or Digest with qop=auth.");
                    // A second retry is allowed only for an explicitly stale Digest nonce.
                    if (response.request().header("Authorization") != null
                            && (!challenge.scheme.equals("Digest") || !"true".equalsIgnoreCase(challenge.parameters.get("stale"))))
                        return null;
                    String authorization = authorization(challenge, response.request().method(),
                            response.request().url(), username, password, 1, randomNonce());
                    // RFC 7616 UTF-8 challenges can contain a non-ASCII realm. Quoted fields
                    // are validated above; do not let header validation echo credentials.
                    return response.request().newBuilder().headers(response.request().headers().newBuilder()
                            .removeAll("Authorization").addUnsafeNonAscii("Authorization", authorization).build()).build();
                })
                .addInterceptor(chain -> {
                    Call call = chain.call();
                    if (cancelled.get()) throw new IOException("Camera task cancelled.");
                    if (!CameraUrls.sameOrigin(origin, chain.request().url()))
                        throw new IOException("Requests to a different camera origin are blocked. Register that camera separately.");
                    try { CameraUrls.rejectCredentialInUrl(chain.request().url(), username, password); }
                    catch (IllegalArgumentException ex) { throw new IOException(ex.getMessage()); }
                    active.add(call);
                    try {
                        if (cancelled.get()) { call.cancel(); throw new IOException("Camera task cancelled."); }
                        Response response = chain.proceed(chain.request());
                        if (response.isRedirect()) {
                            response.close();
                            throw new IOException("Camera redirected the request. Update the saved scheme, port or path; redirects are disabled.");
                        }
                        ResponseBody body = response.body();
                        if (body == null) { active.remove(call); return response; }
                        return response.newBuilder().body(trackedBody(body, call)).build();
                    } catch (IOException | RuntimeException ex) {
                        active.remove(call);
                        throw ex;
                    }
                }).build();
    }

    private static JSONObject resolveCredential(CameraRepository repository, JSONObject camera) throws Exception {
        if (camera == null) return null;
        String id = camera.optString("credentialId", "");
        if (id.isEmpty()) return null;
        JSONObject credential = repository.credential(id);
        if (credential == null) throw new IOException("The selected credential was deleted. Choose a saved credential for this camera.");
        return credential;
    }

    public Response get(String pathOrUrl) throws Exception {
        if (cancelled.get()) throw new IOException("Camera task cancelled.");
        String url = CameraUrls.url(camera, pathOrUrl, 101);
        try {
            return client.newCall(new Request.Builder().url(url).header("Accept", "*/*").get().build()).execute();
        } catch (IOException ex) {
            if (cancelled.get()) throw new IOException("Camera task cancelled.");
            if (ex instanceof SocketTimeoutException)
                throw new IOException("Camera connection timed out. Check the address, port, network and path.");
            if (ex instanceof UnknownHostException)
                throw new IOException("Camera hostname could not be resolved. Check the address and network.");
            if (ex instanceof ConnectException)
                throw new IOException("Camera connection was refused or unreachable. Check its port and network.");
            if (ex instanceof SSLException)
                throw new IOException("HTTPS certificate verification failed. Use a trusted camera certificate matching its hostname.");
            String message = ex.getMessage();
            if (message != null && (message.startsWith("Camera ") || message.startsWith("The camera")
                    || message.startsWith("Requests to ") || message.startsWith("Store ") || message.startsWith("Remove ")
                    || message.startsWith("HTTP Basic ") || message.startsWith("Credential ")))
                throw new IOException(message);
            throw new IOException("Camera network request failed. Check the address, network and camera availability.");
        }
    }

    public void cancel() {
        cancelled.set(true);
        for (Call call : active) call.cancel();
    }

    /** Clones retain origin validation, challenge authentication and cancellation tracking. */
    public OkHttpClient client() { return client; }

    private ResponseBody trackedBody(ResponseBody delegate, Call call) {
        return new ResponseBody() {
            private final BufferedSource tracked = Okio.buffer(new ForwardingSource(delegate.source()) {
                @Override public long read(okio.Buffer sink, long byteCount) throws IOException {
                    try {
                        long count = super.read(sink, byteCount);
                        if (count == -1) active.remove(call);
                        return count;
                    } catch (IOException ex) { active.remove(call); throw ex; }
                }
                @Override public void close() throws IOException {
                    try { super.close(); } finally { active.remove(call); }
                }
            });
            @Override public MediaType contentType() { return delegate.contentType(); }
            @Override public long contentLength() { return delegate.contentLength(); }
            @Override public BufferedSource source() { return tracked; }
        };
    }

    private static int responseCount(Response response) {
        int count = 1;
        while ((response = response.priorResponse()) != null) count++;
        return count;
    }

    static final class Challenge {
        final String scheme;
        final Map<String, String> parameters;
        Challenge(String scheme, Map<String, String> parameters) { this.scheme = scheme; this.parameters = parameters; }
    }

    /** Split challenge schemes and quoted values without treating commas in realm/qop as delimiters. */
    static List<Challenge> parseChallenges(String header) {
        List<Challenge> result = new ArrayList<>();
        if (header == null || header.length() > 16384) return result;
        int position = 0;
        while (position < header.length()) {
            while (position < header.length() && (Character.isWhitespace(header.charAt(position)) || header.charAt(position) == ',')) position++;
            int start = position;
            while (position < header.length() && tokenCharacter(header.charAt(position))) position++;
            if (position == start) { position++; continue; }
            String scheme = header.substring(start, position);
            Map<String, String> parameters = new LinkedHashMap<>();
            while (position < header.length()) {
                while (position < header.length() && (Character.isWhitespace(header.charAt(position)) || header.charAt(position) == ',')) position++;
                int nameStart = position;
                while (position < header.length() && tokenCharacter(header.charAt(position))) position++;
                String name = header.substring(nameStart, position).toLowerCase(Locale.US);
                while (position < header.length() && Character.isWhitespace(header.charAt(position))) position++;
                if (position >= header.length() || header.charAt(position) != '=') { position = nameStart; break; }
                position++;
                while (position < header.length() && Character.isWhitespace(header.charAt(position))) position++;
                StringBuilder value = new StringBuilder();
                if (position < header.length() && header.charAt(position) == '"') {
                    position++;
                    boolean closed = false;
                    while (position < header.length()) {
                        char next = header.charAt(position++);
                        if (next == '"') { closed = true; break; }
                        if (next == '\\' && position < header.length()) next = header.charAt(position++);
                        value.append(next);
                    }
                    if (!closed) return new ArrayList<>();
                } else {
                    while (position < header.length() && header.charAt(position) != ',' && !Character.isWhitespace(header.charAt(position)))
                        value.append(header.charAt(position++));
                }
                if (name.isEmpty() || parameters.containsKey(name)) return new ArrayList<>();
                parameters.put(name, value.toString());
            }
            if (scheme.equalsIgnoreCase("Digest")) result.add(new Challenge("Digest", parameters));
            else if (scheme.equalsIgnoreCase("Basic")) result.add(new Challenge("Basic", parameters));
        }
        return result;
    }

    static Challenge preferredChallenge(List<String> headers) {
        Challenge selected = null;
        int strength = -1;
        for (String header : headers) for (Challenge candidate : parseChallenges(header)) {
            int candidateStrength;
            if (candidate.scheme.equals("Basic")) candidateStrength = 0;
            else {
                String algorithm = candidate.parameters.getOrDefault("algorithm", "MD5").toUpperCase(Locale.US);
                if (!algorithm.matches("MD5(?:-SESS)?|SHA-256(?:-SESS)?")) continue;
                if (candidate.parameters.getOrDefault("nonce", "").isEmpty()) continue;
                String qop = candidate.parameters.get("qop");
                if (qop != null && !hasAuthQop(qop)) continue;
                if ("true".equalsIgnoreCase(candidate.parameters.get("userhash"))) continue;
                candidateStrength = algorithm.startsWith("SHA-256") ? 2 : 1;
            }
            String charset = candidate.parameters.get("charset");
            if (charset != null && !charset.equalsIgnoreCase("UTF-8")) continue;
            if (candidateStrength > strength) { selected = candidate; strength = candidateStrength; }
        }
        return selected;
    }

    static String authorization(Challenge challenge, String method, HttpUrl url, String user, String pass,
                                int nonceCount, String clientNonce) throws IOException {
        rejectSecretControls(user);
        rejectSecretControls(pass);
        Charset charset = "UTF-8".equalsIgnoreCase(challenge.parameters.get("charset"))
                ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
        if (!charset.newEncoder().canEncode(user) || !charset.newEncoder().canEncode(pass))
            throw new IOException("Camera authentication challenge does not support these credential characters. A UTF-8 challenge is required.");
        if (challenge.scheme.equals("Basic")) {
            if (user.contains(":")) throw new IOException("HTTP Basic authentication does not support a colon in the username.");
            return Credentials.basic(user, pass, charset);
        }
        String realm = challenge.parameters.getOrDefault("realm", "");
        String nonce = challenge.parameters.getOrDefault("nonce", "");
        if (nonce.isEmpty()) throw new IOException("The camera sent an invalid Digest challenge.");
        String algorithm = challenge.parameters.getOrDefault("algorithm", "MD5").toUpperCase(Locale.US);
        if (!algorithm.matches("MD5(?:-SESS)?|SHA-256(?:-SESS)?"))
            throw new IOException("The camera uses an unsupported Digest algorithm.");
        String qopList = challenge.parameters.get("qop");
        if (qopList != null && !hasAuthQop(qopList)) throw new IOException("The camera requires unsupported Digest qop. Use qop=auth.");
        String digestAlgorithm = algorithm.startsWith("SHA-256") ? "SHA-256" : "MD5";
        String uri = url.encodedPath() + (url.encodedQuery() == null ? "" : "?" + url.encodedQuery());
        String a1 = hash(digestAlgorithm, user + ":" + realm + ":" + pass, charset);
        if (algorithm.endsWith("-SESS")) a1 = hash(digestAlgorithm, a1 + ":" + nonce + ":" + clientNonce, charset);
        String a2 = hash(digestAlgorithm, method + ":" + uri, charset);
        String nc = String.format(Locale.US, "%08x", nonceCount);
        String response = hash(digestAlgorithm, a1 + ":" + nonce + ":"
                + (qopList == null ? "" : nc + ":" + clientNonce + ":auth:") + a2, charset);
        String userField = user.chars().anyMatch(value -> value > 127) && charset.equals(StandardCharsets.UTF_8)
                ? "username*=UTF-8''" + extendedUser(user) : "username=" + quoted(user);
        StringBuilder header = new StringBuilder("Digest ").append(userField)
                .append(", realm=").append(quoted(realm)).append(", nonce=").append(quoted(nonce))
                .append(", uri=").append(quoted(uri)).append(", response=").append(quoted(response))
                .append(", algorithm=").append(algorithm.equals("MD5-SESS") ? "MD5-sess" : algorithm.equals("SHA-256-SESS") ? "SHA-256-sess" : algorithm);
        if (qopList != null) header.append(", qop=auth, nc=").append(nc).append(", cnonce=").append(quoted(clientNonce));
        else if (algorithm.endsWith("-SESS")) header.append(", cnonce=").append(quoted(clientNonce));
        if (challenge.parameters.containsKey("opaque")) header.append(", opaque=").append(quoted(challenge.parameters.get("opaque")));
        return header.toString();
    }

    private static String hash(String algorithm, String value, Charset charset) throws IOException {
        try {
            byte[] bytes = MessageDigest.getInstance(algorithm).digest(value.getBytes(charset));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format(Locale.US, "%02x", item & 255));
            return result.toString();
        } catch (Exception ex) { throw new IOException("Digest authentication is unavailable on this device."); }
    }

    private static boolean hasAuthQop(String qop) {
        for (String item : qop.split(",")) if (item.trim().equalsIgnoreCase("auth")) return true;
        return false;
    }

    private static String extendedUser(String user) {
        StringBuilder result = new StringBuilder();
        for (byte item : user.getBytes(StandardCharsets.UTF_8)) {
            int value = item & 255;
            if (value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' || value >= '0' && value <= '9'
                    || "!#$&+-.^_`|~".indexOf(value) >= 0) result.append((char) value);
            else result.append(String.format(Locale.US, "%%%02X", value));
        }
        return result.toString();
    }

    private static boolean tokenCharacter(char value) {
        return value >= '0' && value <= '9' || value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                || "!#$%&'*+-.^_`|~".indexOf(value) >= 0;
    }

    private static String quoted(String value) throws IOException {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)))
            throw new IOException("The camera sent an invalid authentication challenge.");
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static void rejectSecretControls(String value) throws IOException {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)))
            throw new IOException("Credential fields cannot contain control characters.");
    }

    private static String randomNonce() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(32);
        for (byte item : bytes) result.append(String.format(Locale.US, "%02x", item & 255));
        return result.toString();
    }
}
