# Development APK signing

`app/development-debug.keystore` is a **public development-only signing key**.
Its conventional password is `android`. Do not use this key to distribute a
production release. Future debug APKs built from this project use this same key,
so they can update one another while retaining application data and settings.

Its public certificate SHA-256 is
`47:06:33:FE:1E:CB:95:AD:A9:E1:8C:A0:5E:2B:7D:37:0B:F3:C5:06:6A:3C:F7:93:D6:F3:C5:36:3C:A0:30:88`.
Version 1.3.0 retains this key, package `com.shodanrecon.mobile` and increments
versionCode to 5. The pull-request emulator check installs the base-version APK,
saves synthetic data and an encrypted legacy credential, then uses `adb install -r`
to install the new APK and verifies retained settings, IPs, camera assignments and
Keystore decryption. This check covers the stable signing identity only.

Earlier GitHub Actions builds used Android's automatically generated debug key
on an ephemeral runner. Their private signing key is unavailable to this project.
Android permits an update only when the installed APK and new APK have the same
signing identity. An earlier installation with a different key therefore cannot
be updated in place. Copy its settings and IP lists and export its CSV results before
uninstalling it; installing the new debug APK then starts the stable development
signing identity. Secrets must be entered again. Keep the old installation until
you have copied any data you need.

For a production release, create and safeguard a separate private release key
and configure release signing outside the repository.
