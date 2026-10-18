"""Validated, network-free routing documents shared by the desktop forms and tests."""
from __future__ import annotations

import base64
import copy
import ipaddress
import itertools
import json
import re
from urllib.parse import urlsplit, parse_qs
from core import package_name

MAX_BYTES = 256 * 1024
ORDERS = ['-'.join(p) for p in itertools.permutations(('block', 'proxy', 'direct'))]
RULE_KEYS = [a + b for a in ('Block', 'Proxy', 'Direct') for b in ('Sites', 'Ip')]
KNOWN = set(RULE_KEYS + ['Name', 'GlobalProxy', 'RouteOrder', 'DomainStrategy', 'FakeDNS', 'DnsHosts', 'Geoipurl', 'Geositeurl', 'UseChunkFiles', 'LastUpdated', 'TunaDNS'] + [p + s for p in ('Remote', 'Domestic') for s in ('Dns', 'DNSType', 'DNSDomain', 'DNSIP')])


def defaults():
    return dict(Name='Мой маршрут', GlobalProxy=True, RouteOrder='block-proxy-direct',
                DomainStrategy='AsIs', FakeDNS=False, UseChunkFiles=False, DnsHosts={},
                RemoteDNSType='DoH', RemoteDNSDomain='https://dns.google/dns-query', RemoteDNSIP='8.8.8.8',
                DomesticDNSType='UDP', DomesticDns='1.1.1.1', **{k: [] for k in RULE_KEYS})


def boolean(value, key):
    if type(value) is bool:
        return value
    if isinstance(value, str) and value.lower() in ('true', 'false'):
        return value.lower() == 'true'
    raise ValueError(f'{key}: требуется true или false')


def ip(value):
    try:
        ipaddress.ip_address(value)
        return True
    except ValueError:
        return False


def domain(value):
    return bool(value) and len(value) <= 253 and all(
        0 < len(part) <= 63 and all(c.isalnum() or c in '-_' for c in part) for part in value.split('.'))


def match(value, address=False):
    if not isinstance(value, str) or not value or len(value) > 500 or any(c in '\r\n' for c in value):
        raise ValueError('Правило: требуется непустая строка до 500 символов')
    if value.startswith('regexp:'):
        # TUNA's portable subset deliberately excludes lookarounds/backreferences.
        pattern = value[7:]
        if address or not pattern or '(?' in pattern or re.search(r'\\[1-9]', pattern):
            raise ValueError('Регулярное выражение не входит в переносимое подмножество TUNA')
        try:
            re.compile(pattern)
        except re.error as error:
            raise ValueError(f'Некорректное регулярное выражение: {error}') from None
        return value
    if any(c.isspace() or c == ',' for c in value):
        raise ValueError('Правило не должно содержать пробелы или запятые')
    if value.startswith(('geoip:', 'geosite:')):
        if value.startswith('geoip:') != address or not re.fullmatch(r'[a-zA-Z0-9_@!.:-]+', value.split(':', 1)[1]):
            raise ValueError('Используйте geoip: в IP, geosite: в сайтах')
        return value
    if address:
        try:
            ipaddress.ip_network(value, strict=False)
        except ValueError:
            raise ValueError('Требуется IPv4/IPv6, CIDR или geoip:категория') from None
    else:
        if value.startswith('keyword:') and value[8:]:
            return value
        name = value.removeprefix('domain:').removeprefix('full:')
        if not domain(name) or ip(name) or all(c.isdigit() or c == '.' for c in name):
            raise ValueError('Требуется домен, domain:, full:, keyword:, regexp: или geosite:')
    return value


def https(value):
    if not isinstance(value, str) or any(c.isspace() for c in value):
        raise ValueError('Требуется HTTPS URL без пробелов')
    u = urlsplit(value)
    if u.scheme != 'https' or not u.hostname or u.username or u.password or u.fragment or (u.port is not None and not 1 <= u.port <= 65535):
        raise ValueError('Требуется HTTPS URL без логина, пароля и фрагмента')
    return value


