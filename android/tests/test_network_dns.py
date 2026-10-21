"""Focused, offline tests for the Android downloader's DNS fallback."""
import importlib.util
import json
from pathlib import Path
import socket
import unittest
from unittest.mock import patch


class DnsFallbackTests(unittest.TestCase):
    def setUp(self):
        self.original = socket.getaddrinfo
        source = Path(__file__).parents[1] / 'app/src/main/assets/network/medialens_dns.py'
        spec = importlib.util.spec_from_file_location('medialens_dns_test', source)
        self.dns = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.dns)
        socket.getaddrinfo = self.original

    def tearDown(self):
        socket.getaddrinfo = self.original

    def response(self, answers, status=0):
        from unittest.mock import MagicMock
        response = MagicMock()
        response.status = 200
        response.read.return_value = json.dumps({'Status': status, 'Answer': answers}).encode()
        response.__enter__.return_value = response
        return response

    def failure(self, *args):
        raise socket.gaierror(7, 'No address associated with hostname')

    def test_working_system_dns_does_not_contact_resolver(self):
        with patch.object(self.dns, '_original_getaddrinfo', return_value=['normal']), patch.object(self.dns, '_addresses') as encrypted:
            self.assertEqual(self.dns._getaddrinfo('edge.ahcdn.com', 443), ['normal'])
            encrypted.assert_not_called()

    def test_source_sites_and_lookalikes_keep_original_dns_failure(self):
        for host in ['xhamster.com', 'edge.ahcdn.com.attacker.test', 'localhost', 'ahcdn.com', b'edge.ahcdn.com']:
            with patch.object(self.dns, '_original_getaddrinfo', side_effect=self.failure), patch.object(self.dns, '_addresses') as encrypted:
                with self.assertRaises(socket.gaierror): self.dns._getaddrinfo(host, 443)
                encrypted.assert_not_called()

    def test_only_public_ipv4_answers_are_accepted(self):
        response = self.response([{'type': 1, 'data': ip, 'TTL': 60} for ip in ['127.0.0.1', '10.1.2.3', '0.0.0.0', '224.1.1.1', '8.8.8.8']])
        with patch.object(self.dns.urllib.request, 'urlopen', return_value=response) as request:
            self.assertEqual(self.dns._addresses('edge.ahcdn.com'), ['8.8.8.8'])
            sent = request.call_args.args[0]
            self.assertEqual(sent.full_url, 'https://dns.google/resolve?name=edge.ahcdn.com&type=A')
            self.assertFalse(sent.has_header('Cookie'))

    def test_fallback_preserves_port_and_socket_options(self):
        def lookup(host, port, family, kind, proto, flags):
            if host == 'edge.ahcdn.com': return self.failure()
            self.assertEqual((host, port, family, kind, proto), ('8.8.8.8', 443, socket.AF_INET, socket.SOCK_STREAM, 6))
            self.assertTrue(flags & socket.AI_NUMERICHOST)
            return [('resolved', host, port)]
        with patch.object(self.dns, '_original_getaddrinfo', side_effect=lookup), patch.object(self.dns, '_addresses', return_value=['8.8.8.8']):
            self.assertEqual(self.dns._getaddrinfo('edge.ahcdn.com', 443, socket.AF_INET, socket.SOCK_STREAM, 6), [('resolved', '8.8.8.8', 443)])

    def test_encrypted_failure_preserves_original_exception(self):
        with patch.object(self.dns, '_original_getaddrinfo', side_effect=self.failure), patch.object(self.dns, '_addresses', side_effect=TimeoutError):
            with self.assertRaisesRegex(socket.gaierror, 'No address associated'):
                self.dns._getaddrinfo('edge.xhcdn.com', 443)

    def test_dns_answer_is_cached_only_until_ttl(self):
        response = self.response([{'type': 1, 'data': '8.8.8.8', 'TTL': 2}])
        with patch.object(self.dns.urllib.request, 'urlopen', return_value=response) as request, patch.object(self.dns.time, 'monotonic', side_effect=[10, 11, 13]):
            for _ in range(3): self.assertEqual(self.dns._addresses('edge.ahcdn.com'), ['8.8.8.8'])
            self.assertEqual(request.call_count, 2)

    def test_cached_fallback_skips_the_failing_system_lookup(self):
        self.dns._cache['edge.ahcdn.com'] = (999999999999, ['8.8.8.8'])
        def lookup(host, *args):
            self.assertEqual(host, '8.8.8.8', 'Repeated the failing hostname lookup')
            return [('cached', host)]
        with patch.object(self.dns, '_original_getaddrinfo', side_effect=lookup), patch.object(self.dns, '_addresses') as encrypted:
            self.assertEqual(self.dns._getaddrinfo('edge.ahcdn.com', 443), [('cached', '8.8.8.8')])
            encrypted.assert_not_called()

    def test_nxdomain_does_not_provide_an_address(self):
        with patch.object(self.dns.urllib.request, 'urlopen', return_value=self.response([], status=3)):
            self.assertEqual(self.dns._addresses('edge.ahcdn.com'), [])


if __name__ == '__main__':
    unittest.main()
