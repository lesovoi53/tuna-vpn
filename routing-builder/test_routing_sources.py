import io
import unittest
from unittest.mock import patch
import policy
from routing_sources import import_source, merge_documents
from completion import suggestions


class Tests(unittest.TestCase):
    def test_happ_import_and_merge(self):
        old = policy.defaults()
        old['DirectSites'] = ['domain:keep.example']
        new = policy.defaults()
        new['DirectSites'] = ['domain:new.example', 'domain:keep.example']
        result = merge_documents(policy.document(old, ['com.android.chrome']), import_source(policy.happ_export(new)))
        self.assertEqual(result['policy']['DirectSites'], ['domain:keep.example', 'domain:new.example'])
        self.assertEqual(result['apps']['packages'], ['com.android.chrome'])

    def test_http_file(self):
        response = io.BytesIO(policy.happ_export(policy.defaults(), 'json').encode())
        with patch('routing_sources.urllib.request.build_opener') as opener:
            opener.return_value.open.return_value = response
            self.assertEqual(import_source('https://example.org/rules')['policy'], policy.defaults())

    def test_http_size_and_html(self):
        for content in (b'x'*350001, b'<html>not routing</html>'):
            with patch('routing_sources.urllib.request.build_opener') as opener:
                opener.return_value.open.return_value = io.BytesIO(content)
                with self.assertRaises(ValueError):
                    import_source('https://example.org/rules')

    def test_completion_uses_actual_categories(self):
        self.assertEqual(suggestions('geo', False, []), ['geosite:'])
        self.assertEqual(suggestions('geo', True, []), ['geoip:'])
        self.assertEqual(suggestions('geosite:yo', False, ['geosite:youtube', 'geosite:google']), ['geosite:youtube'])
        self.assertEqual(suggestions('geosite:yo', False, []), [])
        self.assertNotIn('processPath:', suggestions('', False, []))
