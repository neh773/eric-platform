import type { StringTransport, ViewDocument } from "@oip/hmi-runtime";

/** Designer diagnostics (DES-005): what the open view costs while it runs in the Studio preview. */
export interface BindingStats {
  bindings: number;
  /** Distinct tag paths the tag bindings subscribe to. */
  tagPaths: number;
}

export function bindingStats(view: ViewDocument): BindingStats {
  let bindings = 0;
  const paths = new Set<string>();
  const walk = (node: unknown) => {
    if (!node || typeof node !== "object") return;
    const record = node as Record<string, unknown>;
    const own = record.bindings;
    if (own && typeof own === "object") {
      for (const binding of Object.values(own as Record<string, unknown>)) {
        bindings += 1;
        const b = binding as { kind?: string; path?: unknown };
        if (b?.kind === "tag" && typeof b.path === "string") paths.add(b.path);
      }
    }
    if (Array.isArray(record.children)) record.children.forEach(walk);
  };
  walk(view.spec.root);
  return { bindings, tagPaths: paths.size };
}

/** Counts what the preview's tag transport receives and how long the last renders took. */
export class DiagnosticsMeter {
  private stamps: number[] = [];
  private durations: number[] = [];
  total = 0;

  constructor(private readonly now: () => number = () => Date.now()) {}

  message(): void {
    this.total += 1;
    this.stamps.push(this.now());
    if (this.stamps.length > 5000) this.stamps.splice(0, this.stamps.length - 5000);
  }

  render(ms: number): void {
    this.durations.push(ms);
    if (this.durations.length > 50) this.durations.shift();
  }

  /** Messages per second over the last [windowMs]. */
  rate(windowMs = 5000): number {
    const cutoff = this.now() - windowMs;
    return this.stamps.filter((stamp) => stamp >= cutoff).length / (windowMs / 1000);
  }

  lastRenderMs(): number | null {
    return this.durations.at(-1) ?? null;
  }

  averageRenderMs(): number | null {
    return this.durations.length === 0 ? null : this.durations.reduce((a, b) => a + b, 0) / this.durations.length;
  }
}

/** The transport with every received message counted by [meter]. */
export function meteredTransport(inner: StringTransport, meter: DiagnosticsMeter): StringTransport {
  return {
    send: (data) => inner.send(data),
    onMessage: (handler) =>
      inner.onMessage((data) => {
        meter.message();
        handler(data);
      }),
    onClose: (handler) => inner.onClose(handler),
    close: () => inner.close(),
  };
}
