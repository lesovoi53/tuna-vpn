"""TUNA routing links. Standard library only; downloaded APKs are never executed."""
from __future__ import annotations

import re
import struct
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from threading import Event
import xml.etree.ElementTree as ET

PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+")
MAX_APK = 512 * 1024 * 1024
MAX_MANIFEST = 8 * 1024 * 1024


def package_name(value: str) -> str:
    if len(value) > 255 or not PACKAGE.fullmatch(value):
        raise ValueError("Некорректный package name приложения")
    return value


def routing_link(packages: list[str], mode: str = "bypass") -> str:
    if mode not in ("bypass", "vpn"):
        raise ValueError("Неизвестный режим")
    unique = list(dict.fromkeys(package_name(p) for p in packages))
    if not 1 <= len(unique) <= 100:
        raise ValueError("Добавьте от 1 до 100 приложений")
    result = f"tuna://app-exclusions/v1?mode={mode}&packages={','.join(unique)}"
    if len(result) > 32768:
        raise ValueError("Ссылка слишком длинная")
    return result


def web_url(value: str):
    if len(value) > 16384 or any(ord(c) < 32 for c in value):
        raise ValueError("Некорректный URL")
    u = urllib.parse.urlsplit(value)
    if u.scheme not in ("https", "http") or not u.hostname or u.username or u.password:
        raise ValueError("Нужна ссылка http(s) без логина и пароля в адресе")
    _ = u.port  # Reject malformed ports early.
    return u


def store_package(url: str) -> str | None:
    u = web_url(url)
    host = u.hostname.lower()
    if host == "play.google.com":
        ids = urllib.parse.parse_qs(u.query).get("id", [])
        if u.path.rstrip("/") != "/store/apps/details" or len(ids) != 1:
            raise ValueError("Нужна страница Google Play /store/apps/details?id=package.name")
        return package_name(ids[0])
    if host in ("rustore.ru", "www.rustore.ru"):
        match = re.fullmatch(r"/catalog/app/([^/]+)/?", u.path)
        if not match:
            raise ValueError("Нужна страница RuStore /catalog/app/package.name")
        try:
            return package_name(urllib.parse.unquote(match[1]))
        except ValueError:
            raise ValueError("В ссылке RuStore нет package name. Используйте новую ссылку магазина или APK.") from None
    return None


def manifest_package(data: bytes) -> str:
    """Read only the manifest package attribute, not the rest of Android resources.

    Binary layout: AOSP androidfw/ResourceTypes.h (ResStringPool, ResXMLTree).
    """
    if len(data) > MAX_MANIFEST:
        raise ValueError("Манифест APK слишком большой")
    if data.lstrip().startswith(b"<"):
        if b"<!" in data:
            raise ValueError("DTD и сущности в манифесте запрещены")
        root = ET.fromstring(data)
        if root.tag != "manifest":
            raise ValueError("Нет корневого manifest")
        return package_name(root.attrib.get("package", ""))

    def unpack(fmt, offset, end=None):
        size = struct.calcsize(fmt)
        if offset < 0 or offset + size > (len(data) if end is None else end):
            raise ValueError("Обрезанный бинарный манифест")
        return struct.unpack_from(fmt, data, offset)

    kind, header, total = unpack("<HHI", 0)
    if kind != 3 or header < 8 or total != len(data) or header > total:
        raise ValueError("Неподдерживаемый формат AndroidManifest.xml")
    strings: list[str] = []
    pos = header
    while pos < total:
        kind, hs, size = unpack("<HHI", pos)
        end = pos + size
        if hs < 8 or size < hs or end > total:
            raise ValueError("Повреждённый блок манифеста")
        if kind == 1:
            if strings or hs < 28:
                raise ValueError("Некорректная таблица строк")
            count, styles, flags, start, style_start = unpack("<IIIII", pos + 8, end)
            string_end = pos + (style_start or size)
            if count > 100000 or hs + 4 * (count + styles) > start or not start <= (style_start or size) <= size:
                raise ValueError("Некорректные границы таблицы строк")
            for i in range(count):
                offset, = unpack("<I", pos + hs + 4 * i, end)
                cursor = pos + start + offset

                def length(at, utf8):
                    fmt, step, high = ("<B", 1, 0x80) if utf8 else ("<H", 2, 0x8000)
                    first, = unpack(fmt, at, string_end)
                    if first & high:
                        second, = unpack(fmt, at + step, string_end)
                        return ((first & (high - 1)) << (8 * step)) | second, at + 2 * step
                    return first, at + step

                if flags & 0x100:
                    _, cursor = length(cursor, True)
                    n, cursor = length(cursor, True)
                    terminator, encoding = 1, "utf-8"
                else:
                    n, cursor = length(cursor, False)
                    n *= 2
                    terminator, encoding = 2, "utf-16le"
                if cursor + n + terminator > string_end or data[cursor+n:cursor+n+terminator] != b"\0" * terminator:
                    raise ValueError("Повреждённая строка манифеста")
                strings.append(data[cursor:cursor+n].decode(encoding))
        elif kind == 0x102:
            if hs < 16 or not strings:
                raise ValueError("Нет таблицы строк XML")

            def string(index):
                if not 0 <= index < len(strings):
                    raise ValueError("Некорректный индекс строки XML")
                return strings[index]

            ext = pos + hs
            _, name, attr_start, attr_size, count = unpack("<IIHHH", ext, end)
            if string(name) != "manifest":
                raise ValueError("Первый элемент APK не manifest")
            if attr_start < 20 or attr_size < 20 or ext + attr_start + count * attr_size > end:
                raise ValueError("Повреждённые атрибуты manifest")
            for i in range(count):
                at = ext + attr_start + i * attr_size
                ns, name, raw, value_size, _, value_type, value = unpack("<IIIHBBI", at, end)
                if ns == 0xFFFFFFFF and string(name) == "package":
                    if value_size != 8:
                        raise ValueError("Некорректный тип package")
                    if raw != 0xFFFFFFFF:
                        return package_name(string(raw))
                    if value_type == 3:
                        return package_name(string(value))
                    raise ValueError("Package не является строкой")
            raise ValueError("В APK не найден package name")
        pos = end
    raise ValueError("В APK не найден manifest")


