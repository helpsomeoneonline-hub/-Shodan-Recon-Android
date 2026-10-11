package com.shodanrecon.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Commits each camera/channel outcome; interrupted runs remain reviewable after app restart. */
final class CameraBatchStore {
    private static final int RETAINED_RUNS = 20;
    private final SharedPreferences prefs;

    CameraBatchStore(Context context) {
        this(context.getApplicationContext().getSharedPreferences("recon_camera_batches_v1", Context.MODE_PRIVATE));
    }

    CameraBatchStore(SharedPreferences prefs) { this.prefs = prefs; }

    private JSONObject data() throws Exception {
        try { return new JSONObject(prefs.getString("batches", "{\"history\":[]}")); }
        catch (Exception e) { throw new Exception("Saved camera check reports cannot be read. Existing reports were preserved."); }
    }

    private void persist(JSONObject data) throws Exception {
        if (!prefs.edit().putString("batches", data.toString()).commit()) throw new Exception("Cannot save camera check results. Check available device storage.");
    }

    synchronized JSONObject state() throws Exception {
        JSONObject current = data().optJSONObject("current");
        return current == null ? CameraBatchReport.empty() : current;
    }

    synchronized JSONArray history() throws Exception {
        JSONArray result = data().optJSONArray("history");
        return result == null ? new JSONArray() : result;
    }

    synchronized JSONObject start(int total) throws Exception {
        return start(total, "assigned");
    }

    synchronized JSONObject start(int total, String credentialMode) throws Exception {
        JSONObject source = data();
        JSONObject previous = source.optJSONObject("current");
        if (previous != null && previous.optBoolean("running")) throw new Exception("A camera batch is already running.");
        if (previous != null && !previous.optString("runId").isEmpty()) {
            JSONArray history = new JSONArray().put(previous), old = source.optJSONArray("history");
            if (old != null) for (int i = 0; i < Math.min(old.length(), RETAINED_RUNS - 1); i++) history.put(old.get(i));
            source.put("history", history);
        }
        JSONObject current = CameraBatchReport.start(total, credentialMode);
        source.put("current", current); persist(source); return current;
    }

    /** A closed bridge cannot overwrite a terminal report or a newer run. */
    synchronized boolean update(JSONObject current) throws Exception {
        JSONObject source = data(), saved = source.optJSONObject("current");
        if (saved == null || !saved.optBoolean("running") || !saved.optString("runId").equals(current.optString("runId"))) return false;
        source.put("current", current); persist(source); return true;
    }

    synchronized void interrupt() throws Exception {
        JSONObject source = data(), current = source.optJSONObject("current");
        if (current == null || !current.optBoolean("running")) return;
        CameraBatchReport.finish(current, "interrupted"); source.put("current", current); persist(source);
    }

    synchronized String csv() throws Exception {
        JSONObject source = data();
        JSONArray batches = new JSONArray(); JSONObject current = source.optJSONObject("current");
        if (current != null) batches.put(current);
        JSONArray old = source.optJSONArray("history");
        if (old != null) for (int i = 0; i < old.length(); i++) batches.put(old.get(i));
        return CameraBatchReport.csv(batches);
    }
}

