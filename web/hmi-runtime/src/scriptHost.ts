import type { ScriptActionResult, ScriptHost } from "./bindings";
import type { StringTransport } from "./tags";
import { ScriptPending, ScriptUnavailable } from "./transforms";
import type { DockAction, MethodAction, SessionAction } from "./bindings";
import type { MessageAction, NavigationAction, PopupAction, QualityCode } from "./types";

/**
 * Script transforms and event scripts run on the gateway (D-5a), over the session socket `/api/v1/ws`, as the
 * signed-in user. The browser never runs script code; it sends the hook name and its inputs and gets the answer back.
 *
 * Frames (in addition to the tag frames of `tags.ts`):
 * - client `script {requestId, call: "transform", project, view, hook, value, quality, timestamp, nodeId, prop}`
 * - client `script {requestId, call: "event", project, view, hook, event, payload, nodeId, params, custom}`
 * - server `scriptResult {requestId, ok, value?, actions?, error?, code?}`; `code` is `unknown_hook` (no saved source)
 *   or `busy` (the gateway's caps are full; asked again later, never kept as an error)
 *
 * A script transform answers asynchronously, so [transform] returns a {@link ScriptPending} (with the previous answer
 * for that property, when there is one) until the gateway answers, then notifies [subscribe] listeners so the session
 * evaluates its bindings again. Answers are kept per property and input, so a re-render does not run the script again.
 * Without a gateway, [transform] returns {@link ScriptUnavailable} and the binding shows a not-connected quality,
 * and the host tries the socket again after a delay that doubles after each failure (one delay per host, not per
 * property).
 */
export type ScriptClientFrame =
  | {
      type: "script";
      requestId: string;
      call: "transform";
      project: string;
      view?: string;
      hook: string;
      value: unknown;
      quality: QualityCode;
      timestamp: number;
      nodeId: string;
      prop: string;
    }
  | {
      type: "script";
      requestId: string;
      call: "event";
      project: string;
      view?: string;
      hook: string;
      event: string;
      payload?: unknown;
      nodeId: string;
      params: Record<string, unknown>;
      custom: Record<string, unknown>;
      /** The session's properties as the page reports them (HMP-084); the gateway takes the user from the login. */
      session?: Record<string, unknown>;
      tree?: Array<{ id: string; name: string; type: string; parent: string | null; props: Record<string, unknown> }>;
    };

export interface ScriptResultFrame {
  type: "scriptResult";
  requestId: string;
  ok: boolean;
  value?: unknown;
  actions?: Array<NavigationAction | PopupAction | MessageAction | MethodAction | DockAction | SessionAction>;
  error?: string;
  /**
   * `unknown_hook`: the project has no saved source for the hook (for example an unsaved Designer edit).
   * `busy`: the gateway's script caps are full; the call is asked again after a short wait.
   */
  code?: string;
}

export interface GatewayScriptHostOptions {
  /** Opens the gateway session socket, or a channel on one; rejects when there is none. */
  connect: () => Promise<StringTransport>;
  /**
   * True when [connect] returns a channel on a session socket that has already said hello (the tab's shared socket):
   * the host sends no hello, and closing it only detaches the host.
   */
  welcomed?: boolean;
  /** Gateway project whose hooks run. */
  project: string;
  /** How long one call may take before it fails here. The gateway applies its own script limits first. */
  callTimeoutMs?: number;
  /** How long the socket may take to answer `hello`. */
  welcomeTimeoutMs?: number;
  /** Calls in flight at once. The gateway answers more than its per-session cap with "busy", so stay under it. */
  maxInFlight?: number;
  /** After a failed connect or a busy answer, the wait before a transform tries the gateway again. */
  retryMs?: number;
  /** Longest wait between connect tries; the wait doubles from [retryMs] after each failed try. */
  maxRetryMs?: number;
  /** Properties whose answers are kept; the oldest go first. */
  maxEntries?: number;
}

/** Call failed because the gateway could not be reached (as opposed to a script error). */
class NotConnected extends Error {}

