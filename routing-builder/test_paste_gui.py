"""GUI paste path; clipboard reads are mocked, system clipboard is untouched."""
from types import SimpleNamespace
import time
from unittest.mock import patch
from tkinter import TclError
from app import App, SourceDialog, ctk

root = ctk.CTk()
root.withdraw()
app = App(root)
dialog = SourceDialog(app)
dialog.withdraw()
url = "https://play.google.com/store/apps/details?id=ru.rostel&hl=ru"
try:
    with patch.object(dialog, "clipboard_get", return_value=url):
        dialog.paste_button.invoke()
        assert dialog.url.get() == url
        dialog.url.set("")
        assert dialog.paste_shortcut(SimpleNamespace(keycode=86, keysym="Cyrillic_em")) == "break"
        assert dialog.url.get() == url
        dialog.url.set("")
        dialog.paste_menu.invoke(0)
        assert dialog.url.get() == url
    with patch.object(dialog, "clipboard_get", side_effect=TclError("no text")):
        dialog.paste_button.invoke()
        assert dialog.error.cget("text")
        assert dialog.url.get() == url
    dialog.kind.set("APK на ПК")
    dialog.switch()
    with patch.object(dialog, "clipboard_get") as read:
        dialog.paste_source()
        read.assert_not_called()
    dialog.kind.set("Ссылка")
    dialog.switch()
    dialog.submit()
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        root.update()
        if not app.futures and app.events.empty():
            break
        time.sleep(0.02)
    assert app.link() == "tuna://app-exclusions/v1?mode=bypass&packages=ru.rostel"
    assert app.copy_button.cget("state") == "normal"
    print("PASS: paste button, Russian-layout shortcut handler, context menu, empty clipboard, APK guard")
finally:
    if dialog.winfo_exists():
        dialog.destroy()
    app.close()
