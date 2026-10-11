package com.shodanrecon.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Registered cameras are deliberately separate from Shodan discovery and scan results. */
public final class CameraRepository {
    private static final String ALIAS = "shodan_recon_device_credentials_v1";
    private final SharedPreferences prefs;

    public CameraRepository(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("recon_cameras_v1", Context.MODE_PRIVATE);
    }

    private JSONObject data() throws Exception {
        try { return new JSONObject(prefs.getString("devices", "{\"cameras\":[],\"credentials\":{},\"history\":[]}")); }
        catch (Exception e) { throw new Exception("Saved camera data cannot be read. Existing storage was preserved."); }
    }

    private void persist(JSONObject value) throws Exception {
        if (!prefs.edit().putString("devices", value.toString()).commit())
            throw new Exception("Cannot save camera configuration. Check available device storage.");
    }

    public synchronized JSONObject camera(String id) throws Exception {
        JSONArray cameras = data().optJSONArray("cameras");
        if (cameras != null) for (int i = 0; i < cameras.length(); i++) {
            JSONObject camera = cameras.optJSONObject(i);
            if (camera != null && camera.optString("id").equals(id)) {
                if (!camera.optBoolean("authorized")) throw new Exception("Register this camera and confirm authorization first.");
                return new JSONObject(camera.toString());
            }
        }
        throw new Exception("Camera profile no longer exists. Select a registered camera.");
    }

    /** Secrets may only be read by native connection code; never expose this through JavaScript. */
    public synchronized JSONObject credential(String id) throws Exception {
        if (id == null || id.isEmpty()) return null;
        JSONObject records = data().optJSONObject("credentials");
        if (records == null || !records.has(id)) return null;
        try { return new JSONObject(decrypt(records.getString(id))); }
        catch (Exception e) { throw new Exception("The saved device credential cannot be decrypted. Re-enter it in Credentials."); }
    }

