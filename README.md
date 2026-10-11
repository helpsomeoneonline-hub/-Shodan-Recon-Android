# Shodan Recon Android v1.1

Standalone Android (Android 8+) app built with Java and a responsive WebView. It runs on the phone without a Node.js server.

## Working features

- Direct Shodan API search and API-credit information, with the key stored locally using Android Keystore encryption.
- Default Shodan query: `port:80 (DNVRS-Webs OR Hikvision-Webs OR web OR App-webs OR DVRDVS-Webs)`.
- Default search settings: 100 results, one page, repeat threshold 0.5, and a 1,000 ms delay between requested pages. Up to five pages can be selected.
- Authorized private-network port inventory for up to 256 IPs in one /24 subnet, up to eight specified ports, and a configurable 1–8 worker limit (default five).
- Search history, saved profiles, IP-list import, favorites, filters and CSV export.
- Manual HTTP/HTTPS camera web-page opening for cameras on private IPv4 networks you administer.
- Manual ISAPI snapshot-address opening in your browser with a channel selector (101–1601); authentication and image display depend on the camera/browser. **The app does not download or store these snapshots.**
- Open user-entered RTSP streams in a compatible installed player, such as VLC.
- Portrait, landscape and tablet screen layouts.

## APK build

Open **Actions → Build Android APK** and download the latest successful `ShodanRecon-debug-apk` artifact. This is a debug build rather than a Play Store release. Version 1.1 has `versionCode 2`, so it can update version 1.0 if installed with the same signing key.

## Shodan API secret

Your `SHODAN_API_KEY` GitHub Actions secret is **not embedded** in the APK. Enter the key once in **Settings → Shodan API** inside the Android app. It is encrypted on your device. A repository secret cannot be safely shipped inside an extractable APK.

## Boundaries and limitations

Shodan results are discovery-only. Direct service checks and HTTP-camera links are restricted to explicitly supplied private IPv4 addresses. The app does not try password dictionaries, test credentials on public Shodan results, download protected camera snapshots, or include the old Node.js FFmpeg proxy. The manual HTTP camera viewer opens your system browser; it is not an in-app live-video decoder.

Only connect to networks and cameras you own or have explicit permission to administer.
