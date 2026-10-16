"""Explicit one-shot URL import. Never executes downloaded content."""
import time
import urllib.request
from core import Redirects, web_url
import policy


def merge_documents(current, incoming):
    old, new = policy.validate_document(current), policy.validate_document(incoming)
    p = new['policy']
    for key in policy.RULE_KEYS:
        p[key] = list(dict.fromkeys(old['policy'].get(key, []) + p.get(key, [])))
    for key in ('Geoipurl', 'Geositeurl'):
        if key not in p and key in old['policy']:
            p[key] = old['policy'][key]
    apps = new.get('apps') or old.get('apps')
    if old.get('apps') and new.get('apps'):
        if old['apps']['mode'] != new['apps']['mode']:
            raise ValueError('Режимы приложений различаются. Снимите «Дополнить» для замены профиля.')
        apps = dict(mode=apps['mode'], packages=list(dict.fromkeys(old['apps']['packages'] + apps['packages'])))
    return policy.document(p, apps['packages'] if apps else None, apps['mode'] if apps else 'bypass', new.get('dns', old.get('dns')))


def import_source(text, cancel=None):
    value = text.strip()
    if not value.startswith(('https://', 'http://')):
        return policy.import_text(value)
    web_url(value)
    started = time.monotonic()
    try:
        request = urllib.request.Request(value, headers={'User-Agent': 'TunaRoutingBuilder/2.2', 'Accept-Encoding': 'identity'})
        with urllib.request.build_opener(Redirects()).open(request, timeout=15) as response:
            blocks, size = [], 0
            while True:
                if cancel and cancel.is_set():
                    raise ValueError('Импорт отменён')
                if time.monotonic()-started > 60:
                    raise ValueError('Истекло время загрузки правил')
                block = response.read(16384)
                if not block:
                    break
                size += len(block)
                if size > 350000:
                    raise ValueError('Файл правил превышает 350 КБ')
                blocks.append(block)
        return policy.import_text(b''.join(blocks).decode('utf-8-sig'))
    except (OSError, UnicodeError):
        raise ValueError('Не удалось загрузить текст правил: проверьте сеть, HTTPS и доступность ссылки') from None
