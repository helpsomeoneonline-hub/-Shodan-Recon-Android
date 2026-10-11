package com.shodanrecon.mobile;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import static org.junit.Assert.*;

public class CameraHttpTest {
    private MockWebServer server;
    private JSONObject camera;
    private JSONObject credential;

    @Before public void startCameraFixture() throws Exception {
        server = new MockWebServer(); server.start();
        camera = new JSONObject().put("authorized", true).put("host", server.url("/").host())
                .put("scheme", "http").put("port", server.getPort());
        credential = new JSONObject().put("username", "operator").put("password", "selected-device-password");
    }

    @After public void stopCameraFixture() throws Exception { server.shutdown(); }

    @Test public void basicUsesOnlySelectedCredentialAfterChallenge() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"Camera\""));
        server.enqueue(new MockResponse().setBody("camera snapshot"));
        CameraHttp transport = new CameraHttp(camera, credential);
        try (Response response = transport.get("/image.jpg")) {
            assertEquals(200, response.code()); assertEquals("camera snapshot", response.body().string());
        }
        RecordedRequest first = server.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest second = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(first); assertNotNull(second);
        assertNull(first.getHeader("Authorization"));
        assertEquals(Credentials.basic("operator", "selected-device-password"), second.getHeader("Authorization"));
        assertEquals(first.getPath(), second.getPath());
    }

    @Test public void wrongCredentialEndsAfterOneAuthenticatedRetry() throws Exception {
        for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setResponseCode(401)
                .addHeader("WWW-Authenticate", "Basic realm=\"Camera\""));
        try (Response response = new CameraHttp(camera, credential).get("/image.jpg")) { assertEquals(401, response.code()); }
        assertEquals(2, server.getRequestCount());
    }

    @Test public void anonymousProfileNeverAttemptsAuthentication() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"Camera\""));
        try (Response response = new CameraHttp(camera, null).get("/image.jpg")) { assertEquals(401, response.code()); }
        assertEquals(1, server.getRequestCount());
        assertNull(server.takeRequest(5, TimeUnit.SECONDS).getHeader("Authorization"));
    }

    @Test public void digestMatchesPublishedRfc2617Vector() throws Exception {
        CameraHttp.Challenge challenge = CameraHttp.preferredChallenge(Collections.singletonList(
                "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""));
        String authorization = CameraHttp.authorization(challenge, "GET", HttpUrl.get("http://example.com/dir/index.html"),
                "Mufasa", "Circle Of Life", 1, "0a4f113b");
        assertTrue(authorization.contains("response=\"6629fae49393a05397450978507c4ef1\""));
        assertTrue(authorization.contains("qop=auth, nc=00000001, cnonce=\"0a4f113b\""));
        assertFalse(authorization.contains("Circle Of Life"));
    }

    @Test public void digestChallengeSupportsQuotedCommasAndPrefersSha256() {
        List<CameraHttp.Challenge> challenges = CameraHttp.parseChallenges(
                "Basic realm=\"Camera\", Digest realm=\"Entrance, north\", qop=\"auth,auth-int\", nonce=\"nonce\", algorithm=SHA-256");
        assertEquals(2, challenges.size());
        CameraHttp.Challenge preferred = CameraHttp.preferredChallenge(Collections.singletonList(
                "Basic realm=\"Camera\", Digest realm=\"Entrance, north\", qop=\"auth,auth-int\", nonce=\"nonce\", algorithm=SHA-256"));
        assertNotNull(preferred); assertEquals("Digest", preferred.scheme);
        assertEquals("Entrance, north", preferred.parameters.get("realm"));
        assertEquals("SHA-256", preferred.parameters.get("algorithm"));
    }

    @Test public void unsupportedDigestFailsClearlyWithoutAdditionalAttempts() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate",
                "Digest realm=\"Camera\", nonce=\"nonce\", qop=\"auth-int\""));
        IOException failure = assertThrows(IOException.class, () -> new CameraHttp(camera, credential).get("/image.jpg"));
        assertTrue(failure.getMessage().contains("unsupported")); assertEquals(1, server.getRequestCount());
        assertTrue(failure instanceof CameraHttp.StatusException);
        assertEquals(401, ((CameraHttp.StatusException) failure).statusCode);
        assertFalse(failure.getMessage().contains("selected-device-password"));
    }

    @Test public void redirectCannotForwardCredentialsToAnotherOrigin() throws Exception {
        MockWebServer other = new MockWebServer(); other.start();
        try {
            server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"Camera\""));
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", other.url("/image.jpg")));
            IOException failure = assertThrows(IOException.class, () -> new CameraHttp(camera, credential).get("/image.jpg"));
            assertTrue(failure.getMessage().contains("redirect")); assertEquals(2, server.getRequestCount());
            assertTrue(failure instanceof CameraHttp.StatusException);
            assertEquals(302, ((CameraHttp.StatusException) failure).statusCode);
            assertEquals(0, other.getRequestCount());
        } finally { other.shutdown(); }
    }

    @Test public void mediaClientRetainsOriginConfinement() throws Exception {
        CameraHttp transport = new CameraHttp(camera, credential);
        assertThrows(IOException.class, () -> transport.client().newCall(
                new Request.Builder().url("http://example.com/stream.ts").build()).execute());
        assertEquals(0, server.getRequestCount());
    }

    @Test public void cancellationInterruptsAnActiveRequestAndPreventsRestart() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        CameraHttp transport = new CameraHttp(camera, credential);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> outcome = executor.submit(() -> {
                try (Response ignored = transport.get("/image.jpg")) { return "unexpected success"; }
                catch (IOException failure) { return failure.getMessage(); }
            });
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
            transport.cancel();
            assertEquals("Camera task cancelled.", outcome.get(5, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> transport.get("/image.jpg"));
            assertEquals(1, server.getRequestCount());
        } finally { executor.shutdownNow(); }
    }

    @Test public void matchesIndependentSha256AndSessionDigestVectors() throws Exception {
        String[] algorithms = {"MD5-sess", "SHA-256", "SHA-256-sess"};
        String[] expected = {"8e3825c57e897f5a0dec6c2d4e5059d0",
                "5abdd07184ba512a22c53f41470e5eea7dcaa3a93a59b630c13dfe0a5dc6e38b",
                "b8822e12417cb7750f4e2b8515f0dcf25b7dd26993e80bee1426201446a7f59b"};
        for (int i = 0; i < algorithms.length; i++) {
            CameraHttp.Challenge challenge = CameraHttp.parseChallenges("Digest realm=\"testrealm@host.com\", "
                    + "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", qop=\"auth\", algorithm=" + algorithms[i]).get(0);
            String header = CameraHttp.authorization(challenge, "GET", HttpUrl.get("http://camera.example/dir/index.html"),
                    "Mufasa", "Circle Of Life", 1, "0a4f113b");
            assertTrue(header.contains("response=\"" + expected[i] + "\""));
        }
    }

    @Test public void supportsLegacyDigestWithoutQop() throws Exception {
        CameraHttp.Challenge challenge = CameraHttp.parseChallenges("Digest realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\"").get(0);
        String header = CameraHttp.authorization(challenge, "GET", HttpUrl.get("http://camera.example/dir/index.html"),
                "Mufasa", "Circle Of Life", 1, "unused");
        assertTrue(header.contains("response=\"670fd8c2df070c60b045671b8b24ff02\""));
        assertFalse(header.contains("qop="));
    }

    @Test public void basicUtf8CharsetUsesChallengeCharset() throws Exception {
        CameraHttp.Challenge challenge = CameraHttp.parseChallenges("Basic realm=\"camera\", charset=\"UTF-8\"").get(0);
        assertEquals(Credentials.basic("café", "päss", java.nio.charset.StandardCharsets.UTF_8),
                CameraHttp.authorization(challenge, "GET", server.url("/"), "café", "päss", 1, "unused"));
    }

    @Test public void digestUtf8EncodesNonAsciiUsernameWithoutExposingPassword() throws Exception {
        CameraHttp.Challenge challenge = CameraHttp.parseChallenges("Digest realm=\"cam\", nonce=\"nonce\", qop=auth, charset=UTF-8").get(0);
        String header = CameraHttp.authorization(challenge, "GET", server.url("/picture"), "café", "päss", 1, "sample");
        assertTrue(header.startsWith("Digest username*=UTF-8''caf%C3%A9,"));
        assertFalse(header.contains("päss"));
    }

    @Test public void parsesEscapedQuotesAndRejectsMalformedOrUnsupportedChallenges() {
        CameraHttp.Challenge challenge = CameraHttp.parseChallenges("Digest realm=\"front, \\\"door\\\"\", nonce=\"a=b\"").get(0);
        assertEquals("front, \"door\"", challenge.parameters.get("realm"));
        assertEquals("a=b", challenge.parameters.get("nonce"));
        assertNull(CameraHttp.preferredChallenge(List.of("Digest nonce=\"x\", algorithm=SHA-512")));
        assertNull(CameraHttp.preferredChallenge(List.of("Digest nonce=\"x\", userhash=true")));
        assertTrue(CameraHttp.parseChallenges("Digest realm=\"unclosed").isEmpty());
        assertTrue(CameraHttp.parseChallenges("Digest nonce=\"one\", nonce=\"two\"").isEmpty());
    }

    @Test public void permitsOneStaleNonceRetryAndBoundsDigestAttempts() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Digest realm=\"cam\", nonce=\"first\", qop=auth"));
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Digest realm=\"cam\", nonce=\"second\", qop=auth, stale=true"));
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Digest realm=\"cam\", nonce=\"third\", qop=auth, stale=true"));
        try (Response response = new CameraHttp(camera, credential).get("/picture")) { assertEquals(401, response.code()); }
        assertEquals(3, server.getRequestCount());
    }

    @Test public void digestChallengeUsesEncodedRequestTargetAndHashedPassword() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate",
                "Digest realm=\"cam\", nonce=\"nonce\", qop=auth, algorithm=SHA-256"));
        server.enqueue(new MockResponse().setBody("image"));
        try (Response response = new CameraHttp(camera, credential).get("/picture%20one?channel=101")) { assertEquals(200, response.code()); }
        assertNull(server.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        String header = request.getHeader("Authorization");
        CameraHttp.Challenge sent = CameraHttp.parseChallenges(header).get(0);
        assertEquals("/picture%20one?channel=101", sent.parameters.get("uri"));
        assertFalse(header.contains("selected-device-password"));
        assertEquals(64, sent.parameters.get("response").length());
    }

    @Test public void rejectsSelectedSecretInArbitraryQueryValueWithoutLeakingIt() throws Exception {
        IOException error = assertThrows(IOException.class,
                () -> new CameraHttp(camera, credential).get("/picture?value=selected-device-password"));
        assertFalse(error.getMessage().contains("selected-device-password"));
        assertEquals(0, server.getRequestCount());
    }
}
