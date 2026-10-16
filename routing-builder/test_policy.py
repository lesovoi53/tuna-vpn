import base64
import copy
import json
import unittest
import policy


class PolicyTests(unittest.TestCase):
    def test_roundtrip_formats(self):
        p = policy.defaults()
        p['DirectSites'] = ['domain:example.com', 'geosite:youtube']
        p['Geositeurl'] = 'https://example.com/geosite.dat'
        p['ProxyIp'] = ['2001:db8::/32']
        p['DnsHosts'] = {'host.example': '192.0.2.1'}
        for kind in ('json', 'base64', 'link'):
            self.assertEqual(policy.import_text(policy.happ_export(p, kind))['policy'], p)
        doc = policy.document(p, ['com.android.chrome'], 'vpn')
        self.assertEqual(policy.import_text(policy.tuna_link(doc)), doc)

    def test_dns_all_transports(self):
        for transport, endpoint in [('UDP', '1.1.1.1:53'), ('TCP', '[2001:4860:4860::8888]:53'), ('DoH', 'https://dns.google/dns-query')]:
            p = policy.defaults()
            p['RemoteDNSType'] = transport
            p['Remote' + ('DNSDomain' if transport == 'DoH' else 'Dns')] = endpoint
            self.assertEqual(policy.validate_policy(p)['RemoteDNSType'], transport)

    def test_tuna_geo_requires_asset_url(self):
        for rule, value, key in [('DirectSites', 'geosite:youtube', 'Geositeurl'), ('DirectIp', 'geoip:private', 'Geoipurl')]:
            p = policy.defaults()
            p[rule] = [value]
            with self.assertRaisesRegex(ValueError, key):
                policy.tuna_link(policy.document(p))
            p[key] = 'https://example.com/asset.dat'
            self.assertTrue(policy.tuna_link(policy.document(p)).startswith('tuna://routing/v1'))

    def test_limits_and_types(self):
        for key, value in [('GlobalProxy', 1), ('DirectSites', 'x'), ('DirectSites', ['192.0.2.1']), ('DirectIp', ['domain:example.com']), ('DirectIp', ['256.0.0.1']), ('DnsHosts', {'x': 'not-ip'}), ('RemoteDNSDomain', 'http://dns.google/query'), ('RouteOrder', 'block-block-direct'), ('DomainStrategy', 'wrong')]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                p = policy.defaults()
                p[key] = value
                policy.validate_policy(p)
        p = policy.defaults()
        p['DirectSites'] = ['example.com'] * 500
        policy.validate_policy(p)
        p['ProxyIp'] = ['1.1.1.1']
        with self.assertRaises(ValueError):
            policy.validate_policy(p)
        for packages in [[], ['a.b'] * 2, ['a.b&evil=x'], [f'a.p{i}' for i in range(101)]]:
            with self.assertRaises(ValueError):
                policy.document(policy.defaults(), packages)

    def test_tuna_automatically_cleans_unsupported_without_changing_happ(self):
        for key, value in [('FakeDNS', True), ('UseChunkFiles', True), ('ExtraField', 'keep')]:
            p = policy.defaults()
            p[key] = value
            self.assertIn(key, policy.import_text(policy.happ_export(p))['policy'])
            doc = policy.document(p, ['com.android.chrome'])
            before = copy.deepcopy(doc)
            cleaned = policy.import_text(policy.tuna_link(doc))
            self.assertFalse(cleaned['policy']['FakeDNS'])
            self.assertFalse(cleaned['policy']['UseChunkFiles'])
            self.assertNotIn('ExtraField', cleaned['policy'])
            self.assertEqual(cleaned['apps'], doc['apps'])
            self.assertEqual(doc, before)
            self.assertEqual(policy.import_text(policy.happ_export(p))['policy'][key], value)

    def test_malformed_import(self):
        for value in ['{}', '{"Name":"a","Name":"b"}', '%%%bad', 'tuna://routing/v1?data=e30=', 'tuna://routing/v2?data=e30', json.dumps({'version': 2, 'policy': policy.defaults()}), json.dumps({'version': True, 'policy': policy.defaults()}), '{"Name":NaN}', 'x' * 350001]:
            with self.subTest(value=value[:50]), self.assertRaises(ValueError):
                policy.import_text(value)


if __name__ == '__main__':
    unittest.main()
