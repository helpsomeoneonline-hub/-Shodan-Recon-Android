# Shodan Recon Android 1.4.0

Standalone Android 8+ app extending the existing Java/WebView project. Package
`com.shodanrecon.mobile`, existing `recon_local_v1` search data and Shodan
Keystore alias are preserved. No Node.js server is required.

Version 1.4.0 is a source update. Its automatic saved-password feature has not
been executed or verified; tests and CI are skipped at the user's request.

## Discovery

- Shodan searches and account credits with your own key entered in Settings.
- Defaults: `port:80 (DNVRS-Webs OR Hikvision-Webs OR web OR App-webs OR DVRDVS-Webs)`,
  limit 100, repeat threshold 0.5, delay 1000 ms, one page, concurrency five.
- Saved search queries/profiles, favorites, paginated discovery, filters, sorting,
  history, TXT/CSV IP import and CSV export of the displayed results.
- Results and saved IPs identify Shodan, private-network scans and imported addresses.
  Save one or all displayed result IPs, then register an address directly from the IP List.
- Authorized private IPv4 range inventory (one /24, maximum 256 IPs/eight ports),
  plus scanning selected imported private IPs. Shared pacing and configurable workers.
- Stop disconnects requests and sockets; session IDs reject stale results. Results
  persist during searches and interrupted sessions can be reviewed.

## Authorized cameras

Register cameras in **Cameras**, confirm administration permission, save the host,
HTTP(S)/RTSP ports and endpoint templates. Assign a saved password preset for
manual viewing, or use automatic saved-password testing in the manager.
Discovery results never initiate camera authentication.

- One shared username and exactly six password presets in **Settings**, encrypted
  using Android Keystore AES-GCM. Saved passwords remain hidden; each preset shows
  configured/unconfigured status and can be replaced or deleted.
- Existing camera profiles, legacy encrypted credentials and assignments remain
  available after updating. Legacy credentials can be explicitly removed.
- Built-in isolated HTTP/HTTPS interface, JPEG and MJPEG viewing.
- Media3 RTSP over TCP and compatible HLS, plus external RTSP player fallback.
- Method/channel selection, connection tests, diagnostics and expandable viewer.
- Templates insert `{host}`, `{ip}`, `{port}` and `{channel}` while enforcing each
  camera's configured origin; HTTP/HTTPS ports remain configurable.
- Rotation retention; network/playback stops when the viewer enters the background.
- HTTP Basic and Digest (MD5/SHA-256/session variants, qop auth or legacy no-qop),
  bounded authentication retries. Automatic password checks use only your six
  saved preset slots and registered cameras you select.
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

## Automatic camera manager

1. Search Shodan, identify addresses belonging to you, and use **Register camera**
   on a result or saved IP. Confirm administration permission and configure that
   camera's snapshot path and channels.
2. Save one shared username and your passwords in the six preset slots in
   **Settings**. Existing encrypted presets remain available after updating.
3. Open **Cameras → Automatic Camera Manager**, select your registered cameras,
   choose **Test saved passwords automatically** and press **Start**.
4. For each camera, the app checks its first configured snapshot channel with
   presets 1 through 6 in order, skipping empty slots. A definite authentication
   rejection advances to the next password. The first successful credential is
   reused for snapshot channels and remembered in that camera's profile. The app
   saves the result, then automatically advances to the next selected camera.
5. Review camera/password progress, the working preset, completed checks and saved
   snapshots. **Stop** cancels the active request and prevents remaining cameras
   from starting.
6. Review saved results or export the camera-check CSV; use **Gallery** to view
   snapshots and **Settings → Diagnostic log** to export diagnostics.

Failed checks do not stop later cameras. Forbidden access, account locks, rate
limits, network/TLS errors and configuration failures stop password attempts for
that camera. An endpoint accessible without authentication is reported without
claiming a password was verified. **Use each camera's assigned credential** keeps
the existing single-credential interface check. Completed and interrupted reports
remain available after reopening the app. These checks do not establish RTSP,
MJPEG or HLS playback compatibility; use the existing viewer for those methods.

## Snapshots

Default template: `/ISAPI/Streaming/channels/{channel}/picture`. Use a manual
path or same-origin URL for other brands. Default channels are 101, 201, 301,
401, 501, 601, 701, 801, 901, 1001, 1101, 1201, 1301, 1401, 1501 and 1601.

- Capture one channel or all selected channels from one registered camera.
- Thumbnail gallery with camera name, IP/host, channel and capture time.
- Gallery filters for camera/address and capture date. New images are organized
  in private camera/date folders; existing gallery images remain accessible.
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

Version 1.4.0 uses versionCode 6 and retains `com.shodanrecon.mobile`, the existing
signing configuration, preference names and storage paths. A matching signed
installation updates in place; do not uninstall to update. The PR workflow installs
the previous base APK, saves synthetic settings/IPs/camera/Keystore credentials,
then installs this APK with `adb install -r` and checks retained data.

The repository `SHODAN_API_KEY` secret is never read by the APK build or embedded
in the app. A separate account-check workflow uses it only for sanitized API verification. Enter your key in Settings; it is encrypted on your device.
See [TESTING.md](TESTING.md) for physical-device checks and compatibility limits.

