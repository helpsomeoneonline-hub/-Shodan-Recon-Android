# Shodan Recon Android 1.2.1

Standalone Android 8+ app extending the existing Java/WebView project. Package
`com.shodanrecon.mobile`, existing `recon_local_v1` search data and Shodan
Keystore alias are preserved. No Node.js server is required.

## Discovery

- Shodan searches and account credits with your own key entered in Settings.
- Defaults: `port:80 (DNVRS-Webs OR Hikvision-Webs OR web OR App-webs OR DVRDVS-Webs)`,
  limit 100, repeat threshold 0.5, delay 1000 ms, one page, concurrency five.
- Editable settings, profiles, favorites, result filters, history, IP import and CSV export.
- Authorized private IPv4 range inventory (one /24, maximum 256 IPs/eight ports),
  plus scanning selected imported private IPs. Shared pacing and configurable workers.
- Stop disconnects requests and sockets; session IDs reject stale results. Results
  persist during searches and interrupted sessions can be reviewed.

## Authorized cameras

Register cameras in **Cameras**, confirm administration permission, save the host,
HTTP(S)/RTSP ports and endpoint paths, then manually select a saved credential.
Discovery results never initiate camera authentication.

- Multiple profiles, Keystore AES-GCM encrypted usernames/passwords, edit/delete credentials.
- Built-in isolated HTTP/HTTPS interface, JPEG and MJPEG viewing.
- Media3 RTSP over TCP and compatible HLS, plus external RTSP player fallback.
- Method/channel selection, connection tests, diagnostics and expandable viewer.
- Rotation retention; network/playback stops when the viewer enters the background.
- HTTP Basic and Digest (MD5/SHA-256/session variants, qop auth or legacy no-qop),
  bounded authentication retries. No guessing or dictionary attacks.
- HTTPS verifies certificate chains and hostnames, trusting system CAs and
  CAs explicitly installed by the user in Android certificate settings. HTTP/HLS requests remain on the configured
  origin; redirects and cross-origin resources are blocked. Save the actual
  destination scheme, port and path.

Built-in authenticated camera pages are read-only. Login/administration forms
open in the Android browser without passing saved credentials; this avoids
WebView redirects leaking credentials.

Old browser plugins such as ActiveX cannot run on Android: use a compatible
snapshot/stream endpoint. Codec support depends on Media3, firmware and the
Android decoder. HLS media must use the same camera origin. External RTSP
launches without saved passwords; enter credentials in the external player.

## Snapshots

Default template: `/ISAPI/Streaming/channels/{channel}/picture`. Use a manual
path or same-origin URL for other brands. Default channels are 101, 201, 301,
401, 501, 601, 701, 801, 901, 1001, 1101, 1201, 1301, 1401, 1501 and 1601.

- Capture one channel or all selected channels from one registered camera.
- Thumbnail gallery with camera name, IP/host, channel and capture time.
- Preview, share, export, delete and bulk deletion of flagged images.
- SHA-256 exact duplicates and empty/corrupt image review flags.
- Progress, cancellation, bounded downloads and temporary-file cleanup.
- Private app storage remains the primary copy. Android's folder picker selects
  an optional secondary export folder. Copy failures preserve the primary image.
- Camera configuration JSON transfer excludes credentials and requires explicit
  authorization when registering imports. Re-select credentials after importing.

Passwords are never included in snapshot filenames, diagnostics, activity logs
or configuration export. Private images are removed on uninstall; exported copies survive.

## Export a diagnostic report

Open **Settings → Diagnostic log → Export Diagnostics**. Choose where to save the text file, then attach it to your ChatGPT conversation. The log includes timestamps, app action categories, HTTP status codes, and JavaScript error line numbers. It is stored privately on the phone and rotated automatically after approximately 128 KB. No API keys, passwords, full camera URLs, device IPs or camera images are written. Review the exported text before sending it. The Clear log button deletes prior events without affecting any camera or search data.

## Build and install

Actions **Build Android APK** runs dashboard checks, Java unit tests, Android
lint and debug APK assembly. Successful runs publish `ShodanRecon-debug-apk`
and `ShodanRecon-verification` artifacts for 30 days. Extract and install `app-debug.apk`.

Debug builds use a stable public development key. Read [DEBUG-SIGNING.md](DEBUG-SIGNING.md)
before updating: earlier Actions used ephemeral signing keys, so an in-place
update may be impossible without the original private key. Export data before
any uninstall. The committed development key is not a production release key.

The repository `SHODAN_API_KEY` secret is never read by the APK build or embedded
in the app. A separate account-check workflow uses it only for sanitized API verification. Enter your key in Settings; it is encrypted on your device.
See [TESTING.md](TESTING.md) for physical-device checks and compatibility limits.