def validate_resolver(server, route):
    if not isinstance(server, dict) or set(server) - {'transport', 'endpoint', 'bootstrapIp', 'route'}:
        raise ValueError('Некорректное описание DNS-сервера')
    if server.get('route', route) != route:
        raise ValueError('Маршрут DNS не соответствует группе')
    server['route'] = route
    t = server.get('transport')
    if not isinstance(t, str) or t.upper() not in ('DOH', 'DOT', 'UDP', 'TCP'):
        raise ValueError('DNS: выберите DoH, DoT, UDP или TCP')
    t = server['transport'] = t.upper()
    endpoint = server.get('endpoint')
    if not isinstance(endpoint, str) or not endpoint or any(c.isspace() for c in endpoint):
        raise ValueError('Укажите адрес DNS без пробелов')
    u = urlsplit(endpoint if t in ('DOH', 'DOT') else 'dns://' + endpoint)
    if not u.hostname or u.username or u.password or u.fragment or (u.port is not None and not 1 <= u.port <= 65535):
        raise ValueError('Некорректный адрес DNS')
    bootstrap = server.get('bootstrapIp')
    if bootstrap is not None and (not isinstance(bootstrap, str) or bootstrap and not ip(bootstrap)):
        raise ValueError('Bootstrap DNS должен быть IP-адресом')
    if t in ('DOH', 'DOT'):
        if u.scheme != ('https' if t == 'DOH' else 'tls') or t == 'DOT' and (u.path or u.query):
            raise ValueError('DNS: нужен https:// для DoH или tls://host:port для DoT')
        if t == 'DOT' and not ip(u.hostname) and not bootstrap:
            raise ValueError('Для DoT с доменом укажите Bootstrap IP')
    elif not ip(u.hostname) or u.path or u.query:
        raise ValueError('UDP/TCP DNS требует IP:порт')


def validate_dns_choice(value, route, *, bootstrap=False):
    modes = ('system', 'resolver') if bootstrap else ('system', 'disabled', 'resolver')
    if not isinstance(value, dict) or value.get('mode') not in modes:
        raise ValueError('Неизвестный режим Bootstrap DNS' if bootstrap else 'Неизвестный режим аварийного DNS')
    if value['mode'] != 'resolver':
        if set(value) != {'mode'}:
            raise ValueError('Системный или отключённый DNS не должен содержать resolver и другие поля')
        return
    if set(value) != {'mode', 'resolver'}:
        raise ValueError('Ручной DNS требует ровно mode и resolver')
    validate_resolver(value['resolver'], route)
    if value['resolver']['transport'] not in ('UDP', 'TCP', 'DOH'):
        raise ValueError('Аварийный DNS: выберите UDP, TCP или DoH')
    if bootstrap and (value['resolver']['transport'] != 'TCP' or value['resolver'].get('bootstrapIp')):
        raise ValueError('Bootstrap DNS: только TCP с IP-адресом, без собственного bootstrap')


