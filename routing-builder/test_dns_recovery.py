import copy
import unittest

import policy


class DnsRecoveryTest(unittest.TestCase):
    def dns(self):
        return dict(
            remoteServers=[dict(transport='DOH', endpoint='https://dns.private.example:8443/query?client=fixture', bootstrapIp=None)],
            domesticServers=[dict(transport='UDP', endpoint='192.0.2.53')],
            remoteEmergency=dict(mode='system'), domesticEmergency=dict(mode='disabled'),
            bootstrap=dict(mode='system'))

    def test_system_and_disabled_roundtrip_preserve_private_doh_without_ip(self):
        source = self.dns()
        before = copy.deepcopy(source)
        doc = policy.document(policy.defaults(), dns=source)
        self.assertEqual(doc, policy.import_text(policy.tuna_link(doc)))
        self.assertEqual(before, source)
        self.assertEqual({'mode': 'system'}, doc['dns']['remoteEmergency'])
        self.assertEqual({'mode': 'disabled'}, doc['dns']['domesticEmergency'])
        self.assertEqual({'mode': 'system'}, doc['dns']['bootstrap'])
        self.assertIsNone(doc['dns']['remoteServers'][0]['bootstrapIp'])

    def test_custom_recovery_and_bootstrap_resolvers_keep_route_and_fields(self):
        dns = self.dns()
        dns['remoteEmergency'] = dict(mode='resolver', resolver=dict(
            transport='DOH', endpoint='https://reserve.private.example/dns-query', bootstrapIp=None, route='VPN'))
        dns['domesticEmergency'] = dict(mode='resolver', resolver=dict(
            transport='UDP', endpoint='198.51.100.53:5353', route='DIRECT'))
        dns['bootstrap'] = dict(mode='resolver', resolver=dict(
            transport='TCP', endpoint='[2001:db8::53]:5353', route='DIRECT', bootstrapIp=None))
        doc = policy.document(policy.defaults(), dns=dns)
        self.assertEqual(doc, policy.import_text(policy.tuna_link(doc)))
        for field in ('remoteEmergency', 'domesticEmergency', 'bootstrap'):
            self.assertEqual(dns[field], doc['dns'][field])

    def test_eight_members_and_separate_reserve_do_not_use_a_ninth_slot(self):
        dns = self.dns()
        dns['remoteServers'] = [dict(transport='TCP', endpoint=f'192.0.2.{i}') for i in range(1, 9)]
        dns['remoteEmergency'] = dict(mode='resolver', resolver=dict(transport='TCP', endpoint='192.0.2.9'))
        doc = policy.document(policy.defaults(), dns=dns)
        self.assertEqual(8, len(doc['dns']['remoteServers']))
        self.assertEqual('VPN', doc['dns']['remoteEmergency']['resolver']['route'])

    def test_missing_emergency_defaults_to_system_but_missing_bootstrap_stays_inherit(self):
        dns = self.dns()
        for field in ('remoteEmergency', 'domesticEmergency', 'bootstrap'):
            dns.pop(field)
        doc = policy.document(policy.defaults(), dns=dns)
        self.assertEqual({'mode': 'system'}, doc['dns']['remoteEmergency'])
        self.assertEqual({'mode': 'system'}, doc['dns']['domesticEmergency'])
        self.assertNotIn('bootstrap', doc['dns'])

    def test_legacy_policy_without_dns_is_not_rewritten_by_parser(self):
        doc = policy.document(policy.defaults())
        self.assertNotIn('dns', policy.import_text(policy.tuna_link(doc)))

    def test_invalid_modes_unknown_fields_and_resolver_shapes_fail(self):
        invalid = [None, 'system', {}, {'mode': 'inherit'}, {'mode': 'SYSTEM'},
                   {'mode': 'system', 'resolver': {}}, {'mode': 'disabled', 'resolver': None},
                   {'mode': 'system', 'unexpected': True}, {'mode': 'resolver'},
                   {'mode': 'resolver', 'resolver': []},
                   {'mode': 'resolver', 'resolver': {'transport': 'TCP', 'endpoint': '192.0.2.53', 'unknown': 1}}]
        for field in ('remoteEmergency', 'domesticEmergency', 'bootstrap'):
            for value in invalid:
                dns = self.dns()
                dns[field] = value
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    policy.document(policy.defaults(), dns=dns)

    def test_cross_route_reserves_and_bootstrap_are_rejected(self):
        for field, route in [('remoteEmergency', 'DIRECT'), ('domesticEmergency', 'VPN'), ('bootstrap', 'VPN')]:
            dns = self.dns()
            dns[field] = dict(mode='resolver', resolver=dict(transport='TCP', endpoint='192.0.2.53', route=route))
            with self.subTest(field=field), self.assertRaises(ValueError):
                policy.document(policy.defaults(), dns=dns)

    def test_custom_bootstrap_requires_tcp_literal_ip_without_recursion(self):
        for transport, endpoint in [('UDP', '192.0.2.53'), ('DOH', 'https://dns.example/query'),
                                    ('TCP', 'bootstrap.example:53'), ('TCP', 'localhost'),
                                    ('TCP', '192.0.2.53/path'), ('TCP', '192.0.2.53:0')]:
            dns = self.dns()
            dns['bootstrap'] = dict(mode='resolver', resolver=dict(transport=transport, endpoint=endpoint))
            with self.subTest(transport=transport, endpoint=endpoint), self.assertRaises(ValueError):
                policy.document(policy.defaults(), dns=dns)
        dns = self.dns()
        dns['bootstrap'] = {'mode': 'disabled'}
        with self.assertRaises(ValueError):
            policy.document(policy.defaults(), dns=dns)

    def test_recovery_rejects_dot_and_bootstrap_rejects_its_own_pin(self):
        for field in ('remoteEmergency', 'domesticEmergency'):
            dns = self.dns()
            dns[field] = dict(mode='resolver', resolver=dict(
                transport='DOT', endpoint='tls://192.0.2.53:853'))
            with self.subTest(field=field), self.assertRaises(ValueError):
                policy.document(policy.defaults(), dns=dns)
        dns = self.dns()
        dns['bootstrap'] = dict(mode='resolver', resolver=dict(
            transport='TCP', endpoint='192.0.2.53', bootstrapIp='192.0.2.54'))
        with self.assertRaises(ValueError):
            policy.document(policy.defaults(), dns=dns)

    def test_reserve_doh_pin_conflict_with_hosts_is_rejected(self):
        dns = self.dns()
        dns['hosts'] = {'reserve.example': '192.0.2.1'}
        dns['remoteEmergency'] = dict(mode='resolver', resolver=dict(
            transport='DOH', endpoint='https://reserve.example/query', bootstrapIp='192.0.2.2'))
        with self.assertRaises(ValueError):
            policy.document(policy.defaults(), dns=dns)

    def test_happ_does_not_silently_drop_recovery_settings(self):
        p = policy.defaults()
        p['TunaDNS'] = self.dns()
        with self.assertRaisesRegex(ValueError, 'Happ'):
            policy.happ_export(p)


if __name__ == '__main__':
    unittest.main()
