import { useEffect, useRef } from "react";
import "leaflet/dist/leaflet.css";
import type { HmiComponentProps, JsonSchema } from "@oip/hmi-runtime";
import { useGatewayClient } from "./gateway";
import { readNumber, readString } from "./read";

/**
 * Street map for a demonstration HMI. Leaflet draws the basemap; OpenStreetMap (or any `{z}/{x}/{y}` template)
 * supplies the tiles. Markers can show a live tag as a level gauge and open an HMI page when tapped.
 * Pinch-zoom and drag are Leaflet's own. This is the map screen only; process graphics stay the native symbols.
 */

export const leafletMapSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Leaflet map",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Accessible label", default: "Map" },
    centerLat: { type: "number", title: "Centre latitude", default: 25.91 },
    centerLon: { type: "number", title: "Centre longitude", default: -97.49 },
    zoom: { type: "number", title: "Zoom", default: 12, minimum: 1, maximum: 19 },
    tileUrl: { type: "string", title: "Tile template", default: "https://tile.openstreetmap.org/{z}/{x}/{y}.png" },
    attribution: { type: "string", title: "Attribution", default: "© OpenStreetMap" },
    markers: { type: "string", title: "Markers JSON [{id,lat,lon,title,path,max,high,unit,href}]", default: "[]" },
    lines: { type: "string", title: "Lines JSON [{points:[[lat,lon],...], color}]", default: "[]" },
  },
};

interface Pin {
  id: string;
  lat: number;
  lon: number;
  title: string;
  path: string;
  max: number;
  high: number;
  unit: string;
  href: string;
}

interface Line {
  points: Array<[number, number]>;
  color: string;
}

function pinsOf(raw: string): Pin[] {
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.flatMap((entry) => {
      if (!entry || typeof entry !== "object") return [];
      const row = entry as Record<string, unknown>;
      const lat = typeof row.lat === "number" ? row.lat : Number.NaN;
      const lon = typeof row.lon === "number" ? row.lon : Number.NaN;
      if (!Number.isFinite(lat) || !Number.isFinite(lon)) return [];
      return [{
        id: typeof row.id === "string" ? row.id : `${lat},${lon}`,
        lat, lon,
        title: typeof row.title === "string" ? row.title : "",
        path: typeof row.path === "string" ? row.path : "",
        max: typeof row.max === "number" ? row.max : 18,
        high: typeof row.high === "number" ? row.high : 14,
        unit: typeof row.unit === "string" ? row.unit : "",
        href: typeof row.href === "string" ? row.href : "",
      }];
    });
  } catch {
    return [];
  }
}

function linesOf(raw: string): Line[] {
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.flatMap((entry) => {
      if (!entry || typeof entry !== "object") return [];
      const row = entry as Record<string, unknown>;
      const points = Array.isArray(row.points)
        ? row.points.flatMap((pair) => {
            if (!Array.isArray(pair) || pair.length < 2) return [];
            const lat = Number(pair[0]);
            const lon = Number(pair[1]);
            return Number.isFinite(lat) && Number.isFinite(lon) ? [[lat, lon] as [number, number]] : [];
          })
        : [];
      if (points.length < 2) return [];
      return [{ points, color: typeof row.color === "string" ? row.color : "#2f6f9f" }];
    });
  } catch {
    return [];
  }
}

function chip(pin: Pin, value: number | undefined): string {
  const alarmed = value !== undefined && value >= pin.high;
  const full = value === undefined || pin.max <= 0 ? 0 : Math.max(0, Math.min(1, value / pin.max));
  const text = value === undefined ? "—" : `${value.toFixed(1)}${pin.unit ? ` ${pin.unit}` : ""}`;
  const color = alarmed ? "#c4473a" : "#1f6f8b";
  return `<div class="oip-leaf" style="border-color:${color};background:${alarmed ? "#4a2424" : "rgba(18,23,28,0.92)"};color:#f4f7fb">` +
    `<i style="height:${Math.round(full * 100)}%;background:${color}"></i>` +
    `<b>${pin.title}</b><span>${text}</span></div>`;
}

