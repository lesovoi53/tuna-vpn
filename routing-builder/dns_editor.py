"""Ordered DNS pools with an explicit, separate emergency resolver and bootstrap."""
import copy
import customtkinter as ctk


# Same basic provider endpoints as Android DnsPresets. These are choices, not
# availability claims; custom endpoints remain editable and are never resolved here.
DNS_PRESETS = {
    'Россия · Яндекс · UDP': ('UDP', '77.88.8.8'),
    'Глобальный · Google · UDP': ('UDP', '8.8.8.8'),
    'Глобальный · Cloudflare · UDP': ('UDP', '1.1.1.1'),
    'Глобальный · Google · DoH': ('DOH', 'https://dns.google/dns-query'),
    'Глобальный · Cloudflare · DoH': ('DOH', 'https://cloudflare-dns.com/dns-query'),
}


class DnsChoiceEditor:
    SYSTEM = 'Системный / провайдера (localhost)'
    DISABLED = 'Отключён'
    INHERIT = 'Наследовать настройки Android'
    CUSTOM = 'Ввести вручную'

    def __init__(self, parent, route, changed, *, bootstrap=False):
        self.route, self.changed, self.bootstrap = route, changed, bootstrap
        self.mode = 'inherit' if bootstrap else 'system'
        self.resolver = dict(transport='TCP', endpoint='', bootstrapIp=None, route=route)
        self.presets = ({label.replace('UDP', 'TCP'): ('TCP', endpoint + ':53')
                         for label, (transport, endpoint) in DNS_PRESETS.items() if transport == 'UDP'}
                        if bootstrap else DNS_PRESETS)
        self.frame = ctk.CTkFrame(parent)
        self.frame.pack(fill='x', padx=8, pady=8)
        title = 'Bootstrap DNS · адреса серверов' if bootstrap else 'Аварийный DNS · после отказа всей группы'
        ctk.CTkLabel(self.frame, text=title, anchor='w').pack(fill='x', padx=8, pady=5)
        self.selection = ctk.StringVar(value=self.INHERIT if bootstrap else self.SYSTEM)
        choices = ([self.INHERIT, self.SYSTEM] if bootstrap else [self.SYSTEM, self.DISABLED]) + list(self.presets) + [self.CUSTOM]
        ctk.CTkOptionMenu(self.frame, variable=self.selection, values=choices, command=self.select).pack(fill='x', padx=8, pady=5)
        text = ('Системный / localhost — DNS исходной сети Android (Wi-Fi или оператора), всегда напрямую, вне VPN. Это не 127.0.0.1 и не DNS внутри TUN. '
                + ('Bootstrap разрешает только адреса серверов. В ручном режиме: TCP и IP; пустое наследование сохраняет настройки Android.' if bootstrap
                   else 'Используется после отказа основных DNS. Ручной резерв идёт по маршруту этой группы. Он не занимает место в списке 1–8.'))
        ctk.CTkLabel(self.frame, text=text, wraplength=550, justify='left').pack(anchor='w', padx=8, pady=5)
        ctk.CTkLabel(self.frame, text='Доступность выбранного сервиса зависит от сети; пресет её не гарантирует.', wraplength=550, justify='left').pack(anchor='w', padx=8)
        self.fields = ctk.CTkFrame(self.frame, fg_color='transparent')
        self.fields.pack(fill='x')
        self.render()

    def load(self, value=None):
        value = copy.deepcopy(value) if value is not None else {'mode': 'inherit' if self.bootstrap else 'system'}
        self.mode = value['mode']
        if self.mode == 'resolver':
            self.resolver = value['resolver']
            self.selection.set(self.CUSTOM)
        else:
            self.selection.set({'inherit': self.INHERIT, 'system': self.SYSTEM, 'disabled': self.DISABLED}[self.mode])
        self.render()

    def select(self, label):
        self.selection.set(label)
        self.mode = {self.INHERIT: 'inherit', self.SYSTEM: 'system', self.DISABLED: 'disabled'}.get(label, 'resolver')
        if label in self.presets:
            transport, endpoint = self.presets[label]
            self.resolver = dict(transport=transport, endpoint=endpoint, bootstrapIp=None, route=self.route)
        self.render()
        self.changed()

    def value(self):
        if self.mode == 'inherit':
            return None
        if self.mode == 'resolver':
            return dict(mode='resolver', resolver=copy.deepcopy(self.resolver))
        return dict(mode=self.mode)

    def render(self):
        for child in self.fields.winfo_children():
            child.destroy()
        if self.mode != 'resolver':
            return
        if self.bootstrap:
            ctk.CTkLabel(self.fields, text='TCP · напрямую · IP:порт (IPv6 в квадратных скобках)').pack(anchor='w', padx=8)
        else:
            self.transport = ctk.StringVar(value=self.resolver['transport'])
            def set_transport(value):
                self.resolver['transport'] = value
                self.selection.set(self.CUSTOM)
                self.changed()
            ctk.CTkOptionMenu(self.fields, variable=self.transport, values=['DOH', 'UDP', 'TCP'], command=set_transport).pack(fill='x', padx=8, pady=5)
        for key, title in [('endpoint', 'Адрес Bootstrap: IP:порт' if self.bootstrap else 'Адрес аварийного DNS: IP:порт / https://…'),
                           *([] if self.bootstrap else [('bootstrapIp', 'Bootstrap IP резервного DoH (необязательно)')])]:
            ctk.CTkLabel(self.fields, text=title, anchor='w').pack(fill='x', padx=8)
            variable = ctk.StringVar(value=self.resolver.get(key) or '')
            def update(*_, var=variable, field=key):
                value = var.get().strip()
                self.resolver[field] = value if field == 'endpoint' else value or None
                self.selection.set(self.CUSTOM)
                self.changed()
            variable.trace_add('write', update)
            entry = ctk.CTkEntry(self.fields, textvariable=variable)
            entry.pack(fill='x', padx=8, pady=5)
            entry._dns_variable = variable