def apk_package(path: str | Path) -> str:
    if Path(path).stat().st_size > MAX_APK:
        raise ValueError("APK больше 512 МиБ")
    try:
        with zipfile.ZipFile(path) as apk:
            entries = [i for i in apk.infolist() if i.filename == "AndroidManifest.xml"]
            if len(entries) != 1:
                raise ValueError("Нужен обычный APK с одним AndroidManifest.xml (не XAPK/APKS)")
            entry = entries[0]
            if entry.file_size > MAX_MANIFEST or entry.flag_bits & 1:
                raise ValueError("Манифест слишком большой или зашифрован")
            with apk.open(entry) as source:
                return manifest_package(source.read(MAX_MANIFEST + 1))
    except (zipfile.BadZipFile, ET.ParseError, struct.error, UnicodeError) as error:
        raise ValueError("Повреждённый APK или манифест") from error


class Redirects(urllib.request.HTTPRedirectHandler):
    max_redirections = 5

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        target = web_url(newurl)
        if req.full_url.startswith("https:") and target.scheme != "https":
            raise ValueError("Небезопасное перенаправление с HTTPS на HTTP")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def resolve(source: str, cancel: Event | None = None) -> str:
    source = source.strip()
    if not source:
        raise ValueError("Пустая строка")
    if "://" not in source and Path(source).is_file():
        return apk_package(Path(source))
    if PACKAGE.fullmatch(source):
        return package_name(source)
    if "://" not in source:
        return apk_package(Path(source))
    known = store_package(source)
    if known:
        return known
    started = time.monotonic()
    request = urllib.request.Request(source, headers={"User-Agent": "TunaRoutingBuilder/1.0", "Accept-Encoding": "identity"})
    try:
        with urllib.request.build_opener(Redirects()).open(request, timeout=15) as response:
            known = store_package(response.url)
            if known:
                return known
            content_type = response.headers.get_content_type()
            if content_type in ("text/html", "application/json", "text/plain"):
                raise ValueError("Ссылка вернула страницу или текст, а не APK. Нужна прямая ссылка скачивания.")
            declared = response.headers.get("Content-Length")
            if declared and int(declared) > MAX_APK:
                raise ValueError("APK больше 512 МиБ")
            # TemporaryDirectory removes the downloaded APK on both success and failure.
            with tempfile.TemporaryDirectory(prefix="tuna-apk-") as folder:
                path = Path(folder) / "download.apk"
                count = 0
                with path.open("wb") as output:
                    while True:
                        if cancel and cancel.is_set():
                            raise ValueError("Загрузка отменена")
                        if time.monotonic() - started > 180:
                            raise ValueError("Превышено время загрузки (3 минуты)")
                        block = response.read(64 * 1024)
                        if not block:
                            break
                        count += len(block)
                        if count > MAX_APK:
                            raise ValueError("APK больше 512 МиБ")
                        output.write(block)
                return apk_package(path)
    except urllib.error.HTTPError as error:
        raise ValueError(f"Сервер вернул HTTP {error.code}") from None
    except urllib.error.URLError:
        raise ValueError("Ошибка сети или сертификата HTTPS") from None
