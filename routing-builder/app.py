"""Modern item-based UI; CustomTkinter dependencies are bundled in _vendor."""
from pathlib import Path
import sys
if not getattr(sys, 'frozen', False):
    sys.path.insert(0, str(Path(__file__).resolve().parent / "_vendor"))
import queue
import threading
import uuid
import json
from concurrent.futures import ThreadPoolExecutor
from tkinter import filedialog, messagebox, Menu, TclError
import customtkinter as ctk
from core import resolve, routing_link, web_url
import policy
from editor import PolicyEditor

BG, PANEL, CARD, ACCENT, MUTED = "#10111b", "#1a1c2c", "#23263a", "#8971ed", "#a5a9c0"
ctk.set_appearance_mode("dark")


class SourceDialog(ctk.CTkToplevel):
    def __init__(self, app, item=None):
        super().__init__(app.root)
        self.app, self.item = app, item
        self.title("Изменить приложение" if item else "Добавить приложение")
        self.geometry("640x450")
        self.resizable(False, False)
        self.configure(fg_color=BG)
        self.transient(app.root)
        self.grid_columnconfigure(0, weight=1)
        self.kind = ctk.StringVar(value=item["kind"] if item else "Ссылка")
        self.name = ctk.StringVar(value=item["name"] if item else "")
        self.url = ctk.StringVar(value=item["source"] if item and item["kind"] == "Ссылка" else "")
        self.file = ctk.StringVar(value=item["source"] if item and item["kind"] == "APK на ПК" else "")
        ctk.CTkLabel(self, text="Отдельное приложение", font=("Segoe UI", 24, "bold")).grid(row=0, column=0, sticky="w", padx=26, pady=(24, 12))
        ctk.CTkSegmentedButton(self, values=["Ссылка", "APK на ПК"], variable=self.kind,
            command=self.switch, selected_color=ACCENT, height=38).grid(row=1, column=0, sticky="ew", padx=26)
        ctk.CTkLabel(self, text="Название · необязательно", text_color=MUTED).grid(row=2, column=0, sticky="w", padx=26, pady=(18, 3))
        ctk.CTkEntry(self, textvariable=self.name, height=40).grid(row=3, column=0, sticky="ew", padx=26)
        self.hint = ctk.CTkLabel(self, text="", text_color=MUTED)
        self.hint.grid(row=4, column=0, sticky="w", padx=26, pady=(16, 3))
        source = ctk.CTkFrame(self, fg_color="transparent")
        source.grid(row=5, column=0, sticky="ew", padx=26)
        source.grid_columnconfigure(0, weight=1)
        self.entry = ctk.CTkEntry(source, height=42)
        self.entry.grid(row=0, column=0, sticky="ew")
        self.browse = ctk.CTkButton(source, text="Выбрать…", width=110, height=42, command=self.pick, fg_color=CARD)
        self.paste_button = ctk.CTkButton(source, text="Вставить", width=110, height=42,
            command=self.paste_source, fg_color=CARD)
        self.paste_menu = Menu(self, tearoff=False)
        self.paste_menu.add_command(label="Вставить ссылку", command=self.paste_source)
        self.entry.bind("<Button-3>", self.show_paste_menu)
        self.entry.bind("<Control-KeyPress>", self.paste_shortcut)
        self.entry.bind("<Shift-Insert>", self.paste_source)
        self.error = ctk.CTkLabel(self, text="", text_color="#ffa2b5", wraplength=580, anchor="w")
        self.error.grid(row=6, column=0, sticky="ew", padx=26, pady=8)
        ctk.CTkButton(self, text="Сохранить и проверить" if item else "Добавить приложение", height=44,
            fg_color=ACCENT, command=self.submit).grid(row=7, column=0, sticky="ew", padx=26, pady=(0, 16))
        self.switch()
        self.bind("<Return>", lambda _: self.submit())
        self.bind("<Escape>", lambda _: self.destroy())
        self.after(150, self.focus_dialog)

    def focus_dialog(self):
        if self.winfo_exists():
            self.grab_set()
            self.entry.focus_set()

    def switch(self, _=None):
        local = self.kind.get() == "APK на ПК"
        self.entry.configure(textvariable=self.file if local else self.url, state="readonly" if local else "normal")
        self.hint.configure(text="APK на вашем компьютере" if local else "Google Play, RuStore или прямая ссылка на APK")
        if local:
            self.paste_button.grid_remove()
            self.browse.grid(row=0, column=1, padx=(10, 0))
        else:
            self.browse.grid_remove()
            self.paste_button.grid(row=0, column=1, padx=(10, 0))
        self.error.configure(text="")

    def paste_shortcut(self, event):
        # Windows virtual-key V is stable when the keyboard layout is Russian.
        if event.keysym.lower() == "v" or (sys.platform == "win32" and event.keycode == 86):
            return self.paste_source()

    def show_paste_menu(self, event):
        if self.kind.get() != "Ссылка":
            return
        try:
            self.paste_menu.tk_popup(event.x_root, event.y_root)
        finally:
            self.paste_menu.grab_release()
        return "break"

    def paste_source(self, event=None):
        if self.kind.get() != "Ссылка":
            return "break"
        try:
            value = self.clipboard_get()
        except TclError:
            self.error.configure(text="В буфере нет доступного текста. Скопируйте адрес ссылки и повторите.")
            return "break"
        if not value.strip():
            self.error.configure(text="Буфер обмена пуст. Сначала скопируйте ссылку.")
            return "break"
        # A source is one address: paste replaces the whole field, never concatenates URLs.
        self.url.set(value.strip())
        self.error.configure(text="")
        self.entry.focus_set()
        self.entry.icursor("end")
        return "break"

    def pick(self):
        path = filedialog.askopenfilename(parent=self, filetypes=[("Android APK", "*.apk")])
        if path:
            self.file.set(path)

    def submit(self):
        source = (self.file if self.kind.get() == "APK на ПК" else self.url).get().strip()
        try:
            if self.kind.get() == "Ссылка":
                web_url(source)
            elif not source or not Path(source).is_file() or Path(source).suffix.lower() != ".apk":
                raise ValueError("Выберите существующий APK-файл.")
            if len(self.name.get()) > 120:
                raise ValueError("Название должно быть не длиннее 120 символов.")
            self.app.add_item(source, self.kind.get(), self.name.get().strip(), self.item["id"] if self.item else None)
        except ValueError as error:
            self.error.configure(text=str(error))
            return
        self.destroy()


