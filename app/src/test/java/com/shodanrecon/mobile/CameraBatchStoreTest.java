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

/** Verifies real JSON report persistence with an in-memory Android interface implementation. */
public class CameraBatchStoreTest {
    private MemoryPreferences memory;
    private CameraBatchStore store;

    @Before public void setup() { memory = new MemoryPreferences(); store = new CameraBatchStore(memory.preferences); }

    private JSONObject row(String runId) throws Exception {
        JSONObject camera = new JSONObject().put("id", "registered-camera").put("host", "192.168.1.20")
                .put("name", "Lab camera").put("scheme", "http").put("port", 8080).put("credentialId", "preset-1");
        return CameraBatchReport.begin(camera, runId).put("connectionStatus", "success").put("httpCode", 200);
    }

    @Test public void interruptedConnectionAndPartialSnapshotProgressSurviveReconstruction() throws Exception {
        JSONObject current = store.start(2);
        current.put("currentResult", row(current.getString("runId")).put("snapshotsSaved", 1))
                .put("currentCamera", new JSONObject().put("id", "registered-camera"));
        assertTrue(store.update(current));
        CameraBatchStore reconstructed = new CameraBatchStore(memory.preferences);
        reconstructed.interrupt();
        JSONObject restored = reconstructed.state();
        assertEquals("interrupted", restored.getString("status"));
        assertFalse(restored.getBoolean("running"));
        assertEquals(1, restored.getInt("snapshotsSaved"));
        assertEquals(1, restored.getJSONArray("results").length());
        assertFalse(restored.has("currentResult"));
    }

    @Test public void savedPasswordAttemptProgressAndWorkingPresetSurviveInterruptionAndExport() throws Exception {
        JSONObject current = store.start(2, "saved-passwords");
        JSONArray attempts = new JSONArray()
                .put(new JSONObject().put("credentialId", "preset-1").put("status", "authentication-failed").put("httpCode", 401))
                .put(new JSONObject().put("credentialId", "preset-2").put("status", "success").put("httpCode", 200));
        current.put("currentResult", row(current.getString("runId")).put("credentialMode", "saved-passwords")
                .put("credentialAttempts", 2).put("workingCredentialId", "preset-2").put("attemptResults", attempts));
        CameraBatchReport.totals(current);
        assertTrue(store.update(current));
        CameraBatchStore reconstructed = new CameraBatchStore(memory.preferences);
        reconstructed.interrupt();
        JSONObject restored = reconstructed.state();
        JSONObject saved = restored.getJSONArray("results").getJSONObject(0);
        assertEquals("saved-passwords", restored.getString("credentialMode"));
        assertEquals(2, restored.getInt("credentialAttempts"));
        assertEquals(2, saved.getInt("credentialAttempts"));
        assertEquals("preset-2", saved.getString("workingCredentialId"));
        assertEquals(attempts.toString(), saved.getJSONArray("attemptResults").toString());
        assertTrue(reconstructed.csv().contains(",\"saved-passwords\",\"2\",\"preset-2\"\r\n"));
    }

    @Test public void legacyStoredReportExportsAndMovesIntoHistoryWithoutAttemptMetadata() throws Exception {
        JSONObject legacy = CameraBatchReport.start(1);
        legacy.remove("credentialMode"); legacy.remove("credentialAttempts");
        JSONObject finished = row(legacy.getString("runId"));
        finished.remove("credentialMode"); finished.remove("credentialAttempts");
        finished.remove("workingCredentialId"); finished.remove("attemptResults");
        CameraBatchReport.finishRow(finished, "success", "HTTP 200");
        legacy.getJSONArray("results").put(finished);
        CameraBatchReport.finish(legacy, "completed");
        legacy.remove("credentialAttempts");
        memory.values.put("batches", new JSONObject().put("current", legacy).put("history", new JSONArray()).toString());
        assertTrue(store.csv().endsWith(",\"assigned\",\"\",\"\"\r\n"));
        store.start(1, "saved-passwords");
        assertEquals(legacy.getString("runId"), store.history().getJSONObject(0).getString("runId"));
        assertTrue(store.csv().contains(legacy.getString("runId")));
    }

    @Test public void terminalReportsCannotBeOverwrittenByOldWorkerProgressOrAnotherRun() throws Exception {
        JSONObject old = store.start(1);
        store.interrupt();
        assertFalse(store.update(old));
        JSONObject newest = store.start(1);
        assertFalse(store.update(old));
        assertEquals(newest.getString("runId"), store.state().getString("runId"));
        assertEquals("interrupted", store.history().getJSONObject(0).getString("status"));
    }

    @Test public void completedAndStoppedRunsExportTogetherAndHistoryRemainsBounded() throws Exception {
        JSONObject first = store.start(1);
        JSONObject finished = row(first.getString("runId"));
        CameraBatchReport.finishRow(finished, "success", "HTTP 200: connected");
        first.getJSONArray("results").put(finished);
        CameraBatchReport.finish(first, "completed"); assertTrue(store.update(first));
        JSONObject second = store.start(2);
        second.put("currentResult", row(second.getString("runId")).put("connectionStatus", "pending"));
        CameraBatchReport.finish(second, "cancelled"); assertTrue(store.update(second));
        String csv = store.csv();
        assertTrue(csv.contains(first.getString("runId")));
        assertTrue(csv.contains(second.getString("runId")));
        assertTrue(csv.contains("\"cancelled\""));
        for (int i = 0; i < 25; i++) { JSONObject run = store.start(1); CameraBatchReport.finish(run, "completed"); assertTrue(store.update(run)); }
        assertEquals(20, store.history().length());
    }

    @Test public void failedCommitDoesNotDiscardPreviouslySavedResults() throws Exception {
        JSONObject current = store.start(1);
        String saved = memory.values.get("batches");
        current.put("message", "Changed progress"); memory.failCommit = true;
        try { store.update(current); fail("Failed storage commit was ignored"); }
        catch (Exception expected) { assertTrue(expected.getMessage().contains("save")); }
        assertEquals(saved, memory.values.get("batches"));
    }

    @Test public void corruptStorageAndOverlappingStartsPreserveOriginalData() throws Exception {
        store.start(1); String saved = memory.values.get("batches");
        try { store.start(1); fail("Overlapping run started"); }
        catch (Exception expected) { assertTrue(expected.getMessage().contains("already running")); }
        assertEquals(saved, memory.values.get("batches"));
        memory.values.put("batches", "not-json");
        try { store.interrupt(); fail("Corrupt report was overwritten"); }
        catch (Exception expected) { assertTrue(expected.getMessage().contains("preserved")); }
        assertEquals("not-json", memory.values.get("batches"));
    }

    private static final class MemoryPreferences {
        final Map<String, String> values = new HashMap<>();
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
                        if (method.getName().equals("commit")) { if (failCommit) return false; values.putAll(pending); return true; }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}

