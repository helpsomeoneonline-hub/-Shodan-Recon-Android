#!/usr/bin/env bash
set -euo pipefail
mkdir -p smoke-results smoke-build/classes smoke-build/dex
SMOKE_SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "$SMOKE_SDK" ]]; then
  echo 'Android SDK is unavailable.'
  exit 1
fi
SMOKE_TOOLS="$SMOKE_SDK/build-tools/35.0.0"
if [[ ! -x "$SMOKE_TOOLS/aapt2" ]]; then
  SMOKE_TOOLS=$(python3 -c 'import os,pathlib; p=pathlib.Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"],"build-tools"); print(sorted([x for x in p.iterdir() if (x/"aapt2").exists()])[-1])')
fi
SMOKE_PLATFORM="$SMOKE_SDK/platforms/android-35/android.jar"
if [[ ! -f "$SMOKE_PLATFORM" ]]; then
  SMOKE_PLATFORM=$(python3 -c 'import os,pathlib; p=pathlib.Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"],"platforms"); print(sorted(p.glob("*/android.jar"))[-1])')
fi
javac -source 17 -target 17 -classpath "$SMOKE_PLATFORM" -d smoke-build/classes smoke/SmokeInstrumentation.java
mapfile -t SMOKE_CLASSES < <(python3 -c 'from pathlib import Path; print("\n".join(str(p) for p in Path("smoke-build/classes").rglob("*.class")))')
if [[ "${#SMOKE_CLASSES[@]}" -eq 0 ]]; then
  echo 'No instrumentation classes were generated.'
  exit 1
fi
"$SMOKE_TOOLS/d8" --lib "$SMOKE_PLATFORM" --min-api 26 --output smoke-build/dex "${SMOKE_CLASSES[@]}"
"$SMOKE_TOOLS/aapt2" link --manifest smoke/AndroidManifest.xml -I "$SMOKE_PLATFORM" -o smoke-build/harness-unsigned.apk
python3 -c 'import zipfile; z=zipfile.ZipFile("smoke-build/harness-unsigned.apk","a"); z.write("smoke-build/dex/classes.dex","classes.dex"); z.close()'
"$SMOKE_TOOLS/zipalign" -f 4 smoke-build/harness-unsigned.apk smoke-build/harness-aligned.apk
"$SMOKE_TOOLS/apksigner" sign --ks app/development-debug.keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out smoke-build/harness.apk smoke-build/harness-aligned.apk
SMOKE_PHASE=fresh
if [[ -f update-baseline-apk/app-debug.apk ]]; then
  "$SMOKE_TOOLS/apksigner" verify --print-certs update-baseline-apk/app-debug.apk > smoke-results/baseline-signature.txt
  "$SMOKE_TOOLS/apksigner" verify --print-certs smoke-apk/app-debug.apk > smoke-results/update-signature.txt
  python3 -c 'import re; from pathlib import Path; cert=lambda p: re.search(r"Signer #1 certificate SHA-256 digest: (\w+)",Path(p).read_text()).group(1); assert cert("smoke-results/baseline-signature.txt")==cert("smoke-results/update-signature.txt"), "Installed-version signing identity changed"'
  adb install -r -t update-baseline-apk/app-debug.apk > smoke-results/baseline-install.txt
  adb install -r -t smoke-build/harness.apk
  adb shell am instrument -w -e phase seed com.shodanrecon.smoke/com.shodanrecon.smoke.SmokeInstrumentation > smoke-results/baseline-seed.txt 2>&1
  python3 -c 'from pathlib import Path; assert "smoke_success=true" in Path("smoke-results/baseline-seed.txt").read_text(), "Installed-version seed assertions failed"'
  SMOKE_PHASE=upgrade
fi
adb install -r -t smoke-apk/app-debug.apk > smoke-results/update-install.txt
adb install -r -t smoke-build/harness.apk
adb logcat -c
set +e
adb shell am instrument -w -e phase "$SMOKE_PHASE" com.shodanrecon.smoke/com.shodanrecon.smoke.SmokeInstrumentation > smoke-results/instrumentation.txt 2>&1
SMOKE_STATUS=$?
set -e
cat smoke-results/instrumentation.txt
adb logcat -d -s AndroidRuntime:E > smoke-results/android-runtime.txt
python3 smoke/collect.py
if [[ "$SMOKE_STATUS" -ne 0 ]]; then exit "$SMOKE_STATUS"; fi
python3 -c 'from pathlib import Path; t=Path("smoke-results/instrumentation.txt").read_text(); assert "smoke_success=true" in t, "Emulator smoke assertions failed"; c=Path("smoke-results/android-runtime.txt").read_text(); assert "com.shodanrecon.mobile" not in c, "AndroidRuntime reported an app crash"'
adb shell am force-stop com.shodanrecon.mobile
adb shell am instrument -w -e phase review com.shodanrecon.smoke/com.shodanrecon.smoke.SmokeInstrumentation > smoke-results/restart-review.txt 2>&1
python3 -c 'from pathlib import Path; assert "smoke_success=true" in Path("smoke-results/restart-review.txt").read_text(), "Reports/gallery/preset did not survive app restart"'
