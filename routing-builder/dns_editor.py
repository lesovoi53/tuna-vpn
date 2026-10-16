"""Ordered DNS pools. Each pool has its own mode and never crosses routing boundaries."""
import copy
import customtkinter as ctk


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

    def load(self, servers, mode='auto'):
        self.servers = copy.deepcopy(servers)
        self.mode.set('Приоритет' if mode == 'priority' else 'Авто')
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
