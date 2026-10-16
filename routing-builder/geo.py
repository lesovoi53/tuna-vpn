"""Offline category indexing and bounded downloads of real Xray GEO protobuf files."""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import tempfile
import time
import urllib.error
import urllib.request

from core import Redirects, web_url

MAX_BYTES = 80 * 1024 * 1024
DEADLINE_SECONDS = 180
PRESETS = {
    'runetfreedom': {
        'geoip': 'https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geoip.dat',
        'geosite': 'https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geosite.dat'},
    'v2fly': {
        'geoip': 'https://github.com/v2fly/geoip/releases/latest/download/geoip.dat',
        'geosite': 'https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat'},
    'roscomvpn': {
        'geoip': 'https://github.com/hydraponique/roscomvpn-geoip/releases/latest/download/geoip.dat',
        'geosite': 'https://github.com/hydraponique/roscomvpn-geosite/releases/latest/download/geosite.dat'},
    'loyalsoldier': {
        'geoip': 'https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat',
        'geosite': 'https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat'},
}


def _prefix(value):
    if value not in ('geoip', 'geosite', 'geoip:', 'geosite:'):
        raise ValueError('Тип GEO должен быть geoip или geosite')
    return value.rstrip(':') + ':'


def _fields(data, start, end):
    def varint(pos):
        value = 0
        for shift in range(0, 70, 7):
            if pos >= end:
                raise ValueError('Обрезанный GEO protobuf')
            byte = data[pos]
            pos += 1
            if shift == 63 and byte > 1:
                raise ValueError('Переполнение GEO protobuf')
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, pos
        raise ValueError('Некорректный GEO varint')

    pos = start
    while pos < end:
        tag, pos = varint(pos)
        field, wire = tag >> 3, tag & 7
        if not 0 < field <= 536870911:
            raise ValueError('Некорректное поле GEO protobuf')
        if wire == 0:
            _, finish = varint(pos)
        elif wire == 1:
            finish = pos + 8
        elif wire == 2:
            length, pos = varint(pos)
            finish = pos + length
        elif wire == 5:
            finish = pos + 4
        else:
            raise ValueError('Неподдерживаемый GEO wire type')
        if finish > end:
            raise ValueError('Обрезанное поле GEO protobuf')
        yield field, wire, pos, finish
        pos = finish


def parse_index(data, prefix):
    """Read GeoSiteList/GeoIPList.entry(1).country_code(1), not guesses from presets."""
    prefix = _prefix(prefix)
    if not data or len(data) > MAX_BYTES:
        raise ValueError('Пустая GEO-база или размер превышает 80 МиБ')
    categories = set()
    for field, wire, start, end in _fields(data, 0, len(data)):
        if field != 1:
            continue
        if wire != 2:
            raise ValueError('Некорректный GEO entry')
        country = None
        for number, kind, a, b in _fields(data, start, end):
            if number != 1:
                continue
            if kind != 2 or country is not None or b - a > 200:
                raise ValueError('Некорректная категория GEO')
            try:
                country = data[a:b].decode('ascii').lower()
            except UnicodeError:
                raise ValueError('Некорректное имя категории GEO') from None
            if not re.fullmatch(r'[a-z0-9_@!.:-]+', country):
                raise ValueError('Некорректное имя категории GEO')
        if country is None:
            raise ValueError('В записи GEO нет country_code')
        categories.add(prefix + country)
        if len(categories) > 100000:
            raise ValueError('Слишком много категорий GEO')
    if not categories:
        raise ValueError('В GEO-файле не найдены категории')
    return sorted(categories)


class _HttpsRedirects(Redirects):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if web_url(newurl).scheme != 'https':
            raise ValueError('GEO: перенаправление разрешено только на HTTPS')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download_index(url, prefix, cacheDir, cancel=None, force=False):
    """Blocking worker API. Valid cache is reused; refresh failure never replaces it."""
    prefix = _prefix(prefix)
    if web_url(url).scheme != 'https':
        raise ValueError('Для GEO нужен HTTPS URL')
    started = time.monotonic()

    def check():
        if cancel is not None and cancel.is_set():
            raise ValueError('Загрузка GEO отменена')
        if time.monotonic() - started > DEADLINE_SECONDS:
            raise ValueError('Превышено время загрузки GEO')

    check()
    folder = Path(cacheDir)
    folder.mkdir(parents=True, exist_ok=True)
    key = hashlib.sha256((prefix + url).encode()).hexdigest()
    target = folder / (key + '.dat')
    if not force and target.is_file():
        if target.stat().st_size > MAX_BYTES:
            raise ValueError('Кэш GEO превышает 80 МиБ; обновите базу')
        return parse_index(target.read_bytes(), prefix)
    request = urllib.request.Request(url, headers={'User-Agent': 'TunaRoutingBuilder/2.0', 'Accept-Encoding': 'identity'})
    temporary = None
    try:
        with urllib.request.build_opener(_HttpsRedirects()).open(request, timeout=15) as response:
            if web_url(response.url).scheme != 'https':
                raise ValueError('GEO: нужен ответ по HTTPS')
            declared = response.headers.get('Content-Length')
            if declared is not None and (not declared.isdigit() or int(declared) > MAX_BYTES):
                raise ValueError('Некорректный размер GEO или больше 80 МиБ')
            with tempfile.NamedTemporaryFile(dir=folder, prefix=key + '.', suffix='.tmp', delete=False) as output:
                temporary = Path(output.name)
                count = 0
                while True:
                    check()
                    chunk = response.read(64 * 1024)
                    if not chunk:
                        break
                    count += len(chunk)
                    if count > MAX_BYTES:
                        raise ValueError('GEO больше 80 МиБ')
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
        check()
        categories = parse_index(temporary.read_bytes(), prefix)
        check()
        os.replace(temporary, target)
        temporary = None
        return categories
    except urllib.error.HTTPError as error:
        raise ValueError(f'GEO: сервер вернул HTTP {error.code}') from None
    except (urllib.error.URLError, TimeoutError):
        raise ValueError('GEO: ошибка сети, таймаут или сертификат HTTPS') from None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
