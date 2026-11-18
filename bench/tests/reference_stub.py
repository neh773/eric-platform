"""A small stand-in for the reference product's web sign-in and documented API.

It is written from the public documentation only (resource routes, the token
header, the session route with its CSRF token, the served OpenAPI document), so
that ``reference/refauto.py`` can be dry-run without starting the trial clock.
Its routes and bodies are guesses where the documentation is silent; the dry run
proves our plumbing (sign-in, secrets, evidence, the trial GET-only rule), not
the product's behaviour.
"""

from __future__ import annotations

import json
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from pathlib import Path

CONFIG = json.loads((Path(__file__).resolve().parents[1] / "reference" / "config.json").read_text(encoding="utf-8"))
API = "/data/api/v1"
HEADER = CONFIG["tokenHeader"].lower()
CORE = CONFIG["coreModule"]
OPCUA = CONFIG["opcuaModule"]


class Stub:
    def __init__(self, flavour: str = "idp", password: str = "admin-pw") -> None:
        self.flavour = flavour
        self.passwords = {"admin": password}
        self.sessions: dict[str, str] = {}
        self.pending: dict[str, str] = {}
        self.keys: dict[str, dict] = {}  # key -> {"name", "write", "enabled"}
        self.hashes: dict[str, str] = {}
        self.resources: dict[tuple, dict] = {
            (CORE, "security-levels", "security-levels"): {"name": "security-levels", "signature": "s1",
                "config": {"securityLevels": [{"name": "Authenticated", "children": [{"name": "Roles", "children": [
                    {"name": "Administrator", "children": []}]}]}]}},
            (CORE, "security-properties", "security-properties"): {"name": "security-properties", "signature": "s2",
                "config": {"writePermissions": {"type": "AnyOf", "securityLevels": []},
                           "readPermissions": {"type": "AnyOf", "securityLevels": []},
                           "userInactivityTimeout": 10}},
            (OPCUA, "server-config", "server-config"): {"name": "server-config", "signature": "s3",
                "config": {"endpoint": {"bindAddresses": ["localhost"], "port": 62541},
                           "securityPolicies": ["Basic256Sha256"], "messageSecurityModes": ["SignAndEncrypt"],
                           "authentication": {"anonymousAccessAllowed": False},
                           "advanced": {"exposedTagsEnabled": False}}},
        }
        self.scim: dict[str, dict] = {"Users": {}, "Groups": {}}
        self.trial_writes = 0
        self.requests: list[tuple[str, str]] = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), self._handler())
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self) -> "Stub":
        self.thread.start()
        return self

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def _handler(self):
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                return

            def _send(self, status, body=None, ctype="application/json", headers=None):
                data = body if isinstance(body, bytes) else json.dumps(body if body is not None else {}).encode()
                if ctype.startswith("text"):
                    data = body.encode() if isinstance(body, str) else data
                self.send_response(status)
                self.send_header("content-type", ctype)
                self.send_header("content-length", str(len(data)))
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(data)

            def _cookie(self):
                for part in (self.headers.get("cookie") or "").split(";"):
                    if part.strip().startswith("JSESSIONID="):
                        return part.strip().split("=", 1)[1]
                return None

            def _who(self):
                token = self.headers.get(HEADER)
                if token:
                    # The header carries "<key name>:<key>"; the bare key is refused, as the product refuses it.
                    name, _, secret = token.partition(":")
                    key = stub.keys.get(secret)
                    if not key or not key["enabled"] or key["name"] != name:
                        return None, False
                    return key["name"], key["write"]
                sid = self._cookie()
                if sid and sid in stub.sessions:
                    return stub.sessions[sid], stub.sessions[sid] == "admin"
                return None, False

            def _body(self):
                n = int(self.headers.get("content-length") or 0)
                raw = self.rfile.read(n) if n else b""
                if (self.headers.get("content-type") or "").startswith("application/x-www-form-urlencoded"):
                    return dict(urllib.parse.parse_qsl(raw.decode()))
                try:
                    return json.loads(raw.decode() or "null")
                except ValueError:
                    return raw

            def do_GET(self):
                self._route("GET")

            def do_POST(self):
                self._route("POST")

            def do_PUT(self):
                self._route("PUT")

            def do_DELETE(self):
                self._route("DELETE")

            def _route(self, method):
                url = urllib.parse.urlparse(self.path)
                path = url.path
                stub.requests.append((method, path))
                body = self._body() if method in ("POST", "PUT") else None
                if "trial" in path and method != "GET":
                    stub.trial_writes += 1
                    return self._send(405, {"error": "no"})
                if path == "/StatusPing":
                    return self._send(200, {"state": "RUNNING"})
                if path == "/data/app/login":
                    target = "/idp/default/authn/login?app_id=gw&token=t1" if stub.flavour == "idp" else "/form-login"
                    return self._send(302, {}, headers={"location": target})
                if path == "/form-login" and method == "GET":
                    return self._send(200, '<html><head><title>Sign in</title></head><body><form method="post" '
                                      'action="/form-login"><input type="hidden" name="flow" value="x"><input name="user">'
                                      '<input type="password" name="pass"></form></body></html>', "text/html")
                if path == "/form-login" and method == "POST":
                    return self._login(body.get("user"), body.get("pass"))
                if path == "/idp/default/authn/login":
                    return self._send(200, "<html><head><title>Sign in</title></head><body><div id=root></div></body></html>",
                                      "text/html")
                if path == "/idp/default/authn/submit-username":
                    sid = "pending-" + str(len(stub.pending))
                    stub.pending[sid] = body.get("username")
                    return self._send(200, {"success": True}, headers={"set-cookie": f"IDP={sid}; Path=/"})
                if path == "/idp/default/authn/submit-password":
                    idp = None
                    for part in (self.headers.get("cookie") or "").split(";"):
                        if part.strip().startswith("IDP="):
                            idp = part.strip().split("=", 1)[1]
                    return self._login(stub.pending.get(idp), body.get("password"))
                if path == "/data/app/session":
                    sid = self._cookie()
                    if sid in stub.sessions:
                        return self._send(200, {"csrfToken": "csrf-" + sid,
                                                "userPayload": {"username": stub.sessions[sid]}})
                    return self._send(200, {"authenticated": False})
                who, write = self._who()
                if who is None:
                    return self._send(401, {"error": "unauthorized"})
                if self._cookie() and method != "GET" and not self.headers.get(HEADER):
                    if self.headers.get("x-csrf-token") != "csrf-" + self._cookie():
                        return self._send(403, {"error": "csrf"})
                if path == "/openapi.json":
                    return self._send(200, {"info": {"version": "stub"}, "paths": {
                        API + "/trial": {"get": {"summary": "Trial state"}},
                        API + "/api-token/generate": {"post": {"summary": "Generate API Token"}},
                        API + "/backup": {"get": {"summary": "Download a gateway backup",
                                                  "parameters": [{"name": "includePeripheralConfig", "in": "query"}]}},
                        API + "/resources/list/{moduleId}/{typeId}": {"get": {}},
                        API + "/opcua/server/endpoints": {"get": {"summary": "server endpoints"}},
                        API + f"/resources/{CORE}/security-properties": {"put": {"requestBody": {"content": {
                            "application/json": {"schema": {"type": "array", "items": {
                                "$ref": "#/components/schemas/SecurityProperties"}}}}}}},
                    }, "components": {"schemas": {
                        "SecurityProperties": {"properties": {"config": {"$ref": "#/components/schemas/SecurityConfig"}}},
                        "SecurityConfig": {"properties": {
                            "gatewayAuditProfile": {"type": "string", "description": "audit profile for the gateway"},
                            "userInactivityTimeout": {"type": "number", "default": 10, "description": "minutes"}}},
                    }}})
                if path == API + "/trial":
                    return self._send(200, {"licenseMode": "Trial", "trialSecondsLeft": 7000})
                if path == API + "/api-token/generate" and method == "POST":
                    n = len(stub.hashes)
                    key, hash_ = f"stub-key-{n}-abcdef", f"stub-hash-{n}"
                    stub.hashes[hash_] = key
                    return self._send(200, {"key": key, "hash": hash_})
                if path == API + "/tags/import":
                    return self._send(200 if write else 403, {"failureCount": 0} if write else {"error": "forbidden"})
                if path == API + "/encryption/encrypt" and method == "POST":
                    return self._send(200, "jwe.stub.value", "text/plain")
                if path.startswith(API + "/scim/default/v2/"):
                    return self._scim(method, path[len(API + "/scim/default/v2/"):].split("/"), body, write)
                if path == API + "/backup":
                    return self._send(200 if write else 403, b"PK\x03\x04stub", "application/octet-stream")
                if path.startswith(API + "/resources/"):
                    return self._resources(method, path[len(API + "/resources/"):].split("/"), body, write)
                return self._send(404, {"error": "not found"})

            def _login(self, user, password):
                if user in stub.passwords and stub.passwords[user] == password:
                    sid = f"sid{len(stub.sessions)}"
                    stub.sessions[sid] = user
                    return self._send(302, {}, headers={"location": "/app/home", "set-cookie": f"JSESSIONID={sid}; Path=/"})
                return self._send(401, {"error": "bad credentials"})

            def _scim(self, method, parts, body, write):
                kind = parts[0]
                if kind not in stub.scim:
                    return self._send(404, {"error": "not found"})
                store = stub.scim[kind]
                if method == "GET" and len(parts) == 1:
                    return self._send(200, {"Resources": list(store.values()), "totalResults": len(store)})
                if method == "GET":
                    return self._send(200, store[parts[1]]) if parts[1] in store else self._send(404, {})
                if not write:
                    return self._send(403, {"error": "forbidden"})
                if method == "POST":
                    if kind == "Users" and len(body.get("password") or "") < 8:
                        return self._send(400, {"detail": "password policy"})
                    sid = f"{kind[0].lower()}{len(store)}"
                    item = {k: v for k, v in body.items() if k != "password"}
                    store[sid] = {**item, "id": sid, "meta": {}}
                    if kind == "Users":
                        stub.passwords[body["userName"]] = body.get("password")
                    return self._send(201, store[sid])
                if method == "PUT" and parts[1] in store:
                    store[parts[1]] = {**body, "id": parts[1]}
                    return self._send(200, store[parts[1]])
                if method == "DELETE":
                    store.pop(parts[1], None)
                    return self._send(204, b"")
                return self._send(404, {})

            def _resources(self, method, parts, body, write):
                if parts[0] == "singleton" and method == "GET":
                    items = [v for (m, k, n), v in stub.resources.items() if m == parts[1] and k == parts[2]]
                    return self._send(200, items[0]) if items else self._send(404, {"error": "missing"})
                if parts[0] == "names" and method == "GET":
                    return self._send(200, [n for (m, k, n) in stub.resources if m == parts[1] and k == parts[2]])
                if parts[0] in ("find", "list", "type") and method == "GET":
                    module, kind = parts[1], parts[2]
                    name = parts[3] if len(parts) > 3 else kind
                    if parts[0] == "type":
                        return self._send(200, {"type": kind, "schema": {"properties": {"enabled": {"type": "boolean"}}}})
                    items = [v for (m, k, n), v in stub.resources.items() if m == module and k == kind and
                             (parts[0] == "list" or n == name)]
                    if parts[0] == "list":
                        return self._send(200, {"items": items})
                    return self._send(200, items[0]) if items else self._send(404, {"error": "missing"})
                if not write:
                    return self._send(403, {"error": "forbidden"})
                module, kind = parts[0], parts[1]
                if method == "DELETE":
                    stub.resources.pop((module, kind, parts[2]), None)
                    return self._send(200, {"success": True})
                for item in body:
                    if kind == "api-token":
                        hash_ = item["config"]["settings"]["tokenHash"]
                        levels = json.dumps(item["config"]["profile"]["securityLevels"])
                        stub.keys[stub.hashes[hash_]] = {"name": item["name"], "write": "BenchWrite" in levels,
                                                         "enabled": item.get("enabled", True)}
                    stub.resources[(module, kind, item["name"])] = {**item, "signature": "sig-" + item["name"]}
                return self._send(200, {"success": True, "changes": [{"name": item["name"], "newSignature": "sig"}]})

        return Handler
