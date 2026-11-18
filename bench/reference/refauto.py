"""Automation of the trial window's operator steps for the reference product.

Black-box only: the product's web sign-in and the same documented HTTP API its
web UI uses (``/data/api/v1``, the OpenAPI document the gateway serves), driven
headless from Python. Nothing here reads, unpacks or copies product files, and
nothing touches a trial or licensing action: the trial route is read with GET
only and :func:`Ref.req` refuses any other method on it.

Every request lands in a per-step trace (method, path, status, time, field
names). Secrets come from the gitignored files the window tool names and are
redacted from anything recorded. A sign-in session (cookies and CSRF token) is
kept in a mode 0600 file under ``bench/out`` so ad-hoc calls can reuse it.

    python3 reference/refauto.py step W02            # one automated step
    python3 reference/refauto.py call GET /data/api/v1/trial --auth token
    python3 reference/refauto.py routes backup audit  # search the served OpenAPI paths
    python3 reference/refauto.py watch-db --seconds 90

Host and port come from ``config.json`` next to this file (``REFAUTO_BASE``
overrides the base URL, which the stub dry run uses).
"""

from __future__ import annotations

import argparse
import html.parser
import http.cookiejar
import io
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
BENCH = HERE.parent
REPO = BENCH.parent
CONFIG = json.loads((HERE / "config.json").read_text(encoding="utf-8"))
PLAN = json.loads((BENCH / "plans" / "window-1.json").read_text(encoding="utf-8"))
OUT = Path(os.environ.get("REFAUTO_OUT") or BENCH / "out" / "window-1" / "reference")
EVIDENCE = Path(os.environ.get("REFAUTO_EVIDENCE") or BENCH / "results" / "window-1" / "reference" / "evidence")
CORE = CONFIG.get("coreModule", "core")
OPCUA = CONFIG.get("opcuaModule", "opcua")
COLLECTION = CONFIG.get("coreCollection", "core")
API = "/data/api/v1"
SECRET_WORDS = ("password", "secret", "token", "key", "hash", "passphrase", "credential", "csrf", "cookie")
USERS = {"parity-ro": "secrets/parity-ro-password.txt", "parity-lock": "secrets/parity-lock-password.txt"}


class AutoError(Exception):
    pass


def base_url() -> str:
    return os.environ.get("REFAUTO_BASE") or f"http://127.0.0.1:{CONFIG['httpPort']}"


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


# ---------------------------------------------------------------------------
# Secrets


def secret_path(name: str) -> Path:
    rel = (PLAN.get("secrets") or {}).get(name) or USERS.get(name)
    if name == "admin":
        rel = CONFIG["passwordFile"]
    if not rel:
        raise AutoError(f"no secret file named {name!r}")
    return Path(os.environ.get("REFAUTO_SECRET_ROOT") or REPO) / rel


def read_secret(name: str) -> str:
    path = secret_path(name)
    if not path.exists():
        raise AutoError(f"secret file {path.name} is missing")
    text = path.read_text(encoding="utf-8").strip()
    if not text or "\n" in text:
        raise AutoError(f"{path.name} must hold one line")
    return text


def write_secret(name: str, value: str) -> None:
    """Write (or replace) a mode 0600 one-line secret file. The value is never printed."""
    path = secret_path(name)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(value.strip() + "\n")
    os.chmod(path, 0o600)


def ensure_user_password(name: str) -> str:
    path = secret_path(name)
    if not path.exists():
        import secrets as _s
        write_secret(name, "Pw-" + _s.token_hex(10) + "aZ9!")
    return read_secret(name)


# ---------------------------------------------------------------------------
# Recording


def shape(payload, depth: int = 0):
    """Field names and list lengths, never values."""
    if isinstance(payload, dict):
        out = {"keys": sorted(str(k) for k in payload)}
        if depth < 2:
            nested = {str(k): shape(v, depth + 1) for k, v in payload.items() if isinstance(v, (dict, list))}
            if nested:
                out["nested"] = nested
        return out
    if isinstance(payload, list):
        first = payload[0] if payload else None
        return {"length": len(payload), "item": shape(first, depth + 1) if isinstance(first, (dict, list)) else
                type(first).__name__ if first is not None else None}
    return type(payload).__name__


def secret_name(name: str) -> bool:
    """True when a field name reads as a secret (password, tokenHash, csrfToken, apiKey...)."""
    words = re.findall(r"[a-z]+", re.sub(r"([a-z])([A-Z])", r"\1 \2", name).lower())
    return any(w in SECRET_WORDS for w in words)


def safe_values(payload, depth: int = 0):
    """A copy with every value under a secret-looking name replaced, and long text cut."""
    if depth > 8:
        return "..."
    if isinstance(payload, dict):
        out = {}
        for key, value in payload.items():
            if isinstance(value, str) and secret_name(str(key)):
                out[key] = "[withheld]"
            else:
                out[key] = safe_values(value, depth + 1)
        return out
    if isinstance(payload, list):
        items = [safe_values(v, depth + 1) for v in payload[:50]]
        if len(payload) > 50:
            items.append(f"... {len(payload) - 50} more")
        return items
    if isinstance(payload, str) and len(payload) > 400:
        return payload[:400] + "..."
    return payload


def _rel(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(REPO))
    except ValueError:
        return str(path)


class Evidence:
    def __init__(self, step: str) -> None:
        self.step = step
        self.dir = EVIDENCE / step
        self.dir.mkdir(parents=True, exist_ok=True)
        self.trace = self.dir / "trace.jsonl"
        self.facts: dict[str, object] = {}
        self.checks: list[dict] = []
        self.notes: list[str] = []

    def record(self, row: dict) -> None:
        with self.trace.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(row, default=str) + "\n")

    def save(self, name: str, payload) -> str:
        path = self.dir / name
        path.write_text(json.dumps(safe_values(payload), indent=2, default=str) + "\n", encoding="utf-8")
        return _rel(path)

    def check(self, name: str, ok: bool | None, detail=None) -> None:
        self.checks.append({"check": name, "ok": ok, "detail": detail})

    def result(self, scrub) -> dict:
        oks = [c["ok"] for c in self.checks]
        if not oks:
            verdict = "no-evidence"
        elif all(o is True for o in oks):
            verdict = "collected"
        elif any(o is True for o in oks):
            verdict = "partial"
        else:
            verdict = "failed"
        out = {"step": self.step, "at": now_iso(), "verdict": verdict, "checks": self.checks,
               "facts": self.facts, "notes": self.notes,
               "evidenceDir": _rel(self.dir),
               "label": PLAN["label"]}
        text = scrub(json.dumps(safe_values(out), indent=2, default=str))
        (self.dir / "result.json").write_text(text + "\n", encoding="utf-8")
        return json.loads(text)


# ---------------------------------------------------------------------------
# HTTP client


