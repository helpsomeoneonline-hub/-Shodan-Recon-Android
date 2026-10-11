package com.shodanrecon.mobile;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Exercises upgrade preservation and atomic credential changes without emulating Keystore. */
public class CameraRepositoryTest {
    private MemoryPreferences memory;
    private TestCodec codec;
    private CameraRepository repository;

    @Before public void setup() {
        memory = new MemoryPreferences();
        codec = new TestCodec();
        repository = new CameraRepository(memory.preferences, codec);
    }

    private static JSONObject update(String id, String password) throws Exception {
        return new JSONObject().put("id", id).put("password", password);
    }

    private void save(String username, JSONObject... updates) throws Exception {
        JSONArray presets = new JSONArray();
        for (JSONObject update : updates) presets.put(update);
        repository.savePresets(new JSONObject().put("username", username).put("presets", presets));
    }

    private JSONObject camera(String id, String credential) throws Exception {
        return new JSONObject().put("id", id).put("name", "Lab camera").put("host", "192.168.1.20")
                .put("authorized", true).put("credentialId", credential).put("channels", new JSONArray().put(101));
    }

    @Test public void emptyStateAlwaysHasExactlySixFixedPresets() throws Exception {
        JSONObject state = repository.state();
        JSONArray presets = state.getJSONArray("passwordPresets");
        assertEquals(6, presets.length());
        for (int i = 0; i < 6; i++) {
            assertEquals("preset-" + (i + 1), presets.getJSONObject(i).getString("id"));
            assertFalse(presets.getJSONObject(i).getBoolean("configured"));
        }
        assertFalse(state.getBoolean("usernameConfigured"));
    }

    @Test public void oneCommitPersistsEncryptedUsernameAndPasswordsWithoutSecretMetadata() throws Exception {
        save("operator", update("preset-1", "first-device-secret"), update("preset-6", "last-device-secret"));
        assertEquals(1, memory.commits);
        String persisted = memory.values.get("devices");
        assertFalse(persisted.contains("operator"));
        assertFalse(persisted.contains("first-device-secret"));
        JSONObject state = repository.state();
        assertEquals("operator", state.getString("sharedUsername"));
        assertFalse(state.toString().contains("first-device-secret"));
        assertTrue(state.getJSONArray("passwordPresets").getJSONObject(5).getBoolean("configured"));
        CameraRepository reloaded = new CameraRepository(memory.preferences, codec);
        assertEquals("last-device-secret", reloaded.credential("preset-6").getString("password"));
    }

    @Test public void blanksKeepPasswordsAndUsernameOnlyChangesEveryConfiguredPreset() throws Exception {
        save("operator", update("preset-1", "first-device-secret"), update("preset-3", "third-device-secret"));
        save("", update("preset-1", ""));
        assertEquals("first-device-secret", repository.credential("preset-1").getString("password"));
        save("new-operator");
        assertEquals("new-operator", repository.credential("preset-1").getString("username"));
        assertEquals("new-operator", repository.credential("preset-3").getString("username"));
        assertEquals("third-device-secret", repository.credential("preset-3").getString("password"));
        assertNull(repository.credential("preset-2"));
    }

    @Test public void legacyRecordsAndCameraAssignmentsSurviveNewPresetSetup() throws Exception {
        repository.saveCredential(new JSONObject().put("id", "legacy-credential").put("name", "Original credential")
                .put("username", "original-operator").put("password", "original-device-secret"));
        repository.saveCamera(camera("existing-camera", "legacy-credential"));
        save("new-operator", update("preset-1", "new-device-secret"));
        assertEquals("legacy-credential", repository.camera("existing-camera").getString("credentialId"));
        assertEquals("original-operator", repository.credential("legacy-credential").getString("username"));
        assertEquals("original-device-secret", repository.credential("legacy-credential").getString("password"));
        assertEquals(2, repository.state().getJSONArray("credentials").length());
    }

    @Test public void deletingPresetClearsOnlyThatPresetsCameraAssignments() throws Exception {
        save("operator", update("preset-1", "first-device-secret"), update("preset-2", "second-device-secret"));
        repository.saveCamera(camera("first-camera", "preset-1"));
        repository.saveCamera(camera("second-camera", "preset-2"));
        save("", new JSONObject().put("id", "preset-1").put("delete", true));
        assertNull(repository.credential("preset-1"));
        assertEquals("", repository.camera("first-camera").getString("credentialId"));
        assertEquals("preset-2", repository.camera("second-camera").getString("credentialId"));
        assertEquals("operator", repository.state().getString("sharedUsername"));
    }

