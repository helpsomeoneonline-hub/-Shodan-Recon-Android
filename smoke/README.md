# Emulator smoke verification

This workflow installs the exact APK from build run `38141506505` after it succeeds
(application commit `9098d4c480b7961947b3663e7c35604c40fa8f0a`). A separately
compiled Android instrumentation harness exercises that installed APK; it does
not rebuild or modify the application.

It checks first launch, the dashboard's fixed bottom navigation and touch
targets in portrait and landscape, saved search input, Cameras navigation,
empty gallery controls, a blank credential form, and native camera viewer
launch and rotation. The fixture is a manually registered authorized loopback
profile; **Connect, capture, scan and account-check are never invoked**. It uses
no real API key, device password or camera network endpoint. The harness shares
the public development signing identity required to instrument the debug APK.

Screenshots, accessibility hierarchy XML, instrumentation results and
AndroidRuntime error output are uploaded as `ShodanRecon-emulator-smoke`.
Emulator success does not verify real cameras, codecs, Shodan account access,
physical device behavior or external Android file providers. An emulator or
runner startup failure is an infrastructure failure, not a completed app test.
