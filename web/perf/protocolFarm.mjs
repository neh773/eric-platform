/**
 * PRF-011 protocol sessions, run in a worker thread (review-4 F1).
 *
 * Each session signs in as its own user, opens the gateway session socket (`/api/v1/ws`), says hello, subscribes to
 * the view's tag paths and reads every update frame, as the HMI runtime's TagClient does. It carries the gateway side
 * of a session's load (its socket, its subscriptions, its frames) and renders nothing.
 *
 * Why a worker: on the main Playwright process these sockets share one event loop with the write driver and the
 * browser protocol traffic of every rendered page. On a loaded runner that loop fell behind, frames waited in socket
 * buffers for seconds, and the measured "arrival" was the test process draining late, not the gateway sending late.
 * Here each farm has its own loop, and every frame records the gateway's `sent` time as well, so a result splits
 * gateway queueing (change to sent) from client draining (sent to arrival).
 *
 * Messages: workerData `{baseURL, password, users, paths, base}`. The worker posts `{type: "ready"}` once every
 * session has a first value for every path (or `{type: "error", message}`). On `{type: "collect", from, to}` it posts
 * `{type: "result", sessions: [{samples, arrival, queue, drain}]}` for the values written in [from, to) (ms since
 * base) and closes its sockets.
 */
import { parentPort, workerData } from "node:worker_threads";

const { baseURL, password, users, paths, base } = workerData;

async function open(username) {
  const login = await fetch(`${baseURL}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!login.ok) throw new Error(`login ${username}: ${login.status} ${await login.text()}`);
  const cookie = login.headers
    .getSetCookie()
    .map((entry) => entry.split(";")[0])
    .join("; ");
  // Node's WebSocket takes request headers in its init object; the session cookie authenticates the upgrade.
  const socket = new WebSocket(`${baseURL.replace(/^http/, "ws")}/api/v1/ws`, { headers: { Cookie: cookie } });
  const session = { socket, values: [], change: [], sent: [], seen: [] };
  const pending = new Set(paths);
  const nonce = crypto.randomUUID();
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${username}: ${pending.size} paths without a first value`)), 60_000);
    socket.addEventListener("open", () => socket.send(JSON.stringify({ type: "hello", nonce })));
    socket.addEventListener("close", () => reject(new Error(`${username}: session socket closed`)));
    socket.addEventListener("message", (event) => {
      const now = Date.now() - base;
      const frame = JSON.parse(String(event.data));
      if (frame.type === "welcome" && frame.nonce === nonce) {
        socket.send(JSON.stringify({ type: "subscribe", paths }));
        return;
      }
      if (frame.type !== "update") return;
      const sent = typeof frame.sent === "number" ? frame.sent - base : Number.NaN;
      for (const update of frame.updates) {
        if (pending.delete(update.path) && pending.size === 0) {
          clearTimeout(timer);
          resolve();
        }
        if (typeof update.value !== "number") continue;
        session.values.push(update.value);
        session.change.push(update.timestamp - base);
        session.sent.push(sent);
        session.seen.push(now);
      }
    });
  });
  return session;
}

const sessions = [];
try {
  // Eight sign-ins at a time, like the rest of the farm set-up.
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(8, users.length) }, async () => {
      while (next < users.length) sessions.push(await open(users[next++]));
    }),
  );
  parentPort.postMessage({ type: "ready" });
} catch (error) {
  parentPort.postMessage({ type: "error", message: error instanceof Error ? error.message : String(error) });
}

parentPort.on("message", (message) => {
  if (message.type !== "collect") return;
  const result = sessions.map((session) => {
    const arrival = [];
    const queue = [];
    const drain = [];
    for (let index = 0; index < session.values.length; index += 1) {
      const value = session.values[index];
      if (value < message.from || value >= message.to) continue;
      arrival.push(session.seen[index] - session.change[index]);
      queue.push(session.sent[index] - session.change[index]);
      drain.push(session.seen[index] - session.sent[index]);
    }
    return { samples: arrival.length, arrival, queue, drain };
  });
  for (const session of sessions) session.socket.close();
  parentPort.postMessage({ type: "result", sessions: result });
});
