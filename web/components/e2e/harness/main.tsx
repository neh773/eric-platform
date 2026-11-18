import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { MemoryTagServer, ViewRenderer, createLinkedTransports, createSession, parseView } from "@oip/hmi-runtime";
import { allowedComponentTypes, componentRegistry } from "@oip/components";

/**
 * End-to-end harness for the live components. Vite proxies /api to the test gateway, so the
 * components use their default same-origin client with the browser session cookie and CSRF token.
 * Query parameters: `pens` (power chart tag paths) and `source` (alarm source filter).
 */
const params = new URLSearchParams(window.location.search);
const pens = params.get("pens") ?? "";
const sourceFilter = params.get("source") ?? "";

const server = new MemoryTagServer();
server.set("harness/ramp", 0);
const [transport, serverSide] = createLinkedTransports();
server.connect(serverSide);
let ramp = 0;
setInterval(() => {
  ramp = (ramp + 7) % 100;
  server.set("harness/ramp", ramp);
}, 200);

// C-14: `?journal=1` renders only the alarm journal table, for the spec that mocks the journal REST API.
const journalOnly = params.get("journal") === "1";
const journalView = {
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Journal", path: "journal" },
  spec: {
    params: {},
    width: 1400,
    height: 860,
    root: {
      id: "root",
      kind: "container",
      name: "Root",
      layout: "coordinate",
      props: { mode: "fixed" },
      children: [
        {
          id: "journal",
          kind: "component",
          name: "Journal",
          type: "alarm-journal-table",
          frame: { x: 0, y: 0, width: 1400, height: 860 },
          props: {
            rangeMode: params.get("mode") ?? "historical",
            startTime: "2026-10-28T00:00:00Z",
            endTime: "2026-10-29T00:00:00Z",
            realtimeMinutes: 60,
            refreshRate: 1000,
            pageSize: 25,
            columns: "eventTime, displayPath, eventType, priority, eventValue, actor, ackNotes",
          },
        },
      ],
    },
  },
};

const view = journalOnly ? parseView(journalView, { allowedTypes: allowedComponentTypes() }) : parseView(
  {
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Live", path: "live" },
    spec: {
      params: {},
      width: 1400,
      height: 860,
      root: {
        id: "root",
        kind: "container",
        name: "Root",
        layout: "coordinate",
        props: { mode: "fixed" },
        children: [
          {
            id: "alarms",
            kind: "component",
            name: "Alarms",
            type: "alarm-status-table",
            frame: { x: 0, y: 0, width: 1400, height: 400 },
            props: { refreshRate: 500, sourceFilter, columns: "state, priority, displayPath, label, activeTime, ackTime, ackUser" },
          },
          {
            id: "power",
            kind: "component",
            name: "Power",
            type: "power-chart",
            frame: { x: 0, y: 410, width: 690, height: 440 },
            props: { title: "Plant", pens, pollRate: 250, rangeSeconds: 60 },
          },
          {
            id: "series",
            kind: "component",
            name: "Series",
            type: "time-series-chart",
            frame: { x: 700, y: 410, width: 690, height: 440 },
            props: { title: "Ramp", valueName: "Ramp", rangeSeconds: 60 },
            bindings: { value: { kind: "tag", mode: "direct", path: "harness/ramp" } },
          },
        ],
      },
    },
  },
  { allowedTypes: allowedComponentTypes() },
);

const root = document.getElementById("root");
if (!root) throw new Error("root element missing");
createRoot(root).render(
  <StrictMode>
    <ViewRenderer view={view} registry={componentRegistry()} transport={transport} session={createSession({ id: "harness", locale: "en-US", theme: "daylight" })} />
  </StrictMode>,
);
