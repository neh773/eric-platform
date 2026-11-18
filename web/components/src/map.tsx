import { useEffect, useMemo, useRef, useState } from "react";
import type { PointerEvent as ReactPointerEvent } from "react";
import type { HmiComponentProps, JsonSchema } from "@oip/hmi-runtime";
import { readList } from "./containers";
import { mediaSource } from "./media";
import { useGatewayClient } from "./gateway";
import { readBoolean, readNumber, readString } from "./read";

/**
 * Interactive map (HMP-045): a slippy map with bindable centre and zoom, a marker list with popups and click output,
 * tile layers and overlay layers with a layer toggle, and zoom controls. Written from the public component page; the
 * code, the tile maths and the wording are our own, and no map library is used.
 * doc source: FEATURES HMP-045
 *
 * Tiles come only from the tile URL templates the designer configures. `tileUrl` and each `tileLayers` entry's `url`
 * use `{z}`, `{x}` and `{y}` (and `{s}`, replaced by `a`), and there is no default pointing at any tile provider: with
 * no template the map shows a plain grid. A tile address goes through the HMI's one URL filter ([mediaSource]), so
 * only a path on this origin or an http(s) address loads. Popup, title and layer text is always plain text.
 *
 * Interaction: drag to pan, wheel or the +/- buttons (or the + and - keys) to zoom, click a marker to select it and
 * open its popup, click empty map to report the clicked position. The component writes back `centerLat`,
 * `centerLon`, `zoom`, `selectedMarker` (the marker id, "" when none) and `clickLat` / `clickLon`, so bindings and
 * the component's own events can react. A host that provides `onEvent` also gets `markerClick` and `mapClick`.
 */

const TILE = 256;
const MAX_LAT = 85.0511287798;
const DEFAULT_SIZE = { width: 480, height: 320 };

export const mapSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Map",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Accessible label", default: "Map" },
    centerLat: { type: "number", title: "Centre latitude", default: 0, minimum: -85, maximum: 85 },
    centerLon: { type: "number", title: "Centre longitude", default: 0, minimum: -180, maximum: 180 },
    zoom: { type: "number", title: "Zoom", default: 2, minimum: 0, maximum: 22 },
    minZoom: { type: "number", title: "Minimum zoom", default: 0, minimum: 0, maximum: 22 },
    maxZoom: { type: "number", title: "Maximum zoom", default: 18, minimum: 0, maximum: 22 },
    tileUrl: { type: "string", title: "Base tile URL template ({z}, {x}, {y}); empty shows a grid", default: "" },
    tileAttribution: { type: "string", title: "Base tile attribution", default: "" },
    tileLayers: { type: "string", title: "Tile layers (JSON array of {name, url, overlay, visible, opacity, attribution})", default: "[]" },
    markers: { type: "string", title: "Markers (JSON array of {id, lat, lon, title, popup, color, layer, path, max, high, unit, href})", default: "[]" },
    shapes: { type: "string", title: "Overlay shapes (JSON array of {layer, type, points, center, radius, color})", default: "[]" },
    showZoomControls: { type: "boolean", title: "Show zoom controls", default: true },
    showLayerControl: { type: "boolean", title: "Show layer control", default: true },
    selectedMarker: { type: "string", title: "Selected marker id (output)", default: "" },
    clickLat: { type: "number", title: "Last clicked latitude (output)", default: 0 },
    clickLon: { type: "number", title: "Last clicked longitude (output)", default: 0 },
  },
};

// ---------------------------------------------------------------- maths

export function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value));
}

function wrapLon(lon: number): number {
  return ((((lon + 180) % 360) + 360) % 360) - 180;
}

/** World pixel position of a coordinate at [zoom] (Web Mercator, 256 px tiles at zoom 0). */
export function project(lat: number, lon: number, zoom: number): { x: number; y: number } {
  const size = TILE * 2 ** zoom;
  const phi = (clamp(lat, -MAX_LAT, MAX_LAT) * Math.PI) / 180;
  return {
    x: ((lon + 180) / 360) * size,
    y: (0.5 - Math.log(Math.tan(Math.PI / 4 + phi / 2)) / (2 * Math.PI)) * size,
  };
}

