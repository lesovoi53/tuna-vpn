"""Hidden-window DNS editor regression; no network or clipboard access."""
import copy
from app import ctk
from editor import PolicyEditor
import policy

root = ctk.CTk()
root.withdraw()
try:
    tabs = ctk.CTkTabview(root)
    editor = PolicyEditor(tabs, lambda: None)
    assert 'FakeDNS' not in editor.vars
    assert 'UseChunkFiles' not in editor.vars
    initial = editor.value()
    assert initial['TunaDNS']['remoteEmergency'] == {'mode': 'system'}
    assert initial['TunaDNS']['domesticEmergency'] == {'mode': 'system'}
    assert 'bootstrap' not in initial['TunaDNS']
    source = policy.defaults()
    source['TunaDNS'] = {
        'remoteServers': [dict(transport='UDP', endpoint=f'8.8.8.{i}', route='VPN') for i in range(1, 9)],
        'domesticServers': [dict(transport='UDP', endpoint='1.1.1.1', route='DIRECT')],
        'remoteSelectionMode': 'priority', 'domesticSelectionMode': 'auto',
        'timeoutMs': 4000, 'hosts': {'test.invalid': '192.0.2.1'},
    }
    editor.load(source)
    pool = editor.dns_pools['Remote']
    assert pool.add_button.cget('state') == 'disabled'
    pool.add()
    assert len(pool.servers) == 8
    pool.move(0, 1)
    assert pool.servers[0]['endpoint'] == '8.8.8.2'
    editor.hosts.delete('1.0', 'end')
    editor.hosts.insert('1.0', 'test.invalid = 192.0.2.2')
    result = policy.document(editor.value())
    assert result['dns']['remoteSelectionMode'] == 'priority'
    assert result['dns']['hosts'] == {'test.invalid': '192.0.2.2'}
    assert result['policy']['DnsHosts'] == result['dns']['hosts']
    assert result['dns']['domesticServers'][0]['endpoint'] == '1.1.1.1'
    for _ in range(9):
        pool.remove(0)
    assert len(pool.servers) == 1
    loaded = copy.deepcopy(result['policy'])
    loaded['TunaDNS'] = result['dns']
    editor.load(loaded)
    assert policy.document(editor.value()) == result
    custom = policy.defaults()
    custom['RemoteDNSType'] = 'DoH'
    custom['RemoteDNSDomain'] = 'https://andemikus.ru/dns-query'
    custom['RemoteDNSIP'] = ''
    editor.load(custom)
    single = policy.document(editor.value())
    assert single['policy']['RemoteDNSIP'] == ''
    assert policy.import_text(policy.tuna_link(single)) == single
    editor.dns_pools['Remote'].servers.append(dict(transport='DOH', endpoint='https://dns.internal.example:8443/query', bootstrapIp=None, route='VPN'))
    multiple = policy.document(editor.value())
    assert multiple['dns']['remoteServers'][1]['bootstrapIp'] is None
    assert policy.import_text(policy.tuna_link(multiple)) == multiple
    # Exercise the actual option and entry callbacks, including preset -> custom.
    for choice in (pool.emergency, editor.dns_pools['Domestic'].emergency, editor.bootstrap_dns):
        for label in choice.presets:
            choice.select(label)
            doc = policy.document(editor.value())
            assert policy.import_text(policy.tuna_link(doc)) == doc
        choice.select(choice.CUSTOM)
        entries = [child for child in choice.fields.winfo_children() if isinstance(child, ctk.CTkEntry)]
        entries[0]._dns_variable.set('[2001:db8::53]:5353' if choice.bootstrap else 'https://reserve.example:8443/query?test=fixture')
        if not choice.bootstrap:
            menu = next(child for child in choice.fields.winfo_children() if isinstance(child, ctk.CTkOptionMenu))
            menu._command('DOH')
        doc = policy.document(editor.value())
        assert policy.import_text(policy.tuna_link(doc)) == doc
        assert choice.selection.get() == choice.CUSTOM
        if not choice.bootstrap:
            assert choice.value()['resolver']['bootstrapIp'] is None
    result = policy.document(editor.value())
    loaded = copy.deepcopy(result['policy'])
    loaded['TunaDNS'] = result['dns']
    editor.load(loaded)
    assert policy.document(editor.value()) == result
    for choice in (pool.emergency, editor.dns_pools['Domestic'].emergency):
        choice.select(choice.DISABLED)
        assert choice.value() == {'mode': 'disabled'}
        choice.select(choice.SYSTEM)
        assert choice.value() == {'mode': 'system'}
    editor.bootstrap_dns.select(editor.bootstrap_dns.SYSTEM)
    assert policy.document(editor.value())['dns']['bootstrap'] == {'mode': 'system'}
    editor.bootstrap_dns.select(editor.bootstrap_dns.INHERIT)
    assert 'bootstrap' not in policy.document(editor.value())['dns']
    print('PASS: DNS GUI legacy roundtrip, 8-server limit, reorder, independent pools, hosts, import/export')
    print('PASS: emergency/bootstrap presets, manual DoH without pin, IPv6 TCP bootstrap, disabled/system/inherit')
finally:
    root.destroy()
