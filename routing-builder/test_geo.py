import io
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch, MagicMock
import geo


def varint(number):
    result = bytearray()
    while number > 127:
        result.append((number & 127) | 128)
        number >>= 7
    return bytes(result) + bytes([number])


def field(number, value):
    return varint(number * 8 + 2) + varint(len(value)) + value


def fixture():
    return b''.join(field(1, field(1, name) + field(2, b'payload')) for name in (b'RU', b'PRIVATE', b'ru'))


class Response(io.BytesIO):
    url = 'https://example.com/geo.dat'
    def __init__(self, data, declared=None):
        super().__init__(data)
        self.headers = {} if declared is None else {'Content-Length': declared}


class GeoTests(unittest.TestCase):
    def test_actual_categories(self):
        self.assertEqual(geo.parse_index(fixture(), 'geoip'), ['geoip:private', 'geoip:ru'])
        self.assertEqual(geo.parse_index(fixture(), 'geosite:'), ['geosite:private', 'geosite:ru'])

    def test_corruption(self):
        for data in (b'', b'<html>error</html>', b'\x00', b'\x0a\xff', b'\xff' * 12, field(1, b''), field(1, field(1, b'bad space')), field(1, field(1, b'A') + field(1, b'B'))):
            with self.subTest(data=data), self.assertRaises(ValueError):
                geo.parse_index(data, 'geoip')

    def test_download_cache_and_failed_refresh(self):
        opener = MagicMock()
        opener.open.return_value = Response(fixture())
        with tempfile.TemporaryDirectory() as folder, patch('geo.urllib.request.build_opener', return_value=opener):
            expected = ['geoip:private', 'geoip:ru']
            self.assertEqual(geo.download_index(Response.url, 'geoip', folder), expected)
            cached = next(Path(folder).glob('*.dat'))
            self.assertEqual(cached.read_bytes(), fixture())
            self.assertEqual(geo.download_index(Response.url, 'geoip', folder), expected)
            self.assertEqual(opener.open.call_count, 1)
            opener.open.return_value = Response(b'bad')
            with self.assertRaises(ValueError):
                geo.download_index(Response.url, 'geoip', folder, force=True)
            self.assertEqual(cached.read_bytes(), fixture())
            self.assertFalse(list(Path(folder).glob('*.tmp')))

    def test_limits_cancel_and_https(self):
        with tempfile.TemporaryDirectory() as folder:
            for response in (Response(fixture(), '100'), Response(fixture())):
                opener = MagicMock()
                opener.open.return_value = response
                with patch('geo.MAX_BYTES', 10), patch('geo.urllib.request.build_opener', return_value=opener), self.assertRaises(ValueError):
                    geo.download_index(Response.url, 'geosite', folder)
                self.assertFalse(list(Path(folder).glob('*')))
            cancel = threading.Event()
            cancel.set()
            with self.assertRaisesRegex(ValueError, 'отменена'):
                geo.download_index(Response.url, 'geoip', folder, cancel)
            for url in ('http://example.com/data', 'https://user:password@example.com/data'):
                with self.assertRaises(ValueError):
                    geo.download_index(url, 'geoip', folder)

    def test_redirect_downgrade(self):
        request = geo.urllib.request.Request(Response.url)
        with self.assertRaises(ValueError):
            geo._HttpsRedirects().redirect_request(request, None, 302, '', {}, 'http://example.com/file')


if __name__ == '__main__':
    unittest.main()