class _Forms(html.parser.HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.forms: list[dict] = []
        self._form: dict | None = None
        self.title = ""
        self._in_title = False

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "form":
            self._form = {"action": a.get("action") or "", "method": (a.get("method") or "get").lower(), "inputs": []}
            self.forms.append(self._form)
        elif tag in ("input", "button") and self._form is not None:
            self._form["inputs"].append({"name": a.get("name"), "type": (a.get("type") or "text").lower(),
                                         "value": a.get("value") or ""})
        elif tag == "title":
            self._in_title = True

    def handle_endtag(self, tag):
        if tag == "form":
            self._form = None
        if tag == "title":
            self._in_title = False

    def handle_data(self, data):
        if self._in_title:
            self.title += data.strip()


class Ref:
    """Session or API-token client for the reference product. Never logs a secret."""

    def __init__(self, ev: Evidence | None = None, session_name: str = "admin") -> None:
        self.base = base_url().rstrip("/")
        self.ev = ev
        self.session_name = session_name
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf: str | None = None
        self.secrets: list[str] = []
        self.username: str | None = None

    # -- persistence of a sign-in (mode 0600, gitignored) --
    def _session_file(self) -> Path:
        return OUT / f".session-{self.session_name}.json"

    def save_session(self) -> None:
        OUT.mkdir(parents=True, exist_ok=True)
        cookies = [{"name": c.name, "value": c.value, "domain": c.domain, "path": c.path} for c in self.jar]
        path = self._session_file()
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump({"cookies": cookies, "csrf": self.csrf, "username": self.username}, handle)

    def load_session(self) -> bool:
        path = self._session_file()
        if not path.exists():
            return False
        data = json.loads(path.read_text(encoding="utf-8"))
        for c in data.get("cookies") or []:
            self.jar.set_cookie(http.cookiejar.Cookie(
                0, c["name"], c["value"], None, False, c["domain"], False, c["domain"].startswith("."),
                c["path"], True, False, None, False, None, None, {}))
            self.secrets.append(c["value"])
        self.csrf = data.get("csrf")
        if self.csrf:
            self.secrets.append(self.csrf)
        self.username = data.get("username")
        return True

    def scrub(self, text: str) -> str:
        for secret in sorted(set(s for s in self.secrets if s and len(s) >= 4), key=len, reverse=True):
            text = text.replace(secret, "[redacted]")
        return text

    def req(self, method: str, path: str, body=None, *, query: dict | None = None, auth: str = "session",
            content_type: str = "application/json", raw: bool = False, form: dict | None = None,
            note: str | None = None, timeout: float = 60.0):
        """Returns (status, payload, info). ``auth`` is session, none, token, token2 or bogus."""
        method = method.upper()
        if "trial" in path.lower() and method not in ("GET", "HEAD"):
            raise AutoError("a trial route may only be read with GET")
        url = path if path.startswith("http") else self.base + path
        if query:
            url += ("&" if "?" in url else "?") + urllib.parse.urlencode(query)
        headers = {"accept": "application/json, text/html;q=0.9, */*;q=0.5"}
        data = None
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers["content-type"] = "application/x-www-form-urlencoded"
        elif body is not None:
            data = body if isinstance(body, (bytes, bytearray)) else json.dumps(body).encode()
            headers["content-type"] = content_type
        if auth in ("token", "token2", "bogus"):
            token = "not-a-key-0000" if auth == "bogus" else read_secret(auth)
            self.secrets.append(token)
            headers[CONFIG.get("tokenHeader", "X-API-Token")] = token
            opener = urllib.request.build_opener()
        elif auth == "none":
            opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        else:
            opener = self.opener
            if self.csrf and method not in ("GET", "HEAD"):
                headers["X-CSRF-Token"] = self.csrf
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        started = time.monotonic()
        final_url = url
        try:
            with opener.open(request, timeout=timeout) as resp:
                status, content = resp.status, resp.read()
                ctype = resp.headers.get("content-type", "")
                final_url = resp.geturl()
        except urllib.error.HTTPError as exc:
            status, content = exc.code, exc.read()
            ctype = exc.headers.get("content-type", "") if exc.headers else ""
        except (urllib.error.URLError, OSError) as exc:
            status, content, ctype = 0, str(exc).encode(), "error"
        ms = round((time.monotonic() - started) * 1000.0, 1)
        payload: object
        if raw:
            payload = content
        elif "json" in ctype:
            try:
                payload = json.loads(content.decode() or "null")
            except (ValueError, UnicodeDecodeError):
                payload = {"raw": self.scrub(content.decode(errors="replace"))[:500]}
        else:
            payload = {"text": self.scrub(content.decode(errors="replace"))}
        info = {"status": status, "ms": ms, "contentType": ctype, "finalUrl": self._rel(final_url), "bytes": len(content)}
        if self.ev is not None:
            row = {"at": now_iso(), "method": method, "path": self._rel(url), "auth": auth, **info}
            if note:
                row["note"] = note
            if not raw and isinstance(payload, (dict, list)) and "text" not in payload:
                row["shape"] = shape(payload)
            self.ev.record(json.loads(self.scrub(json.dumps(row, default=str))))
        return status, payload, info

    def _rel(self, url: str) -> str:
        url = re.sub(r"([?&](?:token|code|state|nonce|session)=)[^&]+", r"\1...", url)
        return url[len(self.base):] if url.startswith(self.base) else re.sub(r"//[^/]+", "//host", url, count=1)

    # -- sign-in, black-box: follow the web UI's sign-in page --
    def signin(self, user: str, password: str, *, expect_ok: bool = True) -> dict:
        """Sign in through the web UI's own sign-in page, rendered headless (ui_signin.js).

        Falls back to plain HTML forms when no headless DOM is installed (or REFAUTO_SIGNIN=form)."""
        if os.environ.get("REFAUTO_SIGNIN") != "form" and ui_modules():
            seen = ui_signin(self, user, password)
            if seen.get("signedIn") or not expect_ok:
                return seen
            raise AutoError("sign-in did not produce a session: " + self.scrub(json.dumps(seen, default=str))[:1500])
        return self.form_signin(user, password, expect_ok=expect_ok)

    def form_signin(self, user: str, password: str, *, expect_ok: bool = True) -> dict:
        """Sign in through an HTML login form (or a JSON identity provider). Returns what was observed."""
        self.secrets.append(password)
        seen: dict[str, object] = {"user": user, "attempts": []}
        status, page, info = self.req("GET", "/data/app/login", note="sign-in page")
        seen["loginPage"] = {"status": status, "finalUrl": info["finalUrl"], "contentType": info["contentType"]}
        text = page.get("text", "") if isinstance(page, dict) else ""
        parser = _Forms()
        try:
            parser.feed(text)
        except Exception:  # noqa: BLE001 - recording only
            pass
        seen["title"] = parser.title
        seen["forms"] = [{"action": f["action"], "method": f["method"],
                          "inputs": [(i["name"], i["type"]) for i in f["inputs"]]} for f in parser.forms]
        final = urllib.parse.urlparse(self.base + info["finalUrl"] if info["finalUrl"].startswith("/") else info["finalUrl"])
        query = dict(urllib.parse.parse_qsl(final.query))
        attempts = seen["attempts"]
        # 1. HTML forms: one with both boxes, or a username page then a password page
        page_url = self.base + final.path + ("?" + final.query if final.query else "")
        forms = parser.forms
        for hop in range(3):
            form = next((f for f in forms if any(i["type"] == "password" for i in f["inputs"])), None) or \
                next((f for f in forms if any(i["type"] in ("text", "email") and i["name"] for i in f["inputs"])), None)
            if form is None:
                break
            names = [i for i in form["inputs"] if i["name"]]
            fields = {i["name"]: i["value"] for i in names if i["type"] in ("hidden", "text", "email", "password", "submit")}
            for i in names:
                if i["type"] in ("text", "email") and not i["value"]:
                    fields[i["name"]] = user
                if i["type"] == "password":
                    fields[i["name"]] = password
            action = urllib.parse.urljoin(page_url, form["action"] or "")
            st, nxt, inf = self.req("POST", action, form=fields, note="html form")
            attempts.append({"kind": f"html-form-{hop + 1}", "status": st, "finalUrl": inf["finalUrl"],
                             "fields": sorted(fields)})
            if self._session_ok():
                return self._signed_in(seen, user)
            nparser = _Forms()
            try:
                nparser.feed(nxt.get("text", "") if isinstance(nxt, dict) else "")
            except Exception:  # noqa: BLE001 - recording only
                pass
            forms, page_url = nparser.forms, self.base + inf["finalUrl"] if inf["finalUrl"].startswith("/") else page_url
        # Paths the sign-in page itself names (its HTML, not its scripts), for the record.
        seen["pagePaths"] = sorted(set(re.findall(r"[\"'](/(?:idp|data)/[A-Za-z0-9_./-]+)", text)))[:40]
        # 2. the identity provider's JSON steps (username, then password), as its login page posts them
        idp = re.match(r"(.*/authn)/", final.path + "/") if "/idp/" in final.path else None
        if idp:
            root = idp.group(1)
            for step, body in (("submit-username", {"username": user, **query}),
                               ("submit-password", {"password": password, **query})):
                st, pl, inf = self.req("POST", root + "/" + step, body, note="idp step")
                attempts.append({"kind": "idp-" + step, "status": st, "finalUrl": inf["finalUrl"],
                                 "shape": shape(pl) if isinstance(pl, (dict, list)) else None,
                                 "values": {k: v for k, v in (pl or {}).items() if isinstance(v, (str, int, bool)) and
                                            not any(w in k.lower() for w in SECRET_WORDS) and len(str(v)) < 200}
                                 if isinstance(pl, dict) and "text" not in pl else None})
                if st >= 400:
                    if self._session_ok():
                        return self._signed_in(seen, user)
                    break
                follow = None
                if isinstance(pl, dict):
                    follow = pl.get("redirectUrl") or pl.get("redirect") or pl.get("location") or pl.get("url")
                if follow:
                    st2, _, inf2 = self.req("GET", urllib.parse.urljoin(self.base + root + "/", follow), note="idp follow")
                    attempts.append({"kind": "idp-follow", "status": st2, "finalUrl": inf2["finalUrl"]})
                if self._session_ok():
                    return self._signed_in(seen, user)
        seen["signedIn"] = False
        if expect_ok:
            raise AutoError("sign-in did not produce a session: " + json.dumps(seen, default=str)[:1500])
        return seen

    def _session_ok(self) -> bool:
        status, payload, _ = self.req("GET", "/data/app/session", note="session check")
        if status >= 400 or not isinstance(payload, dict) or "text" in payload:
            return False
        csrf = payload.get("csrfToken") or payload.get("csrf")
        if csrf:
            self.csrf = str(csrf)
            self.secrets.append(self.csrf)
        user = payload.get("username") or payload.get("user") or payload.get("userPayload") or \
            (payload.get("authenticated") and "?")
        if isinstance(user, dict):
            user = user.get("username") or user.get("userName") or user.get("name") or "signed-in"
        self.username = str(user) if user else None
        self._session_keys = sorted(payload.keys())
        return bool(user) or bool(payload.get("authenticated"))

    def _signed_in(self, seen: dict, user: str) -> dict:
        seen["signedIn"] = True
        seen["sessionKeys"] = getattr(self, "_session_keys", None)
        seen["sessionUser"] = self.username
        seen["csrfPresent"] = bool(self.csrf)
        for c in self.jar:
            self.secrets.append(c.value)
        self.save_session()
        return seen

    def ensure_session(self) -> None:
        if self.load_session() and self._session_ok():
            return
        user = CONFIG["adminUser"] if self.session_name == "admin" else self.session_name
        password = read_secret("admin") if self.session_name == "admin" else ensure_user_password(self.session_name)
        self.signin(user, password)

    # -- the served OpenAPI document --
    def openapi(self) -> dict:
        cached = OUT / "openapi.json"
        if cached.exists():
            return json.loads(cached.read_text(encoding="utf-8"))
        for auth in ("session", "token", "none"):
            if auth == "token" and not secret_path("token").exists():
                continue
            status, payload, _ = self.req("GET", "/openapi.json", auth=auth, note="served API document")
            if status == 200 and isinstance(payload, dict) and payload.get("paths"):
                OUT.mkdir(parents=True, exist_ok=True)
                cached.write_text(json.dumps(payload), encoding="utf-8")
                return payload
        raise AutoError("the served OpenAPI document could not be read")


def ui_modules() -> str | None:
    """Where jsdom is installed (``UI_NODE_MODULES`` or ``reference/ui/node_modules``), if anywhere."""
    for candidate in (os.environ.get("UI_NODE_MODULES"), str(HERE / "ui" / "node_modules")):
        if candidate and (Path(candidate) / "jsdom").is_dir():
            return candidate
    return None


def ui_signin(ref: "Ref", user: str, password: str) -> dict:
    """Run ui_signin.js: the product's sign-in page in a headless DOM, typed into and pressed like a person.

    The password reaches node through a mode 0600 file, never an argument or the environment."""
    OUT.mkdir(parents=True, exist_ok=True)
    pw_file = OUT / f".pw-{os.getpid()}"
    fd = os.open(pw_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(password + "\n")
    session_file = ref._session_file()
    session_file.unlink(missing_ok=True)
    env = dict(os.environ, UI_NODE_MODULES=ui_modules() or "", REFAUTO_BASE=ref.base, REFAUTO_USER=user,
               REFAUTO_PASSWORD_FILE=str(pw_file), REFAUTO_SESSION_OUT=str(session_file))
    try:
        proc = subprocess.run(["node", str(HERE / "ui_signin.js")], env=env, capture_output=True, text=True, timeout=120)
    finally:
        pw_file.unlink(missing_ok=True)
    text = ref.scrub(proc.stdout.strip())
    try:
        page = json.loads(text.splitlines()[-1], strict=False) if text else {}
    except ValueError:
        page = {"unparsed": text[-600:]}
    seen = {"user": user, "via": "web sign-in page in a headless DOM",
            "requests": page.get("log"), "error": page.get("error"),
            "steps": [{k: v for k, v in st.items() if k != "dom"} for st in page.get("steps") or []]}
    alerts = [a for st in page.get("steps") or [] for a in ((st.get("dom") or {}).get("alerts") or [])]
    seen["alerts"] = alerts
    seen["attempts"] = [r for r in page.get("log") or [] if "authn" in str(r.get("path"))]
    seen["signedIn"] = False
    if session_file.exists():
        ref.load_session()
        if ref._session_ok():
            return ref._signed_in(seen, user)
    return seen


def routes(spec: dict, *words: str, method: str | None = None) -> list[dict]:
    """Paths of the served API document whose path, summary or tags match every word."""
    out = []
    for path, ops in (spec.get("paths") or {}).items():
        for verb, op in ops.items():
            if verb.lower() not in ("get", "post", "put", "patch", "delete") or not isinstance(op, dict):
                continue
            if method and verb.lower() != method.lower():
                continue
            hay = " ".join([path, str(op.get("summary") or ""), str(op.get("operationId") or ""),
                            " ".join(op.get("tags") or [])]).lower()
            if all(w.lower() in hay for w in words):
                params = [p.get("name") for p in op.get("parameters") or [] if isinstance(p, dict)]
                out.append({"method": verb.upper(), "path": path, "summary": op.get("summary"),
                            "operationId": op.get("operationId"), "params": params})
    return out


def param_enums(spec: dict, path: str, verb: str) -> dict:
    op = ((spec.get("paths") or {}).get(path) or {}).get(verb.lower()) or {}
    out = {}
    for p in op.get("parameters") or []:
        if isinstance(p, dict):
            schema = p.get("schema") or {}
            out[p.get("name")] = {"in": p.get("in"), "enum": schema.get("enum"), "type": schema.get("type"),
                                  "default": schema.get("default")}
    return out


def no_params(path: str) -> bool:
    return "{" not in path


# ---------------------------------------------------------------------------
# Config resources through the documented resource routes


class Resources:
    def __init__(self, ref: Ref, auth: str = "session") -> None:
        self.ref = ref
        self.auth = auth

    def list(self, module: str, kind: str):
        return self.ref.req("GET", f"{API}/resources/list/{module}/{kind}", auth=self.auth)

    def find(self, module: str, kind: str, name: str | None = None):
        path = f"{API}/resources/find/{module}/{kind}" + (f"/{name}" if name else "")
        return self.ref.req("GET", path, auth=self.auth)

    def singleton(self, module: str, kind: str):
        return self.ref.req("GET", f"{API}/resources/singleton/{module}/{kind}", query={"defaultIfUndefined": "true"},
                            auth=self.auth)

    def describe(self, module: str, kind: str):
        return self.ref.req("GET", f"{API}/resources/type/{module}/{kind}", auth=self.auth)

    def create(self, module: str, kind: str, items: list[dict]):
        return self.ref.req("POST", f"{API}/resources/{module}/{kind}", items, auth=self.auth)

    def modify(self, module: str, kind: str, items: list[dict]):
        return self.ref.req("PUT", f"{API}/resources/{module}/{kind}", items, auth=self.auth)


def signature_of(payload) -> str | None:
    if isinstance(payload, dict):
        for key in ("signature", "newSignature"):
            if isinstance(payload.get(key), str):
                return payload[key]
        for value in payload.values():
            found = signature_of(value)
            if found:
                return found
    if isinstance(payload, list):
        for value in payload:
            found = signature_of(value)
            if found:
                return found
    return None


def resource_items(payload) -> list[dict]:
    if isinstance(payload, list):
        return [p for p in payload if isinstance(p, dict)]
    if isinstance(payload, dict):
        if "config" in payload and isinstance(payload.get("config"), dict):
            return [payload]
        for key in ("items", "resources", "data", "results"):
            if isinstance(payload.get(key), list):
                return [p for p in payload[key] if isinstance(p, dict)]
        if "name" in payload or "config" in payload:
            return [payload]
    return []


def walk_find(payload, predicate, path=""):
    """Yield (path, key, value) for every key in a nested structure that matches."""
    if isinstance(payload, dict):
        for k, v in payload.items():
            p = f"{path}.{k}" if path else str(k)
            if predicate(str(k), v):
                yield p, k, v
            yield from walk_find(v, predicate, p)
    elif isinstance(payload, list):
        for i, v in enumerate(payload):
            yield from walk_find(v, predicate, f"{path}[{i}]")


def get_path(obj, dotted: str):
    cur = obj
    for part in re.findall(r"[^.\[\]]+", dotted):
        if isinstance(cur, list):
            cur = cur[int(part)] if int(part) < len(cur) else None
        elif isinstance(cur, dict):
            cur = cur.get(part)
        else:
            return None
    return cur


def set_path(obj, dotted: str, value) -> bool:
    parts = re.findall(r"[^.\[\]]+", dotted)
    cur = obj
    for part in parts[:-1]:
        cur = cur[int(part)] if isinstance(cur, list) else cur.get(part)
        if cur is None:
            return False
    last = parts[-1]
    if isinstance(cur, list):
        cur[int(last)] = value
    else:
        cur[last] = value
    return True


# ---------------------------------------------------------------------------
# Steps


def _admin(ev: Evidence) -> Ref:
    ref = Ref(ev, "admin")
    ref.ensure_session()
    return ref


def step_w02(ev: Evidence) -> None:
    """Sign in, create keys bench (write) and parity-ro (read), OPC UA server for the run, audit on."""
    ref = Ref(ev, "admin")
    seen = ref.signin(CONFIG["adminUser"], read_secret("admin"))
    ev.save("signin.json", seen)
    ev.check("admin sign-in through the web login flow", seen.get("signedIn"), seen.get("attempts"))
    spec = ref.openapi()
    ev.facts["openapiPaths"] = len(spec.get("paths") or {})
    ev.facts["openapiVersion"] = (spec.get("info") or {}).get("version")
    res = Resources(ref)
    # Security levels and gateway permissions, as they ship.
    for kind in ("security-levels", "security-properties"):
        st, payload, _ = res.singleton(CORE, kind)
        ev.save(f"{kind}-before.json", payload)
    st, schema, _ = res.describe(CORE, "api-token")
    ev.save("api-token-type.json", schema)
    made = {}
    for name, secret_name, write in (("bench", "token", True), ("parity-ro", "token2", False)):
        made[name] = create_api_key(ref, ev, res, name, secret_name, write)
    ev.facts["keys"] = made
    # The token reads the trial route (GET only) and the bench key writes a tag.
    t = Ref(ev)
    st, payload, _ = t.req("GET", f"{API}/trial", auth="token")
    ev.check("bench key accepted (GET trial route)", st < 400, st)
    if isinstance(payload, dict):
        ev.facts["trial"] = {k: payload.get(k) for k in ("licenseMode", "trialSecondsLeft", "expired") if k in payload}
    probe = {"tags": [{"name": "benchWriteProbe", "tagType": "AtomicTag", "valueSource": "memory", "dataType": "Int4", "value": 1}]}
    st, payload, _ = t.req("POST", f"{API}/tags/import", probe, query={"provider": "default", "type": "json",
                                                                         "collisionPolicy": "Overwrite"},
                           auth="token", content_type="application/octet-stream")
    ev.check("bench key may write (tag import of one memory tag)", st < 400, {"status": st, "body": safe_values(payload)})
    st2, _, _ = t.req("GET", f"{API}/trial", auth="token2")
    ev.check("parity-ro key accepted for a read", st2 < 400, st2)
    opcua_server_settings(ref, ev, res)
    opcua_read_check(ev)
    audit_on(ref, ev, res)
    ev.notes.append("No trial or licensing action was opened; the trial route was read with GET only.")


def _levels_for(write: bool) -> list:
    return [{"name": "Authenticated", "children": []}, {"name": "BenchWrite" if write else "BenchRead", "children": []}]


def _has(levels, name: str) -> bool:
    return any(isinstance(l, dict) and l.get("name") == name for l in levels or [])


def ensure_write_level(ref: Ref, ev: Evidence, res: Resources) -> dict:
    """Custom levels BenchWrite (gateway read and write) and BenchRead (read), as an admin sets them up in
    Security > Levels and Security > General Settings; the stock write level is a role level a key cannot hold."""
    out: dict[str, object] = {}
    st, item, _ = res.singleton(CORE, "security-levels")
    if st < 400 and isinstance(item, dict) and isinstance((item.get("config") or {}).get("securityLevels"), list):
        levels = item["config"]["securityLevels"]
        added = []
        for name in ("BenchWrite", "BenchRead"):
            if not _has(levels, name):
                levels.append({"name": name, "description": "trial window bench key level", "children": []})
                added.append(name)
        if added:
            st2, pl2, _ = res.modify(CORE, "security-levels", [item])
            out["levelsAdded"] = {"names": added, "status": st2}
            ev.save("security-levels-modify-response.json", pl2)
    st, props, _ = res.singleton(CORE, "security-properties")
    if st < 400 and isinstance(props, dict) and isinstance(props.get("config"), dict):
        cfg = props["config"]
        changed = []
        for perm, names in (("writePermissions", ("BenchWrite",)), ("readPermissions", ("BenchWrite", "BenchRead"))):
            block = cfg.get(perm)
            if isinstance(block, dict) and isinstance(block.get("securityLevels"), list) and block.get("type") == "AnyOf":
                for name in names:
                    if not _has(block["securityLevels"], name):
                        block["securityLevels"].append({"name": name, "children": []})
                        changed.append(f"{perm}+{name}")
        if changed:
            st3, pl3, _ = res.modify(CORE, "security-properties", [props])
            out["permissionsChanged"] = {"changes": changed, "status": st3}
            ev.save("security-properties-modify-response.json", pl3)
    return out


def create_api_key(ref: Ref, ev: Evidence, res: Resources, name: str, secret_name: str, write: bool) -> dict:
    out: dict[str, object] = {"name": name}
    out["level"] = ensure_write_level(ref, ev, res)
    st, generated, _ = ref.req("POST", f"{API}/api-token/generate", {}, note="generate key and hash (UI step)")
    key = hash_ = None
    if isinstance(generated, dict):
        key = generated.get("key") or generated.get("token")
        hash_ = generated.get("hash") or generated.get("tokenHash")
    out["generateStatus"] = st
    out["generateFields"] = sorted(generated.keys()) if isinstance(generated, dict) else None
    if not key or not hash_:
        ev.check(f"key {name}: generate", False, out)
        return out
    ref.secrets += [key, hash_]
    item = {"name": name, "collection": COLLECTION, "enabled": True, "description": "trial window " + name,
            "config": {"profile": {"type": "basic-token", "secureChannelRequired": False,
                                   "securityLevels": _levels_for(write), "timestamp": int(time.time() * 1000)},
                       "settings": {"tokenHash": hash_}}}
    st, pl, _ = res.find(CORE, "api-token", name)
    sig = signature_of(pl) if st < 400 else None
    if sig:
        ref.req("DELETE", f"{API}/resources/{CORE}/api-token/{name}/{sig}")
    st, created, _ = res.create(CORE, "api-token", [item])
    out["createStatus"] = st
    ev.save(f"api-token-{name}-create-response.json", created)
    if st >= 400 and write:
        # Fall back to the stock levels when the custom one is refused.
        item["config"]["profile"]["securityLevels"] = [{"name": "Authenticated", "children": []}]
        st, created, _ = res.create(CORE, "api-token", [item])
        out["createStatusStockLevels"] = st
    ok = st < 400 and not (isinstance(created, dict) and created.get("success") is False)
    if ok:
        # The key the API takes is "<key name>:<generated key>", as the key page shows it once.
        write_secret(secret_name, f"{name}:{key}")
        out["savedTo"] = "secrets/" + secret_path(secret_name).name
    ev.check(f"key {name}: created and saved to its token file", ok, {k: v for k, v in out.items() if k != "level"})
    out["keyShownOnce"] = "the generate response carries the key; the stored resource holds only its hash"
    return out


def opcua_server_settings(ref: Ref, ev: Evidence, res: Resources) -> None:
    """Record the OPC UA server settings, then bind 0.0.0.0:62541, policy None, anonymous, providers exposed."""
    spec = ref.openapi()
    candidates = [r for r in routes(spec, "opc") if "server" in r["path"].lower()]
    ev.save("opcua-server-routes.json", candidates)
    kinds = ["server-config"]
    found = None
    for kind in kinds:
        st, payload, _ = res.singleton(OPCUA, kind)
        if st < 400 and resource_items(payload):
            found = (kind, payload)
            break
    if not found:
        ev.check("OPC UA server settings resource found", False, kinds)
        return
    kind, payload = found
    item = json.loads(json.dumps(resource_items(payload)[0]))
    ev.save("opcua-server-before.json", item)
    ev.facts["opcuaServerKind"] = kind
    changes = []

    def put(pred, value, label):
        for path, key, current in list(walk_find(item, pred)):
            if current != value:
                set_path(item, path, value)
                changes.append({"field": path, "label": label})
            return True
        return False

    for path, key, current in list(walk_find(item, lambda k, v: "bind" in k.lower() and "address" in k.lower()
                                             and isinstance(v, list))):
        if current != ["0.0.0.0"]:
            set_path(item, path, ["0.0.0.0"])
            changes.append({"field": path, "label": "bind", "was": current})
    put(lambda k, v: k.lower() in ("bindaddress", "bind") and isinstance(v, str), "0.0.0.0", "bind")
    put(lambda k, v: k.lower() in ("bindport", "port") and isinstance(v, int), int(CONFIG["opcuaPort"]), "port")
    put(lambda k, v: "anonymous" in k.lower() and isinstance(v, bool), True, "anonymous")
    put(lambda k, v: "expose" in k.lower() and isinstance(v, bool), True, "expose tag providers")
    for path, key, current in list(walk_find(item, lambda k, v: ("polic" in k.lower() or "securitymode" in k.lower())
                                             and isinstance(v, list))):
        if "None" not in json.dumps(current):
            current.append("None")
            changes.append({"field": path, "label": "policy None"})
    ev.facts["opcuaChanges"] = changes
    st, pl, _ = res.modify(OPCUA, kind, [item])
    ev.save("opcua-server-modify-response.json", pl)
    ev.check("OPC UA server settings saved", st < 400, {"status": st, "changes": changes})


def opcua_read_check(ev: Evidence, attempts: int = int(os.environ.get("REFAUTO_OPCUA_ATTEMPTS") or 6), wait: float = 5.0) -> None:
    """Anonymous, policy None read of the probe tag through the product's OPC UA server, as the ramp reads."""
    try:
        from bench.opcua import OpcUaError, OpcUaReader, reference_node_id
    except ImportError as exc:
        ev.check("OPC UA read of the probe tag", None, f"client library missing: {exc}")
        return
    endpoint = os.environ.get("REFAUTO_OPCUA") or f"opc.tcp://127.0.0.1:{CONFIG['opcuaPort']}"
    last = None
    for _ in range(attempts):
        reader = OpcUaReader(endpoint, timeout=10)
        try:
            value = reader.read([reference_node_id("default", "benchWriteProbe")])[0]
            ev.check("OPC UA read of the probe tag", bool(value.good), {"status": value.status})
            return
        except (OpcUaError, OSError, Exception) as exc:  # noqa: BLE001 - the server may still be restarting
            last = f"{type(exc).__name__}: {exc}"[:300]
        finally:
            try:
                reader.close()
            except Exception:  # noqa: BLE001
                pass
        time.sleep(wait)
    ev.check("OPC UA read of the probe tag", False, last)


def audit_on(ref: Ref, ev: Evidence, res: Resources) -> None:
    """Record the audit profile types and whether one exists; a profile needs a database, so P5-01 makes it."""
    st, payload, _ = res.list(CORE, "audit-profile")
    ev.save("audit-profiles-before.json", payload)
    st2, schema, _ = res.describe(CORE, "audit-profile")
    types = [e.get("typeId") for e in (schema or {}).get("extensionPoints") or []] if isinstance(schema, dict) else []
    ev.facts["auditProfilesAtStart"] = len(resource_items(payload))
    ev.facts["auditProfileTypes"] = types
    ev.notes.append("Audit: no profile exists on a fresh gateway and every profile type stores to a database "
                    "connection, so the audit profile is created in P5-01 once window-db exists.")


def audit_profile_on(ref: Ref, ev: Evidence, res: Resources, database: str) -> None:
    item = {"name": "window-audit", "collection": COLLECTION, "enabled": True,
            "config": {"profile": {"type": "database", "retentionDays": 90}, "settings": {"databaseName": database}}}
    st, created, _ = res.create(CORE, "audit-profile", [item])
    ev.save("audit-profile-create-response.json", created)
    ev.check("audit profile window-audit created on window-db", st < 400, st)
    set_gateway_audit(ref, ev, res, "window-audit")


def set_gateway_audit(ref: Ref, ev: Evidence, res: Resources, profile: str) -> None:
    """Point the gateway's own audit field at a profile. The field is named by the documented schema of the
    security properties (it is absent from the stored resource until set); a stored audit field is used too."""
    spec = ref.openapi()
    b = body_schema(spec, f"{API}/resources/{CORE}/security-properties", "put")
    named = [k for k in (b["config"].get("properties") or {}) if "audit" in k.lower()]
    st, props, _ = res.singleton(CORE, "security-properties")
    if st >= 400 or not isinstance(props, dict):
        ev.check("gateway audit profile set to " + profile, False, {"read": st})
        return
    props.setdefault("config", {})
    hits = [p for p, k, v in walk_find(props, lambda k, v: "audit" in k.lower() and isinstance(v, (str, type(None))))]
    paths = hits or [f"config.{k}" for k in named]
    if not paths:
        ev.check("gateway audit profile set to " + profile, None, "no audit field in the security properties schema")
        return
    for p in paths:
        set_path(props, p, profile)
    st2, pl2, _ = res.modify(CORE, "security-properties", [props])
    st3, again, _ = res.singleton(CORE, "security-properties")
    value = [get_path(again, p) for p in paths] if isinstance(again, dict) else None
    ev.facts["gatewayAuditField"] = {"paths": paths, "status": st2,
                                     "valueAfter": value, "body": safe_values(pl2) if st2 >= 400 else None}
    ev.check("gateway audit profile set to " + profile, st2 < 400 and value == [profile] * len(paths),
             ev.facts["gatewayAuditField"])


def _get_all(ref: Ref, ev: Evidence, found: list[dict], auth: str = "session", limit: int = 12) -> dict:
    got = {}
    for r in [r for r in found if r["method"] == "GET" and no_params(r["path"])][:limit]:
        st, payload, _ = ref.req("GET", r["path"], auth=auth)
        got[r["path"]] = {"status": st, "shape": shape(payload) if isinstance(payload, (dict, list)) else None}
        if st < 400 and isinstance(payload, (dict, list)):
            ev.save(re.sub(r"[^a-z0-9]+", "-", r["path"].lower()).strip("-")[:80] + ".json", payload)
    return got


def step_p1_01(ev: Evidence) -> None:
    ref = _admin(ev)
    spec = ref.openapi()
    sections = {}
    for path in spec.get("paths") or {}:
        if path.startswith(API + "/"):
            head = path[len(API) + 1:].split("/")[0]
            sections[head] = sections.get(head, 0) + 1
    ev.facts["apiSections"] = sections
    ev.check("API sections listed from the served document", bool(sections), len(sections))
    st, trial, _ = ref.req("GET", f"{API}/trial", auth="token")
    if isinstance(trial, dict):
        ev.facts["trialFields"] = sorted(trial.keys())
        ev.facts["licenseMode"] = trial.get("licenseMode")
    ev.check("trial state readable (status banner source)", st < 400, st)
    search = routes(spec, "search")
    ev.facts["searchRoutes"] = search
    ev.check("a global search route exists", bool(search) or None, [r["path"] for r in search])
    ev.notes.append("No browser could be installed in this container, so page navigation and banners were not "
                    "seen as rendered pages; this step records the API's sections and the trial state instead.")


def step_p1_02(ev: Evidence) -> None:
    ref = _admin(ev)
    spec = ref.openapi()
    for label, words in (("scheduled tasks", ("task",)), ("threads", ("thread",)), ("performance", ("performance",))):
        found = routes(spec, *words)
        got = _get_all(ref, ev, found, auth="token")
        ev.facts[label] = {"routes": [f"{r['method']} {r['path']}" for r in found], "read": got}
        ev.check(f"{label}: a route answered", any(v["status"] < 400 for v in got.values()) if got else None, list(got))


def step_p1_03(ev: Evidence) -> None:
    ref = _admin(ev)
    st, loggers, _ = ref.req("GET", f"{API}/logs/loggers", auth="token")
    items = resource_items(loggers) or (loggers if isinstance(loggers, list) else [])
    ev.facts["loggerFields"] = sorted(items[0].keys()) if items and isinstance(items[0], dict) else None
    names = [i.get("name") for i in items if isinstance(i, dict) and i.get("name")]
    ev.facts["loggerCount"] = len(names)
    parent = next((n for n in names if any(m.startswith(n + ".") for m in names) and n.count(".") >= 1), None)
    child = next((m for m in names if parent and m.startswith(parent + ".")), None)
    ev.facts["parent"], ev.facts["child"] = parent, child
    level_of = lambda data, n: next((i.get("level") or i.get("effectiveLevel") for i in (resource_items(data) or data or [])
                                     if isinstance(i, dict) and i.get("name") == n), None)
    if parent:
        st2, _, _ = ref.req("POST", f"{API}/logs/loggers/{parent}", query={"level": "DEBUG"})
        st3, after, _ = ref.req("GET", f"{API}/logs/loggers", auth="token")
        ev.facts["afterSet"] = {"set": st2, "parentLevel": level_of(after, parent), "childLevel": level_of(after, child),
                                "childFields": next((i for i in (resource_items(after) or after or []) if isinstance(i, dict)
                                                     and i.get("name") == child), None)}
        ev.check("logger level applies without a restart", st2 < 400 and str(level_of(after, parent)).upper() == "DEBUG",
                 ev.facts["afterSet"])
        st4, _, _ = ref.req("POST", f"{API}/logs/levelreset")
        ev.facts["levelReset"] = st4
    st, content, info = ref.req("GET", f"{API}/logs/download", auth="token", raw=True)
    kind = "zip" if content[:2] == b"PK" else "gzip" if content[:2] == b"\x1f\x8b" else "other"
    entries = zipfile.ZipFile(io.BytesIO(content)).namelist() if kind == "zip" else None
    ev.facts["download"] = {"status": st, "bytes": info["bytes"], "contentType": info["contentType"], "kind": kind,
                            "entries": entries}
    ev.check("log download answered", st < 400, ev.facts["download"])


def step_p1_04(ev: Evidence) -> None:
    ref = _admin(ev)
    res = Resources(ref)
    st, payload, _ = res.find(CORE, "user-source", "default")
    ev.save("user-source-default.json", payload)
    policy = {p: v for p, k, v in walk_find(payload, lambda k, v: "password" in k.lower() or "length" in k.lower()
                                            or "complex" in k.lower() or "history" in k.lower() or "age" in k.lower())
              if not isinstance(v, (dict, list)) or len(json.dumps(v)) < 300}
    ev.facts["passwordPolicyFields"] = safe_values(policy)
    ev.check("internal user source read", st < 400, st)
    spec = ref.openapi()
    user_routes = routes(spec, "user")
    ev.facts["userRoutes"] = [f"{r['method']} {r['path']}" for r in user_routes]
    for name, role in (("parity-ro", "ReadOnly"), ("parity-lock", None)):
        pw = ensure_user_password(name)
        ref.secrets.append(pw)
        made = create_user(ref, ev, spec, name, pw, role)
        ev.check(f"user {name} created", made.get("ok"), made)
    grant_role_read(ref, ev, "ReadOnly")
    # The documented policy check: a short, simple password is refused or accepted?
    weak = create_user(ref, ev, spec, "parity-weak", "abc", None)
    ev.facts["weakPasswordAccepted"] = weak.get("ok")
    if weak.get("ok"):
        st, pl, _ = ref.req("GET", SCIM + "/Users")
        for u in (pl or {}).get("Resources") or [] if isinstance(pl, dict) else []:
            if u.get("userName") == "parity-weak" and u.get("id"):
                ref.req("DELETE", f"{SCIM}/Users/{u['id']}")


SCIM = API + "/scim/default/v2"


def scim_group(ref: Ref, ev: Evidence, name: str) -> str | None:
    st, payload, _ = ref.req("GET", SCIM + "/Groups")
    for g in (payload or {}).get("Resources") or [] if isinstance(payload, dict) else []:
        if g.get("displayName") == name:
            return g.get("id")
    st, made, _ = ref.req("POST", SCIM + "/Groups", {"schemas": ["urn:ietf:params:scim:schemas:core:2.0:Group"],
                                                      "displayName": name})
    ev.facts.setdefault("groupCreate", {})[name] = st
    return made.get("id") if isinstance(made, dict) else None


def create_user(ref: Ref, ev: Evidence, spec: dict, name: str, password: str, role: str | None) -> dict:
    """Users and roles through the documented SCIM routes of the internal user source (roles are groups)."""
    out: dict[str, object] = {}
    body = {"schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"], "userName": name, "password": password}
    if role:
        gid = scim_group(ref, ev, role)
        out["roleGroup"] = bool(gid)
        if gid:
            body["groups"] = [{"value": gid}]
    st, pl, _ = ref.req("GET", SCIM + "/Users")
    for u in (pl or {}).get("Resources") or [] if isinstance(pl, dict) else []:
        if u.get("userName") == name and u.get("id"):
            ref.req("DELETE", f"{SCIM}/Users/{u['id']}")
    st, made, _ = ref.req("POST", SCIM + "/Users", body)
    out["status"] = st
    # SCIM keeps a user's groups read-only; membership is set on the group (the role).
    if role and st < 400 and isinstance(made, dict) and made.get("id") and out.get("roleGroup"):
        gid = body["groups"][0]["value"]
        gs, group, _ = ref.req("GET", f"{SCIM}/Groups/{gid}")
        if gs < 400 and isinstance(group, dict):
            members = [m for m in group.get("members") or [] if isinstance(m, dict)]
            members.append({"value": made["id"]})
            group["members"] = members
            ps, _, _ = ref.req("PUT", f"{SCIM}/Groups/{gid}", group)
            out["roleMembership"] = ps
    out["fields"] = sorted(made.keys()) if isinstance(made, dict) else None
    if st >= 400:
        ev.save(f"user-{name}-create-response.json", made)
    out["ok"] = st < 400
    return out


def grant_role_read(ref: Ref, ev: Evidence, role: str) -> None:
    """Gateway read permission for a role level (Authenticated/Roles/<role>), as Security > General Settings sets it."""
    res = Resources(ref)
    st, props, _ = res.singleton(CORE, "security-properties")
    block = ((props or {}).get("config") or {}).get("readPermissions") if isinstance(props, dict) else None
    if not isinstance(block, dict) or not isinstance(block.get("securityLevels"), list):
        ev.check(f"read permission granted to role {role}", False, st)
        return
    entry = {"name": "Authenticated", "children": [{"name": "Roles", "children": [{"name": role, "children": []}]}]}
    if role not in json.dumps(block["securityLevels"]):
        block["securityLevels"].append(entry)
    st2, _, _ = res.modify(CORE, "security-properties", [props])
    ev.check(f"read permission granted to role {role}", st2 < 400, st2)


def step_p1_05(ev: Evidence) -> None:
    ro = Ref(ev, "parity-ro-wrong")
    wrong = ro.signin("parity-ro", "wrong-" + os.urandom(4).hex(), expect_ok=False)
    ev.facts["wrongPasswordOnce"] = {"signedIn": wrong.get("signedIn"), "alerts": wrong.get("alerts")}
    ro = Ref(ev, "parity-ro")
    seen = ro.signin("parity-ro", ensure_user_password("parity-ro"), expect_ok=False)
    ev.check("parity-ro signs in", seen.get("signedIn"), seen.get("alerts"))
    if not seen.get("signedIn"):
        return
    reads = {}
    for path in (f"{API}/systemPerformance/currentGauges", f"{API}/resources/list/{CORE}/user-source",
                 f"{API}/resources/find/{CORE}/user-source/default", SCIM + "/Users", SCIM + "/Groups",
                 f"{API}/logs/loggers", f"{API}/projects/list", f"{API}/backup"):
        st, payload, _ = ro.req("GET", path, raw=path.endswith("/backup"))
        hashes = [] if path.endswith("/backup") else [
            p for p, k, v in walk_find(payload, lambda k, v: ("password" in k.lower() or "hash" in k.lower())
                                       and isinstance(v, (str, dict)) and bool(v))]
        reads[path] = {"status": st, "passwordFields": hashes}
    ev.facts["readOnlyReads"] = reads
    ev.check("read-only role sees no password data", not any(r["passwordFields"] for r in reads.values()), reads)
    gid = None
    st, groups, _ = ro.req("GET", SCIM + "/Groups")
    st2, pl2, _ = ro.req("POST", SCIM + "/Groups", {"schemas": ["urn:ietf:params:scim:schemas:core:2.0:Group"],
                                                     "displayName": "parity-ro-made"})
    ev.facts["roRoleCreate"] = st2
    st3, pl3, _ = ref_write_probe(ro)
    ev.facts["roConfigWrite"] = st3
    ev.check("read-only role cannot change roles or write config", st2 in (401, 403) and st3 in (401, 403),
             {"roleCreate": st2, "configWrite": st3})


def ref_write_probe(ref: Ref):
    item = {"name": "parity-ro-write-probe", "collection": COLLECTION, "enabled": False,
            "config": {"profile": {"type": "internal"}, "settings": {}}}
    return ref.req("POST", f"{API}/resources/{CORE}/user-source", [item])


def _token_resource(res: Resources, name: str):
    st, payload, _ = res.find(CORE, "api-token", name)
    items = resource_items(payload)
    return json.loads(json.dumps(items[0])) if items else None


def set_key_enabled(res: Resources, name: str, enabled: bool) -> int:
    item = _token_resource(res, name)
    if item is None:
        return 404
    item["enabled"] = enabled
    st, _, _ = res.modify(CORE, "api-token", [item])
    return st


def step_p1_06(ev: Evidence) -> None:
    ref = _admin(ev)
    spec = ref.openapi()
    rot = routes(spec, "api-token") + routes(spec, "api-key")
    ev.facts["tokenRoutes"] = [f"{r['method']} {r['path']}" for r in rot]
    ev.facts["rotateRoute"] = [f"{r['method']} {r['path']}" for r in rot
                               if re.search("rotat|regenerat|reset", r["path"] + str(r["summary"]), re.I)]
    ev.facts["headerName"] = CONFIG.get("tokenHeader")
    ev.facts["keyFormat"] = "the API takes '<key name>:<generated key>'; the generated key alone is refused (401)"
    res = Resources(ref)
    item = _token_resource(res, "parity-ro")
    ev.facts["storedTokenFields"] = shape(item) if item else None
    ev.facts["storedHoldsOnlyHash"] = bool(item) and "tokenHash" in json.dumps(item) and "key" not in (
        (item.get("config") or {}).get("settings") or {})
    set_key_enabled(res, "parity-ro", True)
    time.sleep(1)
    t = Ref(ev)
    gauges = f"{API}/systemPerformance/currentGauges"
    st_read, _, _ = t.req("GET", gauges, auth="token2")
    probe = {"tags": [{"name": "parityScopeProbe", "tagType": "AtomicTag", "valueSource": "memory", "dataType": "Int4", "value": 0}]}
    st_write, _, _ = t.req("POST", f"{API}/tags/import", probe, query={"provider": "default", "type": "json",
                                                                         "collisionPolicy": "Overwrite"},
                           auth="token2", content_type="application/octet-stream")
    st_bogus, _, _ = t.req("GET", gauges, auth="bogus")
    ev.check("read-only key reads", st_read < 400, st_read)
    ev.check("read-only key refused on a write route", st_write in (401, 403), st_write)
    ev.check("unknown key refused", st_bogus in (401, 403), st_bogus)
    st_dis = set_key_enabled(res, "parity-ro", False)
    time.sleep(1)
    st_after, _, _ = t.req("GET", gauges, auth="token2")
    ev.facts["disabledKeyStatus"] = st_after
    ev.check("disabled key refused", st_dis < 400 and st_after in (401, 403), {"disable": st_dis, "after": st_after})
    st_trial, _, _ = t.req("GET", f"{API}/trial", auth="none")
    ev.facts["trialRouteWithoutKey"] = st_trial


def step_p1_10(ev: Evidence) -> None:
    ref = _admin(ev)
    spec = ref.openapi()
    ev.facts["importParams"] = param_enums(spec, f"{API}/projects/import/{{name}}", "post")
    st, pl, _ = ref.req("GET", f"{API}/projects/find/parity-a")
    if st >= 400:
        st, pl, _ = ref.req("POST", f"{API}/projects", {"name": "parity-a", "title": "parity-a", "enabled": True})
    ev.check("project parity-a exists", st < 400, st)
    st, content, info = ref.req("GET", f"{API}/projects/export/parity-a", raw=True)
    names = zipfile.ZipFile(io.BytesIO(content)).namelist() if st < 400 and content[:2] == b"PK" else None
    ev.facts["export"] = {"status": st, "bytes": info["bytes"], "contentType": info["contentType"], "entries": names}
    ev.check("project exported as a zip", st < 400 and bool(names), ev.facts["export"])
    if st >= 400:
        return
    results = {}
    for label, query in (("default", None), ("overwrite=false", {"overwrite": "false"}), ("overwrite=true", {"overwrite": "true"})):
        st2, pl2, _ = ref.req("POST", f"{API}/projects/import/parity-a", content, query=query, content_type="application/zip")
        results[label] = {"status": st2, "body": safe_values(pl2) if isinstance(pl2, dict) and "text" not in pl2 else None}
    ev.facts["reimport"] = results
    ev.check("import over an existing project: refused without overwrite, accepted with overwrite=true",
             results["default"]["status"] >= 400 and results["overwrite=true"]["status"] < 400, results)
    st3, pl3, _ = ref.req("POST", f"{API}/projects/import/parity-b", content, content_type="application/zip")
    ev.facts["importUnderNewName"] = st3
    st4, parents, _ = ref.req("GET", f"{API}/projects/parents")
    ev.facts["parentsRoute"] = {"status": st4, "shape": shape(parents)}


def audit_rows(ref: Ref, ev: Evidence, word: str) -> list:
    st, payload, _ = ref.req("GET", f"{API}/audit/log/window-audit", auth="token")
    ev.facts.setdefault("auditLogStatus", st)
    if st < 400:
        ev.save("audit-log.json", payload)
    rows = []
    for p, k, v in walk_find(payload, lambda k, v: k.lower() in ("action", "actiontype", "actionname")):
        if word == "*" or word.lower() in str(v).lower():
            rows.append(v)
    return rows


def _names(ref: Ref, kind: str) -> list:
    st, payload, _ = ref.req("GET", f"{API}/resources/names/{CORE}/{kind}", auth="token")
    if isinstance(payload, list):
        return [p if isinstance(p, str) else p.get("name") for p in payload]
    if isinstance(payload, dict):
        return [i if isinstance(i, str) else i.get("name") for i in payload.get("items") or payload.get("names") or []]
    return []


def step_p5_01(ev: Evidence) -> None:
    ref = _admin(ev)
    res = Resources(ref)
    drivers = _names(ref, "database-driver")
    translators = _names(ref, "database-translator")
    ev.facts["drivers"] = drivers
    ev.facts["translators"] = translators
    driver = next((d for d in drivers if d and "postgres" in d.lower()), None)
    translator = next((t for t in translators if t and "postgres" in t.lower()), None)
    password = read_secret("db")
    ref.secrets.append(password)
    st, jwe, _ = ref.req("POST", f"{API}/encryption/encrypt", password.encode(), content_type="text/plain",
                         note="documented encrypt route for an embedded secret")
    ev.check("password encrypted through the documented route", st < 400 and isinstance(jwe, dict), st)
    if isinstance(jwe, dict):
        ref.secrets += [str(v) for v in jwe.values() if isinstance(v, str)]
    db = PLAN["db"]
    item = {"name": "window-db", "collection": COLLECTION, "enabled": True,
            "config": {"driver": driver, "translator": translator,
                       "connectURL": f"jdbc:postgresql://host.docker.internal:{db['port']}/{db['database']}",
                       "username": db["user"], "password": {"type": "Embedded", "data": jwe}}}
    existing = _names(ref, "database-connection")
    if "window-db" in existing:
        st, created, _ = res.modify(CORE, "database-connection", [{**item, "signature": signature_of(res.find(CORE, "database-connection", "window-db")[1])}])
    else:
        st, created, _ = res.create(CORE, "database-connection", [item])
    ev.save("database-connection-create-response.json", created)
    ev.check("database connection window-db created", st < 400, {"status": st, "driver": driver, "translator": translator})
    if st < 400:
        audit_profile_on(ref, ev, res, "window-db")
    status = None
    for _ in range(15):
        status = db_status(ref)
        if status.get("status") and "valid" in str(status.get("status")).lower() or str(status.get("status")).lower() in ("good", "ok", "healthy", "connected"):
            break
        time.sleep(2)
    ev.facts["statusBeforeOutage"] = status
    ev.check("connection status readable", (status or {}).get("status") is not None, status)


def db_status(ref: Ref) -> dict:
    """The connection's health as its resource reports it (healthchecks and metrics)."""
    st, payload, _ = ref.req("GET", f"{API}/resources/find/{CORE}/database-connection/window-db", auth="token")
    if st >= 400 or not isinstance(payload, dict):
        return {"route": "find", "status": None, "http": st}
    checks = payload.get("healthchecks") or {}
    flat = {p: v for p, k, v in walk_find(checks, lambda k, v: not isinstance(v, (dict, list)))}
    state = next((v for p, v in flat.items() if p.lower().endswith(("status", "state", "healthy", "health"))), None)
    if state is None and flat:
        state = next(iter(flat.values()))
    metrics = {p: v for p, k, v in walk_find(payload.get("metrics") or {}, lambda k, v: k == "value")}
    return {"route": "resources/find database-connection", "status": state, "healthchecks": flat, "metrics": metrics}


def watch_db(seconds: float, interval: float) -> dict:
    ev = Evidence("P5-01-watch")
    ref = Ref(ev, "admin")
    ref.load_session()
    rows = []
    end = time.monotonic() + seconds
    begin = time.monotonic()
    while time.monotonic() < end:
        status = db_status(ref)
        rows.append({"t": round(time.monotonic() - begin, 1), "status": status.get("status")})
        time.sleep(interval)
    ev.facts["timeline"] = rows
    ev.check("status polled through the outage", any(r["status"] is not None for r in rows), len(rows))
    return ev.result(ref.scrub)


def step_p6_01(ev: Evidence) -> None:
    ref = _admin(ev)
    spec = ref.openapi()
    found = routes(spec, "gateway-network") + routes(spec, "certificate")
    ev.facts["routes"] = [f"{r['method']} {r['path']}" for r in found]
    got = _get_all(ref, ev, found, auth="token")
    res = Resources(ref)
    for kind in ("gateway-network-settings",):
        st, payload, _ = res.singleton(CORE, kind)
        if st < 400 and resource_items(payload):
            ev.save(f"{kind}.json", payload)
            vals = {p: v for p, k, v in walk_find(payload, lambda k, v: any(w in k.lower() for w in
                    ("ssl", "policy", "port", "incoming", "approv", "twoway")) and not isinstance(v, (dict, list)))}
            ev.facts["settings"] = vals
            break
    ev.check("peer network settings read", bool(ev.facts.get("settings")) or None, ev.facts.get("settings"))
    ev.check("certificate actions documented", any("certificate" in r["path"].lower() for r in found) or None,
             [f"{r['method']} {r['path']}" for r in found if "certificate" in r["path"].lower()])
    ev.facts["read"] = got


def step_p6_02(ev: Evidence) -> None:
    ref = _admin(ev)
    spec = ref.openapi()
    for path in (f"{API}/redundancy", f"{API}/redundancy/config"):
        st, payload, _ = ref.req("GET", path, auth="token")
        ev.save(path.strip("/").replace("/", "-") + ".json", payload)
        ev.facts[path] = {"status": st, "values": {p: v for p, k, v in walk_find(payload, lambda k, v: not isinstance(v, (dict, list)))}}
    op = ((spec.get("paths") or {}).get(f"{API}/redundancy/config") or {}).get("put") or {}
    body = json.dumps(op.get("requestBody") or {})
    ev.facts["modeEnums"] = sorted(set(re.findall(r'"enum": \[([^\]]*)\]', body)))[:20]
    ev.check("redundancy settings and modes read", ev.facts[f"{API}/redundancy/config"]["status"] < 400,
             ev.facts["modeEnums"])


def step_p1_11(ev: Evidence) -> None:
    ref = _admin(ev)
    spec = ref.openapi()
    ev.facts["params"] = param_enums(spec, f"{API}/backup", "get")
    ev.facts["passphraseOption"] = [k for k in ev.facts["params"] if re.search("pass|encrypt|password", str(k), re.I)]
    st, content, info = ref.req("GET", f"{API}/backup", auth="token", raw=True, timeout=300)
    ev.facts["adminDownload"] = {"status": st, "bytes": info["bytes"], "contentType": info["contentType"],
                                 "zip": content[:2] == b"PK"}
    if st < 400 and info["bytes"]:
        target = OUT / "reference-window-1.gwbk"
        target.write_bytes(content)
        ev.facts["savedTo"] = _rel(target) + " (gitignored, restore input for window 2)"
    ev.check("admin key creates and downloads a backup", st < 400 and info["bytes"] > 0, ev.facts["adminDownload"])
    res = Resources(ref)
    set_key_enabled(res, "parity-ro", True)
    time.sleep(1)
    st2, _, info2 = Ref(ev).req("GET", f"{API}/backup", auth="token2", raw=True, timeout=300)
    set_key_enabled(res, "parity-ro", False)
    ro = Ref(ev, "parity-ro")
    ro.load_session()
    st3, _, _ = ro.req("GET", f"{API}/backup", raw=True, timeout=300)
    ev.facts["readOnly"] = {"key": st2, "userSession": st3}
    ev.facts["readOnlyMayDownloadBackup"] = st2 < 400 or st3 < 400
    ev.check("who may download a backup recorded for a read-only key and a read-only user",
             st2 > 0 and st3 > 0, ev.facts["readOnly"])


def step_p1_12(ev: Evidence) -> None:
    ref = _admin(ev)
    res = Resources(ref)
    st, payload, _ = res.singleton(CORE, "security-properties")
    current = ((payload or {}).get("config") or {}).get("gatewayAuditProfile") if isinstance(payload, dict) else None
    if current != "window-audit":
        set_gateway_audit(ref, ev, res, "window-audit")
        # Auditable actions after the profile is on: a logger level set and reset, and a settings save.
        st_l, loggers, _ = ref.req("GET", f"{API}/logs/loggers")
        names = sorted(str(n.get("name")) for n in resource_items(loggers) if isinstance(n, dict)) if st_l < 400 else []
        if names:
            ref.req("POST", f"{API}/logs/loggers/{names[0]}", query={"level": "DEBUG"})
            ref.req("POST", f"{API}/logs/levelreset")
        st, payload, _ = res.singleton(CORE, "security-properties")
        if st < 400 and isinstance(payload, dict):
            res.modify(CORE, "security-properties", [payload])
        time.sleep(3)
    actions = audit_rows(ref, ev, "*")
    counts: dict[str, int] = {}
    for a in actions:
        counts[str(a)] = counts.get(str(a), 0) + 1
    ev.facts["actionCounts"] = counts
    ev.check("audit rows readable with action names", bool(counts) or None, len(actions))


def body_schema(spec: dict, path: str, verb: str) -> dict:
    """The request body schema the served API document gives a route, with references resolved one level."""
    op = ((spec.get("paths") or {}).get(path) or {}).get(verb) or {}
    schema = (((op.get("requestBody") or {}).get("content") or {}).get("application/json") or {}).get("schema") or {}
    schema = schema.get("items", schema)
    comps = (spec.get("components") or {}).get("schemas") or {}

    def res(x):
        return comps.get(x["$ref"].split("/")[-1], {}) if isinstance(x, dict) and "$ref" in x else x
    schema = res(schema)
    cfg = res((schema.get("properties") or {}).get("config") or {})
    return {"schema": schema, "config": cfg, "res": res}


def schema_fields(cfg: dict, res, depth: int = 0) -> dict:
    out = {}
    for k, v in (cfg.get("properties") or {}).items():
        v = res(v)
        row = {key: v.get(key) for key in ("type", "default", "minimum", "maximum", "enum") if key in v}
        row["description"] = str(v.get("description") or "")[:160]
        out[k] = row
        if v.get("type") == "object" and depth < 2:
            for k2, r2 in schema_fields(v, res, depth + 1).items():
                out[f"{k}.{k2}"] = r2
    return out


def step_p1_08(ev: Evidence) -> None:
    ref = _admin(ev)
    spec = ref.openapi()
    b = body_schema(spec, f"{API}/resources/{CORE}/security-zone", "post")
    fields = schema_fields(b["config"], b["res"])
    ev.facts["zoneFields"] = fields
    ev.check("security zone fields and how they combine read from the documented schema", bool(fields), len(fields))


def step_p1_09(ev: Evidence) -> None:
    ref = _admin(ev)
    spec = ref.openapi()
    b = body_schema(spec, f"{API}/resources/{CORE}/security-properties", "put")
    fields = {k: v for k, v in schema_fields(b["config"], b["res"]).items() if re.search("timeout|inactiv", k, re.I)}
    ev.facts["timeoutFields"] = fields
    res = Resources(ref)
    st, props, _ = res.singleton(CORE, "security-properties")
    if st < 400 and isinstance(props, dict):
        before = (props.get("config") or {}).get("userInactivityTimeout")
        ev.facts["userInactivityTimeoutDefault"] = before
        props["config"]["userInactivityTimeout"] = 0
        st2, pl2, _ = res.modify(CORE, "security-properties", [props])
        ev.facts["setZero"] = {"status": st2, "body": safe_values(pl2) if st2 >= 400 else None}
        st3, again, _ = res.singleton(CORE, "security-properties")
        ev.facts["valueAfterSetZero"] = ((again or {}).get("config") or {}).get("userInactivityTimeout")
        if st2 < 400 and isinstance(again, dict):
            again["config"]["userInactivityTimeout"] = before
            res.modify(CORE, "security-properties", [again])
        ev.check("inactivity timeout of 0 accepted or refused (recorded) and the setting's range read",
                 bool(fields) or None, {"setZero": st2, "after": ev.facts["valueAfterSetZero"]})


def step_p1_07(ev: Evidence) -> None:
    ensure_user_password("parity-lock")
    # Baseline first: the account must sign in with its password before any wrong try, or a refusal
    # afterwards says nothing about a lockout.
    base = Ref(ev, "parity-lock")
    before = base.signin("parity-lock", read_secret("parity-lock"), expect_ok=False)
    ev.facts["correctBeforeWrong"] = {"signedIn": before.get("signedIn")}
    ev.check("account signs in with its password before the wrong tries", before.get("signedIn") is True,
             ev.facts["correctBeforeWrong"])
    if before.get("signedIn") is not True:
        ev.notes.append("No baseline sign-in, so the lockout tries were not run: a refusal could not be told "
                        "apart from an account that cannot sign in at all.")
        return
    tries = []
    for i in range(12):
        r = Ref(ev, "parity-lock")
        seen = r.signin("parity-lock", f"wrong-{i}-" + os.urandom(3).hex(), expect_ok=False)
        tries.append({"try": i + 1, "attempts": seen.get("attempts")})
    ev.facts["wrongTries"] = tries
    r = Ref(ev, "parity-lock")
    ok = r.signin("parity-lock", read_secret("parity-lock"), expect_ok=False)
    ev.facts["correctAfterWrong"] = {"signedIn": ok.get("signedIn"), "attempts": ok.get("attempts")}
    admin = Ref(ev, "admin-after-lock")
    seen = admin.signin(CONFIG["adminUser"], read_secret("admin"), expect_ok=False)
    ev.facts["adminSameAddressAfter"] = seen.get("signedIn")
    ev.check("lockout observed (correct password refused after wrong tries)", ok.get("signedIn") is False,
             ev.facts["correctAfterWrong"])
    ev.check("lock is per account (admin from the same address still signs in)", seen.get("signedIn"), None)


STEPS = {
    "W02": step_w02, "P1-01": step_p1_01, "P1-02": step_p1_02, "P1-03": step_p1_03, "P1-04": step_p1_04,
    "P1-05": step_p1_05, "P1-06": step_p1_06, "P1-10": step_p1_10, "P5-01": step_p5_01, "P6-01": step_p6_01,
    "P6-02": step_p6_02, "P1-11": step_p1_11, "P1-12": step_p1_12, "P1-08": step_p1_08, "P1-09": step_p1_09,
    "P1-07": step_p1_07,
}


def run_step(step_id: str) -> dict:
    if step_id not in STEPS:
        raise AutoError(f"no automation for {step_id}")
    ev = Evidence(step_id)
    holder = Ref(ev)
    try:
        STEPS[step_id](ev)
    except (AutoError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as exc:
        ev.check("step ran to the end", False, holder.scrub(f"{type(exc).__name__}: {exc}")[:1500])
    return ev.result(_scrub_all)


def _scrub_all(text: str) -> str:
    secrets = []
    for name in ("admin", "token", "token2", "db", "backup", "parity-ro", "parity-lock"):
        try:
            secrets.append(read_secret(name))
        except AutoError:
            pass
    for path in OUT.glob(".session-*.json"):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            secrets += [c["value"] for c in data.get("cookies") or []] + [data.get("csrf") or ""]
        except (OSError, ValueError):
            pass
    for s in sorted({s for s in secrets if s and len(s) >= 4}, key=len, reverse=True):
        text = text.replace(s, "[redacted]")
    return text


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="refauto", description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("step")
    s.add_argument("id")
    c = sub.add_parser("call", help="one request, recorded under evidence/adhoc; secrets via --body-file {secret:name}")
    c.add_argument("method")
    c.add_argument("path")
    c.add_argument("--auth", default="session", choices=("session", "token", "token2", "none", "bogus"))
    c.add_argument("--body-file")
    c.add_argument("--content-type", default="application/json")
    c.add_argument("--full", action="store_true", help="print the scrubbed body, not just its shape")
    r = sub.add_parser("routes")
    r.add_argument("words", nargs="*")
    sub.add_parser("signin")
    w = sub.add_parser("watch-db")
    w.add_argument("--seconds", type=float, default=90)
    w.add_argument("--interval", type=float, default=2)
    args = parser.parse_args(argv)
    if args.cmd == "step":
        print(json.dumps(run_step(args.id), default=str))
        return 0
    if args.cmd == "watch-db":
        print(json.dumps(watch_db(args.seconds, args.interval), default=str))
        return 0
    ev = Evidence("adhoc")
    ref = Ref(ev, "admin")
    if args.cmd == "signin":
        print(json.dumps(safe_values(ref.signin(CONFIG["adminUser"], read_secret("admin"), expect_ok=False)), default=str))
        return 0
    if args.cmd == "routes":
        ref.ensure_session()
        for row in routes(ref.openapi(), *args.words):
            print(f"{row['method']:6} {row['path']}  {row['summary'] or ''}  {row['params']}")
        return 0
    if args.auth == "session":
        ref.ensure_session()
    body = None
    if args.body_file:
        from bench.window import fill_secrets  # noqa: E402  (bench is on sys.path below)
        body = fill_secrets(json.loads(Path(args.body_file).read_text(encoding="utf-8")), read_secret)
    st, payload, info = ref.req(args.method, args.path, body, auth=args.auth, content_type=args.content_type)
    text = json.dumps(safe_values(payload) if args.full else shape(payload) if isinstance(payload, (dict, list)) else payload,
                      indent=1, default=str)
    print(ref.scrub(f"{st} {info['contentType']} {info['ms']} ms\n{text[:6000]}"))
    return 0


sys.path.insert(0, str(BENCH))

if __name__ == "__main__":
    code = main()
    sys.stdout.flush()
    # The OPC UA client library can leave a non-daemon loop thread behind; do not wait for it.
    os._exit(code)
