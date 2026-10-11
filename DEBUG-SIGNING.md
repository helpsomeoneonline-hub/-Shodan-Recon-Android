# Development APK signing

`app/development-debug.keystore` is a **public development-only signing key**.
Its conventional password is `android`. Do not use this key to distribute a
production release. Future debug APKs built from this project use this same key,
so they can update one another while retaining application data and settings.

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
