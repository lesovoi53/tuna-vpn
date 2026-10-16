"""Form-based policy editing with background GEO downloads and indexed completion."""
import copy
import customtkinter as ctk
from tkinter import messagebox
import policy
import geo
from pathlib import Path
from completion import RuleCompletion
from dns_editor import DnsPoolEditor


class PolicyEditor:
    def __init__(self, tabs, changed, submit=None, cancel=None):
        self.changed = changed
        self.root, self.submit, self.cancel = tabs.winfo_toplevel(), submit, cancel
        self.geo_indexes, self.geo_tokens = {}, {}
        self.completions = []
        self.source = policy.defaults()
        self.vars, self.rules = {}, {}
        for name in ('Правила', 'DNS', 'GEO / профиль'):
            tabs.add(name)
        rules = tabs.tab('Правила')
        ctk.CTkLabel(rules, text='По одному правилу на строку. Порядок групп задаётся в профиле.', wraplength=620).pack(pady=5)
        groups = ctk.CTkTabview(rules)
        groups.pack(fill='both', expand=True)
        for stem, title in [('Direct', 'Напрямую'), ('Proxy', 'Через VPN'), ('Block', 'Блокировать')]:
            group = groups.add(title)
            group.grid_columnconfigure(0, weight=1)
            group.grid_rowconfigure(1, weight=1)
            group.grid_rowconfigure(3, weight=1)
            for row, suffix, hint in [(0, 'Sites', 'Сайты: domain:example.com / full:host.ru / geosite:youtube'), (2, 'Ip', 'IP: 192.0.2.0/24 / 2001:db8::/32 / geoip:private')]:
                ctk.CTkLabel(group, text=hint, anchor='w').grid(row=row, column=0, sticky='ew')
                box = ctk.CTkTextbox(group, height=120, font=('Consolas', 13))
                box.grid(row=row + 1, column=0, sticky='nsew', pady=(3, 10))
                box.bind('<KeyRelease>', lambda _: changed())
                self.rules[stem + suffix] = box
                self.completions.append(RuleCompletion(box, suffix == 'Ip',
                    lambda suffix=suffix: self.categories('geoip' if suffix == 'Ip' else 'geosite'), changed))
        dns = ctk.CTkScrollableFrame(tabs.tab('DNS'))
        dns.pack(fill='both', expand=True)
        ctk.CTkLabel(dns, text='DoH: достаточно HTTPS-ссылки, в том числе своего сервера. Пустой Bootstrap IP требует обновлённого Android-клиента с автоматическим разрешением имени. ПК не определяет и не подставляет IP.', wraplength=580, justify='left').pack(anchor='w', pady=8)
        self.dns_pools = {}
        for prefix, title in [('Remote', 'DNS через VPN'), ('Domestic', 'DNS напрямую')]:
            ctk.CTkLabel(dns, text=title, font=('Segoe UI', 18, 'bold')).pack(anchor='w', pady=(10, 5))
            self.dns_pools[prefix] = DnsPoolEditor(dns, 'VPN' if prefix == 'Remote' else 'DIRECT', changed)
        self.field(dns, 'DnsTimeoutMs', 'Общий тайм-аут одного DNS-запроса, мс (1000–10000)')
        ctk.CTkLabel(dns, text='Резервирование только внутри своей группы. DNS через VPN никогда не переходит напрямую. DoT требует extended, Xray его не поддерживает.', wraplength=580).pack(anchor='w', pady=8)
        ctk.CTkLabel(dns, text='Статические hosts: домен = IP, одна запись на строку').pack(anchor='w', pady=10)
        self.hosts = ctk.CTkTextbox(dns, height=150, font=('Consolas', 13))
        self.hosts.pack(fill='x')
        self.hosts.bind('<KeyRelease>', lambda _: changed())
        profile = ctk.CTkScrollableFrame(tabs.tab('GEO / профиль'))
        profile.pack(fill='both', expand=True)
        self.field(profile, 'Name', 'Название профиля')
        self.field(profile, 'GlobalProxy', 'Маршрут для остальных адресов', ['Через VPN', 'Напрямую'])
        self.field(profile, 'RouteOrder', 'Приоритет групп: первое совпадение побеждает', policy.ORDERS)
        self.field(profile, 'DomainStrategy', 'Стратегия доменов', ['AsIs', 'IPIfNonMatch', 'IPOnDemand'])
        self.field(profile, 'Geoipurl', 'GeoIP DAT URL (HTTPS, необязательно)')
        self.field(profile, 'Geositeurl', 'GeoSite DAT URL (HTTPS, необязательно)')
        ctk.CTkLabel(profile, text='GEO-источники из Android TUNA').pack(anchor='w', pady=(8, 2))
        self.geo_preset = ctk.StringVar(value='Выберите базу')
        ctk.CTkOptionMenu(profile, variable=self.geo_preset, values=list(geo.PRESETS), command=self.select_geo).pack(fill='x')
        ctk.CTkButton(profile, text='Скачать / открыть GEO-кэш', command=self.download_geo).pack(anchor='w', pady=5)
        ctk.CTkButton(profile, text='Обновить GEO из сети', command=lambda: self.download_geo(force=True)).pack(anchor='w', pady=5)
        self.geo_status = ctk.StringVar(value='Выберите источник или скачайте базы из указанных URL для подсказок.')
        ctk.CTkLabel(profile, textvariable=self.geo_status, wraplength=550, justify='left').pack(anchor='w')
        ctk.CTkLabel(profile, text='Подсказки GEO берутся из скачанных DAT. Стрелки — выбор, Enter/Tab — вставка.\nAndroid загружает свои базы отдельно. Неизвестные поля Happ сохраняются.', wraplength=560, justify='left').pack(anchor='w', pady=10)
        ctk.CTkButton(profile, text='Удалить неизвестные поля Happ', command=self.drop_unknown).pack(anchor='w', pady=5)
        self.load(self.source)

    def categories(self, prefix):
        key = 'Geoipurl' if prefix == 'geoip' else 'Geositeurl'
        url = self.vars[key].get().strip()
        return self.geo_indexes.get((prefix, url), [])

    def select_geo(self, name):
        self.vars['Geoipurl'].set(geo.PRESETS[name]['geoip'])
        self.vars['Geositeurl'].set(geo.PRESETS[name]['geosite'])
        self.download_geo()

    def download_geo(self, force=False):
        if self.submit is None:
            return
        urls = {p: self.vars[k].get().strip() for p, k in [('geoip', 'Geoipurl'), ('geosite', 'Geositeurl')]}
        try:
            for url in urls.values():
                policy.https(url)
        except ValueError as error:
            self.geo_status.set(str(error))
            return
        from runtime_paths import geo_cache_dir
        cache = geo_cache_dir()
        token = object()
        self.geo_tokens['current'] = token
        self.geo_status.set('Загрузка GEO-баз… окно остаётся доступным.')
        def work():
            result, errors = {}, []
            for prefix, url in urls.items():
                try:
                    result[(prefix, url)] = geo.download_index(url, prefix, cache, self.cancel, force)
                except Exception as error:
                    errors.append(prefix + ': ' + (str(error) if isinstance(error, ValueError) else type(error).__name__))
            return result, errors
        future = self.submit(work)
        def finish():
            if self.cancel and self.cancel.is_set():
                return
            if not future.done():
                self.root.after(100, finish)
                return
            result, errors = future.result()
            self.geo_indexes.update(result)
            if self.geo_tokens.get('current') is token:
                counts = ', '.join(f'{p}: {len(self.categories(p))}' for p in urls)
                self.geo_status.set(('Готово: ' + counts) + ('; Ошибка: ' + '; '.join(errors) if errors else ''))
        self.root.after(100, finish)

    def field(self, parent, key, title, choices=None):
        ctk.CTkLabel(parent, text=title, anchor='w').pack(fill='x', pady=(7, 1))
        var = self.vars[key] = ctk.StringVar()
        widget = ctk.CTkOptionMenu(parent, variable=var, values=choices, command=lambda _: self.changed()) if choices else ctk.CTkEntry(parent, textvariable=var, height=34)
        widget.pack(fill='x', pady=(0, 3))
        if not choices:
            var.trace_add('write', lambda *_: self.changed())

    def drop_unknown(self):
        self.source = {k: v for k, v in self.source.items() if k in policy.KNOWN}
        self.changed()

    def load(self, source):
        self.loading = True
        self.source = copy.deepcopy(source)
        dns = source.get('TunaDNS', {})
        for prefix, pool in self.dns_pools.items():
            key = prefix.lower()
            transport = source.get(prefix + 'DNSType', 'UDP').upper()
            primary = dict(transport=transport, endpoint=source.get(prefix + ('DNSDomain' if transport == 'DOH' else 'Dns'), ''), bootstrapIp=source.get(prefix + 'DNSIP') or None, route=pool.route)
            pool.load(dns.get(key + 'Servers', [primary]), dns.get(key + 'SelectionMode', 'auto'))
        for key, box in self.rules.items():
            box.delete('1.0', 'end')
            box.insert('1.0', '\n'.join(source.get(key, [])))
        for key, var in self.vars.items():
            value = source.get(key, '')
            if key == 'DnsTimeoutMs':
                value = str(dns.get('timeoutMs', 5000))
            elif key == 'GlobalProxy':
                value = 'Через VPN' if source.get(key, True) else 'Напрямую'
            elif key.endswith('Endpoint'):
                prefix = key.removesuffix('Endpoint')
                value = source.get(prefix + ('DNSDomain' if source.get(prefix + 'DNSType', 'UDP').upper() == 'DOH' else 'Dns'), '')
            var.set(value)
        self.hosts.delete('1.0', 'end')
        self.hosts.insert('1.0', '\n'.join(f'{k} = {v}' for k, v in dns.get('hosts', source.get('DnsHosts', {})).items()))
        self.loading = False

    def value(self):
        p = copy.deepcopy(self.source)
        for key, box in self.rules.items():
            p[key] = [v.strip() for v in box.get('1.0', 'end').splitlines() if v.strip()]
        for key, var in self.vars.items():
            if key.endswith('Endpoint') or key == 'DnsTimeoutMs':
                continue
            p[key] = var.get().strip()
        p['GlobalProxy'] = self.vars['GlobalProxy'].get() == 'Через VPN'
        dns = copy.deepcopy(p.get('TunaDNS', {}))
        for prefix, pool in self.dns_pools.items():
            servers = pool.value()
            dns[prefix.lower() + 'Servers'] = servers
            dns[prefix.lower() + 'SelectionMode'] = pool.selection_mode()
            primary = servers[0]
            # Legacy fields remain a compatible first-server description.
            legacy = primary if primary['transport'] != 'DOT' else dict(transport='UDP', endpoint='1.1.1.1', bootstrapIp=None)
            p[prefix + 'DNSType'] = 'DoH' if legacy['transport'] == 'DOH' else legacy['transport']
            endpoint_key = prefix + ('DNSDomain' if legacy['transport'] == 'DOH' else 'Dns')
            p.pop(prefix + ('Dns' if legacy['transport'] == 'DOH' else 'DNSDomain'), None)
            p[endpoint_key] = legacy['endpoint']
            p[prefix + 'DNSIP'] = legacy.get('bootstrapIp') or ''
        timeout = self.vars['DnsTimeoutMs'].get().strip()
        if not timeout.isdigit():
            raise ValueError('Тайм-аут DNS должен быть целым числом миллисекунд')
        dns['timeoutMs'] = int(timeout)
        if p.get('TunaDNS') is not None or dns['timeoutMs'] != 5000 or any(len(pool.servers) > 1 or pool.selection_mode() != 'auto' or pool.servers[0]['transport'] == 'DOT' for pool in self.dns_pools.values()):
            p['TunaDNS'] = dns
        else:
            p.pop('TunaDNS', None)
        for key in ('Geoipurl', 'Geositeurl'):
            if not p[key]:
                p.pop(key)
        hosts = {}
        for line in self.hosts.get('1.0', 'end').splitlines():
            if not line.strip():
                continue
            pair = line.split('=', 1)
            if len(pair) != 2 or pair[0].strip() in hosts:
                raise ValueError('Hosts: используйте уникальные записи домен = IP')
            hosts[pair[0].strip()] = pair[1].strip()
        p['DnsHosts'] = hosts
        if 'TunaDNS' in p and 'hosts' in p['TunaDNS']:
            p['TunaDNS']['hosts'] = copy.deepcopy(hosts)
        return policy.validate_policy(p)
