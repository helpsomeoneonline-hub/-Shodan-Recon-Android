package com.shodanrecon.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

public class CameraBatchReportTest {
    private JSONObject camera(String id) throws Exception {
        return new JSONObject().put("id", id).put("authorized", true).put("host", "192.168.1.12")
                .put("name", "Camera " + id).put("scheme", "http").put("port", 80).put("credentialId", "preset-2");
    }

    @Test public void resolvesRegisteredSelectionOnceInUserOrderWithoutCredentials() throws Exception {
        java.util.List<String> calls = new java.util.ArrayList<>();
        List<JSONObject> cameras = CameraBatchReport.selection(new JSONArray().put("second").put("first").put("second"), id -> {
            calls.add(id); return camera(id);
        });
        assertEquals(java.util.Arrays.asList("second", "first"), calls);
        assertEquals(2, cameras.size());
        assertEquals("second", cameras.get(0).getString("id"));
        assertFalse(CameraBatchReport.begin(cameras.get(0), "run").has("password"));
    }

    @Test public void rejectsRawDiscoveryObjectsUnknownIdsAndUnconfirmedProfiles() throws Exception {
        for (JSONArray ids : new JSONArray[]{new JSONArray(), new JSONArray().put(new JSONObject().put("host", "192.168.1.12")), new JSONArray().put("http://192.168.1.12")}) {
            try { CameraBatchReport.selection(ids, this::camera); fail("Discovery data became a camera selection"); }
            catch (Exception expected) { assertFalse(expected.getMessage().isEmpty()); }
        }
        try { CameraBatchReport.selection(new JSONArray().put("missing"), id -> { throw new Exception("Not registered"); }); fail("Unknown ID accepted"); }
        catch (Exception expected) { assertEquals("Not registered", expected.getMessage()); }
        try { CameraBatchReport.selection(new JSONArray().put("unconfirmed"), id -> camera(id).put("authorized", false)); fail("Unconfirmed profile accepted"); }
        catch (Exception expected) { assertTrue(expected.getMessage().contains("authorization")); }
    }

    @Test public void finalizedCameraChecksCountOnceWhileSnapshotFailuresRemainSeparate() throws Exception {
        JSONObject batch = CameraBatchReport.start(3);
        JSONObject success = CameraBatchReport.begin(camera("one"), batch.getString("runId"))
                .put("connectionStatus", "success").put("snapshotsSaved", 2).put("snapshotFailures", 1);
        CameraBatchReport.finishRow(success, "snapshot-failed", "One endpoint failed");
        JSONObject failure = CameraBatchReport.begin(camera("two"), batch.getString("runId"))
                .put("connectionStatus", "authentication-failed").put("httpCode", 401);
        CameraBatchReport.finishRow(failure, "authentication-failed", "HTTP 401");
        batch.getJSONArray("results").put(success).put(failure);
        JSONObject pending = CameraBatchReport.begin(camera("three"), batch.getString("runId"))
                .put("snapshotsSaved", 1).put("snapshotsFlagged", 1);
        batch.put("currentResult", pending);
        CameraBatchReport.totals(batch); CameraBatchReport.totals(batch);
        assertEquals(2, batch.getInt("completed"));
        assertEquals(1, batch.getInt("successes"));
        assertEquals(1, batch.getInt("failures"));
        assertEquals(3, batch.getInt("snapshotsSaved"));
        assertEquals(1, batch.getInt("snapshotFailures"));
        assertEquals(1, batch.getInt("snapshotsFlagged"));
    }

    @Test public void restartRecoveryRetainsSavedSnapshotsAndCannotFinalizeTheSameRowTwice() throws Exception {
        JSONObject saved = CameraBatchReport.start(2);
        JSONObject draft = CameraBatchReport.begin(camera("one"), saved.getString("runId"))
                .put("connectionStatus", "success").put("snapshotsSaved", 2);
        saved.put("currentResult", draft).put("currentCamera", camera("one"));
        JSONObject restored = new JSONObject(saved.toString());
        CameraBatchReport.finish(restored, "interrupted"); CameraBatchReport.finish(restored, "interrupted");
        assertFalse(restored.getBoolean("running"));
        assertEquals("interrupted", restored.getString("status"));
        assertEquals(1, restored.getJSONArray("results").length());
        assertEquals(1, restored.getInt("completed"));
        assertEquals(2, restored.getInt("snapshotsSaved"));
        assertEquals("success", restored.getJSONArray("results").getJSONObject(0).getString("connectionStatus"));
        assertFalse(restored.has("currentResult"));
        assertFalse(restored.getString("summary").isEmpty());
    }

    @Test public void stoppingAnUnfinishedConnectionDoesNotCountItAsAFailedCheck() throws Exception {
        JSONObject batch = CameraBatchReport.start(3);
        batch.put("currentResult", CameraBatchReport.begin(camera("one"), batch.getString("runId")));
        CameraBatchReport.finish(batch, "cancelled");
        assertEquals(0, batch.getInt("completed"));
        assertEquals(0, batch.getInt("failures"));
        assertEquals("cancelled", batch.getJSONArray("results").getJSONObject(0).getString("connectionStatus"));
    }

    @Test public void classifiesAuthenticationHttpTimeoutNetworkTlsAndStopResults() {
        assertEquals("authentication-failed", CameraBatchReport.failureStatus(new Exception("HTTP 401"), 401, false));
        assertEquals("authentication-failed", CameraBatchReport.failureStatus(new IOException("The selected credential was deleted"), 0, false));
        assertEquals("http-error", CameraBatchReport.failureStatus(new Exception("Path unavailable"), 404, false));
        assertEquals("http-error", CameraBatchReport.failureStatus(new Exception("Redirect blocked"), 302, false));
        assertEquals("timeout", CameraBatchReport.failureStatus(new IOException("Camera connection timed out."), 0, false));
        assertEquals("unreachable", CameraBatchReport.failureStatus(new IOException("Camera connection refused or unreachable."), 0, false));
        assertEquals("tls-error", CameraBatchReport.failureStatus(new IOException("HTTPS certificate verification failed"), 0, false));
        assertEquals("cancelled", CameraBatchReport.failureStatus(new IOException("Network request failed"), 401, true));
    }

    @Test public void csvIncludesFinalizedRowsFromMultipleRunsEscapesCellsAndOmitsSecrets() throws Exception {
        JSONObject first = CameraBatchReport.start(1), second = CameraBatchReport.start(1);
        JSONObject row = CameraBatchReport.begin(camera("one").put("name", "=SUM(1,2)\"camera"), first.getString("runId"))
                .put("connectionStatus", "success").put("httpCode", 200).put("password", "must-not-export").put("username", "must-not-export-user");
        CameraBatchReport.finishRow(row, "success", "Line one\nLine two"); first.getJSONArray("results").put(row);
        JSONObject failed = CameraBatchReport.begin(camera("two"), second.getString("runId")).put("connectionStatus", "timeout");
        CameraBatchReport.finishRow(failed, "timeout", "Camera timed out"); second.getJSONArray("results").put(failed);
        second.getJSONArray("results").put(CameraBatchReport.begin(camera("unfinished"), second.getString("runId")));
        String csv = CameraBatchReport.csv(new JSONArray().put(first).put(second));
        assertTrue(csv.startsWith("run_id,camera_id,"));
        assertTrue(csv.contains("\"'=SUM(1,2)\"\"camera\""));
        assertTrue(csv.contains("Line one\nLine two"));
        assertTrue(csv.contains("\"timeout\""));
        assertFalse(csv.contains("must-not-export"));
        assertFalse(csv.contains("unfinished"));
    }
}
