package com.shodanrecon.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Credentials;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import static org.junit.Assert.*;

/** Synthetic loopback fixtures only; no Shodan discovery or physical devices. */
public class CameraCredentialCheckTest {
    private MockWebServer server;
    private JSONObject camera;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final JSONArray outcomes = new JSONArray();
    private final List<String> started = new ArrayList<>();
    private CameraHttp active;

    @Before public void setup() throws Exception {
        server = new MockWebServer(); server.start();
        camera = new JSONObject().put("authorized", true).put("host", server.url("/").host())
                .put("scheme", "http").put("port", server.getPort());
    }

    @After public void teardown() throws Exception {
        if (active != null) active.cancel();
        server.shutdown();
    }

    private static JSONObject preset(int number, String password) throws Exception {
        return new JSONObject().put("id", "preset-" + number).put("username", "operator").put("password", password);
    }

    private CameraCredentialCheck.Listener listener() {
        return new CameraCredentialCheck.Listener() {
            @Override public void starting(String id, int index, int total) { started.add(id); }
            @Override public void active(CameraHttp transport) { active = transport; }
            @Override public void finished(String id, String status, int code) throws Exception {
                outcomes.put(new JSONObject().put("credentialId", id).put("status", status).put("httpCode", code));
            }
        };
    }

    private CameraCredentialCheck.Result connect(List<JSONObject> candidates) throws Exception {
        return CameraCredentialCheck.connect(camera, "/picture/101", candidates, cancelled::get, listener());
    }

