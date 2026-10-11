# Shodan Recon — Android

Standalone Android app (Android 8+), built with Java + WebView, no Node.js server.

## Features

- Responsive dark cyan dashboard for phone/tablet/portrait/landscape.
- Shodan search results and API-info/credits using the Shodan API **directly from the phone**.
- Key stored encrypted at rest using Android Keystore; not placed in source, APK, or Android build logs.
- Local-authorized private IPv4 LAN range TCP service inventory; public target probing prohibited by code.
- Paste/import individual IPv4 lists, favorites, filters, saved profiles, local search history and CSV exports.
- Connectivity/battery status, abort for LAN scan, restoration of settings/history.
- Open manually supplied RTSP URLs in an installed compatible video player on Android.

## Getting the APK

Open **Actions → Build Android APK**. When the workflow passes, download the `ShodanRecon-debug-apk` artifact and install `app-debug.apk` on Android. This is a debug build, not a Play Store release.

## Your GitHub Actions secret

`SHODAN_API_KEY` can remain as a repository secret for any **future backend-only workflow**, but **is not embedded into the Android APK**. Android APKs are inspectable; injecting the key at build time would expose it. Enter the key in **Settings → Shodan key** on your own phone. Encrypted storage is device-local.

## Scope and limitations

This is a native, serverless Android implementation and is *not* a direct execution of the uploaded `server.js`. Original Node.js functions (e.g., FFmpeg transcoding, camera snapshot download and dictionary-password authentication) have **not** been ported. Automatic credential attempts against Shodan-discovered IPs are deliberately not implemented. Shodan data is informational; only scan networks and inspect video feeds you are authorized to access.

Network and battery indicators depend on OS permissions; no automatic background scans. Result/profile history is stored locally on the device, not synced.
