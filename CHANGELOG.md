# Changelog

## 1.3.1 — versionCode 6

- Allow 1–20 parallel TCP port inventory workers for authorized RFC1918 private-network scans; default 20 for new installs.
- Keep the existing 256-address, eight-port, cancellation, and authorization constraints. This change does not attempt camera passwords.
- Preserve the existing camera snapshot, Shodan discovery, saved credentials, and export behavior.

## 1.3.0 — versionCode 5

- Add sequential Automatic Camera Manager with multi-selection, Start/Stop,
  assigned-credential HTTP checks, automatic configured-channel snapshots,
  current-camera progress, counters, summaries and persistent per-camera results.
- Continue after authentication/network failures; prevent overlapping runs and
  retain completed/stopped/interrupted reports with CSV export.
- Add one shared username and exactly six Keystore-encrypted password presets,
  configured status, replacement/deletion and camera assignment. Preserve legacy
  credentials and existing camera assignments during updates.
- Add discovery source labels, result sorting/source filters, displayed-result
  CSV export, direct result-to-IP saving and IP-to-camera registration.
- Add Gallery navigation, camera/address/date filters, camera/date storage folders
  and stricter complete-image validation while preserving existing images.
- Add host/address/port/channel URL templates with existing origin and TLS checks.
- Preserve Shodan integration and HTTP 400 metadata/query handling, private scans,
  existing viewer protocols, diagnostic TXT export and saved settings.
- Retain package/signing identity; verify version and signing certificate in CI.
  Add exact-APK emulator smoke for baseline-to-new in-place updates, retained
  Keystore credentials and controlled loopback batch/cancellation checks.

## 1.2.1 — versionCode 4

- Add private, size-bounded diagnostics journal recording Shodan HTTP/network failures, private-LAN scan outcomes, camera operations and viewer failures.
- Add Settings > Diagnostic log with read-only preview, clear and Android TXT export; attach the TXT file in ChatGPT for troubleshooting.
- Record JavaScript runtime and console errors without logging source text, sensitive URLs or device passwords.
- Keep APK build signing identity, app data and existing functions unchanged.


## 1.2.0 — versionCode 3

- Extend the existing app with registered cameras and encrypted device credentials.
- Add isolated HTTP/HTTPS, JPEG/MJPEG, Media3 RTSP/HLS and diagnostics.
- Add selected-channel capture, local gallery, duplicate/corruption review,
  preview/share/export/delete and optional Android folder copies.
- Add non-sensitive configuration transfer and device activity history.
- Add selected imported-IP scanning, authorization confirmation, paced concurrency,
  active network cancellation and stale search response rejection.
- Preserve package, search defaults, existing data, key storage, profiles,
  favorites, history, CSV export and external player integration.
- Add responsive camera/dashboard layouts and expandable native viewer.
- Add authentication, URL, stream-parser and dashboard regression tests.
- Request displayed Shodan metadata explicitly to avoid oversized service banners.
- Run tests/lint/APK build in Actions and stabilize future development signing.