interface Entry {
  key: string;
  state: "pending" | "done" | "error" | "offline" | "missing" | "busy";
  value?: unknown;
  error?: string;
  hasLast: boolean;
  last: unknown;
  retryAt?: number;
}

interface Waiter {
  resolve: (frame: ScriptResultFrame) => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

interface Queued {
  frame: ScriptClientFrame;
  resolve: (frame: ScriptResultFrame) => void;
  reject: (error: Error) => void;
}

interface Connection {
  transport: StringTransport;
  ready: Promise<void>;
}

export class GatewayScriptHost implements ScriptHost {
  private readonly entries = new Map<string, Entry>();
  private readonly listeners = new Set<() => void>();
  private readonly waiters = new Map<string, Waiter>();
  private readonly queue: Queued[] = [];
  private inFlight = 0;
  private connection: Connection | null = null;
  private opening: Promise<Connection> | null = null;
  private sequence = 0;
  private notifyQueued = false;
  private closed = false;
  private readonly callTimeoutMs: number;
  private readonly welcomeTimeoutMs: number;
  private readonly maxInFlight: number;
  private readonly retryMs: number;
  private readonly maxRetryMs: number;
  private readonly maxEntries: number;
  /** No connect try before this time (one delay for the whole host). */
  private connectAfter = 0;
  private backoffMs: number;
  private wakeTimer: ReturnType<typeof setTimeout> | null = null;
  private wakeTime = 0;

  constructor(private readonly options: GatewayScriptHostOptions) {
    this.callTimeoutMs = options.callTimeoutMs ?? 30_000;
    this.welcomeTimeoutMs = options.welcomeTimeoutMs ?? 5_000;
    this.maxInFlight = Math.max(1, options.maxInFlight ?? 4);
    this.retryMs = options.retryMs ?? 2_000;
    this.maxRetryMs = Math.max(this.retryMs, options.maxRetryMs ?? 30_000);
    this.backoffMs = this.retryMs;
    this.maxEntries = Math.max(1, options.maxEntries ?? 2_000);
  }

  transform(hook: string, value: unknown, quality: QualityCode, timestamp: number, nodeId: string, prop: string, viewPath?: string): unknown {
    const slot = `${viewPath ?? ""}\u0000${nodeId}\u0000${prop}\u0000${hook}`;
    const key = inputKey(value, quality);
    const current = this.entries.get(slot);
    if (current && current.key === key) {
      if (current.state === "done") return current.value;
      if (current.state === "error") throw new Error(current.error ?? "script failed");
      if (current.state === "pending") return new ScriptPending(current.hasLast, current.last);
      // No saved source on the gateway: not runnable here until the input changes or the session reloads.
      if (current.state === "missing") return new ScriptUnavailable(current.error);
      if (current.state === "offline" && Math.max(current.retryAt ?? 0, this.connectAfter) > Date.now()) {
        return new ScriptUnavailable(current.error);
      }
      if (current.state === "busy" && (current.retryAt ?? 0) > Date.now()) return new ScriptPending(current.hasLast, current.last);
    }
    if (this.closed) return new ScriptUnavailable("script host is closed");
    // Waiting out the reconnect delay: no call, and the binding stays not connected.
    if (!this.connection && this.connectAfter > Date.now()) {
      const offline: Entry = { key, state: "offline", hasLast: current?.hasLast ?? false, last: current?.last, error: current?.error ?? "gateway socket unavailable" };
      this.entries.delete(slot);
      this.entries.set(slot, offline);
      this.trim();
      return new ScriptUnavailable(offline.error);
    }
    const previousDone = current?.state === "done";
    const entry: Entry = {
      key,
      state: "pending",
      hasLast: previousDone || (current?.hasLast ?? false),
      last: previousDone ? current?.value : current?.last,
    };
    this.entries.delete(slot);
    this.entries.set(slot, entry);
    this.trim();
    const frame: ScriptClientFrame = {
      type: "script",
      requestId: this.nextId(),
      call: "transform",
      project: this.options.project,
      view: viewPath,
      hook,
      value,
      quality,
      timestamp,
      nodeId,
      prop,
    };
    this.request(frame).then(
      (result) => {
        if (this.entries.get(slot) !== entry) return;
        if (result.ok) {
          entry.state = "done";
          entry.value = result.value ?? null;
        } else if (result.code === "unknown_hook") {
          entry.state = "missing";
          entry.error = result.error ?? "script hook has no saved source";
        } else if (result.code === "busy") {
          // Not the script's answer: keep showing the last one and ask again after the wait.
          entry.state = "busy";
          entry.error = result.error ?? "busy";
          entry.retryAt = Date.now() + this.retryMs;
          this.wakeAt(entry.retryAt);
        } else {
          entry.state = "error";
          entry.error = result.error ?? "script failed";
        }
        this.notify();
      },
      (error: unknown) => {
        if (this.entries.get(slot) !== entry) return;
        const message = error instanceof Error ? error.message : String(error);
        if (error instanceof NotConnected) {
          entry.state = "offline";
          entry.retryAt = this.connectAfter;
          // Evaluate again after the host's delay, so the binding tries the gateway once more.
          this.wakeAt(this.connectAfter);
        } else {
          entry.state = "error";
        }
        entry.error = message;
        this.notify();
      },
    );
    return new ScriptPending(entry.hasLast, entry.last);
  }

