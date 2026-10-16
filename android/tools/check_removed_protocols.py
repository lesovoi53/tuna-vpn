"""Verify the explicit shipping component allowlist without legacy transport data."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
COMPONENTS = {'Xray': 'libxray.so', 'sing-box-extended': 'libsingbox.so',
              'Mieru': 'libmieru.so', 'WebDAV': 'libwebdav.so',
              'MasterDNS / StormDNS': 'libmasterdns.so', 'qWDTT': 'libqwdtt.so',
              'HEV': 'libhevsocks5.so'}
# Bundled AndroidX/CameraX/DataStore/ML Kit helpers, not VPN engines.
AUXILIARY = {'libandroidx.graphics.path.so', 'libbarhopper_v3.so',
             'libdatastore_shared_counter.so', 'libimage_processing_util_jni.so',
             'libsurface_util_jni.so'}

def check(apk=None):
    components = json.loads((ROOT / "app/src/main/assets/native/components.json").read_text(encoding="utf-8"))
    assert len(components) == len(COMPONENTS)
    assert {item['name'] for item in components} == set(COMPONENTS)
    if apk:
        with ZipFile(apk) as archive:
            libs = [n for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')]
            assert libs and {Path(n).name for n in libs} == set(COMPONENTS.values()) | AUXILIARY, 'Native inventory differs'
            expected = {COMPONENTS[item['name']]: item['sha256'].lower() for item in components}
            for name in libs:
                if Path(name).name in expected:
                    assert hashlib.sha256(archive.read(name)).hexdigest() == expected[Path(name).name], 'Native binary differs from manifest'
    print('Shipping native allowlist and binary hashes passed.')

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", nargs="?")
    check(parser.parse_args().apk)
