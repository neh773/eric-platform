// Headless sign-in through the reference product's own web sign-in page.
//
// The page is a client-rendered app, so it runs here in a headless DOM (jsdom) the way a
// browser would run it: we load the page, type into the boxes it renders and press its
// button. Nothing reads or copies the page's scripts; we only see the DOM they render and
// the requests they make (method, path, status), as a browser's network panel would.
//
//   UI_NODE_MODULES=<dir with jsdom> REFAUTO_USER=admin REFAUTO_PASSWORD_FILE=secrets/... \
//   REFAUTO_SESSION_OUT=bench/out/.../.session-admin.json node reference/ui_signin.js [--dump]
//
// The password is read from a file and never printed. The session cookies are written to a
// mode 0600 file in the format reference/refauto.py reads.
"use strict";

const fs = require("fs");
const path = require("path");
const modules = process.env.UI_NODE_MODULES || "node_modules";
// Record where the page sends the browser (jsdom has no navigation of its own): wrap the headless
// DOM's navigate hook before the rest of jsdom loads it. This is our tool's code, not the page's.
const navigation = require(path.join(modules, "jsdom", "lib", "jsdom", "living", "window", "navigation.js"));
const { serializeURL } = require(path.join(modules, "whatwg-url"));
const navigated = [];
const originalNavigate = navigation.navigate;
navigation.navigate = (window, newURL, flags) => {
  navigated.push(serializeURL(newURL));
  return originalNavigate(window, newURL, flags);
};
const { JSDOM, CookieJar, VirtualConsole } = require(path.join(modules, "jsdom"));

const base = process.env.REFAUTO_BASE || "http://127.0.0.1:8088";
const user = process.env.REFAUTO_USER || "admin";
const password = fs.readFileSync(process.env.REFAUTO_PASSWORD_FILE, "utf8").trim();
const sessionOut = process.env.REFAUTO_SESSION_OUT;
const dumpOnly = process.argv.includes("--dump");
const log = [];
const scrub = (s) => String(s).split(password).join("[redacted]").replace(/([?&](?:token|code|state|nonce)=)[^&]+/g, "$1…");

function record(kind, method, url, status) {
  const u = new URL(url, base);
  log.push({ kind, method, path: scrub(u.pathname + u.search), status });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function summary(doc) {
  const text = (el) => (el.textContent || "").replace(/\s+/g, " ").trim().slice(0, 80);
  return {
    title: doc.title,
    headings: [...doc.querySelectorAll("h1,h2,h3,h4,label,legend")].map(text).filter(Boolean).slice(0, 20),
    inputs: [...doc.querySelectorAll("input,select,textarea")].map((el) => ({
      tag: el.tagName.toLowerCase(), type: el.type, name: el.name || null, id: el.id || null,
      placeholder: el.placeholder || null, aria: el.getAttribute("aria-label"), autocomplete: el.getAttribute("autocomplete"),
    })),
    buttons: [...doc.querySelectorAll("button,[role=button],input[type=submit]")].map((el) => ({
      text: text(el) || el.value || null, type: el.type || null, disabled: !!el.disabled,
    })),
    alerts: [...doc.querySelectorAll("[role=alert],.error,.message")].map(text).filter(Boolean).slice(0, 5),
    actions: [...doc.querySelectorAll("a,button,[role=button],[tabindex],div,span")]
      .filter((el) => el.children.length === 0 && /continue|sign|log ?in|next|submit|exit/i.test(text(el)))
      .map((el) => ({ tag: el.tagName.toLowerCase(), text: text(el), cls: String(el.className || "").slice(0, 60),
        parent: el.parentElement ? el.parentElement.tagName.toLowerCase() : null })).slice(0, 10),
  };
}

function setValue(win, el, value) {
  const proto = el.tagName === "TEXTAREA" ? win.HTMLTextAreaElement.prototype : win.HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, "value").set.call(el, value);
  el.dispatchEvent(new win.Event("input", { bubbles: true }));
  el.dispatchEvent(new win.Event("change", { bubbles: true }));
}

