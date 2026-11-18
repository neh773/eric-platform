import { parseView, type ViewDocument } from "@oip/hmi-runtime";
import { allowedComponentTypes } from "@oip/components";
import type { StudioProject } from "./project";
import { sampleStyleClasses } from "./sampleStyleClasses";

const types = allowedComponentTypes();

function view(input: unknown): ViewDocument {
  return parseView(input, { allowedTypes: types });
}

const overview = view({
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Overview", path: "overview" },
  spec: {
    params: {},
    width: 680,
    height: 360,
    root: {
      id: "root",
      kind: "container",
      name: "Root",
      layout: "coordinate",
      props: { mode: "fixed" },
      children: [
        {
          id: "title",
          kind: "component",
          name: "Title",
          type: "label",
          frame: { x: 16, y: 12, width: 300, height: 32 },
          props: { text: "North tank", align: "start" },
          styleClasses: ["heading"],
        },
        {
          id: "level",
          kind: "component",
          name: "Level",
          type: "numeric",
          frame: { x: 16, y: 56, width: 200, height: 64 },
          props: { value: 0, decimals: 1, prefix: "", suffix: " m", announce: true },
          bindings: { value: { kind: "tag", mode: "direct", path: "plant/tank/level" } },
        },
        {
          id: "command",
          kind: "component",
          name: "Command",
          type: "toggle",
          frame: { x: 240, y: 64, width: 180, height: 40 },
          props: { value: false, label: "Pump command" },
          bindings: { value: { kind: "tag", mode: "direct", path: "plant/pump/command", bidirectional: true } },
        },
        {
          id: "note",
          kind: "component",
          name: "Note",
          type: "text-input",
          frame: { x: 440, y: 56, width: 220, height: 56 },
          props: { value: "", label: "Note", placeholder: "Operator note", maxLength: 80 },
          bindings: { value: { kind: "tag", mode: "direct", path: "plant/tank/note", bidirectional: true } },
        },
        {
          id: "gauge",
          kind: "component",
          name: "Gauge",
          type: "linear-gauge",
          frame: { x: 16, y: 140, width: 300, height: 52 },
          props: { value: 0, min: 0, max: 100, label: "Tank level", unit: "m" },
          bindings: { value: { kind: "tag", mode: "direct", path: "plant/tank/level" } },
        },
        {
          id: "pump",
          kind: "component",
          name: "Pump",
          type: "indicator",
          frame: { x: 340, y: 140, width: 180, height: 48 },
          props: { value: false, label: "Pump", onText: "Running", offText: "Stopped" },
          bindings: { value: { kind: "tag", mode: "direct", path: "plant/pump/running" } },
        },
        {
          id: "trend",
          kind: "component",
          name: "Trend",
          type: "trend",
          frame: { x: 16, y: 208, width: 400, height: 130 },
          props: { value: 0, capacity: 30, min: 0, max: 100, label: "Level trend" },
          bindings: { value: { kind: "tag", mode: "direct", path: "plant/tank/level" } },
        },
        {
          id: "start",
          kind: "component",
          name: "Start",
          type: "button",
          frame: { x: 460, y: 208, width: 160, height: 40 },
          props: { text: "Start pump", enabled: true, writeValue: true },
          bindings: { value: { kind: "tag", mode: "direct", path: "plant/pump/command", bidirectional: true } },
        },
      ],
    },
  },
});

const pumps = view({
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Pumps", path: "pumps" },
  spec: {
    params: {},
    width: 680,
    height: 200,
    root: {
      id: "pumps-root",
      kind: "container",
      name: "Pump row",
      layout: "flex",
      props: { direction: "row", wrap: "nowrap", justify: "space-between", alignItems: "center", alignContent: "flex-start" },
      children: [
        {
          id: "pump-a",
          kind: "component",
          name: "Pump A",
          type: "label",
          flex: { grow: 0, shrink: 0, basis: "120px", display: true },
          props: { text: "Pump A", align: "start" },
        },
        {
          id: "pump-b",
          kind: "component",
          name: "Pump B",
          type: "label",
          flex: { grow: 1, shrink: 1, basis: "80px", display: true },
          props: { text: "Pump B grows", align: "center" },
        },
        {
          id: "pump-c",
          kind: "component",
          name: "Pump C",
          type: "label",
          flex: { grow: 0, shrink: 0, basis: "120px", display: true },
          props: { text: "Pump C", align: "end" },
        },
      ],
    },
  },
});

const summary = view({
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Summary", path: "summary" },
  spec: {
    params: {},
    width: 680,
    height: 220,
    root: {
      id: "summary-root",
      kind: "container",
      name: "Summary grid",
      layout: "column",
      props: {
        gutters: { vertical: 12, horizontal: 12 },
        breakpoints: [
          { name: "sm", minWidth: 0 },
          { name: "md", minWidth: 480 },
          { name: "lg", minWidth: 960 },
        ],
      },
      children: [
        {
          id: "sum-level",
          kind: "component",
          name: "Summary level",
          type: "label",
          columns: [
            { name: "sm", span: 12, rowIndex: 0, colIndex: 0, order: 0 },
            { name: "md", span: 6, rowIndex: 0, colIndex: 0, order: 0 },
            { name: "lg", span: 6, rowIndex: 0, colIndex: 0, order: 0 },
          ],
          props: { text: "Level column", align: "start" },
        },
        {
          id: "sum-pump",
          kind: "component",
          name: "Summary pump",
          type: "label",
          columns: [
            { name: "sm", span: 12, rowIndex: 1, colIndex: 0, order: 0 },
            { name: "md", span: 6, rowIndex: 0, colIndex: 6, order: 1 },
            { name: "lg", span: 6, rowIndex: 0, colIndex: 6, order: 1 },
          ],
          props: { text: "Pump column", align: "start" },
        },
      ],
    },
  },
});

export const sampleProject: StudioProject = {
  name: "North tank",
  views: [overview, pumps, summary],
  styleClasses: sampleStyleClasses,
};

export function latencyView(): ViewDocument {
  return view({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Latency", path: "latency" },
    spec: {
      params: {},
      width: 480,
      height: 160,
      root: {
        id: "latency-root",
        kind: "container",
        name: "Latency",
        layout: "coordinate",
        props: { mode: "fixed" },
        children: [
          {
            id: "latency-level",
            kind: "component",
            name: "Latency level",
            type: "numeric",
            frame: { x: 24, y: 36, width: 280, height: 80 },
            props: { value: 0, decimals: 1, prefix: "", suffix: "", announce: true },
            bindings: { value: { kind: "tag", mode: "direct", path: "sim/level" } },
          },
        ],
      },
    },
  });
}

export function benchView(count: number): ViewDocument {
  const children = [];
  for (let index = 0; index < count; index += 1) {
    children.push({
      id: `n${index}`,
      kind: "component",
      name: `N${index}`,
      type: "numeric",
      flex: { grow: 0, shrink: 0, basis: "72px", display: true },
      props: { value: 0, decimals: 0, prefix: "", suffix: "", announce: false },
      bindings: { value: { kind: "tag", mode: "direct", path: `bench/${index}` } },
    });
  }
  return view({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Bench", path: "bench" },
    spec: {
      params: {},
      width: 800,
      height: 600,
      root: {
        id: "bench-root",
        kind: "container",
        name: "Bench",
        layout: "flex",
        props: { direction: "row", wrap: "wrap", justify: "flex-start", alignItems: "center", alignContent: "flex-start" },
        children,
      },
    },
  });
}
