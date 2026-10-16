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
    assert 'TunaDNS' not in initial
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
    print('PASS: DNS GUI legacy roundtrip, 8-server limit, reorder, independent pools, hosts, import/export')
finally:
    root.destroy()