    private static MockResponse challenge() {
        return new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"Camera\"");
    }

    private void acceptOnly(String password) {
        String authorization = Credentials.basic("operator", password);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return authorization.equals(request.getHeader("Authorization"))
                        ? new MockResponse().setBody("synthetic snapshot fixture") : challenge();
            }
        });
    }

    @Test public void rejectionAdvancesOnceThenReusesWorkingPasswordForSnapshotChannels() throws Exception {
        acceptOnly("working-synthetic-secret");
        CameraCredentialCheck.Result result = connect(List.of(preset(1, "rejected-synthetic-secret"),
                preset(3, "working-synthetic-secret"), preset(6, "unused-synthetic-secret")));
        assertEquals("preset-3", result.workingCredentialId);
        assertEquals(List.of("preset-1", "preset-3"), started);
        assertEquals("authentication-failed", outcomes.getJSONObject(0).getString("status"));
        assertEquals("success", outcomes.getJSONObject(1).getString("status"));
        assertEquals(4, server.getRequestCount());
        for (int i = 0; i < 4; i++) assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
        for (int channel : new int[]{101, 201}) {
            try (Response response = result.transport.get("/picture/" + channel)) { assertEquals(200, response.code()); }
            RecordedRequest challengeRequest = server.takeRequest(2, TimeUnit.SECONDS);
            RecordedRequest authorized = server.takeRequest(2, TimeUnit.SECONDS);
            assertNull(challengeRequest.getHeader("Authorization"));
            assertEquals("/picture/" + channel, authorized.getPath());
            assertEquals(Credentials.basic("operator", "working-synthetic-secret"), authorized.getHeader("Authorization"));
        }
        assertFalse(outcomes.toString().contains("synthetic-secret"));
        assertFalse(outcomes.toString().contains("operator"));
    }

    @Test public void allSixRejectedCandidatesStopAfterSixWithoutExposingPasswords() throws Exception {
        acceptOnly("not-one-of-the-configured-presets");
        List<JSONObject> presets = new ArrayList<>();
        for (int i = 1; i <= 6; i++) presets.add(preset(i, "synthetic-rejected-password-" + i));
        Exception failure = assertThrows(Exception.class, () -> connect(presets));
        assertTrue(failure.getMessage().contains("All 6"));
        assertEquals(6, started.size());
        assertEquals(6, outcomes.length());
        assertEquals(12, server.getRequestCount());
        assertFalse(failure.getMessage().contains("synthetic-rejected-password"));
        assertFalse(outcomes.toString().contains("synthetic-rejected-password"));
    }

    @Test public void deniedLockedRateLimitedAndBadPathsNeverAdvanceToAnotherPreset() throws Exception {
        for (int code : new int[]{403, 423, 429, 404, 503}) {
            int requestsBefore = server.getRequestCount();
            int attemptsBefore = started.size();
            server.enqueue(new MockResponse().setResponseCode(code));
            CameraHttp.StatusException failure = assertThrows(CameraHttp.StatusException.class,
                    () -> connect(List.of(preset(1, "first-synthetic-secret"), preset(2, "second-synthetic-secret"))));
            assertEquals(code, failure.statusCode);
            assertEquals(requestsBefore + 1, server.getRequestCount());
            assertEquals(attemptsBefore + 1, started.size());
        }
    }

    @Test public void unsupportedChallengeStopsWithoutTryingNextPassword() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Bearer realm=\"Camera\""));
        Exception failure = assertThrows(Exception.class,
                () -> connect(List.of(preset(1, "first-synthetic-secret"), preset(2, "second-synthetic-secret"))));
        assertTrue(failure.getMessage().contains("unsupported"));
        assertEquals(List.of("preset-1"), started);
        assertEquals(1, server.getRequestCount());
    }

    @Test public void retryAfterOnUnauthorizedStopsPasswordRotation() throws Exception {
        server.enqueue(challenge());
        server.enqueue(challenge().addHeader("Retry-After", "60"));
        Exception failure = assertThrows(Exception.class,
                () -> connect(List.of(preset(1, "first-synthetic-secret"), preset(2, "second-synthetic-secret"))));
        assertTrue(failure.getMessage().contains("stopped"));
        assertEquals(List.of("preset-1"), started);
        assertEquals(2, server.getRequestCount());
    }

    @Test public void repeatedStaleDigestNonceIsNotTreatedAsPasswordRejection() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Digest realm=\"cam\", nonce=\"one\", qop=auth"));
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Digest realm=\"cam\", nonce=\"two\", qop=auth, stale=true"));
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Digest realm=\"cam\", nonce=\"three\", qop=auth, stale=true"));
        assertThrows(Exception.class,
                () -> connect(List.of(preset(1, "first-synthetic-secret"), preset(2, "second-synthetic-secret"))));
        assertEquals(List.of("preset-1"), started);
        assertEquals(3, server.getRequestCount());
    }

    @Test public void networkFailureStopsPasswordRotation() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        assertThrows(Exception.class,
                () -> connect(List.of(preset(1, "first-synthetic-secret"), preset(2, "second-synthetic-secret"))));
        assertEquals(List.of("preset-1"), started);
        assertEquals("unreachable", outcomes.getJSONObject(0).getString("status"));
    }

    @Test public void unprotectedEndpointNeverClaimsItVerifiedAPassword() throws Exception {
        server.enqueue(new MockResponse().setBody("public snapshot"));
        CameraCredentialCheck.Result result = connect(List.of(preset(1, "first-synthetic-secret"), preset(2, "second-synthetic-secret")));
        assertEquals("", result.workingCredentialId);
        assertEquals(List.of("preset-1"), started);
        assertNull(server.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
    }

    @Test public void cancellationAfterRejectionPreventsNextCandidate() throws Exception {
        server.enqueue(challenge()); server.enqueue(challenge());
        CameraCredentialCheck.Listener observer = listener();
        Exception failure = assertThrows(Exception.class, () -> CameraCredentialCheck.connect(camera, "/picture/101",
                List.of(preset(1, "first-synthetic-secret"), preset(2, "second-synthetic-secret")), cancelled::get,
                new CameraCredentialCheck.Listener() {
                    @Override public void starting(String id, int index, int total) throws Exception { observer.starting(id, index, total); }
                    @Override public void active(CameraHttp transport) throws Exception { observer.active(transport); }
                    @Override public void finished(String id, String status, int code) throws Exception {
                        observer.finished(id, status, code); cancelled.set(true);
                    }
                }));
        assertTrue(failure.getMessage().contains("cancelled"));
        assertEquals(List.of("preset-1"), started);
        assertEquals(2, server.getRequestCount());
    }

    @Test public void unauthorizedCameraAndUnboundedOrMixedCandidatesFailBeforeRequests() throws Exception {
        camera.put("authorized", false);
        assertThrows(Exception.class, () -> connect(List.of(preset(1, "synthetic-secret"))));
        camera.put("authorized", true);
        assertThrows(Exception.class, () -> connect(List.of(preset(2, "second-synthetic-secret"), preset(1, "first-synthetic-secret"))));
        assertThrows(Exception.class, () -> connect(List.of(preset(1, "synthetic-secret").put("id", "legacy-credential"))));
        assertThrows(Exception.class, () -> connect(List.of(preset(1, "synthetic-secret"), preset(2, "other-secret").put("username", "another-operator"))));
        List<JSONObject> tooMany = new ArrayList<>();
        for (int i = 1; i <= 7; i++) tooMany.add(preset(i, "synthetic-secret-" + i));
        assertThrows(Exception.class, () -> connect(tooMany));
        assertEquals(0, server.getRequestCount());
        assertTrue(started.isEmpty());
    }
}