    @Test public void deletingSharedSetupLeavesLegacyCredentialsIntact() throws Exception {
        save("operator", update("preset-1", "first-device-secret"));
        repository.saveCredential(new JSONObject().put("id", "legacy").put("username", "legacy-user").put("password", "legacy-secret"));
        repository.saveCamera(camera("preset-camera", "preset-1"));
        repository.savePresets(new JSONObject().put("deleteUsername", true));
        assertNull(repository.credential("preset-1"));
        assertEquals("", repository.camera("preset-camera").getString("credentialId"));
        assertNotNull(repository.credential("legacy"));
        assertFalse(repository.state().getBoolean("usernameConfigured"));
        assertEquals(6, repository.state().getJSONArray("passwordPresets").length());
    }

    @Test public void failedValidationOrEncryptionNeverPartiallyChangesSavedSetup() throws Exception {
        save("operator", update("preset-1", "first-device-secret"));
        String before = memory.values.get("devices");
        assertThrows(Exception.class, () -> save("changed-operator", update("preset-2", "valid-secret"), update("preset-7", "bad-secret")));
        assertEquals(before, memory.values.get("devices"));
        assertThrows(Exception.class, () -> save("changed-operator", update("preset-2", "contains\nnewline")));
        assertEquals(before, memory.values.get("devices"));
        codec.failEncryption = true;
        assertThrows(Exception.class, () -> save("changed-operator", update("preset-2", "valid-secret")));
        assertEquals(before, memory.values.get("devices"));
    }

    @Test public void failedStorageCommitPreservesPriorCredentialsAndAssignments() throws Exception {
        save("operator", update("preset-1", "first-device-secret"));
        repository.saveCamera(camera("first-camera", "preset-1"));
        String before = memory.values.get("devices");
        memory.failCommit = true;
        assertThrows(Exception.class, () -> save("", new JSONObject().put("id", "preset-1").put("delete", true)));
        assertEquals(before, memory.values.get("devices"));
    }

    @Test public void unavailableCiphertextIsVisiblePreservedAndCanBeExplicitlyReplaced() throws Exception {
        save("operator", update("preset-1", "first-device-secret"), update("preset-2", "second-device-secret"));
        repository.saveCamera(camera("first-camera", "preset-1"));
        JSONObject saved = new JSONObject(memory.values.get("devices"));
        saved.getJSONObject("credentials").put("preset-1", "unavailable-key-ciphertext");
        memory.values.put("devices", saved.toString());
        assertTrue(repository.state().getJSONArray("passwordPresets").getJSONObject(0).getBoolean("unavailable"));
        save("changed-operator");
        assertEquals("unavailable-key-ciphertext", new JSONObject(memory.values.get("devices")).getJSONObject("credentials").getString("preset-1"));
        assertEquals("changed-operator", repository.credential("preset-2").getString("username"));
        assertThrows(Exception.class, () -> repository.credential("preset-1"));
        save("", update("preset-1", "replacement-device-secret"));
        assertEquals("changed-operator", repository.credential("preset-1").getString("username"));
        assertEquals("preset-1", repository.camera("first-camera").getString("credentialId"));
        assertFalse(repository.state().getJSONArray("passwordPresets").getJSONObject(0).getBoolean("unavailable"));
    }

    @Test public void newPasswordNeedsSharedUsernameAndDuplicatePresetIdsAreRejected() throws Exception {
        assertThrows(Exception.class, () -> save("", update("preset-1", "first-device-secret")));
        assertThrows(Exception.class, () -> save("operator", update("preset-1", "first-device-secret"), update("preset-1", "other-secret")));
        assertFalse(memory.values.containsKey("devices"));
    }

    @Test public void unassignedKnownPasswordCannotAppearInExpandedPathButOriginPortIsAllowed() throws Exception {
        save("operator", update("preset-1", "8099"));
        JSONObject allowed = camera("port-camera", "").put("port", 8099).put("rtspPath", "");
        repository.saveCamera(allowed);
        assertEquals(8099, repository.camera("port-camera").getInt("port"));
        JSONObject exposed = new JSONObject(allowed.toString()).put("httpPath", "/picture/{port}");
        Exception failure = assertThrows(Exception.class, () -> repository.saveCamera(exposed));
        assertTrue(failure.getMessage().contains("template expands to a saved password"));
        assertEquals("/", repository.camera("port-camera").getString("httpPath"));
    }

