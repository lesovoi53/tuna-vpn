"""Rule completion from the currently selected, actually parsed GEO databases."""
import tkinter as tk


def suggestions(fragment, address, categories):
    prefixes = ['geoip:'] if address else ['domain:', 'full:', 'keyword:', 'regexp:', 'geosite:']
    values = categories if fragment.lower().startswith(('geoip:', 'geosite:')) else prefixes
    return [v for v in values if v.lower().startswith(fragment.lower())][:100]


class RuleCompletion:
    def __init__(self, box, address, categories, changed):
        self.text, self.address, self.categories, self.changed = box._textbox, address, categories, changed
        self.popup = None
        self.text.bind('<KeyRelease>', self.update, add='+')
        self.text.bind('<Control-space>', self.update, add='+')
        self.text.bind('<Down>', lambda e: self.move(1), add='+')
        self.text.bind('<Up>', lambda e: self.move(-1), add='+')
        self.text.bind('<Tab>', self.accept, add='+')
        self.text.bind('<Return>', self.accept, add='+')
        self.text.bind('<Escape>', lambda e: self.hide(), add='+')
        self.text.bind('<FocusOut>', lambda e: self.text.after(120, self.hide), add='+')

    def hide(self):
        if self.popup is not None:
            self.popup.destroy()
            self.popup = None

    def update(self, event=None):
        if event and event.keysym in ('Up', 'Down', 'Return', 'Tab', 'Escape'):
            return
        fragment = self.text.get('insert linestart', 'insert').strip()
        values = suggestions(fragment, self.address, self.categories())
        self.hide()
        if not values or not self.text.winfo_viewable():
            return
        bounds = self.text.bbox('insert')
        if not bounds:
            return
        self.popup = tk.Toplevel(self.text)
        self.popup.overrideredirect(True)
        self.popup.attributes('-topmost', True)
        self.list = tk.Listbox(self.popup, bg='#23263a', fg='#eeeeff', selectbackground='#8971ed',
                               font=('Segoe UI', 12), height=min(8, len(values)), width=44, takefocus=False, exportselection=False)
        self.list.pack(fill='both', expand=True)
        for item in values:
            self.list.insert('end', item)
        self.list.selection_set(0)
        self.list.bind('<ButtonRelease-1>', self.accept)
        x, y, _, height = bounds
        self.popup.geometry(f'+{min(self.text.winfo_rootx()+x, self.text.winfo_screenwidth()-430)}+{min(self.text.winfo_rooty()+y+height,self.text.winfo_screenheight()-230)}')

    def move(self, offset):
        if self.popup is None:
            return
        index = max(0, min(self.list.size()-1, self.list.curselection()[0]+offset))
        self.list.selection_clear(0, 'end')
        self.list.selection_set(index)
        self.list.see(index)
        return 'break'

    def accept(self, event=None):
        if self.popup is None:
            return
        index = self.list.nearest(event.y) if event and event.widget is self.list else self.list.curselection()[0]
        self.replace(self.list.get(index))
        self.hide()
        return 'break'

    def replace(self, value):
        self.text.delete('insert linestart', 'insert lineend')
        self.text.insert('insert', value)
        self.changed()
