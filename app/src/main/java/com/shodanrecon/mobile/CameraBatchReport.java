package com.shodanrecon.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Batch bookkeeping is independent of Android and never holds a password or request URL. */
final class CameraBatchReport {
    private CameraBatchReport() {}

    interface CameraLookup { JSONObject camera(String id) throws Exception; }

    static java.util.List<JSONObject> selection(JSONArray ids, CameraLookup lookup) throws Exception {
        if (ids == null || ids.length() < 1 || ids.length() > 500) throw new Exception("Select 1–500 registered cameras.");
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        java.util.List<JSONObject> selected = new java.util.ArrayList<>();
        for (int i = 0; i < ids.length(); i++) {
            Object value = ids.get(i);
            if (!(value instanceof String) || !((String) value).matches("[A-Za-z0-9_-]{1,80}"))
                throw new Exception("Select registered camera profile identifiers.");
            String id = (String) value;
            if (seen.add(id)) {
                JSONObject camera = lookup.camera(id);
                if (camera == null || !camera.optBoolean("authorized") || !id.equals(camera.optString("id")))
                    throw new Exception("Register this camera and confirm authorization first.");
                selected.add(new JSONObject(camera.toString()));
            }
        }
        return selected;
    }

    static JSONObject empty() throws Exception {
        return new JSONObject().put("running", false).put("status", "idle").put("runId", "")
                .put("currentCamera", JSONObject.NULL).put("index", 0).put("total", 0)
                .put("completed", 0).put("successes", 0).put("failures", 0)
                .put("credentialMode", "assigned").put("credentialAttempts", 0)
                .put("snapshotsSaved", 0).put("snapshotsFlagged", 0).put("snapshotFailures", 0)
                .put("results", new JSONArray()).put("message", "Select registered cameras and press Start.")
                .put("summary", "").put("startedAt", "").put("finishedAt", "");
    }

    static JSONObject start(int total) throws Exception {
        return start(total, "assigned");
    }

    static JSONObject start(int total, String credentialMode) throws Exception {
        if (total < 1 || total > 500) throw new Exception("Select 1–500 registered cameras.");
        requireCredentialMode(credentialMode);
        return empty().put("running", true).put("status", "running").put("total", total)
                .put("credentialMode", credentialMode)
                .put("runId", UUID.randomUUID().toString()).put("startedAt", Instant.now().toString())
                .put("message", "Starting camera checks…");
    }

    static JSONObject begin(JSONObject camera, String runId) throws Exception {
        return begin(camera, runId, "assigned");
    }

    static JSONObject begin(JSONObject camera, String runId, String credentialMode) throws Exception {
        requireCredentialMode(credentialMode);
        return new JSONObject().put("runId", runId).put("cameraId", camera.getString("id"))
                .put("cameraName", camera.optString("name", camera.optString("host")))
                .put("host", camera.optString("host")).put("scheme", camera.optString("scheme"))
                .put("port", camera.optInt("port")).put("credentialId", camera.optString("credentialId"))
                .put("credentialMode", credentialMode).put("credentialAttempts", 0)
                .put("workingCredentialId", "").put("attemptResults", new JSONArray())
                .put("startedAt", Instant.now().toString()).put("startedTimestamp", System.currentTimeMillis())
                .put("finishedAt", "").put("elapsedMs", 0).put("status", "pending")
                .put("connectionStatus", "pending").put("httpCode", 0).put("message", "Testing HTTP interface…")
                .put("snapshotsSaved", 0).put("snapshotsFlagged", 0).put("snapshotFailures", 0)
                .put("snapshotResults", new JSONArray());
    }

    private static void requireCredentialMode(String credentialMode) throws Exception {
        if (!"assigned".equals(credentialMode) && !"saved-passwords".equals(credentialMode))
            throw new Exception("Select assigned credentials or saved passwords.");
    }

    /** Classification includes sanitized transport messages, whose original exception type may be hidden. */
    static String failureStatus(Throwable error, int code, boolean cancelled) {
        if (cancelled) return "cancelled";
        if (code == 401) return "authentication-failed";
        if (code == 403) return "forbidden";
        if (code == 423) return "locked";
        if (code == 429) return "rate-limited";
        if (code >= 300) return "http-error";
        String message = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        if (error instanceof java.net.SocketTimeoutException || message.contains("timed out") || message.contains("timeout")) return "timeout";
        if (error instanceof javax.net.ssl.SSLException || message.contains("certificate") || message.contains("ssl") || message.contains("tls")) return "tls-error";
        if (message.contains("authentication") || message.contains("credential") || message.contains("digest") || message.contains("http basic")) return "authentication-failed";
        if (error instanceof java.io.IOException || message.contains("unreachable") || message.contains("network")
                || message.contains("connection refused") || message.contains("could not be resolved")) return "unreachable";
        return "configuration-error";
    }