    public synchronized JSONObject state() throws Exception {
        JSONObject source = data();
        JSONObject result = new JSONObject();
        result.put("cameras", source.optJSONArray("cameras") == null ? new JSONArray() : source.getJSONArray("cameras"));
        JSONArray credentials = new JSONArray();
        JSONObject records = source.optJSONObject("credentials");
        if (records != null) {
            java.util.Iterator<String> keys = records.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONObject item = new JSONObject().put("id", id);
                try {
                    JSONObject secret = credential(id);
                    item.put("name", secret.optString("name", "Device credential"));
                    item.put("hasPassword", !secret.optString("password").isEmpty());
                } catch (Exception e) {
                    item.put("name", "Unavailable credential — re-enter").put("hasPassword", false).put("unavailable", true);
                }
                credentials.put(item);
            }
        }
        result.put("credentials", credentials);
        result.put("history", source.optJSONArray("history") == null ? new JSONArray() : source.getJSONArray("history"));
        return result;
    }

    private static String text(JSONObject source, String key, String fallback, int limit) throws Exception {
        String value = source.optString(key, fallback).trim();
        if (value.length() > limit || value.indexOf('\u0000') >= 0 || value.contains("\r") || value.contains("\n"))
            throw new Exception("Invalid " + key + ".");
        return value;
    }

    private JSONObject validatedCamera(JSONObject input) throws Exception {
        if (!input.optBoolean("authorized", false)) throw new Exception("Confirm that you own or are authorized to administer this camera.");
        String id = text(input, "id", "", 80);
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw new Exception("Invalid camera profile identifier.");
        String host = text(input, "host", "", 253);
        if (host.isEmpty()) throw new Exception("Enter the camera IP address or hostname.");
        String scheme = text(input, "scheme", "http", 8).toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new Exception("Select HTTP or HTTPS.");
        int port = input.optInt("port", scheme.equals("https") ? 443 : 80);
        int rtspPort = input.optInt("rtspPort", 554);
        if (port < 1 || port > 65535 || rtspPort < 1 || rtspPort > 65535) throw new Exception("Camera ports must be 1–65535.");
        JSONObject camera = new JSONObject().put("id", id).put("name", text(input, "name", host, 100))
                .put("host", host).put("port", port).put("scheme", scheme).put("rtspPort", rtspPort)
                .put("authorized", true).put("credentialId", text(input, "credentialId", "", 80));
        camera.put("httpPath", text(input, "httpPath", "/", 2048));
        camera.put("snapshotPath", text(input, "snapshotPath", "/ISAPI/Streaming/channels/{channel}/picture", 2048));
        camera.put("mjpegPath", text(input, "mjpegPath", "", 2048));
        camera.put("hlsPath", text(input, "hlsPath", "", 2048));
        camera.put("rtspPath", text(input, "rtspPath", "/Streaming/Channels/{channel}", 2048));
        for (String key : new String[]{"httpPath", "snapshotPath", "mjpegPath", "hlsPath"}) {
            if (!camera.optString(key).isEmpty()) CameraUrls.url(camera, camera.getString(key), 101);
        }
        // Validate the RTSP path with the same origin rules without persisting embedded credentials.
        String rtsp = camera.optString("rtspPath");
        if (!rtsp.isEmpty()) {
            JSONObject rtspOrigin = new JSONObject(camera.toString()).put("port", rtspPort).put("scheme", "http");
            if (rtsp.startsWith("rtsp://")) rtsp = "http://" + rtsp.substring(7);
            CameraUrls.url(rtspOrigin, rtsp, 101);
        }
        JSONArray source = input.optJSONArray("channels");
        JSONArray channels = new JSONArray();
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        if (source == null || source.length() == 0) for (int i = 1; i <= 16; i++) channels.put(i * 100 + 1);
        else {
            if (source.length() > 64) throw new Exception("Select at most 64 camera channels.");
            for (int i = 0; i < source.length(); i++) {
                int channel = source.optInt(i, -1);
                if (channel < 1 || channel > 65535) throw new Exception("Channel numbers must be 1–65535.");
                if (seen.add(channel)) channels.put(channel);
            }
        }
        camera.put("channels", channels);
        return camera;
    }

    public synchronized void saveCamera(JSONObject input) throws Exception {
        JSONObject source = data();
        JSONObject camera = validatedCamera(input);
        String credentialId = camera.optString("credentialId");
        JSONObject credentials = source.optJSONObject("credentials");
        if (!credentialId.isEmpty() && (credentials == null || !credentials.has(credentialId)))
            throw new Exception("Select an existing saved credential or choose no credential.");
        if (!credentialId.isEmpty()) validateSecretFreePaths(camera, credential(credentialId));
        validateKnownPasswords(camera, knownPasswords(source));
        JSONArray old = source.optJSONArray("cameras");
        JSONArray cameras = new JSONArray();
        boolean replaced = false;
        if (old != null) for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.getJSONObject(i);
            if (item.optString("id").equals(camera.getString("id"))) { cameras.put(camera); replaced = true; }
            else cameras.put(item);
        }
        if (!replaced) {
            if (cameras.length() >= 500) throw new Exception("Camera limit reached (500 profiles).");
            cameras.put(camera);
        }
        source.put("cameras", cameras);
        persist(source);
    }

    public synchronized void saveCredential(JSONObject input) throws Exception {
        JSONObject source = data();
        JSONObject records = source.optJSONObject("credentials");
        if (records == null) records = new JSONObject();
        String id = text(input, "id", "", 80);
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw new Exception("Invalid credential identifier.");
        JSONObject old = null;
        if (records.has(id)) {
            try { old = new JSONObject(decrypt(records.getString(id))); }
            catch (Exception ignored) { /* User can replace a lost Keystore credential with a new password. */ }
        }
        String username = input.optString("username", "");
        String password = input.optString("password", "");
        if (username.isEmpty() && old != null) username = old.optString("username");
        if (password.isEmpty() && old != null) password = old.optString("password");
        if (username.isEmpty() || password.isEmpty()) throw new Exception("Enter the device username and password.");
        if (username.length() > 256 || password.length() > 1024 || username.contains("\r") || username.contains("\n") || username.contains(":"))
            throw new Exception("Device credentials are too long or have an unsupported username.");
        if (!records.has(id) && records.length() >= 500) throw new Exception("Credential limit reached (500).");
        JSONObject secret = new JSONObject().put("id", id).put("name", text(input, "name", "Device credential", 100))
                .put("username", username).put("password", password);
        JSONArray cameras = source.optJSONArray("cameras");
        if (cameras != null) for (int i = 0; i < cameras.length(); i++) {
            JSONObject camera = cameras.getJSONObject(i);
            if (camera.optString("credentialId").equals(id)) validateSecretFreePaths(camera, secret);
        }
        records.put(id, encrypt(secret.toString()));
        source.put("credentials", records);
        persist(source);
    }

    public synchronized void deleteCamera(String id) throws Exception {
        JSONObject source = data();
        JSONArray cameras = new JSONArray(), old = source.optJSONArray("cameras");
        if (old != null) for (int i = 0; i < old.length(); i++) if (!old.getJSONObject(i).optString("id").equals(id)) cameras.put(old.getJSONObject(i));
        source.put("cameras", cameras); persist(source);
    }

    public synchronized void deleteCredential(String id) throws Exception {
        JSONObject source = data();
        JSONObject records = source.optJSONObject("credentials");
        if (records != null) records.remove(id);
        JSONArray cameras = source.optJSONArray("cameras");
        if (cameras != null) for (int i = 0; i < cameras.length(); i++) {
            JSONObject camera = cameras.getJSONObject(i);
            if (camera.optString("credentialId").equals(id)) camera.put("credentialId", "");
        }
        persist(source);
    }

    public synchronized void record(String cameraId, String action, String message) throws Exception {
        JSONObject source = data();
        JSONArray old = source.optJSONArray("history"), history = new JSONArray();
        JSONObject item = new JSONObject().put("cameraId", cameraId).put("action", action)
                .put("message", sanitize(message)).put("timestamp", System.currentTimeMillis()).put("time", Instant.now().toString());
        JSONArray cameras = source.optJSONArray("cameras");
        if (cameras != null) for (int i = 0; i < cameras.length(); i++) {
            JSONObject camera = cameras.getJSONObject(i);
            if (camera.optString("id").equals(cameraId)) {
                item.put("cameraName", sanitize(camera.optString("name"))).put("ip", sanitize(camera.optString("host")));
                break;
            }
        }
        history.put(item);
        if (old != null) for (int i = 0; i < Math.min(old.length(), 199); i++) history.put(old.get(i));
        source.put("history", history); persist(source);
    }

    public synchronized JSONObject exportConfig() throws Exception {
        JSONObject source = data();
        JSONArray cameras = new JSONArray(), old = source.optJSONArray("cameras");
        java.util.List<String> passwords = knownPasswords(source);
        if (old != null) for (int i = 0; i < old.length(); i++) {
            JSONObject camera = new JSONObject(old.getJSONObject(i).toString());
            String credentialId = camera.optString("credentialId");
            if (!credentialId.isEmpty()) validateSecretFreePaths(camera, credential(credentialId));
            validateKnownPasswords(camera, passwords);
            camera.remove("credentialId");
            camera.put("authorized", false); // A file never grants permission to contact a device.
            cameras.put(camera);
        }
        return new JSONObject().put("format", "shodan-recon-camera-config").put("version", 1).put("cameras", cameras);
    }

    private static void validateSecretFreePaths(JSONObject camera, JSONObject credential) throws Exception {
        if (credential == null) return;
        JSONObject validation = new JSONObject(camera.toString()).put("username", credential.optString("username"))
                .put("password", credential.optString("password"));
        for (String field : new String[]{"httpPath", "snapshotPath", "mjpegPath", "hlsPath"}) {
            if (!camera.optString(field).isEmpty()) CameraUrls.url(validation, camera.optString(field), 101);
        }
        String rtsp = camera.optString("rtspPath");
        if (!rtsp.isEmpty()) {
            validation.put("scheme", "http").put("port", camera.optInt("rtspPort", 554));
            if (rtsp.startsWith("rtsp://")) rtsp = "http://" + rtsp.substring(7);
            CameraUrls.url(validation, rtsp, 101);
        }
        String password = credential.optString("password");
        if (!password.isEmpty() && (camera.optString("name").contains(password) || camera.optString("host").contains(password)))
            throw new Exception("Remove the saved password from the camera name or address.");
    }

    private java.util.List<String> knownPasswords(JSONObject source) throws Exception {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        JSONObject records = source.optJSONObject("credentials");
        if (records == null) return result;
        java.util.Iterator<String> ids = records.keys();
        while (ids.hasNext()) {
            String encrypted = records.optString(ids.next());
            JSONObject secret;
            try { secret = new JSONObject(decrypt(encrypted)); }
            catch (Exception ignored) { continue; }
            String password = secret.optString("password");
            if (!password.isEmpty()) result.add(password);
        }
        return result;
    }

    private static void validateKnownPasswords(JSONObject camera, java.util.List<String> passwords) throws Exception {
        // An unselected saved password must not be smuggled into a URL under an arbitrary query key.
        for (String field : new String[]{"host", "name", "httpPath", "snapshotPath", "mjpegPath", "hlsPath", "rtspPath"}) {
            String value = camera.optString(field);
            String decoded;
            try { decoded = field.endsWith("Path") ? java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") : value; }
            catch (Exception e) { throw new Exception("Camera path encoding is invalid."); }
            for (String password : passwords) if (value.contains(password) || decoded.contains(password))
                throw new Exception("Remove the saved password from the camera configuration before saving or exporting.");
        }
    }

    public synchronized String sanitize(String message) {
        String safe = message == null ? "Camera operation failed." : message;
        try {
            JSONObject records = data().optJSONObject("credentials");
            if (records != null) {
                java.util.Iterator<String> keys = records.keys();
                while (keys.hasNext()) {
                    try {
                        JSONObject secret = new JSONObject(decrypt(records.getString(keys.next())));
                        for (String field : new String[]{"username", "password"}) {
                            String value = secret.optString(field);
                            if (!value.isEmpty()) {
                                safe = safe.replace(value, "[redacted]");
                                safe = safe.replace(java.net.URLEncoder.encode(value, "UTF-8"), "[redacted]");
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return safe.length() > 500 ? safe.substring(0, 500) : safe;
    }

    /** Called only after the Android import confirmation dialog grants explicit registration. */
    public synchronized void importConfig(JSONObject config) throws Exception {
        if (!"shodan-recon-camera-config".equals(config.optString("format")) || config.optInt("version") != 1)
            throw new Exception("Select a Shodan Recon camera configuration JSON file.");
        JSONArray input = config.optJSONArray("cameras");
        if (input == null || input.length() == 0 || input.length() > 500) throw new Exception("Configuration must contain 1–500 cameras.");
        JSONArray validated = new JSONArray();
        JSONObject source = data();
        java.util.List<String> passwords = knownPasswords(source);
        for (int i = 0; i < input.length(); i++) {
            JSONObject camera = new JSONObject(input.getJSONObject(i).toString());
            camera.put("authorized", true).put("credentialId", "");
            // Create independent registrations instead of silently replacing existing profiles.
            camera.put("id", UUID.randomUUID().toString());
            JSONObject checked = validatedCamera(camera);
            validateKnownPasswords(checked, passwords);
            validated.put(checked);
        }
        JSONArray cameras = source.optJSONArray("cameras");
        if (cameras == null) cameras = new JSONArray();
        if (cameras.length() + validated.length() > 500) throw new Exception("Import exceeds the 500-profile limit.");
        for (int i = 0; i < validated.length(); i++) cameras.put(validated.get(i));
        source.put("cameras", cameras); persist(source);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private String decrypt(String value) throws Exception {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 2) throw new Exception("Invalid encrypted credential.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
