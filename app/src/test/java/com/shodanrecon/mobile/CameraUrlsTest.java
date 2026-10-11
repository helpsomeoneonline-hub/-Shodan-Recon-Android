package com.shodanrecon.mobile;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class CameraUrlsTest {
    private JSONObject camera() throws Exception {
        return new JSONObject().put("authorized", true).put("scheme", "http").put("host", "192.168.1.12").put("port", 80);
    }
    private void invalid(JSONObject camera, String path) {
        try { CameraUrls.url(camera, path, 101); fail("Unsafe camera URL accepted"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }
    @Test public void substitutesSelectedChannelAndKeepsQuery() throws Exception {
        assertEquals("http://192.168.1.12/ISAPI/Streaming/channels/1601/picture?size=full",
                CameraUrls.url(camera(), "/ISAPI/Streaming/channels/{channel}/picture?size=full", 1601));
    }
    @Test public void resolvesRelativePathsAndDefaults() throws Exception {
        assertEquals("http://192.168.1.12/", CameraUrls.url(camera(), "", 101));
        assertEquals("http://192.168.1.12/snapshot.jpg", CameraUrls.url(camera(), "snapshot.jpg", 101));
    }
    @Test public void supportsHttpsNonstandardPortAndIpv6() throws Exception {
        JSONObject c = camera().put("scheme", "https").put("host", "[2001:db8::1]").put("port", 8443);
        assertEquals("https://[2001:db8::1]:8443/picture", CameraUrls.url(c, "/picture", 101));
    }
    @Test public void fullUrlTemplatesInsertTheRegisteredOriginAndSelectedChannel() throws Exception {
        JSONObject c = camera().put("scheme", "https").put("port", 8443);
        assertEquals("https://192.168.1.12:8443/cameras/1601/snapshot.jpg",
                CameraUrls.url(c, "{scheme}://{host}:{port}/cameras/{channel}/snapshot.jpg", 1601));
        assertEquals("https://192.168.1.12:8443/cameras/101/snapshot.jpg",
                CameraUrls.url(c, "{scheme}://{ip}:{port}/cameras/{channel}/snapshot.jpg", 101));
        assertEquals("https://[2001:db8::1]:8443/cameras/101/snapshot.jpg",
                CameraUrls.url(c.put("host", "2001:db8::1"), "{scheme}://{host}:{port}/cameras/{channel}/snapshot.jpg", 101));
    }
    @Test public void substitutedTemplatesStillRejectOriginChangesAndCredentials() throws Exception {
        invalid(camera(), "{scheme}://elsewhere.example:{port}/picture");
        invalid(camera(), "https://{host}:{port}/picture");
        invalid(camera(), "{scheme}://admin:secret@{ip}:{port}/picture");
        invalid(camera(), "{scheme}://{host}:{port}/picture?password=secret");
        invalid(camera(), "/picture/{username}");
        invalid(camera(), "/picture/{password}");
        try { CameraUrls.url(camera(), "/picture/{channel}", 0); fail("Invalid channel accepted"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }
    @Test public void canonicalizesDomainButChecksOrigin() throws Exception {
        JSONObject c = camera().put("host", "CAMERA.example");
        assertEquals("http://camera.example/path", CameraUrls.url(c, "http://camera.example:80/path", 101));
        invalid(c, "https://camera.example/path");
        invalid(c, "http://camera.example:81/path");
        invalid(c, "http://elsewhere.example/path");
    }
    @Test public void rejectsHostAndAuthorityTricks() throws Exception {
        invalid(camera(), "//192.168.1.12/path");
        invalid(camera(), "http://user:secret@192.168.1.12/path");
        invalid(camera(), "http://192.168.1.12@evil.example/path");
        invalid(camera(), "/\\evil.example/path");
        invalid(camera(), "/picture#secret");
        invalid(camera().put("host", "192.168.01.12"), "/");
        invalid(camera().put("host", "192.168.1.999"), "/");
        invalid(camera().put("host", "camera.example:80"), "/");
    }
    @Test public void rejectsSecretsAndControlCharactersInConfig() throws Exception {
        invalid(camera(), "/picture?password=secret");
        invalid(camera(), "/picture?%75sername=admin");
        invalid(camera(), "/picture?api_key=secret");
        invalid(camera(), "/picture?token=secret");
        invalid(camera(), "/picture?auth=secret");
        invalid(camera(), "/picture%0d%0aHeader:value");
        invalid(camera(), "/picture\nother");
        invalid(camera(), "\n/picture");
        invalid(camera(), "/picture\u0085other");
        invalid(camera().put("password", "distinct-secret"), "/distinct-secret/picture");
    }
    @Test public void rejectsInvalidPortsSchemesAndUnconfirmedCameras() throws Exception {
        invalid(camera().put("port", 0), "/");
        invalid(camera().put("port", 65536), "/");
        invalid(camera().put("port", "80.0"), "/");
        invalid(camera().put("scheme", "file"), "/");
        invalid(camera().put("authorized", false), "/");
        invalid(camera(), "rtsp://192.168.1.12/stream");
    }
}
