import { useRef, useState } from "react";
import { ViewRenderer, createSession, type SessionModel, type ViewDocument } from "@oip/hmi-runtime";
import { componentRegistry } from "@oip/components";
import { benchView, latencyView } from "./sampleProject";
import { embedBench } from "./embedBench";
import type { TagRuntime } from "./runtime";

const registry = componentRegistry();

/** Latency, 500-component, and 50-embedded-view probes. Dev/test builds only. */
export function DevRoutes({ route, runtime }: { route: string; runtime: TagRuntime }) {
  if (route === "/latency") return <Probe title="Latency probe" view={latencyView()} runtime={runtime} />;
  if (route === "/bench") return <Probe title="Render bench" view={benchView(500)} runtime={runtime} ready="500" />;
  if (route === "/bench-embed") return <EmbedProbe runtime={runtime} />;
  return null;
}

function Probe({
  title,
  view,
  runtime,
  ready,
  views,
}: {
  title: string;
  view: ViewDocument;
  runtime: TagRuntime;
  ready?: string;
  views?: ViewDocument[];
}) {
  const [session, setSession] = useState<SessionModel>(() =>
    createSession({ id: "probe", theme: "daylight", locale: "en-US", gateway: { connected: true } }),
  );
  return (
    <main className="oip-probe">
      <h1>{title}</h1>
      <div data-testid="live-preview" data-bench-ready={ready}>
        <ViewRenderer
          view={view}
          views={views}
          registry={registry}
          transport={runtime.transport}
          session={session}
          onConnectionChange={(connected) => setSession((current) => ({ ...current, gateway: { connected } }))}
        />
      </div>
    </main>
  );
}

function EmbedProbe({ runtime }: { runtime: TagRuntime }) {
  const seeded = useRef(false);
  if (!seeded.current) {
    seeded.current = true;
    for (let index = 0; index < 50; index += 1) runtime.server.set(`embed/${index}`, 0);
  }
  const { shell, child } = embedBench();
  return <Probe title="Embedded bench" view={shell} views={[child]} runtime={runtime} ready="50" />;
}