/** The coordinate under a world pixel position at [zoom]. Inverse of [project]. */
export function unproject(x: number, y: number, zoom: number): { lat: number; lon: number } {
  const size = TILE * 2 ** zoom;
  const lon = (x / size) * 360 - 180;
  const n = Math.PI - (2 * Math.PI * y) / size;
  const lat = (Math.atan(Math.sinh(n)) * 180) / Math.PI;
  return { lat: clamp(lat, -MAX_LAT, MAX_LAT), lon: wrapLon(lon) };
}

/** A tile address from a template. `{s}` becomes `a`. */
export function tileAddress(template: string, z: number, x: number, y: number): string {
  return template.replace(/\{z\}/g, String(z)).replace(/\{x\}/g, String(x)).replace(/\{y\}/g, String(y)).replace(/\{s\}/g, "a");
}

// ---------------------------------------------------------------- props

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function text(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function finite(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

export interface MapLayer {
  name: string;
  url: string;
  overlay: boolean;
  visible: boolean;
  opacity: number;
  attribution: string;
}

export interface MapMarker {
  id: string;
  lat: number;
  lon: number;
  title: string;
  popup: string;
  color: string;
  layer: string;
  /** Tag whose number is drawn on the marker. */
  path: string;
  max: number;
  high: number;
  unit: string;
  /** Page path opened when the marker is tapped, such as `/station/CinderFlat`. */
  href: string;
}

export interface MapShape {
  layer: string;
  type: "polyline" | "polygon" | "circle";
  points: Array<[number, number]>;
  center: [number, number] | null;
  radius: number;
  color: string;
}

/** The tile layers: the base template first (when set), then the listed layers. Entries without a name or url are dropped. */
export function readLayers(props: Record<string, unknown>): MapLayer[] {
  const layers: MapLayer[] = [];
  const base = readString(props, "tileUrl", "").trim();
  if (base) {
    layers.push({ name: "Base", url: base, overlay: false, visible: true, opacity: 1, attribution: readString(props, "tileAttribution", "") });
  }
  for (const entry of readList(props.tileLayers)) {
    if (!isRecord(entry)) continue;
    const name = text(entry.name).trim();
    const url = text(entry.url).trim();
    if (!name || !url || layers.some((layer) => layer.name === name)) continue;
    layers.push({
      name,
      url,
      overlay: entry.overlay === true,
      visible: entry.visible !== false,
      opacity: clamp(finite(entry.opacity) ?? 1, 0, 1),
      attribution: text(entry.attribution),
    });
  }
  return layers;
}

export function readMarkers(value: unknown): MapMarker[] {
  const markers: MapMarker[] = [];
  readList(value).forEach((entry, index) => {
    if (!isRecord(entry)) return;
    const lat = finite(entry.lat);
    const lon = finite(entry.lon);
    if (lat === null || lon === null || Math.abs(lat) > 90 || Math.abs(lon) > 180) return;
    const id = text(entry.id) || `m${index}`;
    if (markers.some((marker) => marker.id === id)) return;
    markers.push({
      id, lat, lon, title: text(entry.title), popup: text(entry.popup), color: text(entry.color), layer: text(entry.layer),
      path: text(entry.path), max: finite(entry.max) ?? 100, high: finite(entry.high) ?? Number.POSITIVE_INFINITY,
      unit: text(entry.unit), href: text(entry.href),
    });
  });
  return markers;
}

function point(value: unknown): [number, number] | null {
  if (!Array.isArray(value) || value.length < 2) return null;
  const lat = finite(value[0]);
  const lon = finite(value[1]);
  return lat === null || lon === null || Math.abs(lat) > 90 || Math.abs(lon) > 180 ? null : [lat, lon];
}

export function readShapes(value: unknown): MapShape[] {
  const shapes: MapShape[] = [];
  for (const entry of readList(value)) {
    if (!isRecord(entry)) continue;
    const type = entry.type;
    if (type !== "polyline" && type !== "polygon" && type !== "circle") continue;
    const points = (Array.isArray(entry.points) ? entry.points : []).map(point).filter((p): p is [number, number] => p !== null);
    const center = point(entry.center);
    const radius = finite(entry.radius) ?? 0;
    if (type === "circle" ? !center || radius <= 0 : points.length < 2) continue;
    shapes.push({ layer: text(entry.layer), type, points, center, radius, color: text(entry.color) });
  }
  return shapes;
}

/** A colour the map will use as given: a token, hex or functional colour. Anything else falls back to the accent. */
function safeColor(value: string, fallback: string): string {
  return /^(#[0-9a-fA-F]{3,8}|var\(--[A-Za-z0-9-]+\)|[a-zA-Z]{3,20}|(rgb|hsl)a?\([0-9 ,.%]+\))$/.test(value) ? value : fallback;
}

// ---------------------------------------------------------------- component

export function MapView({ props, onChange, onEvent, navigate }: HmiComponentProps) {
  const label = readString(props, "label", "Map");
  const minZoom = clamp(Math.round(readNumber(props, "minZoom", 0)), 0, 22);
  const maxZoom = clamp(Math.round(readNumber(props, "maxZoom", 18)), minZoom, 22);
  const propLat = clamp(readNumber(props, "centerLat", 0), -MAX_LAT, MAX_LAT);
  const propLon = wrapLon(readNumber(props, "centerLon", 0));
  const propZoom = clamp(readNumber(props, "zoom", 2), minZoom, maxZoom);
  const showZoom = readBoolean(props, "showZoomControls", true);
  const showLayerControl = readBoolean(props, "showLayerControl", true);
  const selected = readString(props, "selectedMarker", "");

  const [center, setCenter] = useState({ lat: propLat, lon: propLon });
  const [zoom, setZoom] = useState(propZoom);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const [openPopup, setOpenPopup] = useState(selected);
  const [toggles, setToggles] = useState<Record<string, boolean>>({});
  const [base, setBase] = useState<string | null>(null);
  const frame = useRef<HTMLDivElement>(null);
  const drag = useRef<{ x: number; y: number; moved: boolean; lat: number; lon: number; zoom: number } | null>(null);
  const pins = useRef(new Map<number, { x: number; y: number }>());
  const pinchDist = useRef(0);

  // Bound centre and zoom win when they change.
  useEffect(() => setCenter({ lat: propLat, lon: propLon }), [propLat, propLon]);
  useEffect(() => setZoom(propZoom), [propZoom]);
  useEffect(() => setOpenPopup(selected), [selected]);

  useEffect(() => {
    const element = frame.current;
    if (!element) return;
    const measure = () => setSize({ width: element.clientWidth, height: element.clientHeight });
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const layers = useMemo(() => readLayers(props), [props.tileUrl, props.tileAttribution, props.tileLayers]);
  const markers = useMemo(() => readMarkers(props.markers), [props.markers]);
  const client = useGatewayClient();
  const [live, setLive] = useState<Record<string, number>>({});
  const liveKey = markers.map((marker) => marker.path).filter(Boolean).join("\n");
  useEffect(() => {
    const paths = liveKey ? liveKey.split("\n") : [];
    if (!paths.length) return undefined;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const read = async () => {
      try {
        const body = await client.post("/api/v1/tags/read", { paths }) as { values?: Array<{ path?: string; value?: unknown }> };
        if (!stopped) {
          const next: Record<string, number> = {};
          for (const row of body.values ?? []) {
            if (row && typeof row.path === "string" && typeof row.value === "number" && Number.isFinite(row.value)) next[row.path] = row.value;
          }
          setLive(next);
        }
      } catch {
        // Keep the last reading when a poll fails.
      }
      if (!stopped) timer = setTimeout(() => void read(), 2000);
    };
    void read();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
    };
  }, [client, liveKey]);
  const shapes = useMemo(() => readShapes(props.shapes), [props.shapes]);

  const baseLayers = layers.filter((layer) => !layer.overlay);
  const overlayLayers = layers.filter((layer) => layer.overlay);
  const activeBase = baseLayers.find((layer) => layer.name === base) ?? baseLayers.find((layer) => layer.visible) ?? null;
  const overlayOn = (layer: MapLayer) => toggles[`tile:${layer.name}`] ?? layer.visible;
  const vectorNames = [...new Set([...markers.map((marker) => marker.layer), ...shapes.map((shape) => shape.layer)].filter((name) => name !== ""))];
  const vectorOn = (name: string) => toggles[`vector:${name}`] ?? true;
  const shownVector = (name: string) => name === "" || vectorOn(name);

  const width = size.width || DEFAULT_SIZE.width;
  const height = size.height || DEFAULT_SIZE.height;
  const tileZoom = Math.floor(zoom);
  const scale = 2 ** (zoom - tileZoom);
  const tileSize = TILE * scale;
  const middle = project(center.lat, center.lon, zoom);
  const originX = middle.x - width / 2;
  const originY = middle.y - height / 2;
  const toScreen = (lat: number, lon: number) => {
    const p = project(lat, lon, zoom);
    return { x: p.x - originX, y: p.y - originY };
  };

  const tilesFor = (layer: MapLayer) => {
    const count = 2 ** tileZoom;
    const x0 = Math.floor(originX / tileSize);
    const y0 = Math.floor(originY / tileSize);
    const x1 = Math.floor((originX + width) / tileSize);
    const y1 = Math.floor((originY + height) / tileSize);
    const tiles: Array<{ key: string; src: string; left: number; top: number }> = [];
    for (let ty = y0; ty <= y1; ty++) {
      if (ty < 0 || ty >= count) continue;
      for (let tx = x0; tx <= x1; tx++) {
        const wrapped = ((tx % count) + count) % count;
        const src = mediaSource(tileAddress(layer.url, tileZoom, wrapped, ty));
        if (!src) continue;
        tiles.push({ key: `${layer.name}/${tileZoom}/${tx}/${ty}`, src, left: tx * tileSize - originX, top: ty * tileSize - originY });
      }
    }
    return tiles;
  };

  const commitView = (lat: number, lon: number, nextZoom: number) => {
    onChange("centerLat", Number(lat.toFixed(6)));
    onChange("centerLon", Number(lon.toFixed(6)));
    onChange("zoom", nextZoom);
  };

  const zoomTo = (next: number) => {
    const value = clamp(next, minZoom, maxZoom);
    if (value === zoom) return;
    setZoom(value);
    commitView(center.lat, center.lon, value);
  };

  const onPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0 && event.pointerType === "mouse") return;
    pins.current.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (pins.current.size >= 2) {
      const pts = [...pins.current.values()];
      pinchDist.current = Math.hypot(pts[0]!.x - pts[1]!.x, pts[0]!.y - pts[1]!.y);
      drag.current = null;
      return;
    }
    drag.current = { x: event.clientX, y: event.clientY, moved: false, lat: center.lat, lon: center.lon, zoom };
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };
  const onPointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (pins.current.has(event.pointerId)) pins.current.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (pins.current.size >= 2) {
      const pts = [...pins.current.values()];
      const dist = Math.hypot(pts[0]!.x - pts[1]!.x, pts[0]!.y - pts[1]!.y);
      if (pinchDist.current > 0 && dist > 0) zoomTo(zoom + Math.log2(dist / pinchDist.current));
      pinchDist.current = dist;
      return;
    }
    const d = drag.current;
    if (!d) return;
    const dx = event.clientX - d.x;
    const dy = event.clientY - d.y;
    if (!d.moved && Math.hypot(dx, dy) < 4) return;
    d.moved = true;
    const start = project(d.lat, d.lon, d.zoom);
    setCenter(unproject(start.x - dx, clamp(start.y - dy, 0, TILE * 2 ** d.zoom), d.zoom));
  };
  const onPointerUp = (event: ReactPointerEvent<HTMLDivElement>) => {
    pins.current.delete(event.pointerId);
    pinchDist.current = 0;
    const d = drag.current;
    drag.current = null;
    if (!d) return;
    if (d.moved) {
      const dx = event.clientX - d.x;
      const dy = event.clientY - d.y;
      const start = project(d.lat, d.lon, d.zoom);
      const next = unproject(start.x - dx, clamp(start.y - dy, 0, TILE * 2 ** d.zoom), d.zoom);
      setCenter(next);
      commitView(next.lat, next.lon, zoom);
      return;
    }
    // A click on empty map: report where, close the popup.
    const box = event.currentTarget.getBoundingClientRect();
    const at = unproject(originX + (event.clientX - box.left), originY + (event.clientY - box.top), zoom);
    setOpenPopup("");
    onChange("selectedMarker", "");
    onChange("clickLat", Number(at.lat.toFixed(6)));
    onChange("clickLon", Number(at.lon.toFixed(6)));
    onEvent?.("mapClick");
  };

  const selectMarker = (marker: MapMarker) => {
    if (marker.href) {
      navigate?.(marker.href);
      return;
    }
    const next = openPopup === marker.id ? "" : marker.id;
    setOpenPopup(next);
    onChange("selectedMarker", next);
    onEvent?.("markerClick");
  };

  const attributions = [...baseLayers.filter((l) => l === activeBase), ...overlayLayers.filter(overlayOn)]
    .map((layer) => layer.attribution)
    .filter((value) => value !== "");
  const popupMarker = markers.find((marker) => marker.id === openPopup && shownVector(marker.layer));
  const accent = "var(--oip-color-accent)";
  const metersPerPixel = (lat: number) => (Math.cos((lat * Math.PI) / 180) * 2 * Math.PI * 6378137) / (TILE * 2 ** zoom);

  return (
    <div
      className="oip-map"
      ref={frame}
      role="application"
      aria-label={label}
      tabIndex={0}
      data-zoom={zoom}
      data-center={`${center.lat.toFixed(5)},${center.lon.toFixed(5)}`}
      data-has-tiles={activeBase || overlayLayers.some(overlayOn) ? "true" : "false"}
      onKeyDown={(event) => {
        if (event.key === "+" || event.key === "=") zoomTo(Math.round(zoom) + 1);
        else if (event.key === "-" || event.key === "_") zoomTo(Math.round(zoom) - 1);
      }}
      onWheel={(event) => zoomTo(Math.round(zoom) + (event.deltaY < 0 ? 1 : -1))}
    >
      <div
        className="oip-map-surface"
        data-testid="map-surface"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={() => (drag.current = null)}
      >
        {[...(activeBase ? [activeBase] : []), ...overlayLayers.filter(overlayOn)].map((layer) => (
          <div key={layer.name} className="oip-map-tiles" data-layer={layer.name} style={{ opacity: layer.opacity }}>
            {tilesFor(layer).map((tile) => (
              <img
                key={tile.key}
                alt=""
                draggable={false}
                src={tile.src}
                referrerPolicy="no-referrer"
                style={{ left: tile.left, top: tile.top, width: tileSize, height: tileSize }}
              />
            ))}
          </div>
        ))}
        <svg className="oip-map-shapes" width={width} height={height} aria-hidden="true">
          {shapes.filter((shape) => shownVector(shape.layer)).map((shape, index) => {
            const color = safeColor(shape.color, accent);
            if (shape.type === "circle" && shape.center) {
              const c = toScreen(shape.center[0], shape.center[1]);
              return <circle key={index} data-shape="circle" cx={c.x} cy={c.y} r={shape.radius / metersPerPixel(shape.center[0])} fill={color} fillOpacity={0.2} stroke={color} strokeWidth={2} />;
            }
            const pts = shape.points.map(([lat, lon]) => {
              const p = toScreen(lat, lon);
              return `${p.x},${p.y}`;
            }).join(" ");
            return shape.type === "polygon" ? (
              <polygon key={index} data-shape="polygon" points={pts} fill={color} fillOpacity={0.2} stroke={color} strokeWidth={2} />
            ) : (
              <polyline key={index} data-shape="polyline" points={pts} fill="none" stroke={color} strokeWidth={2} />
            );
          })}
        </svg>
        {markers.filter((marker) => shownVector(marker.layer)).map((marker) => {
          const p = toScreen(marker.lat, marker.lon);
          const visible = p.x > -20 && p.y > -40 && p.x < width + 20 && p.y < height + 20;
          if (!visible) return null;
          const reading = marker.path ? live[marker.path] : undefined;
          const alarmed = reading !== undefined && reading >= marker.high;
          const full = marker.max > 0 && reading !== undefined ? Math.max(0, Math.min(1, reading / marker.max)) : 0;
          const shown = reading === undefined ? "" : `${reading.toFixed(1)}${marker.unit ? ` ${marker.unit}` : ""}`;
          return (
            <button
              key={marker.id}
              type="button"
              className={marker.path ? "oip-map-marker oip-map-gauge" : "oip-map-marker"}
              data-marker={marker.id}
              data-alarm={alarmed ? "true" : "false"}
              aria-label={shown ? `${marker.title || marker.id}, ${shown}` : marker.title || marker.id}
              aria-pressed={openPopup === marker.id}
              style={{ left: p.x, top: p.y, background: alarmed ? "#c4473a" : safeColor(marker.color, accent) }}
              onPointerDown={(event) => event.stopPropagation()}
              onPointerUp={(event) => event.stopPropagation()}
              onClick={(event) => {
                event.stopPropagation();
                selectMarker(marker);
              }}
            >
              {marker.path ? (
                <>
                  <i className="oip-map-level" style={{ height: `${Math.round(full * 100)}%` }} />
                  <b>{marker.title || marker.id}</b>
                  <span>{shown || "—"}</span>
                </>
              ) : null}
            </button>
          );
        })}
        {popupMarker ? (
          (() => {
            const p = toScreen(popupMarker.lat, popupMarker.lon);
            return (
              <div className="oip-map-popup" role="dialog" aria-label={popupMarker.title || popupMarker.id} style={{ left: p.x, top: p.y }} onPointerDown={(event) => event.stopPropagation()} onPointerUp={(event) => event.stopPropagation()}>
                {popupMarker.title ? <strong>{popupMarker.title}</strong> : null}
                {popupMarker.path && live[popupMarker.path] !== undefined ? <div>{`${live[popupMarker.path]!.toFixed(1)}${popupMarker.unit ? ` ${popupMarker.unit}` : ""}`}</div> : null}
                {popupMarker.popup ? <div>{popupMarker.popup}</div> : null}
                <button
                  type="button"
                  aria-label="Close popup"
                  onClick={(event) => {
                    event.stopPropagation();
                    setOpenPopup("");
                    onChange("selectedMarker", "");
                  }}
                >
                  ×
                </button>
              </div>
            );
          })()
        ) : null}
        {!activeBase && !overlayLayers.some(overlayOn) ? <div className="oip-map-empty">No tile source</div> : null}
      </div>
      {showZoom ? (
        <div className="oip-map-zoom" role="group" aria-label="Zoom">
          <button type="button" aria-label="Zoom in" disabled={zoom >= maxZoom} onClick={() => zoomTo(Math.round(zoom) + 1)}>
            +
          </button>
          <button type="button" aria-label="Zoom out" disabled={zoom <= minZoom} onClick={() => zoomTo(Math.round(zoom) - 1)}>
            −
          </button>
        </div>
      ) : null}
      {showLayerControl && (baseLayers.length > 1 || overlayLayers.length > 0 || vectorNames.length > 0) ? (
        <fieldset className="oip-map-layers">
          <legend>Layers</legend>
          {baseLayers.length > 1
            ? baseLayers.map((layer) => (
                <label key={`base:${layer.name}`}>
                  <input type="radio" name="oip-map-base" checked={activeBase?.name === layer.name} onChange={() => setBase(layer.name)} /> {layer.name}
                </label>
              ))
            : null}
          {overlayLayers.map((layer) => (
            <label key={`tile:${layer.name}`}>
              <input type="checkbox" checked={overlayOn(layer)} onChange={(event) => setToggles((prev) => ({ ...prev, [`tile:${layer.name}`]: event.target.checked }))} /> {layer.name}
            </label>
          ))}
          {vectorNames.map((name) => (
            <label key={`vector:${name}`}>
              <input type="checkbox" checked={vectorOn(name)} onChange={(event) => setToggles((prev) => ({ ...prev, [`vector:${name}`]: event.target.checked }))} /> {name}
            </label>
          ))}
        </fieldset>
      ) : null}
      {attributions.length > 0 ? <div className="oip-map-attribution">{attributions.join(" | ")}</div> : null}
    </div>
  );
}