class App:
    def __init__(self, root):
        self.root = root
        root.title("TUNA Routing Builder")
        root.geometry("1200x880")
        root.minsize(1060, 680)
        root.configure(fg_color=BG)
        root.grid_columnconfigure(0, weight=1)
        root.grid_columnconfigure(1, weight=0, minsize=330)
        root.grid_rowconfigure(1, weight=1)
        self.items, self.events = {}, queue.Queue()
        self.cancel = threading.Event()
        self.pool = ThreadPoolExecutor(max_workers=2)
        self.futures = []
        self.mode = ctk.StringVar(value="Без VPN")
        self.search = ctk.StringVar()
        self.status = ctk.StringVar(value="Добавьте приложение — выберите ссылку или APK-файл.")
        self.dialog = None
        header = ctk.CTkFrame(root, fg_color="transparent")
        header.grid(row=0, column=0, columnspan=2, sticky="ew", padx=28, pady=(24, 20))
        ctk.CTkLabel(header, text="TUNA", font=("Segoe UI", 30, "bold"), text_color="#bcacff").pack(side="left")
        ctk.CTkLabel(header, text="  /  ROUTING BUILDER", font=("Segoe UI", 14), text_color=MUTED).pack(side="left", padx=12)
        ctk.CTkLabel(header, text="DESKTOP  2.3.1", text_color=MUTED).pack(side="right")
        self.tabs = ctk.CTkTabview(root)
        self.tabs.grid(row=1, column=0, sticky="nsew", padx=(24, 14), pady=(0, 14))
        self.tabs.add('Приложения')
        left = ctk.CTkFrame(self.tabs.tab('Приложения'), fg_color=PANEL, corner_radius=18)
        left.pack(fill='both', expand=True)
        left.grid_columnconfigure(0, weight=1)
        left.grid_rowconfigure(2, weight=1)
        top = ctk.CTkFrame(left, fg_color="transparent")
        top.grid(row=0, column=0, sticky="ew", padx=20, pady=20)
        self.count = ctk.CTkLabel(top, text="Приложения · 0", font=("Segoe UI", 20, "bold"))
        self.count.pack(side="left")
        ctk.CTkButton(top, text="+ Добавить", width=130, height=38, fg_color=ACCENT, command=self.open_dialog).pack(side="right")
        ctk.CTkEntry(left, textvariable=self.search, placeholder_text="Найти приложение…", height=40,
            fg_color=BG, border_width=0).grid(row=1, column=0, sticky="ew", padx=20, pady=(0, 12))
        self.cards = ctk.CTkScrollableFrame(left, fg_color="transparent")
        self.cards.grid(row=2, column=0, sticky="nsew", padx=12, pady=(0, 12))
        self.cards.grid_columnconfigure(0, weight=1)
        side = ctk.CTkScrollableFrame(root, width=330, fg_color=PANEL, corner_radius=18)
        side.grid(row=1, column=1, sticky="nsew", padx=(0, 24), pady=(0, 14))
        side.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(side, text="Правило маршрутизации", font=("Segoe UI", 18, "bold")).grid(row=0, column=0, padx=20, pady=(24, 14), sticky="w")
        ctk.CTkSegmentedButton(side, values=["Без VPN", "Через VPN"], variable=self.mode,
            command=lambda _: self.refresh_output(), height=40, selected_color=ACCENT).grid(row=1, column=0, padx=20, sticky="ew")
        self.description = ctk.CTkLabel(side, text="", wraplength=280, justify="left", text_color=MUTED)
        self.description.grid(row=2, column=0, sticky="w", padx=20, pady=(12, 24))
        self.export_format = ctk.StringVar(value='TUNA: полный профиль')
        ctk.CTkOptionMenu(side, variable=self.export_format, values=['TUNA: полный профиль', 'Happ: ссылка', 'Happ: Base64', 'Happ: JSON', 'TUNA: только приложения'], command=lambda _: self.refresh_output()).grid(row=3, column=0, sticky='ew', padx=20)
        self.output = ctk.CTkTextbox(side, height=110, width=280, fg_color=BG, wrap="char", font=("Consolas", 12))
        self.output.grid(row=4, column=0, sticky="ew", padx=20, pady=12)
        self.copy_button = ctk.CTkButton(side, text="Копировать ссылку", height=44, fg_color=ACCENT, command=self.copy)
        self.copy_button.grid(row=5, column=0, sticky="ew", padx=20)
        self.save_button = ctk.CTkButton(side, text="Сохранить в TXT", height=40, fg_color=CARD, command=self.save)
        self.save_button.grid(row=6, column=0, sticky="ew", padx=20, pady=(10, 20))
        self.autoactivate = ctk.BooleanVar(value=True)
        ctk.CTkCheckBox(side, text='Happ: активировать (onadd)', variable=self.autoactivate, command=self.refresh_output).grid(row=7, column=0, sticky='w', padx=20, pady=5)
        for row, text, command in [(8, 'Импорт JSON / Base64 / ссылки', self.import_dialog), (9, 'Открыть проект…', self.open_project), (10, 'Сохранить проект…', self.save_project)]:
            ctk.CTkButton(side, text=text, command=command, fg_color=CARD, height=32).grid(row=row, column=0, sticky='ew', padx=20, pady=4)
        self.warning_label = ctk.CTkLabel(side, text='', text_color='#e6b87a', justify='left', wraplength=280)
        self.warning_label.grid(row=11, column=0, sticky='w', padx=20, pady=8)
        self.editor = PolicyEditor(self.tabs, self.refresh_output, self.submit_background, self.cancel)
        ctk.CTkLabel(root, textvariable=self.status, text_color=MUTED, anchor="w", wraplength=900).grid(row=2, column=0, columnspan=2, sticky="ew", padx=28, pady=(0, 14))
        self.search.trace_add("write", lambda *_: self.render_cards())
        self.render_cards()
        self.refresh_output()
        root.protocol("WM_DELETE_WINDOW", self.close)
        self.timer = root.after(100, self.poll)

    def open_dialog(self, ident=None):
        if self.dialog and self.dialog.winfo_exists():
            self.dialog.focus()
            return
        self.dialog = SourceDialog(self, self.items.get(ident))

    def submit_background(self, work):
        future = self.pool.submit(work)
        self.futures.append(future)
        return future

    def add_item(self, source, kind="Ссылка", name="", ident=None):
        if ident is None and len(self.items) >= 100:
            raise ValueError("В списке уже 100 пунктов. Удалите лишние.")
        ident, token = ident or uuid.uuid4().hex, uuid.uuid4().hex
        self.items[ident] = dict(id=ident, source=source, kind=kind, name=name, package=None, status="Проверка источника…", token=token)
        self.render_cards()
        self.refresh_output()

        def task():
            try:
                self.events.put((ident, token, resolve(source, self.cancel), None))
            except Exception as error:
                self.events.put((ident, token, None, str(error) if isinstance(error, ValueError) else type(error).__name__))
        self.futures.append(self.pool.submit(task))
        return ident

    def poll(self):
        try:
            while True:
                ident, token, package, error = self.events.get_nowait()
                item = self.items.get(ident)
                if not item or item["token"] != token:
                    continue
                if package and any(other["package"] == package for key, other in self.items.items() if key != ident):
                    error, package = "Это приложение уже добавлено. Удалите повтор или измените источник.", None
                item.update(package=package, status=error or "Готово")
                self.render_cards()
                self.refresh_output()
        except queue.Empty:
            pass
        self.futures = [f for f in self.futures if not f.done()]
        self.timer = self.root.after(100, self.poll)

    def render_cards(self):
        for child in self.cards.winfo_children():
            child.destroy()
        self.count.configure(text=f"Приложения · {len(self.items)}")
        query = self.search.get().lower()
        visible = [i for i in self.items.values() if query in (i["name"] + (i["package"] or "") + i["source"]).lower()]
        if not visible:
            ctk.CTkLabel(self.cards, text="Добавьте первое приложение" if not self.items else "Ничего не найдено",
                font=("Segoe UI", 17), text_color=MUTED).grid(row=0, column=0, pady=70, padx=12)
        for row, item in enumerate(visible):
            card = ctk.CTkFrame(self.cards, fg_color=CARD, corner_radius=14)
            card.grid(row=row, column=0, sticky="ew", padx=4, pady=5)
            card.grid_columnconfigure(0, weight=1)
            name = item["name"] or item["package"] or "Новое приложение"
            ctk.CTkLabel(card, text=name[:55], font=("Segoe UI", 15, "bold"), anchor="w", wraplength=300).grid(row=0, column=0, sticky="ew", padx=16, pady=(12, 0))
            buttons = ctk.CTkFrame(card, fg_color="transparent")
            buttons.grid(row=0, column=1, padx=(0, 10), pady=(10, 0))
            ctk.CTkButton(buttons, text="Изменить", width=80, height=28, fg_color=PANEL, command=lambda key=item["id"]: self.open_dialog(key)).pack(side="left", padx=3)
            ctk.CTkButton(buttons, text="×", width=30, height=28, fg_color=PANEL, command=lambda key=item["id"]: self.remove(key)).pack(side="left")
            ctk.CTkLabel(card, text=item["package"] or item["status"], anchor="w", justify="left", wraplength=400,
                text_color="#b7a7fa" if item["package"] else MUTED).grid(row=1, column=0, columnspan=2, sticky="ew", padx=16)
            source = item["source"]
            if len(source) > 60:
                source = source[:35] + "…" + source[-20:]
            ctk.CTkLabel(card, text=f'{item["kind"]}  ·  {source}', anchor="w", text_color=MUTED, font=("Segoe UI", 11), wraplength=400).grid(row=2, column=0, columnspan=2, sticky="ew", padx=16, pady=(2, 12))

    def remove(self, ident):
        self.items.pop(ident, None)
        self.render_cards()
        self.refresh_output()

    def link(self):
        return routing_link([i["package"] for i in self.items.values() if i["package"]], "bypass" if self.mode.get() == "Без VPN" else "vpn")

    def current_document(self):
        if any(not i['package'] for i in self.items.values()):
            raise ValueError('Исправьте ошибки приложений или дождитесь проверки источников')
        return policy.document(self.editor.value(), [i['package'] for i in self.items.values()] or None, 'bypass' if self.mode.get() == 'Без VPN' else 'vpn')

    def export_text(self):
        kind = self.export_format.get()
        if kind == 'TUNA: только приложения':
            if any(not i['package'] for i in self.items.values()):
                raise ValueError('Есть ошибки или незавершённые проверки приложений')
            return self.link()
        if kind == 'TUNA: полный профиль':
            return policy.tuna_link(self.current_document())
        result = policy.happ_export(self.editor.value(), {'Happ: JSON': 'json', 'Happ: Base64': 'base64'}.get(kind, 'link'))
        return result.replace('happ://routing/onadd/', 'happ://routing/add/', 1) if not self.autoactivate.get() else result

    def refresh_output(self):
        if not hasattr(self, 'editor') or getattr(self.editor, 'loading', False):
            return
        try:
            result = self.export_text()
            ready = True
            self.status.set(f'Готово. Приложений: {len(self.items)}. Выберите формат экспорта.')
            notices = [] if self.export_format.get() == 'TUNA: только приложения' else policy.warnings(self.editor.value())
            if self.export_format.get() == 'TUNA: полный профиль':
                p = self.editor.value()
                notices = policy.warnings({k: v for k, v in p.items() if k in policy.KNOWN and k not in ('FakeDNS', 'UseChunkFiles')})
                if p.get('FakeDNS') or p.get('UseChunkFiles') or set(p) - policy.KNOWN:
                    notices.insert(0, 'Для TUNA автоматически отключены FakeDNS/UseChunkFiles и исключены неизвестные Happ-поля. Исходный проект сохранён.')
            if self.export_format.get().startswith('Happ'):
                notices.insert(0, 'Happ не содержит список приложений; он остаётся в проекте.')
            self.warning_label.configure(text='\n'.join(notices[:3]))
        except ValueError as error:
            ready, result = False, str(error)
            self.status.set('Ошибка: ' + result)
            self.warning_label.configure(text='')
        self.output.configure(state='normal')
        self.output.delete('1.0', 'end')
        self.output.insert('1.0', result)
        self.output.configure(state='disabled')
        for button in (self.copy_button, self.save_button):
            button.configure(state='normal' if ready else 'disabled')
        self.description.configure(text='Приложения напрямую. Политика сайтов действует на трафик внутри VPN.' if self.mode.get() == 'Без VPN' else 'Только эти приложения входят в VPN. Далее действует политика сайтов.')

    def copy(self):
        self.refresh_output()
        if self.copy_button.cget("state") == "disabled":
            return
        try:
            text = self.export_text()
        except ValueError as error:
            messagebox.showerror('Экспорт', str(error))
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.status.set("Ссылка скопирована.")

    def save(self):
        self.refresh_output()
        if self.save_button.cget("state") == "disabled":
            return
        path = filedialog.asksaveasfilename(defaultextension=".txt", initialfile="tuna-routing.txt", filetypes=[("Текст", "*.txt")])
        if path:
            try:
                Path(path).write_text(self.export_text() + "\n", encoding="utf-8")
                self.status.set("Ссылка сохранена.")
            except (OSError, ValueError) as error:
                messagebox.showerror("Ошибка сохранения", str(error))

    def apply_document(self, doc, metadata=None):
        checked = policy.validate_document(doc)
        # Validate everything before touching the current editor or pending workers.
        new_items = {}
        apps = checked.get('apps', {})
        packages = apps.get('packages', [])
        if metadata is not None:
            if not isinstance(metadata, list) or len(metadata) != len(packages):
                raise ValueError('Некорректные данные приложений проекта')
            if [i.get('package') for i in metadata if isinstance(i, dict)] != packages:
                raise ValueError('Приложения проекта не совпадают с профилем')
        for index, package in enumerate(packages):
            meta = metadata[index] if metadata is not None else {}
            for key, limit in [('name', 120), ('source', 16384), ('kind', 30)]:
                if not isinstance(meta.get(key, ''), str) or len(meta.get(key, '')) > limit:
                    raise ValueError('Некорректное описание приложения')
            if meta.get('kind', 'Ссылка') not in ('Ссылка', 'APK на ПК'):
                raise ValueError('Неизвестный тип источника приложения')
            ident = uuid.uuid4().hex
            new_items[ident] = dict(id=ident, token=uuid.uuid4().hex, package=package, name=meta.get('name', ''), source=meta.get('source') or 'https://play.google.com/store/apps/details?id=' + package, kind=meta.get('kind') or 'Ссылка', status='Готово')
        source = dict(checked['policy'])
        if 'dns' in checked:
            source['TunaDNS'] = checked['dns']
        self.editor.load(source)
        self.items = new_items
        self.mode.set('Через VPN' if apps.get('mode') == 'vpn' else 'Без VPN')
        self.render_cards()
        self.refresh_output()

    def import_dialog(self):
        dialog = ctk.CTkToplevel(self.root)
        dialog.title('Импорт маршрутизации')
        dialog.geometry('720x440')
        dialog.transient(self.root)
        ctk.CTkLabel(dialog, text='Вставьте Happ, TUNA, JSON/Base64 или HTTP(S)-ссылку на файл правил.').pack(pady=10)
        merge = ctk.BooleanVar(value=True)
        ctk.CTkCheckBox(dialog, text='Дополнить текущие правила (без повторов)', variable=merge).pack(pady=5)
        box = ctk.CTkTextbox(dialog)
        box.pack(fill='both', expand=True, padx=15, pady=5)
        error = ctk.CTkLabel(dialog, text='', text_color='#ffa2b5', wraplength=680)
        error.pack()
        def apply():
            from routing_sources import import_source, merge_documents
            source = box.get('1.0', 'end')
            use_merge = merge.get()
            button.configure(state='disabled')
            error.configure(text='Чтение и проверка правил…')
            future = self.submit_background(lambda: import_source(source, self.cancel))
            def finish():
                if self.cancel.is_set() or not dialog.winfo_exists():
                    return
                if not future.done():
                    self.root.after(100, finish)
                    return
                try:
                    doc = future.result()
                    if use_merge:
                        doc = merge_documents(self.current_document(), doc)
                    metadata = None
                    if use_merge and doc.get('apps'):
                        saved = {i['package']: i for i in self.items.values()}
                        metadata = [{k: saved.get(p, {}).get(k, p if k == 'package' else '')
                                     for k in ('package', 'name', 'source', 'kind')} for p in doc['apps']['packages']]
                    self.apply_document(doc, metadata)
                    if all(self.editor.vars[k].get().strip() for k in ('Geoipurl', 'Geositeurl')):
                        self.editor.download_geo()
                except Exception as exc:
                    error.configure(text=str(exc) if isinstance(exc, ValueError) else 'Не удалось импортировать правила')
                    button.configure(state='normal')
                    return
                dialog.destroy()
            self.root.after(100, finish)
        def paste():
            try:
                text = dialog.clipboard_get()
                box.delete('1.0', 'end')
                box.insert('1.0', text)
            except TclError:
                error.configure(text='В буфере нет текста')
        ctk.CTkButton(dialog, text='Вставить из буфера', command=paste).pack(pady=5)
        button = ctk.CTkButton(dialog, text='Проверить и импортировать', command=apply)
        button.pack(pady=10)

    def save_project(self):
        try:
            doc = self.current_document()
            project = dict(format='tuna-routing-builder', version=1, document=doc, items=[{k: i[k] for k in ('package', 'name', 'source', 'kind')} for i in self.items.values()])
            path = filedialog.asksaveasfilename(defaultextension='.json', initialfile='tuna-project.json', filetypes=[('Проект JSON', '*.json')])
            if path:
                Path(path).write_text(json.dumps(project, ensure_ascii=False, indent=2), encoding='utf-8')
                self.status.set('Проект сохранён с названиями и источниками приложений.')
        except (ValueError, OSError) as error:
            messagebox.showerror('Проект', str(error))

    def open_project(self):
        path = filedialog.askopenfilename(filetypes=[('JSON / текст', '*.json *.txt'), ('Все файлы', '*.*')])
        if not path:
            return
        try:
            if Path(path).stat().st_size > 4 * 1024 * 1024:
                raise ValueError('Файл слишком большой')
            text = Path(path).read_text(encoding='utf-8-sig')
            try:
                source = json.loads(text)
            except json.JSONDecodeError:
                source = None
            if isinstance(source, dict) and source.get('format') == 'tuna-routing-builder':
                if source.get('version') != 1:
                    raise ValueError('Неизвестная версия проекта')
                self.apply_document(source.get('document'), source.get('items'))
            else:
                self.apply_document(policy.import_text(text))
        except (ValueError, OSError, UnicodeError) as error:
            messagebox.showerror('Импорт', str(error))

    def close(self):
        self.cancel.set()
        self.pool.shutdown(wait=False, cancel_futures=True)
        if any(not f.done() for f in self.futures):
            self.status.set("Завершаю загрузку и удаляю временный APK…")
            self.root.after(100, self.close)
            return
        self.root.after_cancel(self.timer)
        self.root.destroy()


if __name__ == "__main__":
    if '--self-test' in sys.argv:
        from frozen_smoke import run
        run()
    else:
        App(ctk.CTk()).root.mainloop()