function press(win, doc, box) {
  const label = (b) => (b.textContent || b.value || "").trim();
  const scope = (box && box.closest("form")) || doc;
  const buttons = [...scope.querySelectorAll("button,input[type=submit]")]
    .filter((b) => !b.disabled && !/exit|cancel|back|forgot/i.test(label(b)));
  const submit = buttons.find((b) => /continue|next|sign in|log ?in|submit/i.test(label(b))) ||
    buttons.find((b) => b.type === "submit");
  if (submit) {
    submit.click();
    return "button";
  }
  // A control drawn as a styled element with its label inside (no <button>): click the label's box.
  const drawn = [...doc.querySelectorAll("span,div,a")].find((el) => el.children.length === 0 &&
    /^(continue|next|sign in|log ?in|submit)$/i.test((el.textContent || "").trim()) && el.closest("[class*=button],a,[role=button]"));
  if (drawn) {
    const target = drawn.parentElement || drawn;
    const opts = { bubbles: true, cancelable: true, view: win, button: 0 };
    for (const type of ["pointerdown", "mousedown", "pointerup", "mouseup"]) {
      const Ctor = type.startsWith("pointer") && win.PointerEvent ? win.PointerEvent : win.MouseEvent;
      target.dispatchEvent(new Ctor(type, opts));
    }
    target.dispatchEvent(new win.MouseEvent("click", opts));
    return "drawn-control:" + (drawn.textContent || "").trim().toLowerCase();
  }
  const form = box && box.closest("form");
  if (form) {
    form.dispatchEvent(new win.Event("submit", { bubbles: true, cancelable: true }));
    return "form-submit-event";
  }
  box.dispatchEvent(new win.KeyboardEvent("keydown", { key: "Enter", code: "Enter", keyCode: 13, bubbles: true }));
  return "enter";
}

async function waitFor(doc, selector, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const el = doc.querySelector(selector);
    if (el) return el;
    await sleep(200);
  }
  return null;
}