    static void finishRow(JSONObject row, String status, String message) throws Exception {
        row.put("status", status).put("message", message).put("finishedAt", Instant.now().toString())
                .put("elapsedMs", Math.max(0, System.currentTimeMillis() - row.optLong("startedTimestamp")));
    }

    /** Recompute from finalized rows: one camera outcome, separate credential and snapshot counters. */
    static void totals(JSONObject batch) throws Exception {
        int completed = 0, successes = 0, failures = 0, saved = 0, flagged = 0, snapshotFailures = 0, credentialAttempts = 0;
        JSONArray rows = batch.optJSONArray("results");
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String connection = row.optString("connectionStatus", "pending");
            if (!connection.equals("pending") && !connection.equals("cancelled") && !connection.equals("interrupted")) {
                completed++;
                if (connection.equals("success")) successes++; else failures++;
            }
            saved += row.optInt("snapshotsSaved");
            flagged += row.optInt("snapshotsFlagged");
            snapshotFailures += row.optInt("snapshotFailures");
            credentialAttempts += row.optInt("credentialAttempts");
        }
        JSONObject current = batch.optJSONObject("currentResult");
        if (current != null) {
            saved += current.optInt("snapshotsSaved");
            flagged += current.optInt("snapshotsFlagged");
            snapshotFailures += current.optInt("snapshotFailures");
            credentialAttempts += current.optInt("credentialAttempts");
        }
        batch.put("completed", completed).put("successes", successes).put("failures", failures)
                .put("snapshotsSaved", saved).put("snapshotsFlagged", flagged).put("snapshotFailures", snapshotFailures)
                .put("credentialAttempts", credentialAttempts);
    }

    static void finish(JSONObject batch, String status) throws Exception {
        if (!batch.optBoolean("running")) return;
        JSONObject unfinished = batch.optJSONObject("currentResult");
        if (unfinished != null) {
            if (unfinished.optString("connectionStatus").equals("pending")) unfinished.put("connectionStatus", status);
            finishRow(unfinished, status, status.equals("interrupted") ? "Interrupted when the app closed. Saved snapshots were retained." : "Camera task cancelled.");
            batch.getJSONArray("results").put(unfinished);
        }
        batch.remove("currentResult");
        totals(batch);
        String summary = batch.optInt("completed") + "/" + batch.optInt("total") + " camera checks completed; "
                + batch.optInt("successes") + " connected, " + batch.optInt("failures") + " failed; "
                + batch.optInt("snapshotsSaved") + " snapshots saved, " + batch.optInt("snapshotFailures")
                + " snapshot failures, " + batch.optInt("snapshotsFlagged") + " flagged for review.";
        batch.put("running", false).put("status", status).put("currentCamera", JSONObject.NULL)
                .put("finishedAt", Instant.now().toString()).put("summary", summary)
                .put("message", (status.equals("completed") ? "Batch finished. " : status.equals("cancelled") ? "Batch stopped. " : "Batch interrupted. ") + summary);
    }

    static String csv(JSONArray batches) throws Exception {
        StringBuilder out = new StringBuilder("run_id,camera_id,camera_name,address,scheme,port,credential_id,started_at,finished_at,elapsed_ms,status,connection_status,http_code,snapshots_saved,snapshots_flagged,snapshot_failures,message,credential_mode,credential_attempts,working_credential_id\r\n");
        String[] fields = {"runId", "cameraId", "cameraName", "host", "scheme", "port", "credentialId", "startedAt", "finishedAt", "elapsedMs", "status", "connectionStatus", "httpCode", "snapshotsSaved", "snapshotsFlagged", "snapshotFailures", "message", "credentialMode", "credentialAttempts", "workingCredentialId"};
        for (int i = 0; i < batches.length(); i++) {
            JSONArray rows = batches.getJSONObject(i).optJSONArray("results");
            if (rows == null) continue;
            for (int r = 0; r < rows.length(); r++) {
                JSONObject row = rows.getJSONObject(r);
                if (row.optString("finishedAt").isEmpty()) continue;
                for (int f = 0; f < fields.length; f++) {
                    if (f > 0) out.append(',');
                    // Existing reports predate attempt metadata; preserve an unknown count as blank.
                    out.append(cell(row.optString(fields[f], fields[f].equals("credentialMode") ? "assigned" : "")));
                }
                out.append("\r\n");
            }
        }
        return out.toString();
    }

    static String cell(String text) {
        String value = text == null ? "" : text;
        // Quoting protects commas/newlines; a prefix also prevents spreadsheet formula execution.
        String trimmed = value.replaceFirst("^[\\s\\u0000-\\u001f]+", "");
        if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) value = "'" + value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}

