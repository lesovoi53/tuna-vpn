"""Offline packaging acceptance: actual hidden widgets and routing round-trip."""
def run():
    import json
    import sys
    from pathlib import Path
    from app import App, ctk
    import policy
    from runtime_paths import geo_cache_dir
    root = ctk.CTk()
    root.withdraw()
    app = App(root)
    try:
        app.editor.rules['DirectSites'].insert('1.0', 'domain:example.com')
        app.editor.dns_pools['Remote'].load([dict(transport='DOH', endpoint='https://private.example/dns-query', bootstrapIp=None, route='VPN')])
        app.refresh_output()
        imported = app.editor.value()
        imported.update(FakeDNS=True, UseChunkFiles=True, UnsupportedHappField='preserve-original')
        app.editor.load(imported)
        app.refresh_output()
        exported = app.export_text()
        parsed = policy.import_text(exported)
        assert 'domain:example.com' in parsed['policy']['DirectSites']
        assert parsed['policy']['FakeDNS'] is False and parsed['policy']['UseChunkFiles'] is False
        assert 'UnsupportedHappField' not in parsed['policy']
        assert app.editor.value()['UnsupportedHappField'] == 'preserve-original'
        assert app.copy_button.cget('state') == 'normal'
        assert not getattr(sys, 'frozen', False) or str(getattr(sys, '_MEIPASS', '---')) not in str(geo_cache_dir())
        root.update_idletasks()
        if '--self-test-report' in sys.argv:
            Path(sys.argv[sys.argv.index('--self-test-report') + 1]).write_text(json.dumps({'ok': True, 'frozen': bool(getattr(sys, 'frozen', False)), 'checks': ['Tk widgets', 'CustomTkinter assets', 'rules', 'DoH without IP', 'TUNA export/import', 'persistent cache path']}), encoding='utf-8')
    finally:
        app.close()