    @Test public void onlyConfiguredChannelsAreCheckedForExpandedPasswordConflicts() throws Exception {
        save("operator", update("preset-1", "201"));
        JSONObject allowed = camera("channel-camera", "").put("snapshotPath", "/picture/{channel}").put("rtspPath", "");
        repository.saveCamera(allowed); // Configured channel 101 is safe even though another channel would conflict.
        JSONObject exposed = new JSONObject(allowed.toString()).put("channels", new JSONArray().put(101).put(201));
        assertThrows(Exception.class, () -> repository.saveCamera(exposed));
        assertEquals(1, repository.camera("channel-camera").getJSONArray("channels").length());
    }

    @Test public void automaticCandidatesUseOnlyConfiguredPresetsInOrderWithSharedUsername() throws Exception {
        repository.saveCredential(new JSONObject().put("id", "legacy").put("username", "legacy-user").put("password", "legacy-secret"));
        save("operator", update("preset-6", "last-device-secret"), update("preset-2", "second-device-secret"));
        java.util.List<JSONObject> candidates = repository.savedPasswordCredentials();
        assertEquals(2, candidates.size());
        assertEquals("preset-2", candidates.get(0).getString("id"));
        assertEquals("preset-6", candidates.get(1).getString("id"));
        assertEquals("operator", candidates.get(0).getString("username"));
        assertEquals("operator", candidates.get(1).getString("username"));
        candidates.get(0).put("password", "modified-in-memory");
        assertEquals("second-device-secret", repository.credential("preset-2").getString("password"));
    }

    @Test public void automaticCandidatesRequireReadableUsernameAndPresets() throws Exception {
        assertThrows(Exception.class, () -> repository.savedPasswordCredentials());
        save("operator");
        assertThrows(Exception.class, () -> repository.savedPasswordCredentials());
        save("", update("preset-1", "first-device-secret"));
        JSONObject saved = new JSONObject(memory.values.get("devices"));
        saved.getJSONObject("credentials").put("preset-1", "unavailable-key-ciphertext");
        memory.values.put("devices", saved.toString());
        assertThrows(Exception.class, () -> repository.savedPasswordCredentials());
    }

    @Test public void verifiedPresetAssignmentPreservesCameraAndOtherAssignments() throws Exception {
        save("operator", update("preset-1", "first-device-secret"), update("preset-2", "second-device-secret"));
        repository.saveCamera(camera("first-camera", "preset-1").put("snapshotPath", "/custom/{channel}"));
        repository.saveCamera(camera("second-camera", "preset-1"));
        repository.assignVerifiedCredential("first-camera", "preset-2");
        assertEquals("preset-2", repository.camera("first-camera").getString("credentialId"));
        assertEquals("/custom/{channel}", repository.camera("first-camera").getString("snapshotPath"));
        assertEquals("preset-1", repository.camera("second-camera").getString("credentialId"));
        assertThrows(Exception.class, () -> repository.assignVerifiedCredential("unknown-camera", "preset-2"));
    }

    private static final class TestCodec implements CameraRepository.CredentialCodec {
        final Map<String, String> secrets = new HashMap<>();
        boolean failEncryption;
        @Override public String encrypt(String value) throws Exception {
            if (failEncryption) throw new Exception("Synthetic encryption failure");
            String token = "encrypted-test-token-" + secrets.size();
            secrets.put(token, value);
            return token;
        }
        @Override public String decrypt(String value) throws Exception {
            if (!secrets.containsKey(value)) throw new Exception("Synthetic unavailable key");
            return secrets.get(value);
        }
    }

    private static final class MemoryPreferences {
        final Map<String, String> values = new HashMap<>();
        int commits;
        boolean failCommit;
        final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getString")) return values.getOrDefault((String) args[0], (String) args[1]);
                    if (method.getName().equals("edit")) return editor();
                    throw new UnsupportedOperationException(method.getName());
                });
        private SharedPreferences.Editor editor() {
            Map<String, String> pending = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        if (method.getName().equals("putString")) { pending.put((String) args[0], (String) args[1]); return proxy; }
                        if (method.getName().equals("commit")) {
                            commits++;
                            if (failCommit) return false;
                            values.putAll(pending);
                            return true;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}

