"""Download pinned, public upstream source archives and record their hashes.

Archives are not extracted or executed. An existing manifest pins subsequent
downloads to their recorded SHA-256. Cache files with the wrong hash fail closed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

SOURCES = [
    ('sing-box-extended', 'shtorm-7/sing-box-extended', '55faa763f986f4ca8a492d9b2719bc6330d2bef5', 'android/external/sing-box-extended'),
    ('mieru', 'enfein/mieru', 'a2208ee7a38fb41d843a13ecdde858e67ef2f485', 'android/external/mieru'),
    ('masterdnsvpn', 'masterking32/MasterDnsVPN', '7de2476f1c33e69eec35360c52810dab43c5c986', 'android/external/masterdnsvpn'),
    ('webdav-tunnel', 'spkprsnts/webdav-tunnel', 'b1af4c05eb80fd29a6f676be894fa61cd12a3dec', 'android/external/webdav-tunnel'),
    ('qwdtt', 'SpaceNeuroX/proxy-turn-vk-android', 'a296c57eaba69bb9479a24f9157856490890e47d', 'android/external/proxy-turn-vk-android'),
    ('hev', 'heiher/hev-socks5-tunnel', '941c758101385d145c66210ac88991daaf27d4b6', 'android/external/hev-socks5-tunnel'),
    ('cronet-build', 'SagerNet/cronet-go', '049f2909701ca088c9569f6b303bba5376a2b2ff', None),
    ('cronet-bindings', 'SagerNet/cronet-go', '0d28acc44093df24b2526dea3d6ffefd6b0a54f0', None),
    ('naiveproxy', 'SagerNet/naiveproxy', '72a06c9fca0e2d228588c7f3074bf7efff3ff686', None),
]

def main():
    p = argparse.ArgumentParser()
    p.add_argument('cache', type=Path)
    p.add_argument('--manifest', type=Path, default=Path(__file__).resolve().parents[1] / 'docs/releases/upstream-sources.json')
    a = p.parse_args()
    old = {r['name']: r for r in json.loads(a.manifest.read_text())['sources']} if a.manifest.exists() else {}
    a.cache.mkdir(parents=True, exist_ok=True)
    rows = []
    for name, repo, commit, submodule in SOURCES:
        filename = f'{name}-{commit}.tar.gz'
        target = a.cache / filename
        url = f'https://codeload.github.com/{repo}/tar.gz/{commit}'
        if not target.exists():
            request = urllib.request.Request(url, headers={'User-Agent': 'TUNA-source-distribution/1.6'})
            temp = target.with_suffix('.part')
            with urllib.request.urlopen(request, timeout=90) as response, temp.open('wb') as out:
                while chunk := response.read(1024 * 1024):
                    out.write(chunk)
            temp.replace(target)
        sha = hashlib.file_digest(target.open('rb'), 'sha256').hexdigest()
        if name in old and (old[name]['sha256'] != sha or old[name]['commit'] != commit):
            raise ValueError(f'Source cache differs from manifest: {name}')
        rows.append(dict(name=name, repository=f'https://github.com/{repo}', commit=commit,
                         url=url, archive=filename, sha256=sha, submodule=submodule))
        print(f'{name}: verified archive, {target.stat().st_size} bytes', flush=True)
    a.manifest.parent.mkdir(parents=True, exist_ok=True)
    a.manifest.write_text(json.dumps({'schemaVersion': 1, 'sources': rows}, indent=2) + '\n', encoding='utf-8')

if __name__ == '__main__':
    main()