export function LeafletMap({ props, navigate }: HmiComponentProps) {
  const host = useRef<HTMLDivElement>(null);
  const client = useGatewayClient();
  const lat = readNumber(props, "centerLat", 25.91);
  const lon = readNumber(props, "centerLon", -97.49);
  const zoom = readNumber(props, "zoom", 12);
  const tileUrl = readString(props, "tileUrl", "https://tile.openstreetmap.org/{z}/{x}/{y}.png");
  const attribution = readString(props, "attribution", "© OpenStreetMap");
  const markerText = readString(props, "markers", "[]");
  const lineText = readString(props, "lines", "[]");

  useEffect(() => {
    const element = host.current;
    if (!element) return undefined;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let map: { remove: () => void } | null = null;
    const pins = pinsOf(markerText);
    const lines = linesOf(lineText);
    void import("leaflet").then((mod) => {
      if (stopped) return;
      type LeafletNs = {
        map: (el: HTMLElement, opts: object) => {
          setView: (c: [number, number], z: number) => unknown;
          remove: () => void;
        };
        tileLayer: (url: string, opts: object) => { addTo: (map: unknown) => void };
        polyline: (pts: Array<[number, number]>, opts: object) => { addTo: (map: unknown) => void };
        marker: (at: [number, number], opts: object) => {
          addTo: (map: unknown) => { on: (ev: string, fn: () => void) => void; getElement: () => HTMLElement | undefined };
        };
        divIcon: (opts: object) => unknown;
      };
      const loaded = mod as unknown as LeafletNs & { default?: LeafletNs };
      const leaflet = loaded.default ?? loaded;
      const created = leaflet.map(element, { zoomControl: true, attributionControl: true });
      created.setView([lat, lon], zoom);
      leaflet.tileLayer(tileUrl, { attribution, maxZoom: 19 }).addTo(created);
      for (const line of lines) leaflet.polyline(line.points, { color: line.color, weight: 3, opacity: 0.85 }).addTo(created);
      const nodes = new Map<string, HTMLElement | undefined>();
      for (const pin of pins) {
        const marker = leaflet.marker([pin.lat, pin.lon], {
          icon: leaflet.divIcon({ className: "oip-leaf-icon", html: chip(pin, undefined), iconSize: [108, 52], iconAnchor: [54, 52] }),
        }).addTo(created);
        marker.on("click", () => { if (pin.href) navigate?.(pin.href); });
        nodes.set(pin.path || pin.id, marker.getElement());
      }
      map = created;
      const paths = pins.map((pin) => pin.path).filter(Boolean);
      const paint = (values: Record<string, number>) => {
        for (const pin of pins) {
          const node = nodes.get(pin.path || pin.id);
          if (!node) continue;
          const shell = node.querySelector(".oip-leaf");
          if (shell) shell.outerHTML = chip(pin, pin.path ? values[pin.path] : undefined);
        }
      };
      const read = async () => {
        if (!paths.length || stopped) return;
        try {
          const body = await client.post("/api/v1/tags/read", { paths }) as { values?: Array<{ path?: string; value?: unknown }> };
          const values: Record<string, number> = {};
          for (const row of body.values ?? []) {
            if (row && typeof row.path === "string" && typeof row.value === "number") values[row.path] = row.value;
          }
          if (!stopped) paint(values);
        } catch {
          // Keep the last gauge reading.
        }
        if (!stopped) timer = setTimeout(() => void read(), 2000);
      };
      void read();
    }).catch(() => {
      if (element) element.dataset.error = "map library failed to load";
    });
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      map?.remove();
    };
  }, [client, lat, lon, zoom, tileUrl, attribution, markerText, lineText, navigate]);

  return <div className="oip-leaflet" ref={host} role="application" aria-label={readString(props, "label", "Map")} />;
}
