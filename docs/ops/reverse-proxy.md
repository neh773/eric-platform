# Reverse proxy for the HMI

Run the gateway behind a reverse proxy when TLS, the public host name or a shared port is handled in front of it. The proxy terminates TLS, passes plain HTTP to the gateway, upgrades the HMI session socket to a websocket, and tells the gateway how the browser reached it. Behaviour follows the public session-proxy page (-); the settings and sample files here are our own.

Sample files (`docs/ops/reverse-proxy/`):

| File | Proxy |
| --- | --- |
| `nginx.conf` | nginx style server block |
| `haproxy.cfg` | HAProxy style frontend and backend |

`scripts/tests/test_reverse_proxy_samples.py` checks that both samples keep every directive this guide depends on. The checker does not start either proxy, so run the proxy's own configuration test (`nginx -t`, `haproxy -c -f ...`) before you load a file.

## What the proxy must do

1. **Terminate TLS.** Listen on 443 with a certificate for the public name, redirect port 80 to HTTPS, and send plain HTTP to the gateway port (8090 by default) on a private address.
2. **Upgrade websockets.** The HMI session socket is `/api/v1/ws`. Pass `Upgrade` and `Connection` through, use HTTP/1.1 to the gateway, and keep the idle read timeout above the gateway's socket idle timeout (5 minutes), for example 3600 s. A proxy that closes idle sockets after 60 s makes every session reconnect.
3. **Set the forwarded headers, replacing any the client sent.** Never append to a client-supplied value:

   | Header | Value |
   | --- | --- |
   | `X-Forwarded-Proto` | `https` (the scheme the browser used) |
   | `X-Forwarded-Host` | the public host and port the browser typed |
   | `X-Forwarded-For` | the browser's address |
   | `X-Forwarded-Prefix` | the path the proxy publishes the gateway under, only when it is not `/` |

4. **Keep `Host` or send `X-Forwarded-Host`.** Either is fine. The session socket checks that the `Origin` of the page matches the public host, so one of the two must carry the public name.
5. **Do not buffer** the websocket or server-sent streams, and allow request bodies as large as the largest project import or upload you use.

## Telling the gateway to trust the proxy

The gateway believes the forwarded headers only when the TCP peer is listed in `http.trustedProxies` (IP or IPv4 CIDR, the same list that gates `X-Forwarded-For`). From any other peer they are ignored, so a client that reaches the gateway directly cannot choose the address in links, cookies or redirects.

```yaml
http:
  host: 0.0.0.0
  port: 8090
  trustedProxies: ["10.0.0.5"]   # the proxy's address as the gateway sees it
```

With a non-empty list, the session cookie is `Secure` even though the hop from the proxy is plain HTTP (`http.secureCookies` can force it either way). When the peer is trusted the gateway uses:

- `X-Forwarded-Proto` for the cookie `Secure` flag, the HTTPS-only checks (event streams that require HTTPS, web-dev resources) and the force-HTTPS redirect.
- `X-Forwarded-Host` for the callback addresses of identity-provider logins, the browser shortcut shortcut, the session-socket origin check and redirects it builds.
- `X-Forwarded-Prefix` for the callback addresses, the launcher shortcut and the `/hmi` launch redirect.

A value that is malformed (a host with a path, `@` or spaces; a prefix with `..`, `//`, a query or other unsafe characters) is dropped and the gateway falls back to what it saw on the socket. Only the first entry of a comma separated list is read. If an identity provider sets `redirectBaseUrl`, that address wins over all of this.

## Sub-path publishing

The simplest and best supported layout gives the gateway its own host name, or the root of a host. Publishing under a prefix (`https://plant.example.test/oip/`) needs the proxy to strip the prefix before passing the request on and to route the gateway's absolute paths (`/hmi`, `/studio`, `/admin`, `/assets`, `/api`) for that host as well, because the web bundles are built with absolute asset addresses (DIFFERENCES 881). The prefix header only shapes the addresses the gateway itself writes. Use a dedicated host name when you can.

## Checks

- `curl -sI https://<public name>/api/v1/health` returns 200 over TLS.
- Open a project at `/hmi/<project>/`. The browser's network list shows the session socket with status 101.
- Sign in and look at the `Set-Cookie` header: `Secure; HttpOnly; SameSite=Strict`.
- From a host that is not in `trustedProxies`, send `X-Forwarded-Proto: https` straight to the gateway port. The cookie must not gain `Secure` from it (the gateway tests `ForwardedHeadersTest` cover this).
