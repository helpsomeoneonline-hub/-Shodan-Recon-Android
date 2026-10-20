# Permanent Android APK signing — required for seamless updates

GitHub Actions runners are disposable. Their default debug keystore changes across runs, so a new APK often cannot update an installed APK. Every delivered APK must retain the original applicationId, increase versionCode, and be signed with the **same private key and signing certificate**.

This workflow builds the existing Android variant, then re-signs the final APK with a retained app-specific keystore from encrypted GitHub Actions secrets. It checks the public SHA-256 fingerprint from an Actions variable before allowing the APK artifact to be published.

## One-time setup in this repository

Go to **Settings > Secrets and variables > Actions**.

If the private keystore used to sign the installed app exists, REUSE that key. If the key alias is not `app`, change the alias without changing the key/certificate (e.g. `keytool -changealias`). If its old signing key is lost, a new key cannot update that original installed APK: first back up app data and plan one controlled migration. The APK and its public certificate are insufficient to reconstruct the private key.

If no old key can be recovered, create a **new and then permanent** key on your trusted Windows computer with Java JDK keytool:
```powershell
keytool -genkeypair -keystore .\Shodan-Recon-Android-signing.jks -storetype PKCS12 -alias app -keyalg RSA -keysize 3072 -validity 10950 -dname "CN=Shodan Recon Android, O=Personal Apps, C=TT"
```
Use a strong unique password; a PKCS12 private key uses the same password as its keystore.

Configure all three items in GitHub:
1. **Secret** `ANDROID_SIGNING_KEYSTORE_B64`: one-line Base64 of that original binary keystore file, generated with PowerShell:
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path '.\Shodan-Recon-Android-signing.jks')))
   ```
2. **Secret** `ANDROID_SIGNING_STORE_PASSWORD`: the keystore password.
3. **Variable** `ANDROID_SIGNING_CERT_SHA256`: the SHA-256 fingerprint for alias `app`, shown by:
   ```powershell
   keytool -list -v -keystore .\Shodan-Recon-Android-signing.jks -alias app
   ```

Back up the keystore securely in two locations you control and retain its password in a password manager. Do not commit or send a private signing key/password in chat. Encrypted GitHub secrets are **not readable back** by the assistant; GitHub makes them available to the workflow for each new build. The certificate fingerprint, unlike the private key, is public and can be retrieved again from any signed APK.

Until setup is complete, the workflow will **intentionally fail** instead of delivering another APK with a different certificate. Do not mistake a successful Gradle compilation for a deliverable signed update.

For every future release: preserve the package ID and user data, increment versionCode, reuse the exact signing key, and test an install-over-existing-app path with a data backup.


Repository: helpsomeoneonline-hub/-Shodan-Recon-Android. For PR emulator update tests, signing secrets must be available or the APK is intentionally blocked.
