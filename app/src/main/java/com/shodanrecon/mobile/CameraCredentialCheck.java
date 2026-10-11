package com.shodanrecon.mobile;

import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

import okhttp3.Response;

/** Sequential preset checks on one authorized camera; only a definite rejection advances a preset. */
final class CameraCredentialCheck {
    interface Listener {
        void starting(String credentialId, int index, int total) throws Exception;
        void active(CameraHttp transport) throws Exception;
        void finished(String credentialId, String status, int httpCode) throws Exception;
    }

    static final class Result {
        final CameraHttp transport;
        final int httpCode;
        final String workingCredentialId;
        Result(CameraHttp transport, int httpCode, String workingCredentialId) {
            this.transport = transport;
            this.httpCode = httpCode;
            this.workingCredentialId = workingCredentialId;
        }
    }

    private CameraCredentialCheck() {}

    static Result connect(JSONObject camera, String probeUrl, List<JSONObject> candidates,
                          BooleanSupplier cancelled, Listener listener) throws Exception {
        if (camera == null || !camera.optBoolean("authorized"))
            throw new IOException("Register this camera and confirm authorization first.");
        validateCandidates(candidates);
        for (int index = 0; index < candidates.size(); index++) {
            checkCancelled(cancelled);
            JSONObject candidate = candidates.get(index);
            String id = candidate.getString("id");
            listener.starting(id, index + 1, candidates.size());
            int code = 0;
            boolean rejected = false;
            CameraHttp transport = null;
            Result result = null;
            Exception failure = null;
            try {
                transport = new CameraHttp(camera, candidate);
                listener.active(transport);
                checkCancelled(cancelled);
                try (Response response = transport.get(probeUrl)) {
                    code = response.code();
                    checkCancelled(cancelled);
                    if (code == 401) {
                        rejected = CameraHttp.authenticationRejected(response);
                        throw new CameraHttp.StatusException(code, rejected
                                ? "HTTP 401: The camera rejected this password preset."
                                : "HTTP 401: Camera authentication could not be completed. Password testing stopped for this camera; check its authentication or account restrictions.");
                    }
                    checkResponse(response);
                    if (response.body().byteStream().read() == -1)
                        throw new IOException("The camera returned an empty snapshot response. Check its configured path.");
                    checkCancelled(cancelled);
                    // An unprotected endpoint is reachable, but does not verify a saved password.
                    String workingId = response.request().header("Authorization") == null ? "" : id;
                    result = new Result(transport, code, workingId);
                }
            } catch (Exception e) {
                failure = e;
                if (e instanceof CameraHttp.StatusException) code = ((CameraHttp.StatusException) e).statusCode;
            }
            listener.finished(id, result == null ? CameraBatchReport.failureStatus(failure, code, cancelled.getAsBoolean()) : "success", code);
            if (result != null) return result;
            if (transport != null) transport.cancel();
            checkCancelled(cancelled);
            if (!rejected) throw failure;
        }
        throw new CameraHttp.StatusException(401, "HTTP 401: All " + candidates.size() + " configured password presets were rejected by this camera.");
    }

    private static void validateCandidates(List<JSONObject> candidates) throws Exception {
        if (candidates == null || candidates.isEmpty() || candidates.size() > 6)
            throw new IOException("Configure one to six saved password presets first.");
        int previous = 0;
        String username = "";
        for (JSONObject candidate : candidates) {
            if (candidate == null || !candidate.optString("id").matches("preset-[1-6]"))
                throw new IOException("Choose the configured saved password presets.");
            int number = candidate.getString("id").charAt(7) - '0';
            if (number <= previous) throw new IOException("Saved password presets must be checked once in their configured order.");
            previous = number;
            String candidateUsername = candidate.optString("username");
            if (candidateUsername.isEmpty() || candidate.optString("password").isEmpty())
                throw new IOException("Save a shared username and password for every configured preset.");
            if (!username.isEmpty() && !username.equals(candidateUsername))
                throw new IOException("Saved password presets must use the same shared username.");
            username = candidateUsername;
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new IOException("Camera task cancelled.");
    }

    private static void checkResponse(Response response) throws IOException {
        int code = response.code();
        if (code == 403) throw new CameraHttp.StatusException(code, "HTTP 403: Camera access is denied. Password testing stopped for this camera; check account permissions.");
        if (code == 423) throw new CameraHttp.StatusException(code, "HTTP 423: The camera or account is locked. Password testing stopped for this camera.");
        if (code == 429) throw new CameraHttp.StatusException(code, "HTTP 429: The camera is limiting requests. Password testing stopped for this camera.");
        if (code == 404) throw new CameraHttp.StatusException(code, "HTTP 404: Snapshot path or channel was not found. Check the configured URL template.");
        if (!response.isSuccessful()) throw new CameraHttp.StatusException(code, "Camera returned HTTP " + code + ". Password testing stopped for this camera; check configuration and availability.");
        if (response.body() == null) throw new IOException("The camera returned no snapshot response body.");
    }
}