  private sessionProvider: (() => Record<string, unknown>) | undefined;

  setSessionProvider(provider: () => Record<string, unknown>): void {
    this.sessionProvider = provider;
  }

  async action(
    hook: string,
    event: string,
    nodeId: string,
    viewPath: string,
    params: Record<string, unknown>,
    custom: Record<string, unknown>,
    payload?: unknown,
    tree?: Array<{ id: string; name: string; type: string; parent: string | null; props: Record<string, unknown> }>,
  ): Promise<ScriptActionResult> {
    const frame: ScriptClientFrame = {
      type: "script",
      requestId: this.nextId(),
      call: "event",
      project: this.options.project,
      view: viewPath,
      hook,
      event,
      nodeId,
      params,
      custom,
    };
    if (payload !== undefined) frame.payload = payload;
    if (tree !== undefined) frame.tree = tree;
    const session = this.sessionProvider?.();
    if (session !== undefined) frame.session = session;
    const result = await this.request(frame);
    if (!result.ok) throw new Error(result.error ?? "script failed");
    const actions = result.actions ?? [];
    const navigate = actions.filter(isNavigation);
    const popup = actions.filter(isPopupAction);
    const message = actions.filter(isMessageAction);
    const method = actions.filter(isMethodAction);
    const dock = actions.filter(isDockAction);
    const sessionActions = actions.filter(isSessionAction);
    const asked: ScriptActionResult = {};
    if (navigate.length > 0) asked.navigate = navigate;
    if (popup.length > 0) asked.popup = popup;
    if (message.length > 0) asked.message = message;
    if (method.length > 0) asked.method = method;
    if (dock.length > 0) asked.dock = dock;
    if (sessionActions.length > 0) asked.session = sessionActions;
    return asked;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /** Whether the session socket is open (tests and diagnostics). */
  connected(): boolean {
    return this.connection !== null;
  }

  close(): void {
    this.closed = true;
    if (this.wakeTimer !== null) clearTimeout(this.wakeTimer);
    this.wakeTimer = null;
    const live = this.connection;
    this.connection = null;
    this.opening = null;
    this.failAll(new NotConnected("script host is closed"));
    if (live) {
      try {
        live.transport.close();
      } catch {
        // already closed
      }
    }
  }

  private nextId(): string {
    this.sequence += 1;
    return `s${this.sequence}`;
  }

  private request(frame: ScriptClientFrame): Promise<ScriptResultFrame> {
    if (this.closed) return Promise.reject(new NotConnected("script host is closed"));
    return new Promise((resolve, reject) => {
      this.queue.push({ frame, resolve, reject });
      this.pump();
    });
  }

  private pump(): void {
    while (this.inFlight < this.maxInFlight && this.queue.length > 0) {
      const job = this.queue.shift()!;
      this.inFlight += 1;
      void this.send(job).finally(() => {
        this.inFlight -= 1;
        this.pump();
      });
    }
  }

  private async send(job: Queued): Promise<void> {
    let connection: Connection;
    try {
      connection = await this.ensureConnection();
    } catch (error) {
      job.reject(error instanceof NotConnected ? error : new NotConnected(error instanceof Error ? error.message : String(error)));
      return;
    }
    await new Promise<void>((done) => {
      const finish = () => {
        const waiter = this.waiters.get(job.frame.requestId);
        if (waiter) clearTimeout(waiter.timer);
        this.waiters.delete(job.frame.requestId);
        done();
      };
      const timer = setTimeout(() => {
        finish();
        job.reject(new Error("script timed out"));
      }, this.callTimeoutMs);
      this.waiters.set(job.frame.requestId, {
        timer,
        resolve: (frame) => {
          finish();
          job.resolve(frame);
        },
        reject: (error) => {
          finish();
          job.reject(error);
        },
      });
      try {
        connection.transport.send(JSON.stringify(job.frame));
      } catch {
        this.waiters.get(job.frame.requestId)?.reject(new NotConnected("gateway socket closed"));
      }
    });
  }

  /** One socket at a time: concurrent calls share the connection being opened, and failed tries back off. */
  private ensureConnection(): Promise<Connection> {
    if (!this.opening) {
      if (this.connectAfter > Date.now()) return Promise.reject(new NotConnected("gateway socket unavailable; trying again shortly"));
      const opening = this.open();
      this.opening = opening;
      opening.then(
        () => {
          this.backoffMs = this.retryMs;
          this.connectAfter = 0;
        },
        () => {
          if (this.opening === opening) this.opening = null;
          this.connectAfter = Date.now() + this.backoffMs;
          this.backoffMs = Math.min(this.backoffMs * 2, this.maxRetryMs);
        },
      );
    }
    return this.opening;
  }

  /** One timer for the host: re-evaluate bindings at [at] (the earliest pending retry wins). */
  private wakeAt(at: number): void {
    if (this.closed) return;
    if (this.wakeTimer !== null && this.wakeTime <= at) return;
    if (this.wakeTimer !== null) clearTimeout(this.wakeTimer);
    this.wakeTime = at;
    this.armWake(at);
  }

  /**
   * Timers run on the event loop's monotonic clock and can fire a little before Date.now() reaches [at]. A wake
   * that notified then would find the retry time still ahead, make no call and arm nothing, leaving the binding
   * pending for good. So notify only once Date.now() has reached [at], and wait out the remainder otherwise.
   */
  private armWake(at: number): void {
    this.wakeTimer = setTimeout(() => {
      if (this.closed) {
        this.wakeTimer = null;
        return;
      }
      if (Date.now() < at) {
        this.armWake(at);
        return;
      }
      this.wakeTimer = null;
      this.notify();
    }, Math.max(0, at - Date.now()));
  }

  private async open(): Promise<Connection> {
    const transport = await this.options.connect();
    if (this.closed) {
      transport.close();
      throw new NotConnected("script host is closed");
    }
    const welcomedAlready = this.options.welcomed === true;
    const nonce = `script-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    let welcomed: () => void = () => {};
    let refused: (error: Error) => void = () => {};
    const ready = new Promise<void>((resolve, reject) => {
      welcomed = resolve;
      refused = reject;
    });
    const connection: Connection = { transport, ready };
    const timer = welcomedAlready
      ? undefined
      : setTimeout(() => refused(new NotConnected("gateway socket did not answer hello")), this.welcomeTimeoutMs);
    transport.onMessage((data) => {
      let frame: { type?: unknown; nonce?: unknown; requestId?: unknown };
      try {
        frame = JSON.parse(data) as typeof frame;
      } catch {
        return;
      }
      if (frame.type === "welcome" && frame.nonce === nonce) {
        clearTimeout(timer);
        welcomed();
        return;
      }
      if (frame.type === "scriptResult" && typeof frame.requestId === "string") {
        this.waiters.get(frame.requestId)?.resolve(frame as ScriptResultFrame);
      }
    });
    transport.onClose(() => {
      clearTimeout(timer);
      refused(new NotConnected("gateway socket closed"));
      if (this.connection === connection) {
        this.connection = null;
        this.opening = null;
        // A dropped socket waits out the host's delay too, so a flapping gateway is not asked again at once.
        this.connectAfter = Date.now() + this.backoffMs;
        this.backoffMs = Math.min(this.backoffMs * 2, this.maxRetryMs);
      }
      for (const waiter of [...this.waiters.values()]) waiter.reject(new NotConnected("gateway socket closed"));
    });
    if (welcomedAlready) welcomed();
    else transport.send(JSON.stringify({ type: "hello", nonce }));
    try {
      await ready;
    } catch (error) {
      try {
        transport.close();
      } catch {
        // already closed
      }
      throw error;
    }
    this.connection = connection;
    return connection;
  }

  private failAll(error: Error): void {
    for (const waiter of [...this.waiters.values()]) waiter.reject(error);
    for (const job of this.queue.splice(0)) job.reject(error);
  }

  private notify(): void {
    // Several answers in one tick re-render once.
    if (this.notifyQueued) return;
    this.notifyQueued = true;
    queueMicrotask(() => {
      this.notifyQueued = false;
      for (const listener of [...this.listeners]) listener();
    });
  }

  private trim(): void {
    while (this.entries.size > this.maxEntries) {
      const oldest = this.entries.keys().next().value;
      if (oldest === undefined) return;
      this.entries.delete(oldest);
    }
  }
}

function inputKey(value: unknown, quality: QualityCode): string {
  try {
    return `${quality}\u0000${JSON.stringify(value) ?? "undefined"}`;
  } catch {
    return `${quality}\u0000${String(value)}`;
  }
}

function isNavigation(value: unknown): value is NavigationAction {
  if (value === null || typeof value !== "object") return false;
  const action = value as Partial<NavigationAction>;
  return action.kind === "navigation" && (action.mode === "page" || action.mode === "view");
}

function isPopupAction(value: unknown): value is PopupAction {
  if (value === null || typeof value !== "object") return false;
  const action = value as Partial<PopupAction>;
  return action.kind === "popup" && (action.mode === "open" || action.mode === "close" || action.mode === "toggle") && typeof action.id === "string" && action.id.length > 0;
}

function isMethodAction(value: unknown): value is MethodAction {
  if (value === null || typeof value !== "object") return false;
  const action = value as Partial<MethodAction>;
  return action.kind === "method" && (action.method === "setProp" || action.method === "refresh" || action.method === "call") && typeof action.nodeId === "string";
}

function isDockAction(value: unknown): value is DockAction {
  if (value === null || typeof value !== "object") return false;
  const action = value as Partial<DockAction>;
  return action.kind === "dock" && (action.mode === "open" || action.mode === "close" || action.mode === "toggle") && typeof action.id === "string" && action.id.length > 0;
}

function isSessionAction(value: unknown): value is SessionAction {
  if (value === null || typeof value !== "object") return false;
  const action = value as Partial<SessionAction>;
  return action.kind === "session" && (action.prop === "locale" || action.prop === "timeZoneId" || action.prop === "theme") && typeof action.value === "string";
}

function isMessageAction(value: unknown): value is MessageAction {
  if (value === null || typeof value !== "object") return false;
  const action = value as Partial<MessageAction>;
  return action.kind === "message" && typeof action.messageType === "string" && (action.scope === "page" || action.scope === "view" || action.scope === "session");
}