def validate_dns(source, fallback):
    allowed = {'remoteServers', 'domesticServers', 'remoteSelectionMode', 'domesticSelectionMode', 'timeoutMs', 'hosts', 'queryStrategy', 'disableCache', 'remoteEmergency', 'domesticEmergency', 'bootstrap'}
    if not isinstance(source, dict) or set(source) - allowed:
        raise ValueError('Неизвестные поля DNS')
    result = copy.deepcopy(source)
    for key, prefix, route in [('remote', 'Remote', 'VPN'), ('domestic', 'Domestic', 'DIRECT')]:
        mode = result.setdefault(key + 'SelectionMode', 'auto')
        if mode not in ('auto', 'priority'):
            raise ValueError('Режим DNS: auto или priority')
        transport = fallback.get(prefix + 'DNSType', 'UDP').upper()
        primary = dict(transport=transport, endpoint=fallback.get(prefix + ('DNSDomain' if transport == 'DOH' else 'Dns'), ''), bootstrapIp=fallback.get(prefix + 'DNSIP') or None, route=route)
        servers = result.setdefault(key + 'Servers', [primary])
        if not isinstance(servers, list) or not 1 <= len(servers) <= 8:
            raise ValueError('В каждой группе требуется от 1 до 8 DNS-серверов')
        identities = set()
        for server in servers:
            validate_resolver(server, route)
            identity = (server['transport'], server['endpoint'].lower().rstrip('/'))
            if identity in identities:
                raise ValueError('Повтор DNS-сервера')
            identities.add(identity)
        emergency = result.setdefault(key + 'Emergency', {'mode': 'system'})
        validate_dns_choice(emergency, route)
    if 'bootstrap' in result:
        validate_dns_choice(result['bootstrap'], 'DIRECT', bootstrap=True)
    timeout = result.setdefault('timeoutMs', 5000)
    if type(timeout) is not int or not 1000 <= timeout <= 10000:
        raise ValueError('Общий тайм-аут DNS: 1000–10000 мс')
    if result.get('queryStrategy', 'UseIP') not in ('UseIP', 'UseIPv4', 'UseIPv6') or type(result.get('disableCache', False)) is not bool:
        raise ValueError('Некорректные настройки DNS-кэша или A/AAAA')
    hosts = result.get('hosts', {})
    if not isinstance(hosts, dict) or any(not domain(k) or ip(k) or not isinstance(v, str) or not ip(v) for k, v in hosts.items()):
        raise ValueError('Некорректные DNS hosts')
    emergency_resolvers = [result[key]['resolver'] for key in ('remoteEmergency', 'domesticEmergency') if result[key]['mode'] == 'resolver']
    for server in result['remoteServers'] + result['domesticServers'] + emergency_resolvers:
        host = urlsplit(server['endpoint'] if server['transport'] in ('DOH', 'DOT') else 'dns://' + server['endpoint']).hostname
        if not ip(host) and host in hosts and server.get('bootstrapIp') and hosts[host] != server.get('bootstrapIp'):
            raise ValueError('DNS hosts конфликтует с Bootstrap IP')
    return result


