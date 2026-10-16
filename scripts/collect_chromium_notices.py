"""Collect unchanged notices from the pinned upstream archive; no unsafe extraction."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import tarfile
import zipfile

COMMIT = '72a06c9fca0e2d228588c7f3074bf7efff3ff686'

def collect(source, target):
    records = []
    target.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(source, 'r:gz') as archive, zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as out:
        for member in archive:
            path = PurePosixPath(member.name)
            name = path.name.upper()
            if not member.isfile() or not (name.startswith(('LICENSE', 'COPYING', 'COPYRIGHT', 'NOTICE')) or name == 'README.CHROMIUM'):
                continue
            if len(path.parts) < 2 or '..' in path.parts or path.is_absolute():
                raise ValueError('Unsafe archive member')
            if path.parts[0] != f'naiveproxy-{COMMIT}':
                raise ValueError('Unexpected source revision')
            relative = '/'.join(path.parts[1:])
            data = archive.extractfile(member).read()
            info = zipfile.ZipInfo(relative, (2026, 10, 16, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, data)
            records.append({'path': relative, 'sha256': hashlib.sha256(data).hexdigest()})
        if not any(r['path'] == 'src/LICENSE' for r in records):
            raise ValueError('Chromium license absent')
        origin = {'repository': 'https://github.com/SagerNet/naiveproxy', 'commit': COMMIT,
                  'sourceArchiveSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                  'scope': 'Broad source notices; exact linked-target coverage not independently verified',
                  'files': records}
        out.writestr('TUNA-NOTICE-PROVENANCE.json', json.dumps(origin, ensure_ascii=False, indent=2))
    print(f'Collected {len(records)} unchanged upstream notices; archive {target.stat().st_size} bytes')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('target', type=Path)
    args = parser.parse_args()
    collect(args.source, args.target)
