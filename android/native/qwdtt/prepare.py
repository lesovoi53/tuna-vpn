"""Restore pinned upstream archive and apply the small private-config overlay."""
import hashlib, json, sys, tarfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
ARCHIVE_SHA256 = '3db2ecb5873bc080fc2805bcef1ed09bdfb9151fa6f9d9deed805fd4c622c715'
COMMIT = 'a296c57eaba69bb9479a24f9157856490890e47d'
archive = Path(sys.argv[1])
assert hashlib.sha256(archive.read_bytes()).hexdigest() == ARCHIVE_SHA256, 'Unexpected qWDTT archive'
out = ROOT / 'build/qwdtt-overlay'
source = out / 'source'
source.mkdir(parents=True, exist_ok=True)
prefix = f'proxy-turn-vk-android-{COMMIT}/go_client/'
with tarfile.open(archive) as tar:
    for item in tar.getmembers():
        if not item.isfile() or not item.name.startswith(prefix): continue
        relative = Path(item.name[len(prefix):])
        assert not relative.is_absolute() and '..' not in relative.parts
        target = source / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(tar.extractfile(item).read())
for name in ('go.mod', 'go.sum'):
    (source / name).write_bytes((ROOT / 'native/qwdtt' / name).read_bytes())
main = source / 'main.go'
text = main.read_text(encoding='utf-8')
anchor = '\tflag.Parse()'
assert text.count(anchor) == 1
text = text.replace(anchor, '\ttunaConfigPath := flag.String("config", "", "Private configuration descriptor")\n'+anchor+'\n\tif err := tunaLoadConfig(*tunaConfigPath, flag.CommandLine); err != nil { log.Fatal("Private configuration rejected") }')
echo = '\t\t\tif !strings.Contains(line, "error:tunnel stopped") {\n\t\t\t\tlog.Printf("[STDIN] %s", line)\n\t\t\t}'
assert text.count(echo) == 1
text = text.replace(echo, '\t\t\t// Never echo stdin: CAPTCHA and TURN credentials share this channel.')
raw_error = 'log.Printf("[RAW] Некорректный RAWCONF: %q", rawConf)'
assert text.count(raw_error) == 1
text = text.replace(raw_error, 'log.Printf("[RAW] Некорректный RAWCONF")')
start = text.index('\t\t\tfmt.Println()\n\t\t\tfmt.Println("╔')
end = text.index('\n\t\t\tif activeConnMode == "socks" {', start)
text = text[:start] + '\t\t\tif activeConnMode != "socks" {\n' + text[start:end] + '\n\t\t\t}\n' + text[end:]
patched = out / 'main.go'
patched.write_text(text, encoding='utf-8')
mapping = {str(main):str(patched)}
for name in ('config.go', 'config_test.go'):
    mapping[str(source / ('tuna_'+name))] = str(ROOT / 'native/qwdtt' / name)
(out / 'overlay.json').write_text(json.dumps({'Replace':mapping}), encoding='utf-8')