async function main() {
  const jar = new CookieJar();
  const vc = new VirtualConsole();
  const navigations = [];
  vc.on("jsdomError", (e) => {
    if (/navigation/i.test(e.message)) navigations.push("navigation requested");
    else log.push({ kind: "page-error", message: scrub(e.message).slice(0, 200) });
  });
  const dom = await JSDOM.fromURL(base + "/data/app/login", {
    runScripts: "dangerously", resources: "usable", pretendToBeVisual: true, cookieJar: jar, virtualConsole: vc,
    beforeParse(win) {
      // fetch for the page, through the same cookie jar; each request is recorded (method, path, status).
      win.fetch = async (input, init = {}) => {
        const url = new URL(typeof input === "string" ? input : input.url, win.location.href).href;
        const headers = new Headers(init.headers || {});
        headers.set("cookie", await jar.getCookieString(url));
        const res = await fetch(url, { ...init, headers, redirect: "manual" });
        for (const c of res.headers.getSetCookie ? res.headers.getSetCookie() : []) await jar.setCookie(c, url, { ignoreError: true });
        record("fetch", init.method || "GET", url, res.status);
        const loc = res.headers.get("location");
        if (loc && res.status >= 300 && res.status < 400) {
          const next = new URL(loc, url).href;
          const r2 = await win.fetch(next, { method: "GET" });
          return r2;
        }
        const body = await res.arrayBuffer();
        return new win.Response ? new win.Response(body, { status: res.status, headers: res.headers }) :
          new Response(body, { status: res.status, headers: res.headers });
      };
      if (typeof win.Response === "undefined") win.Response = Response;
      if (typeof win.Headers === "undefined") win.Headers = Headers;
      const open = win.XMLHttpRequest.prototype.open;
      win.XMLHttpRequest.prototype.open = function (method, url, ...rest) {
        const xhr = this;
        xhr.addEventListener("loadend", () => {
          record("xhr", method, new URL(url, win.location.href).href, xhr.status);
          // The response, as a network panel shows it: keep its field names, and any URL it hands the page.
          try {
            const body = JSON.parse(xhr.responseText || "null");
            if (body && typeof body === "object") {
              log[log.length - 1].fields = Object.keys(body);
              for (const [k, v] of Object.entries(body)) {
                if (typeof v === "string" && /^(https?:\/\/|\/)/.test(v)) win.__handBack.push(new URL(v, win.location.href).href);
                if (v && typeof v === "object" && !Array.isArray(v)) log[log.length - 1][k] = Object.keys(v);
              }
              if (typeof body.type === "string") log[log.length - 1].type = body.type;
            }
          } catch (e) { /* not JSON */ }
        });
        return open.call(this, method, url, ...rest);
      };
      // Where the page sends the browser once signed in: assignments to location are recorded.
      win.__handBack = [];
    },
  });
  const win = dom.window;
  const doc = win.document;
  const result = { steps: [] };
  const first = await waitFor(doc, "input", 15000);
  result.steps.push({ at: "page", dom: summary(doc) });
  if (dumpOnly || !first) {
    result.log = log;
    emit(JSON.stringify(result), 0);
    return 0;
  }
  // Username box, then the password box (on the same screen or the next).
  const userBox = doc.querySelector("input[autocomplete=username],input[name=username],input[type=text],input[type=email]") || first;
  if (userBox && userBox.type !== "password") {
    setValue(win, userBox, user);
    const before = log.length;
    result.steps.push({ at: "username", pressed: press(win, doc, userBox) });
    for (let i = 0; i < 25 && log.length === before; i++) await sleep(200);
    await sleep(500);
  }
  const pw = await waitFor(doc, "input[type=password]", 10000);
  if (!pw) {
    result.steps.push({ at: "no-password-box", dom: summary(doc) });
  } else {
    setValue(win, pw, password);
    result.steps.push({ at: "password", pressed: press(win, doc, pw) });
  }
  for (let i = 0; i < 30; i++) {
    await sleep(500);
    const cookies = await jar.getCookies(base + "/data/app/session");
    if (navigated.length || log.some((r) => /federate\/callback/.test(r.path))) break;
    if (cookies.length && log.length && i > 6) break;
  }
  result.steps.push({ at: "after", dom: summary(doc), navigations });
  // Finish the hand-back the page asked for: follow it ourselves with the same cookies.
  for (const target of navigated.slice(-1).concat(navigated.length ? [] : win.__handBack.slice(-1))) {
    let url = target;
    for (let hop = 0; hop < 8 && url; hop++) {
      const res = await fetch(url, { headers: { cookie: await jar.getCookieString(url) }, redirect: "manual" });
      for (const c of res.headers.getSetCookie()) await jar.setCookie(c, url, { ignoreError: true });
      record("hand-back", "GET", url, res.status);
      const loc = res.headers.get("location");
      url = loc && res.status >= 300 && res.status < 400 ? new URL(loc, url).href : null;
    }
  }
  const cookies = await jar.getCookies(base + "/");
  result.cookieNames = cookies.map((c) => c.key);
  result.log = log;
  if (sessionOut) {
    const out = { cookies: cookies.map((c) => ({ name: c.key, value: c.value, domain: c.domain || "127.0.0.1", path: c.path || "/" })), csrf: null, username: null };
    fs.writeFileSync(sessionOut, JSON.stringify(out), { mode: 0o600 });
    fs.chmodSync(sessionOut, 0o600);
  }
  result.buttonsSeen = summary(doc).buttons;
  win.close();
  emit(scrub(JSON.stringify(result)), 0);
  return 0;
}

function emit(text, code) {
  process.stdout.write(text + "\n", () => process.exit(code));
}

main().then((code) => code, (err) => {
  emit(JSON.stringify({ error: scrub(err && err.stack || err).slice(0, 800), log }), 1);
});
