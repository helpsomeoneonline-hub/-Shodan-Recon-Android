# Emulator and update verification

The pull-request **Build Android APK** workflow builds both the PR-base installed
version and the new application. Its dependent emulator job downloads these exact
same-run artifacts. The main-branch standalone **Android emulator smoke** workflow
uses the successful build's exact run ID and commit SHA; manual runs require both
values and verify them before downloading an APK.

A separately compiled instrumentation harness uses the existing public development
signing identity. It exercises the downloaded APKs without rebuilding the new app.
The update check installs the base APK, saves synthetic search settings, a private
IP, registered loopback camera and encrypted legacy credential, then installs the
new APK with `adb install -r`. Matching certificate digests, installation success,
retained data and original Android Keystore credential decryption are asserted.

UI checks cover portrait/landscape navigation, touch targets, persisted search
inputs, Cameras/Gallery, exactly one shared username and six blank password inputs,
successful saves/replacement, native validation failure and retry, hidden passwords,
and native viewer launch/rotation without connecting the viewer.

The batch fixture listens only on emulator `127.0.0.1`. It uses a synthetic HTTP
Basic credential and generated 8×8 JPEGs. Selected registered cameras produce
success → HTTP 401 → unreachable port → success; the harness checks request order,
assigned-credential authentication, configured channel 101, counters and gallery.
A stalled local request checks repeated Start, prompt Stop, retained cancelled
outcome and prevention of later-camera requests. A process restart must retain
completed/stopped reports, images and encrypted preset status. No Shodan API key,
real camera, user password or external device network endpoint is contacted.

`ShodanRecon-emulator-smoke` contains screenshots, accessibility hierarchy XML,
installation and signing evidence, instrumentation results, completed/stopped batch
JSON, local request order, restart review and AndroidRuntime output. The APK and
JVM/lint reports remain separate artifacts.

Emulator success does not verify real cameras, codecs, Shodan account access,
physical device behavior or external Android file providers. The update test covers
the preserved stable development certificate; it cannot establish compatibility
with historical ephemeral signing keys. Runner/emulator startup failures are
infrastructure failures, not completed application tests.
