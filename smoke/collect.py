from pathlib import Path
import subprocess

destination = Path('smoke-results')
result = subprocess.run(['adb', 'shell', 'run-as', 'com.shodanrecon.mobile', 'ls', 'files/smoke-results'], capture_output=True, text=True)
if result.returncode == 0:
    for name in result.stdout.splitlines():
        if name.replace('-', '').replace('.', '').replace('_', '').isalnum():
            data = subprocess.run(['adb', 'exec-out', 'run-as', 'com.shodanrecon.mobile', 'cat', 'files/smoke-results/' + name], capture_output=True, check=True).stdout
            (destination / name).write_bytes(data)