class DnsPoolEditor:
    def __init__(self, parent, route, changed):
        self.route, self.changed = route, changed
        self.servers = []
        self.frame = ctk.CTkFrame(parent)
        self.frame.pack(fill='x', pady=8)
        self.mode = ctk.StringVar(value='Авто')
        ctk.CTkOptionMenu(self.frame, variable=self.mode, values=['Авто', 'Приоритет'], command=lambda _: changed()).pack(fill='x', padx=8, pady=5)
        ctk.CTkLabel(self.frame, text='Авто: быстрый и стабильный. Приоритет: порядок списка. До 8 серверов.', wraplength=580).pack(anchor='w', padx=8)
        self.rows = ctk.CTkFrame(self.frame, fg_color='transparent')
        self.rows.pack(fill='x')
        self.add_button = ctk.CTkButton(self.frame, text='+ Добавить DNS', command=self.add)
        self.add_button.pack(anchor='w', padx=8, pady=8)
        self.emergency = DnsChoiceEditor(self.frame, route, changed)

    def load(self, servers, mode='auto', emergency=None):
        self.servers = copy.deepcopy(servers)
        self.mode.set('Приоритет' if mode == 'priority' else 'Авто')
        self.emergency.load(emergency)
        self.render()

    def value(self):
        return copy.deepcopy(self.servers)

    def selection_mode(self):
        return 'priority' if self.mode.get() == 'Приоритет' else 'auto'

    def add(self):
        if len(self.servers) < 8:
            self.servers.append(dict(transport='UDP', endpoint='', bootstrapIp=None, route=self.route))
            self.render()
            self.changed()

    def move(self, index, delta):
        target = index + delta
        if 0 <= target < len(self.servers):
            self.servers.insert(target, self.servers.pop(index))
            self.render()
            self.changed()

    def remove(self, index):
        if len(self.servers) > 1:
            self.servers.pop(index)
            self.render()
            self.changed()

    def render(self):
        for child in self.rows.winfo_children():
            child.destroy()
        for index, server in enumerate(self.servers):
            row = ctk.CTkFrame(self.rows)
            row.pack(fill='x', padx=8, pady=5)
            row.grid_columnconfigure(1, weight=1)
            ctk.CTkLabel(row, text=f'DNS {index + 1}').grid(row=0, column=0, padx=6)
            transport = ctk.StringVar(value=server['transport'])
            def set_transport(value, item=server):
                item['transport'] = value
                self.changed()
            ctk.CTkOptionMenu(row, variable=transport, values=['DOH', 'UDP', 'TCP', 'DOT'], command=set_transport, width=100).grid(row=0, column=1, sticky='w', padx=6, pady=5)
            for col, label, delta in [(2, '↑', -1), (3, '↓', 1)]:
                state = 'normal' if 0 <= index + delta < len(self.servers) else 'disabled'
                ctk.CTkButton(row, text=label, width=32, state=state, command=lambda i=index, d=delta: self.move(i, d)).grid(row=0, column=col, padx=2)
            ctk.CTkButton(row, text='×', width=32, state='normal' if len(self.servers) > 1 else 'disabled', command=lambda i=index: self.remove(i)).grid(row=0, column=4, padx=5)
            for line, key, hint in [(1, 'endpoint', 'Адрес DNS: IP:порт / https://…/dns-query / tls://…'), (3, 'bootstrapIp', 'Bootstrap IP: для DoH необязательно; для DoT с доменом обязательно')]:
                ctk.CTkLabel(row, text=hint, anchor='w').grid(row=line, column=0, columnspan=5, sticky='ew', padx=6)
                variable = ctk.StringVar(value=server.get(key) or '')
                def update(*_, var=variable, item=server, field=key):
                    value = var.get().strip()
                    item[field] = value if field == 'endpoint' else value or None
                    self.changed()
                variable.trace_add('write', update)
                entry = ctk.CTkEntry(row, textvariable=variable, placeholder_text=hint)
                entry.grid(row=line + 1, column=0, columnspan=5, sticky='ew', padx=6, pady=4)
                # Keep Tk variables alive for as long as the row exists.
                entry._dns_variable = variable
            row._dns_transport = transport
        self.add_button.configure(state='normal' if len(self.servers) < 8 else 'disabled')
