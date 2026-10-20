# Shodan Recon APK upgrade signing (existing stable identity)

This repository already uses a persistent debug keystore at `app/development-debug.keystore` and a dedicated Gradle `signingConfigs.development`, alias `androiddebugkey`, with the package ID `com.shodanrecon.mobile`. The key remains constant between GitHub runners because Gradle references the retained repo file, not a new `~/.android/debug.keystore`.

**DO NOT regenerate, replace, or re-sign with another key.** The same original certificate is required to update existing installs. Increase versionCode for new distributed versions and verify the new APK fingerprint against the older installed APK. The existing PR smoke test checks an install-over-install path using old/new builds, but this does not by itself prove compatibility with every external installed version.

For better security, the same private signing key may eventually be migrated to a GitHub Actions secret (with private backup), but copying the identity, not rotating keys, is essential. A private key cannot be reconstructed from the certificate of an APK. If the repository is accessible beyond trusted maintainers, treat the tracked keystore as sensitive.

See Android's signing guidance: https://developer.android.com/studio/publish/app-signing
