import copy
import unittest
import policy
from routing_sources import merge_documents


class DnsPoolsTest(unittest.TestCase):
    def test_custom_doh_without_ip_roundtrip(self):
        for endpoint in ('https://andemikus.ru/dns-query', 'https://dns.internal.example:8443/private/dns-query?client=test'):
            for bootstrap in (None, '', '192.0.2.53'):
                dns = self.dns()
                dns['remoteServers'] = [dict(transport='DOH', endpoint=endpoint, bootstrapIp=bootstrap)]
                doc = policy.document(policy.defaults(), dns=dns)
                decoded = policy.import_text(policy.tuna_link(doc))
                self.assertEqual(doc, decoded)
                self.assertEqual(endpoint, decoded['dns']['remoteServers'][0]['endpoint'])
                self.assertEqual(bootstrap, decoded['dns']['remoteServers'][0]['bootstrapIp'])

    def test_dot_still_requires_bootstrap_and_invalid_ip_rejected(self):
        for server in (dict(transport='DOT', endpoint='tls://dns.example'), dict(transport='DOH', endpoint='https://dns.example/query', bootstrapIp='not-an-ip')):
            dns = self.dns()
            dns['remoteServers'] = [server]
            with self.assertRaises(ValueError):
                policy.document(policy.defaults(), dns=dns)

    def dns(self):
        return dict(remoteServers=[dict(transport='UDP', endpoint=f'192.0.2.{i}') for i in range(1, 9)],
                    domesticServers=[dict(transport='TCP', endpoint=f'192.0.2.{i}') for i in range(11, 19)],
                    remoteSelectionMode='auto', domesticSelectionMode='priority', timeoutMs=3000)

    def test_maximum_pools_roundtrip_and_happ_refuses_loss(self):
        p = policy.defaults()
        p['TunaDNS'] = self.dns()
        doc = policy.document(p)
        self.assertNotIn('TunaDNS', doc['policy'])
        self.assertEqual(doc, policy.import_text(policy.tuna_link(doc)))
        self.assertEqual(8, len(doc['dns']['remoteServers']))
        self.assertTrue(all(s['route'] == 'DIRECT' for s in doc['dns']['domesticServers']))
        with self.assertRaises(ValueError):
            policy.happ_export(p)

    def test_invalid_pools_and_cross_route_rejected(self):
        for field, value in [('timeoutMs', 10001), ('timeoutMs', True), ('remoteSelectionMode', 'race'),
                             ('remoteServers', []), ('remoteServers', self.dns()['remoteServers'] + [dict(transport='UDP', endpoint='192.0.2.99')]),
                             ('remoteServers', [dict(transport='UDP', endpoint='192.0.2.1', route='DIRECT')]),
                             ('remoteServers', [dict(transport='UDP', endpoint='192.0.2.1')] * 2)]:
            source = self.dns()
            source[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                policy.document(policy.defaults(), dns=source)

    def test_legacy_is_unchanged_and_unspecified_dns_survives_merge(self):
        legacy = policy.document(policy.defaults())
        self.assertNotIn('dns', legacy)
        self.assertEqual(legacy, policy.import_text(policy.tuna_link(legacy)))
        extended = policy.document(policy.defaults(), dns=self.dns())
        merged = merge_documents(extended, legacy)
        self.assertEqual(extended['dns'], merged['dns'])

    def test_partial_dns_retains_legacy_other_route(self):
        p = policy.defaults()
        doc = policy.document(p, dns=dict(remoteServers=[dict(transport='TCP', endpoint='192.0.2.53')]))
        self.assertEqual(p['DomesticDns'], doc['dns']['domesticServers'][0]['endpoint'])
        self.assertEqual('auto', doc['dns']['domesticSelectionMode'])


if __name__ == '__main__':
    unittest.main()
