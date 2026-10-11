"""Check source policy and the built APK without loading any repository secret."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--apk', type=Path)
args = parser.parse_args()

patterns = [
    re.compile(r'https?://api\.shodan\.io/[^\s\x00\"\']*[?&]key=[A-Za-z0-9_-]{16,}', re.I),
    re.compile(r'(?:SHODAN_API_KEY|shodanApiKey|shodan_api_key)\s*[:=]\s*[\"\'][A-Za-z0-9_-]{16,}', re.I),
    re.compile(r'[\"\']apiKey[\"\']\s*:\s*[\"\'][A-Za-z0-9_-]{16,}', re.I),
    re.compile(r'putString\s*\(\s*[\"\']apiKey[\"\']\s*,\s*[\"\'][A-Za-z0-9_-]{16,}', re.I),
    re.compile(r'buildConfigField[^\n]*(?:SHODAN|API_KEY|PASSWORD|SECRET)', re.I),
]

def no_embedded_key(text, filename):
    if any(pattern.search(text) for pattern in patterns):
        raise SystemExit(f'Possible embedded secret in {filename}; contents were withheld.')

for path in Path('app').rglob('*'):
    if path.is_file() and 'build' not in path.parts and path.suffix in {'.java', '.kt', '.xml', '.html', '.js', '.gradle'}:
        no_embedded_key(path.read_text(encoding='utf-8'), path)
for path in Path('.github/workflows').glob('*.y*ml'):
    workflow = path.read_text()
    if re.search(r'secrets\s*(?:\.\s*SHODAN_API_KEY|\[\s*[\"\']SHODAN_API_KEY)', workflow, re.I):
        # A separate account-validation job may use the explicitly provided secret;
        # it never builds, downloads or uploads an APK. Application builds stay secret-free.
        if path.name != 'shodan-api-check.yml' or 'verification/shodan_api_check.py' not in workflow:
            raise SystemExit(f'{path} reads SHODAN_API_KEY; standalone builds must not receive that secret.')
        if re.search(r'gradle|assembleDebug|apksigner|upload-artifact|download-artifact', workflow, re.I):
            raise SystemExit('The isolated Shodan check must not build or exchange application artifacts.')

manifest = ET.parse('app/src/main/AndroidManifest.xml').getroot()
android = '{http://schemas.android.com/apk/res/android}'
application = manifest.find('application')
assert application is not None and application.get(android + 'allowBackup') == 'false', 'Secret-bearing app backup must stay disabled'
assert manifest.find("uses-permission[@" + android + "name='android.permission.INTERNET']") is not None
for entry in application.findall('activity'):
    if entry.get(android + 'name') != '.MainActivity':
        assert entry.get(android + 'exported') == 'false', 'Internal camera activity must not be exported'
for provider in application.findall('provider'):
    assert provider.get(android + 'exported') == 'false', 'Snapshot provider must not be exported'
print('Manifest and source secret-injection checks passed; no repository secret was accessed.')

if args.apk:
    with zipfile.ZipFile(args.apk) as apk:
        members = set(apk.namelist())
        for required in ('AndroidManifest.xml', 'classes.dex', 'assets/index.html', 'assets/cameras.js'):
            assert required in members, f'APK missing required component: {required}'
        for member in members:
            if member.endswith(('.keystore', '.jks', '.env')):
                raise SystemExit(f'APK unexpectedly packages sensitive-file type: {member}')
            if member.endswith(('.dex', '.html', '.js', '.json', '.xml', '.properties')):
                raw = apk.read(member)
                no_embedded_key(raw.decode('latin-1'), member)
                if b'SHODAN_API_KEY' in raw:
                    raise SystemExit(f'APK contains SHODAN_API_KEY injection marker in {member}; contents were withheld.')
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    assert sdk, 'Android SDK is required to verify APK identity and signature'
    versions = sorted(Path(sdk, 'build-tools').glob('*'), key=lambda path: tuple(int(part) if part.isdigit() else 0 for part in path.name.split('.')))
    toolset = next((path for path in reversed(versions) if (path / 'aapt').exists() and (path / 'apksigner').exists()), None)
    assert toolset, 'Android build tools with aapt and apksigner are required'
    badging = subprocess.check_output([str(toolset / 'aapt'), 'dump', 'badging', str(args.apk)], text=True)
    identity = badging.splitlines()[0]
    assert "name='com.shodanrecon.mobile'" in identity, 'Android package identity changed'
    gradle_file = Path('app/build.gradle').read_text(encoding='utf-8')
    version_match = re.search(r'\bversionCode\s+(\d+)\b', gradle_file)
    assert version_match is not None, 'Unable to find app version code in Gradle'
    assert "versionCode='" + version_match.group(1) + "'" in identity, 'APK version code does not match Gradle'
    version_name = re.search(r"\bversionName\s+['\"]([^'\"]+)['\"]", gradle_file)
    assert version_name is not None and "versionName='" + version_name.group(1) + "'" in identity, 'APK version name does not match Gradle'
    signing = subprocess.check_output([str(toolset / 'apksigner'), 'verify', '--print-certs', str(args.apk)], text=True)
    certificates = re.findall(r'^Signer[^\n]*certificate SHA-256 digest:\s*([a-fA-F0-9:]+)\s*$', signing, re.MULTILINE)
    # Public certificate of the existing committed development key; no private
    # key or account secret is accessed by this comparison.
    expected_certificate = '470633fe1ecb95ada9e18ca05e2b7d370bf3c5066a3cf793d6f3c5363ca03088'
    actual_certificates = {value.replace(':', '').lower() for value in certificates}
    assert actual_certificates == {expected_certificate}, 'Existing development signing certificate changed: ' + repr(sorted(actual_certificates))
    print(identity)
    print('APK contents, version, package identity and existing signing certificate verified.')
