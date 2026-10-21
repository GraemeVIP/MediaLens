"""Retry failed xHamster CDN DNS queries over HTTPS; keep sockets on Android's network.

Only DNS names are sent to the resolver. The original URL, HTTP headers, TLS
hostname and certificate validation are untouched. This module is loaded by
our private yt-dlp plugin directory, not installed into the Python runtime.
"""
import ipaddress
import json
import re
import socket
import threading
import time
import urllib.parse
import urllib.request

_original_getaddrinfo = socket.getaddrinfo
_cache = {}
_lock = threading.Lock()
_resolver = 'https://dns.google/resolve'


def _cdn(host):
    if not isinstance(host, str):
        return False
    host = host.lower().rstrip('.')
    return (len(host) <= 253 and re.fullmatch(r'[a-z0-9.-]+', host)
            and any(host.endswith('.' + domain) for domain in ('ahcdn.com', 'xhcdn.com')))


def _addresses(host):
    now = time.monotonic()
    with _lock:
        cached = _cache.get(host)
        if cached and cached[0] > now:
            return cached[1]
    request = urllib.request.Request(
        _resolver + '?' + urllib.parse.urlencode({'name': host, 'type': 'A'}),
        headers={'Accept': 'application/dns-json', 'User-Agent': 'MediaLens/0.1'})
    with urllib.request.urlopen(request, timeout=8) as response:
        if response.status != 200:
            return []
        body = response.read(65537)
    if len(body) > 65536:
        return []
    result = json.loads(body)
    if result.get('Status') != 0:
        return []
    addresses = []
    ttl = 300
    for answer in result.get('Answer', []):
        if answer.get('type') != 1:
            continue
        try:
            address = ipaddress.ip_address(answer.get('data', ''))
            if address.version == 4 and address.is_global and not address.is_multicast:
                addresses.append(str(address))
                ttl = min(ttl, max(0, int(answer.get('TTL', 0))))
        except (ValueError, TypeError):
            continue
    if addresses:
        with _lock:
            _cache[host] = (now + ttl, addresses)
    return addresses


def _numeric(addresses, port, family, type, proto, flags):
    result = []
    for address in addresses:
        result.extend(_original_getaddrinfo(
            address, port, family, type, proto, flags | socket.AI_NUMERICHOST))
    return result


def _getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
    eligible = _cdn(host) and family in (socket.AF_UNSPEC, socket.AF_INET)
    if eligible:
        with _lock:
            cached = _cache.get(host)
            addresses = cached[1] if cached and cached[0] > time.monotonic() else None
        if addresses:
            return _numeric(addresses, port, family, type, proto, flags)
    try:
        return _original_getaddrinfo(host, port, family, type, proto, flags)
    except socket.gaierror:
        if not eligible:
            raise
        try:
            addresses = _addresses(host)
            result = _numeric(addresses, port, family, type, proto, flags)
            if result:
                return result
        except Exception:
            # Preserve the original DNS error if the encrypted resolver also fails.
            pass
        raise


if not getattr(socket.getaddrinfo, '_media_lens_dns', False):
    _getaddrinfo._media_lens_dns = True
    socket.getaddrinfo = _getaddrinfo
