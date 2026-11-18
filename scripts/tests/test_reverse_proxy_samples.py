"""Structure checks for the reverse-proxy sample configs (HMP-088).

The proxies themselves are not run here; these tests keep the directives docs/ops/reverse-proxy.md relies on in
place (TLS, websocket upgrade, forwarded headers that replace client values, long idle timeouts) and keep vendor
product addresses out of the guide.
"""
import re
import unittest
from pathlib import Path

OPS = Path(__file__).resolve().parents[2] / "docs" / "ops"
NGINX = (OPS / "reverse-proxy" / "nginx.conf").read_text()
HAPROXY = (OPS / "reverse-proxy" / "haproxy.cfg").read_text()
GUIDE = (OPS / "reverse-proxy.md").read_text()


def active(text: str) -> str:
    return "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))


class NginxSample(unittest.TestCase):
    cfg = active(NGINX)

    def test_terminates_tls(self):
        self.assertRegex(self.cfg, r"listen 443 ssl;")
        self.assertIn("ssl_certificate ", self.cfg)
        self.assertIn("ssl_certificate_key ", self.cfg)
        self.assertRegex(self.cfg, r"return 308 https://")

    def test_upgrades_websockets(self):
        self.assertIn("proxy_http_version 1.1;", self.cfg)
        self.assertRegex(self.cfg, r"proxy_set_header Upgrade\s+\$http_upgrade;")
        self.assertRegex(self.cfg, r"proxy_set_header Connection\s+\$connection_upgrade;")

    def test_sets_forwarded_headers_by_replacement(self):
        for name in ("X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-For"):
            self.assertRegex(self.cfg, rf"proxy_set_header {name}\s")
        self.assertNotIn("$proxy_add_x_forwarded_for", self.cfg)

    def test_idle_timeouts_outlast_the_socket_heartbeat(self):
        for directive in ("proxy_read_timeout", "proxy_send_timeout"):
            seconds = int(re.search(rf"{directive} (\d+)s;", self.cfg).group(1))
            self.assertGreaterEqual(seconds, 600)
        self.assertIn("proxy_buffering    off;", self.cfg)


class HaproxySample(unittest.TestCase):
    cfg = active(HAPROXY)

    def test_terminates_tls(self):
        self.assertRegex(self.cfg, r"bind :443 ssl crt ")
        self.assertRegex(self.cfg, r"redirect scheme https code 308")

    def test_sets_forwarded_headers_by_replacement(self):
        for name in ("X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-For"):
            self.assertRegex(self.cfg, rf"http-request set-header {name}\s")
        self.assertNotIn("add-header", self.cfg)

    def test_long_lived_connections_are_not_cut(self):
        for directive in ("timeout client", "timeout server", "timeout tunnel"):
            self.assertRegex(self.cfg, rf"{directive}\s+1h")


class Guide(unittest.TestCase):
    def test_names_the_pieces(self):
        for needle in ("/api/v1/ws", "http.trustedProxies", "X-Forwarded-Prefix", "X-Forwarded-Host", "X-Forwarded-Proto"):
            self.assertIn(needle, GUIDE)

    def test_only_the_public_session_proxy_page_is_linked(self):
        urls = re.findall(r"https?://[a-z0-9.-]+\.[a-z]+[^\s)]*", GUIDE + NGINX + HAPROXY)
        extra = [u for u in urls if not re.match(r"https?://(hmi|plant)\.example\.test", u)]
        self.assertEqual(extra, [])


if __name__ == "__main__":
    unittest.main()
