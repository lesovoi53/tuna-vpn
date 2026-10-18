"""Create an ignored, synthetic localhost TLS fixture for JVM loopback tests.

No production key is read or reused. The fixed password is a test constant.
Run before the full JVM suite on a fresh checkout; keytool comes from JDK 21.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--keytool', help='Path to the JDK keytool executable')
    parser.add_argument('--replace', action='store_true', help='Regenerate only the known synthetic test fixture')
    args = parser.parse_args()
    keytool = args.keytool or (str(Path(os.environ['JAVA_HOME']) / 'bin' / ('keytool.exe' if os.name == 'nt' else 'keytool'))
                              if os.environ.get('JAVA_HOME') else shutil.which('keytool'))
    if not keytool:
        parser.error('Set JAVA_HOME or --keytool to JDK 21 keytool')
    target = Path(__file__).resolve().parents[1] / 'app/src/test/resources/group-probe-fixture.p12'
    if target.exists() and not args.replace:
        print('Synthetic TLS fixture already exists; use --replace to regenerate it.')
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='tuna-test-tls-') as temp:
        candidate = Path(temp) / 'fixture.p12'
        subprocess.run([keytool, '-genkeypair', '-alias', 'synthetic-loopback-test',
                        '-keystore', str(candidate), '-storetype', 'PKCS12',
                        '-storepass', 'fixture-only', '-keypass', 'fixture-only',
                        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650',
                        '-dname', 'CN=localhost,OU=TUNA synthetic tests',
                        '-ext', 'SAN=dns:localhost,ip:127.0.0.1', '-noprompt'],
                       check=True, timeout=45)
        shutil.copyfile(candidate, target)
    print('Generated synthetic localhost TLS fixture (ignored by Git); no production keys used.')


if __name__ == '__main__':
    main()