def validate_policy(source):
    if not isinstance(source, dict):
        raise ValueError('Policy должен быть JSON-объектом')
    p = copy.deepcopy(source)
    for key in ('Name', 'RouteOrder', 'DomainStrategy'):
        if key in p and not isinstance(p[key], str):
            raise ValueError(f'{key}: требуется строка')
    p.setdefault('Name', 'Happ routing')
    if not p['Name'].strip() or len(p['Name']) > 200:
        raise ValueError('Название должно содержать 1–200 символов')
    if p.setdefault('RouteOrder', 'block-proxy-direct') not in ORDERS:
        raise ValueError('RouteOrder должен включать block, proxy и direct по одному разу')
    if p.setdefault('DomainStrategy', 'AsIs') not in ('AsIs', 'IPIfNonMatch', 'IPOnDemand'):
        raise ValueError('Неизвестная DomainStrategy')
    for key, default in [('GlobalProxy', True), ('FakeDNS', False), ('UseChunkFiles', False)]:
        p[key] = boolean(p.get(key, default), key)
    count = 0
    for key in RULE_KEYS:
        values = p.setdefault(key, [])
        if not isinstance(values, list):
            raise ValueError(f'{key}: требуется массив')
        for value in values:
            match(value, key.endswith('Ip'))
        count += len(values)
    if count > 500:
        raise ValueError('Разрешено не более 500 правил суммарно')
    for prefix in ('Remote', 'Domestic'):
        transport = p.setdefault(prefix + 'DNSType', 'UDP')
        if not isinstance(transport, str) or transport.upper() not in ('DOH', 'UDP', 'TCP'):
            raise ValueError(f'{prefix}: доступны DoH, UDP, TCP')
        transport = 'DoH' if transport.upper() == 'DOH' else transport.upper()
        p[prefix + 'DNSType'] = transport
        endpoint = p.get(prefix + ('DNSDomain' if transport == 'DoH' else 'Dns'))
        if not isinstance(endpoint, str) or not endpoint:
            raise ValueError(f'{prefix}: укажите DNS-сервер')
        if transport == 'DoH':
            https(endpoint)
        else:
            u = urlsplit('dns://' + endpoint)
            if not u.hostname or any(c.isspace() for c in endpoint) or u.username or u.password or u.path or u.query or u.fragment or (u.port is not None and not 1 <= u.port <= 65535):
                raise ValueError(f'{prefix}: требуется host[:port], IPv6 в квадратных скобках')
            if not (ip(u.hostname) or domain(u.hostname)):
                raise ValueError(f'{prefix}: некорректный адрес DNS')
        bootstrap = p.get(prefix + 'DNSIP', '')
        if not isinstance(bootstrap, str) or (bootstrap and not ip(bootstrap)):
            raise ValueError(f'{prefix}: bootstrap должен быть IPv4 или IPv6')
    hosts = p.setdefault('DnsHosts', {})
    if not isinstance(hosts, dict):
        raise ValueError('DnsHosts должен быть объектом домен → IP')
    for name, address in hosts.items():
        if not domain(name) or ip(name) or not isinstance(address, str) or not ip(address):
            raise ValueError(f'DnsHosts: некорректная запись {name}')
    for key in ('Geoipurl', 'Geositeurl'):
        if key in p:
            https(p[key])
    if 'TunaDNS' in p:
        p['TunaDNS'] = validate_dns(p['TunaDNS'], p)
    if len(json.dumps(p, ensure_ascii=False).encode('utf-8')) > MAX_BYTES:
        raise ValueError('Документ превышает 256 КиБ')
    return p


def warnings(p):
    result = [f'Неизвестное Happ-поле сохранено: {k}' for k in p if k not in KNOWN]
    if p.get('UseChunkFiles'):
        result.append('UseChunkFiles не применяется в TUNA; нужны проверенные DAT-базы.')
    if p.get('FakeDNS'):
        result.append('FakeDNS зависит от поддержки выбранного ядра; проверьте предупреждения TUNA.')
    if any(v.startswith(('geosite:', 'geoip:')) for k in RULE_KEYS for v in p.get(k, [])):
        result.append('GEO-правила требуют соответствующих проверенных баз на телефоне.')
    return result


def document(policy, packages=None, mode='bypass', dns=None):
    result = dict(version=1, policy=validate_policy(policy))
    embedded = result['policy'].pop('TunaDNS', None)
    if embedded is not None and dns is not None and validate_dns(dns, result['policy']) != embedded:
        raise ValueError('Конфликтующие настройки DNS')
    if dns is not None or embedded is not None:
        result['dns'] = validate_dns(dns if dns is not None else embedded, result['policy'])
    if packages is not None:
        if mode not in ('bypass', 'vpn') or not isinstance(packages, list) or not 1 <= len(packages) <= 100:
            raise ValueError('Apps: режим bypass/vpn и от 1 до 100 приложений')
        if any(not isinstance(v, str) for v in packages):
            raise ValueError('Package name должен быть строкой')
        checked = [package_name(v) for v in packages]
        if len(set(checked)) != len(checked):
            raise ValueError('Повтор package name')
        result['apps'] = dict(mode=mode, packages=checked)
    compact(result)
    return result


def compact(value):
    raw = json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')
    if len(raw) > MAX_BYTES:
        raise ValueError('Документ превышает 256 КиБ')
    return raw


