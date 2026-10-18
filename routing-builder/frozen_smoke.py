"""Offline packaging acceptance: actual hidden widgets and routing round-trip."""
def run():
    import json
    import sys
    import tempfile
    from unittest.mock import patch
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
        app.editor.dns_pools['Remote'].emergency.select(app.editor.dns_pools['Remote'].emergency.SYSTEM)
        app.editor.dns_pools['Domestic'].emergency.select(app.editor.dns_pools['Domestic'].emergency.DISABLED)
        app.editor.bootstrap_dns.load(dict(mode='resolver', resolver=dict(transport='TCP', endpoint='[2001:db8::53]:5353', route='DIRECT')))
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
        assert parsed['dns']['remoteEmergency'] == {'mode': 'system'}
        assert parsed['dns']['domesticEmergency'] == {'mode': 'disabled'}
        assert parsed['dns']['bootstrap']['resolver']['endpoint'] == '[2001:db8::53]:5353'
        # Use the same file/dialog handlers as the user; only the dialog is mocked.
        with tempfile.TemporaryDirectory(prefix='tuna-builder-selftest-') as directory:
            project = str(Path(directory) / 'project.json')
            expected = app.current_document()
            with patch('app.filedialog.asksaveasfilename', return_value=project):
                app.save_project()
            assert Path(project).is_file()
            app.editor.load(policy.defaults())
            with patch('app.filedialog.askopenfilename', return_value=project):
                app.open_project()
            assert app.current_document() == expected
            assert policy.import_text(app.export_text()) == parsed
        assert not getattr(sys, 'frozen', False) or str(getattr(sys, '_MEIPASS', '---')) not in str(geo_cache_dir())
        root.update_idletasks()
        if '--self-test-report' in sys.argv:
            Path(sys.argv[sys.argv.index('--self-test-report') + 1]).write_text(json.dumps({'ok': True, 'version': '2.4', 'frozen': bool(getattr(sys, 'frozen', False)), 'checks': ['Tk widgets', 'CustomTkinter assets', 'rules', 'DoH without IP', 'emergency system/disabled', 'manual IPv6 TCP bootstrap', 'TUNA export/import', 'project save/open', 'persistent cache path']}), encoding='utf-8')
    finally:
        app.close()
