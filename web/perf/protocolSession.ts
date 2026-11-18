import { Worker } from "node:worker_threads";

/**
 * PRF-011 protocol sessions (review-4 F1): sessions without a browser, each with its own user, gateway session
 * socket and subscriptions, run in worker threads (`protocolFarm.mjs`) so their frames are read on time even while
 * the main test process drives writes and talks to the browsers.
 */
export interface ProtocolSessionResult {
  /** Values written inside the window that this session received. */
  samples: number;
  /** Change (the tag's gateway timestamp) to frame arrival, ms. */
  arrival: number[];
  /** Change to the gateway's `sent` time of the frame: gateway queueing, ms. */
  queue: number[];
  /** `sent` to arrival: the socket and the client draining it, ms. */
  drain: number[];
}

export interface ProtocolFarm {
  /** Resolves once every session has a first value for every path. */
  ready: Promise<void>;
  /** Per-session results for the values written in [from, to) (ms since base); closes the sessions. */
  collect(from: number, to: number): Promise<ProtocolSessionResult[]>;
  /** Stops the workers. */
  close(): Promise<void>;
}

/** Spreads [users] over [workers] worker threads, one protocol session per user. */
export function startProtocolFarm(options: {
  baseURL: string;
  password: string;
  users: string[];
  paths: string[];
  base: number;
  workers: number;
}): ProtocolFarm {
  const count = Math.max(1, Math.min(options.workers, options.users.length));
  const groups = Array.from({ length: count }, (_, index) => options.users.filter((_, user) => user % count === index)).filter((group) => group.length > 0);
  const threads = groups.map(
    (users) =>
      new Worker(new URL("./protocolFarm.mjs", import.meta.url), {
        workerData: { baseURL: options.baseURL, password: options.password, users, paths: options.paths, base: options.base },
      }),
  );
  const ready = Promise.all(
    threads.map(
      (thread) =>
        new Promise<void>((resolve, reject) => {
          const onMessage = (message: { type: string; message?: string }) => {
            if (message.type === "ready") {
              thread.off("message", onMessage);
              resolve();
            } else if (message.type === "error") {
              thread.off("message", onMessage);
              reject(new Error(message.message));
            }
          };
          thread.on("message", onMessage);
          thread.once("error", reject);
        }),
    ),
  ).then(() => undefined);
  return {
    ready,
    async collect(from, to) {
      const parts = await Promise.all(
        threads.map(
          (thread) =>
            new Promise<ProtocolSessionResult[]>((resolve) => {
              const onMessage = (message: { type: string; sessions?: ProtocolSessionResult[] }) => {
                if (message.type !== "result") return;
                thread.off("message", onMessage);
                resolve(message.sessions ?? []);
              };
              thread.on("message", onMessage);
              thread.postMessage({ type: "collect", from, to });
            }),
        ),
      );
      return parts.flat();
    },
    async close() {
      await Promise.all(threads.map((thread) => thread.terminate()));
    },
  };
}