def tuna_link(doc):
    checked = validate_document(doc)
    p = checked['policy']
    # validate_document returns a deep copy: preserve the editable/Happ original.
    p['FakeDNS'] = False
    p['UseChunkFiles'] = False
    for key in set(p) - KNOWN:
        del p[key]
    for prefix, key in [('geosite:', 'Geositeurl'), ('geoip:', 'Geoipurl')]:
        if any(value.startswith(prefix) for rule in RULE_KEYS for value in p[rule]) and not p.get(key):
            raise ValueError(f'TUNA: для правил {prefix} укажите {key} в GEO / профиль')
    return 'tuna://routing/v1?data=' + base64.urlsafe_b64encode(compact(checked)).decode().rstrip('=')


def happ_export(policy, kind='link'):
    p = validate_policy(policy)
    if 'TunaDNS' in p:
        raise ValueError('DNS-группы, аварийный DNS и Bootstrap требуют ссылки TUNA. Happ-экспорт не может представить их без потери; эквивалентный экспорт невозможен.')
    if kind == 'json':
        return json.dumps(p, ensure_ascii=False, indent=2)
    encoded = base64.b64encode(compact(p)).decode()
    return encoded if kind == 'base64' else 'happ://routing/onadd/' + encoded


def validate_document(root):
    if not isinstance(root, dict) or type(root.get('version')) is not int or root['version'] != 1 or set(root) - {'version', 'policy', 'apps', 'dns'}:
        raise ValueError('Неизвестный формат или версия TUNA')
    apps = root.get('apps')
    if 'apps' in root and (not isinstance(apps, dict) or set(apps) != {'mode', 'packages'}):
        raise ValueError('Некорректный Apps')
    if 'dns' in root and not isinstance(root['dns'], dict):
        raise ValueError('DNS должен быть объектом')
    return document(root.get('policy'), apps['packages'] if apps is not None else None, apps['mode'] if apps is not None else 'bypass', root.get('dns'))


def _decode(text):
    if not re.fullmatch(r'[A-Za-z0-9_+/=-]+', text):
        raise ValueError('Некорректный Base64')
    try:
        raw = base64.b64decode(text + '=' * (-len(text) % 4), altchars=b'-_', validate=True)
        if len(raw) > MAX_BYTES:
            raise ValueError('Документ превышает 256 КиБ')
        return raw.decode('utf-8')
    except (UnicodeError, ValueError) as error:
        raise ValueError('Некорректный Base64/UTF-8 или слишком большой документ') from error


def import_text(text):
    if len(text.encode('utf-8')) > 350000:
        raise ValueError('Импортируемый текст слишком большой')
    value = text.strip().lstrip('\ufeff')
    if value.startswith('tuna://'):
        u = urlsplit(value)
        query = parse_qs(u.query, keep_blank_values=True)
        if u.netloc != 'routing' or u.path != '/v1' or u.fragment or set(query) != {'data'} or len(query['data']) != 1:
            raise ValueError('Некорректная TUNA routing ссылка')
        if not re.fullmatch(r'[A-Za-z0-9_-]+', query['data'][0]):
            raise ValueError('TUNA требует Base64url без padding')
        value = _decode(query['data'][0])
        if base64.urlsafe_b64encode(value.encode()).decode().rstrip('=') != query['data'][0]:
            raise ValueError('Неканонический Base64url')
        unified = True
    else:
        unified = False
        for prefix in ('happ://routing/onadd/', 'happ://routing/add/'):
            if value.startswith(prefix):
                value = value[len(prefix):]
                break
        if not value.startswith('{'):
            value = _decode(value)
    if len(value.encode('utf-8')) > MAX_BYTES:
        raise ValueError('Документ превышает 256 КиБ')
    def pairs(entries):
        result = {}
        for k, v in entries:
            if k in result:
                raise ValueError(f'Повтор JSON-ключа: {k}')
            result[k] = v
        return result
    try:
        root = json.loads(value, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Некорректное число JSON')))
    except (json.JSONDecodeError, RecursionError) as error:
        raise ValueError('Некорректный JSON') from error
    if unified or isinstance(root, dict) and 'version' in root and 'policy' in root:
        return validate_document(root)
    return document(root)
