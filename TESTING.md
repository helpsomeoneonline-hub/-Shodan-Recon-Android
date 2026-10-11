# Verification and physical-device acceptance

The GitHub Actions run and `ShodanRecon-verification` artifact record dashboard
checks, JVM tests, Android lint and debug APK assembly. Tests use local HTTP
fixtures, never public cameras. Compilation alone does not establish compatibility.

## Recorded build verification — 2026-10-11

Application commit: `9098d4c480b7961947b3663e7c35604c40fa8f0a`.
[Successful Actions run](https://github.com/helpsomeoneonline-hub/-Shodan-Recon-Android/actions/runs/38141506505).
[APK artifact](https://github.com/helpsomeoneonline-hub/-Shodan-Recon-Android/actions/runs/38141506505/artifacts/11702468827).
[Verification reports](https://github.com/helpsomeoneonline-hub/-Shodan-Recon-Android/actions/runs/38141506505/artifacts/11702773441).

- Ten dashboard regression tests passed, plus syntax and 126 control-reference checks.
- 31 JVM tests passed: 17 HTTP/authentication, seven URL validation, four MJPEG parser,
  three snapshot completion-marker tests.
- Android Java/unit-test compilation, lint and debug assembly passed. Lint has warnings
  for compatibility/dependency and deliberate camera networking/browser configuration;
  there are no lint errors.
- APK package `com.shodanrecon.mobile`, versionCode 3, signature and required packaged
  assets verified; key-injection pattern checks passed without reading the Shodan secret.

No physical Android device, authorized camera or user Shodan account is attached to this
workspace. Physical testing is required for:

1. Install on Android 8+ phone/tablet. Compare signing identities before updating;
   verify retained app data when signatures match. See DEBUG-SIGNING.md.
2. Enter a Shodan key, check credits, search/stop/retry API and network failures.
   Confirm a late reply cannot change a newer session.
3. Scan authorized private ranges and selected imported IPs; verify pacing,
   workers, Stop, favorites/history and CSV via Android document pickers.
4. Register an authorized camera; test Basic/Digest, wrong credentials, 403/404,
   timeouts and trusted HTTPS. Confirm untrusted TLS fails and exports/logs omit secrets.
5. Test the external-browser fallback for HTTP login/admin forms, JPEG/MJPEG and actual RTSP/HLS codecs. Switch methods
   and channels; rotate; background the app; check external player fallback.
6. Capture one channel and multiple selected channels using ISAPI and manual
   endpoints. Check capture metadata, progress, cancellation and saved files.
7. Capture a fixed image twice; review duplicates. Use controlled empty/truncated
   replies to review corruption. Preview/share/export/delete and bulk-delete flagged images.
8. Pick a writable Android folder, revoke access and capture again. Verify the
   primary image remains and a failed optional copy is reported.
9. Import/export profiles, confirm authorization, edit/delete credentials, filter
   devices/gallery, reopen the app and check persistence.
10. Check phone portrait/landscape, split-screen and tablets. Bottom controls must
    remain above Android navigation, cards must scroll and viewer must expand
    without losing saved profiles or unnecessarily restarting playback on rotation.

Compatibility limits: obsolete browser plugins, unsupported codecs, cross-origin
HLS resources, Digest auth-int/userhash and untrusted TLS certificates require a
supported endpoint or device configuration change. No Node/FFmpeg proxy,
credential guessing or discovery-triggered device authentication is implemented.
