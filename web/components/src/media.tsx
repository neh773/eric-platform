import { useEffect, useId, useLayoutEffect, useMemo, useRef, type SyntheticEvent } from "react";
import { linkTarget, type HmiComponentProps, type JsonSchema } from "@oip/hmi-runtime";
import { allowedImageSource } from "./extra";
import { readBoolean, readNumber, readString } from "./read";

/**
 * Display palette media components: drawing (HMP-031), PDF viewer (HMP-033, HMV-069), audio (HMP-035) and video
 * player (HMP-042).
 *
 * Every media URL goes through [mediaSource], which is the HMI's one URL filter ([linkTarget]): a path on this
 * origin (a project resource the gateway serves) or an http(s) URL. `javascript:`, `data:`, `blob:` and every other
 * scheme are refused and the component shows an empty placeholder. The video poster is an image and follows the
 * image component's rule, which also allows `data:image/*`.
 *
 * The drawing holds SVG markup. It is never put in the page as markup: it is parsed as XML and copied element by
 * element into new SVG nodes, keeping only drawing elements and presentation attributes. Scripts, event handler
 * attributes, `style`, `foreignObject`, images, links, SMIL animation and external `href`/`url()` references are
 * dropped. Its `elements` property overrides attributes of elements by their `id`, so a binding on, say,
 * `elements.level.height` animates one shape.
 *
 * Doc sources: FEATURES HMP-031, HMP-033, HMP-035, HMP-042, HMV-069.
 */

/** A media URL the components may load, or null. A same-origin path or an http(s) URL. */
export function mediaSource(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const target = linkTarget(value);
  if (target.kind === "page") return target.path;
  if (target.kind === "external") return target.url;
  return null;
}

// ---------------------------------------------------------------------------------------------------------------
// Drawing

const SVG_NS = "http://www.w3.org/2000/svg";

/** Whether an SVG element name is one the drawing keeps. */
export function drawingElementAllowed(name: string): boolean {
  return DRAWING_ELEMENTS.has(name);
}

const DRAWING_ELEMENTS = new Set([
  "svg",
  "g",
  "defs",
  "title",
  "desc",
  "path",
  "rect",
  "circle",
  "ellipse",
  "line",
  "polyline",
  "polygon",
  "text",
  "tspan",
  "linearGradient",
  "radialGradient",
  "stop",
  "clipPath",
  "mask",
  "pattern",
  "symbol",
  "use",
  "marker",
]);

/** Geometry and presentation attributes. Anything else (`on*`, `style`, `href` to another document) is dropped. */
const DRAWING_ATTRIBUTES = new Set([
  "id",
  "class",
  "transform",
  "viewBox",
  "preserveAspectRatio",
  "width",
  "height",
  "x",
  "y",
  "x1",
  "y1",
  "x2",
  "y2",
  "cx",
  "cy",
  "r",
  "rx",
  "ry",
  "fx",
  "fy",
  "d",
  "points",
  "pathLength",
  "fill",
  "fill-opacity",
  "fill-rule",
  "stroke",
  "stroke-width",
  "stroke-opacity",
  "stroke-linecap",
  "stroke-linejoin",
  "stroke-dasharray",
  "stroke-dashoffset",
  "stroke-miterlimit",
  "opacity",
  "visibility",
  "display",
  "color",
  "font-family",
  "font-size",
  "font-weight",
  "font-style",
  "text-anchor",
  "dominant-baseline",
  "letter-spacing",
  "dx",
  "dy",
  "rotate",
  "textLength",
  "lengthAdjust",
  "offset",
  "stop-color",
  "stop-opacity",
  "gradientUnits",
  "gradientTransform",
  "spreadMethod",
  "clip-path",
  "clip-rule",
  "clipPathUnits",
  "mask",
  "maskUnits",
  "maskContentUnits",
  "patternUnits",
  "patternContentUnits",
  "patternTransform",
  "marker-start",
  "marker-mid",
  "marker-end",
  "markerWidth",
  "markerHeight",
  "markerUnits",
  "refX",
  "refY",
  "orient",
  "vector-effect",
  "href",
]);

const LOCAL_REFERENCE = /^url\(\s*#([A-Za-z_][\w.-]*)\s*\)$/;
const LOCAL_HREF = /^#([A-Za-z_][\w.-]*)$/;
const ID = /^[A-Za-z_][\w.-]*$/;

/**
 * The attribute value kept for [name], or null to drop it. Values referring to another element (`url(#id)`, `#id`)
 * are kept only when local and are renamed with [prefix], so two drawings on a page do not share ids. Any other
 * `url(`, and anything that could name a script, is dropped.
 */
export function drawingAttribute(name: string, raw: unknown, prefix: string): string | null {
  if (!DRAWING_ATTRIBUTES.has(name)) return null;
  if (typeof raw !== "string" && !(typeof raw === "number" && Number.isFinite(raw))) return null;
  const value = String(raw).trim();
  if (value.length > 100_000) return null;
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code <= 0x08 || (code >= 0x0e && code <= 0x1f) || code === 0x7f) return null;
  }
  if (name === "id") return ID.test(value) ? `${prefix}${value}` : null;
  if (name === "href") {
    const local = LOCAL_HREF.exec(value);
    return local ? `#${prefix}${local[1]!}` : null;
  }
  if (/url\s*\(/i.test(value)) {
    const local = LOCAL_REFERENCE.exec(value);
    return local ? `url(#${prefix}${local[1]!})` : null;
  }
  if (/script\s*:|[<>]/i.test(value)) return null;
  return value;
}

export type DrawingOverrides = Record<string, Record<string, unknown>>;

/** The `elements` property as id -> attribute overrides; anything else is ignored. */
export function readOverrides(value: unknown): DrawingOverrides {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const overrides: DrawingOverrides = {};
  for (const [id, attrs] of Object.entries(value as Record<string, unknown>)) {
    if (attrs && typeof attrs === "object" && !Array.isArray(attrs)) overrides[id] = attrs as Record<string, unknown>;
  }
  return overrides;
}

export type BuiltDrawing = { ok: true; svg: SVGSVGElement } | { ok: false; reason: string };

/**
 * Parses [markup] and copies its drawing elements into new nodes of [doc]. Element ids get [prefix]; [overrides]
 * (by the original id) replace or add attributes, through the same filter as the markup's own.
 */
export interface BuildExtras {
  /** Text content by element id, for `text` and `tspan` elements (an SVG component's text parameters). */
  texts?: Record<string, string>;
  /**
   * A `fill` or `stroke` override on a group also repaints the shapes inside it that paint themselves explicitly
   * (not `none`, not a gradient or pattern), so a whole imported group changes colour with one value.
   */
  cascade?: boolean;
}

type GroupPaint = { fill?: string; stroke?: string };

function explicitPaint(value: string | null): boolean {
  if (value === null) return false;
  const paint = value.trim().toLowerCase();
  return paint !== "" && paint !== "none" && paint !== "transparent" && paint !== "inherit" && !paint.startsWith("url(");
}

export function buildDrawing(doc: Document, markup: string, prefix: string, overrides: DrawingOverrides = {}, extras: BuildExtras = {}): BuiltDrawing {
  if (!markup.trim()) return { ok: false, reason: "No drawing" };
  let parsed = new DOMParser().parseFromString(markup, "image/svg+xml");
  // Markup written without `xmlns` is read as SVG too: the namespace is added to the root and the markup read again.
  if (parsed.documentElement?.localName === "svg" && !parsed.documentElement.namespaceURI) {
    const withNamespace = markup.replace(/^(\s*(?:<\?xml[^>]*\?>\s*)?<svg)(?=[\s>/])/, `$1 xmlns="${SVG_NS}"`);
    parsed = new DOMParser().parseFromString(withNamespace, "image/svg+xml");
  }
  const root = parsed.documentElement;
  if (!root || root.getElementsByTagName("parsererror").length > 0 || root.localName === "parsererror") {
    return { ok: false, reason: "The drawing is not valid SVG" };
  }
  if (root.localName !== "svg" || root.namespaceURI !== SVG_NS) return { ok: false, reason: "The drawing is not valid SVG" };

  const copy = (source: Element, inherited: GroupPaint = {}): Element | null => {
    if (source.namespaceURI !== SVG_NS || !DRAWING_ELEMENTS.has(source.localName)) return null;
    const target = doc.createElementNS(SVG_NS, source.localName);
    const set = (name: string, raw: unknown) => {
      const value = drawingAttribute(name, raw, prefix);
      if (value !== null) target.setAttribute(name, value);
    };
    for (const attribute of Array.from(source.attributes)) {
      // `xlink:href` is read as `href`; other namespaced attributes are dropped.
      if (attribute.namespaceURI === "http://www.w3.org/1999/xlink" && attribute.localName === "href") set("href", attribute.value);
      else if (!attribute.namespaceURI) set(attribute.name, attribute.value);
    }
    const id = source.getAttribute("id");
    if (id && Object.prototype.hasOwnProperty.call(overrides, id)) {
      for (const [name, raw] of Object.entries(overrides[id]!)) {
        if (name === "id") continue;
        set(name, raw);
      }
    }
    let paint = inherited;
    if (extras.cascade) {
      const own: GroupPaint = {};
      if (id && Object.prototype.hasOwnProperty.call(overrides, id)) {
        const fill = drawingAttribute("fill", overrides[id]!.fill, prefix);
        const stroke = drawingAttribute("stroke", overrides[id]!.stroke, prefix);
        if (fill !== null) own.fill = fill;
        if (stroke !== null) own.stroke = stroke;
      }
      paint = { ...inherited, ...own };
      const hasOwn = id !== null && Object.prototype.hasOwnProperty.call(overrides, id);
      // A group's own override is already set above; a descendant that paints itself explicitly takes the group's paint,
      // unless its own id has an override of its own.
      if (inherited.fill !== undefined && !(hasOwn && overrides[id!]!.fill !== undefined) && explicitPaint(source.getAttribute("fill"))) target.setAttribute("fill", inherited.fill);
      if (inherited.stroke !== undefined && !(hasOwn && overrides[id!]!.stroke !== undefined) && explicitPaint(source.getAttribute("stroke"))) target.setAttribute("stroke", inherited.stroke);
    }
    const textOverride = id !== null && extras.texts && Object.prototype.hasOwnProperty.call(extras.texts, id) && (source.localName === "text" || source.localName === "tspan") ? extras.texts[id] : undefined;
    if (textOverride !== undefined) {
      target.appendChild(doc.createTextNode(String(textOverride)));
      return target;
    }
    for (const child of Array.from(source.childNodes)) {
      if (child.nodeType === 3) {
        if (source.localName === "text" || source.localName === "tspan" || source.localName === "title" || source.localName === "desc") {
          target.appendChild(doc.createTextNode(child.textContent ?? ""));
        }
      } else if (child.nodeType === 1) {
        const element = copy(child as Element, paint);
        if (element) target.appendChild(element);
      }
    }
    return target;
  };

  const svg = copy(root) as SVGSVGElement | null;
  if (!svg) return { ok: false, reason: "The drawing is not valid SVG" };
  if (!svg.hasAttribute("viewBox")) {
    const width = Number.parseFloat(root.getAttribute("width") ?? "");
    const height = Number.parseFloat(root.getAttribute("height") ?? "");
    if (width > 0 && height > 0) svg.setAttribute("viewBox", `0 0 ${String(width)} ${String(height)}`);
  }
  return { ok: true, svg };
}

const FIT_ASPECT: Record<string, string> = { contain: "xMidYMid meet", cover: "xMidYMid slice", fill: "none" };

const DEFAULT_DRAWING =
  '<svg viewBox="0 0 100 100"><rect id="body" x="20" y="8" width="60" height="84" rx="6" fill="#d9dee3" stroke="#4a5560" stroke-width="2"/>' +
  '<rect id="level" x="22" y="50" width="56" height="40" fill="#3d7ab8"/></svg>';

export const drawingSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Drawing",
  additionalProperties: false,
  properties: {
    svg: { type: "string", title: "SVG markup", default: DEFAULT_DRAWING },
    elements: { type: "object", title: "Element overrides (by id)", description: "{ \"id\": { \"fill\": \"#c00\" } }", default: {} },
    alt: { type: "string", title: "Alt text", default: "Drawing" },
    fit: { type: "string", title: "Fit", enum: ["contain", "cover", "fill"], default: "contain" },
  },
};

export function Drawing({ props }: HmiComponentProps) {
  return (
    <DrawingView
      markup={readString(props, "svg")}
      overrides={readOverrides(props.elements)}
      alt={readString(props, "alt", "Drawing")}
      fit={typeof props.fit === "string" && props.fit in FIT_ASPECT ? props.fit : "contain"}
    />
  );
}

/** The drawing renderer shared by the drawing and the imported SVG component. */
export function DrawingView({ markup, overrides, alt, fit, extras }: { markup: string; overrides: DrawingOverrides; alt: string; fit: string; extras?: BuildExtras }) {
  const host = useRef<HTMLDivElement>(null);
  const prefix = `oipd${useId().replace(/[^A-Za-z0-9]/g, "")}-`;
  const overridesKey = JSON.stringify(overrides);
  const extrasKey = JSON.stringify(extras ?? {});
  const built = useMemo(
    () =>
      typeof document === "undefined"
        ? ({ ok: false, reason: "No drawing" } as BuiltDrawing)
        : buildDrawing(document, markup, prefix, JSON.parse(overridesKey) as DrawingOverrides, JSON.parse(extrasKey) as BuildExtras),
    [markup, prefix, overridesKey, extrasKey],
  );

  useLayoutEffect(() => {
    const element = host.current;
    if (!element) return;
    if (!built.ok) {
      element.replaceChildren();
      return;
    }
    built.svg.setAttribute("width", "100%");
    built.svg.setAttribute("height", "100%");
    built.svg.setAttribute("preserveAspectRatio", FIT_ASPECT[fit]!);
    built.svg.setAttribute("aria-hidden", "true");
    built.svg.setAttribute("focusable", "false");
    element.replaceChildren(built.svg);
  }, [built, fit]);

  return (
    <div className="oip-drawing" role="img" aria-label={alt} data-fit={fit} data-invalid={built.ok ? undefined : "true"} title={built.ok ? undefined : built.reason}>
      <div className="oip-drawing-canvas" ref={host} />
      {built.ok ? null : <span className="oip-media-empty">{built.reason}</span>}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------------------------
// PDF viewer

export const pdfViewerSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "PDF viewer",
  additionalProperties: false,
  properties: {
    source: { type: "string", title: "Source (path or http(s) URL)", default: "" },
    page: { type: "integer", title: "Page", minimum: 1, default: 1 },
    toolbar: { type: "boolean", title: "Show toolbar", default: true },
    title: { type: "string", title: "Title", default: "PDF document" },
  },
};

/** The frame URL for [source]: the source with `#page=N` (and `toolbar=0` when hidden), replacing any fragment. */
export function pdfFrameSource(source: string, page: number, toolbar: boolean): string {
  const base = source.split("#")[0]!;
  const fragment = [`page=${String(Math.max(1, Math.floor(page)))}`];
  if (!toolbar) fragment.push("toolbar=0");
  return `${base}#${fragment.join("&")}`;
}

export function PdfViewer({ props }: HmiComponentProps) {
  const source = mediaSource(props.source);
  const title = readString(props, "title", "PDF document");
  if (!source) {
    return (
      <div className="oip-pdf-viewer" role="document" aria-label={title} data-empty="true">
        <span className="oip-media-empty">No PDF</span>
      </div>
    );
  }
  const frame = pdfFrameSource(source, readNumber(props, "page", 1), readBoolean(props, "toolbar", true));
  // The browser's own PDF viewer draws the document. A sandboxed frame would not show it, so the frame is not
  // sandboxed; the page's Content-Security-Policy limits frames to this origin and http(s) URLs.
  return (
    <div className="oip-pdf-viewer" data-source={source}>
      <iframe key={frame} title={title} src={frame} referrerPolicy="same-origin" />
    </div>
  );
}

// ---------------------------------------------------------------------------------------------------------------
// Audio and video

export const audioSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Audio",
  additionalProperties: false,
  properties: {
    source: { type: "string", title: "Source (path or http(s) URL)", default: "" },
    label: { type: "string", title: "Label", default: "Audio" },
    playing: { type: "boolean", title: "Playing", default: false },
    controls: { type: "boolean", title: "Show controls", default: true },
    autoplay: { type: "boolean", title: "Autoplay", default: false },
    loop: { type: "boolean", title: "Loop", default: false },
    muted: { type: "boolean", title: "Muted", default: false },
    volume: { type: "number", title: "Volume (0 to 1)", minimum: 0, maximum: 1, default: 1 },
  },
};

export const videoSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Video player",
  additionalProperties: false,
  properties: {
    source: { type: "string", title: "Source (path or http(s) URL)", default: "" },
    poster: { type: "string", title: "Poster image", default: "" },
    label: { type: "string", title: "Label", default: "Video" },
    playing: { type: "boolean", title: "Playing", default: false },
    controls: { type: "boolean", title: "Show controls", default: true },
    autoplay: { type: "boolean", title: "Autoplay", default: false },
    loop: { type: "boolean", title: "Loop", default: false },
    muted: { type: "boolean", title: "Muted", default: true },
    volume: { type: "number", title: "Volume (0 to 1)", minimum: 0, maximum: 1, default: 1 },
    fit: { type: "string", title: "Fit", enum: ["contain", "cover", "fill"], default: "contain" },
  },
};

/**
 * Keeps a media element in step with the `playing` and `volume` properties, and writes `playing` back when the
 * operator (or the end of the clip) starts or stops it, so a bidirectional binding follows the player.
 */
function useMediaControl(props: Record<string, unknown>, onChange: HmiComponentProps["onChange"], source: string | null) {
  const element = useRef<HTMLMediaElement | null>(null);
  const playing = readBoolean(props, "playing", false);
  const volume = Math.min(1, Math.max(0, readNumber(props, "volume", 1)));
  const playingRef = useRef(playing);
  playingRef.current = playing;

  useEffect(() => {
    const media = element.current;
    if (!media || !source) return;
    if (playing && media.paused) {
      const started = media.play() as Promise<void> | undefined;
      // A browser that refuses to start (no source, autoplay policy) leaves the player stopped; say so.
      started?.catch(() => {
        if (playingRef.current) onChange("playing", false);
      });
    } else if (!playing && !media.paused) {
      media.pause();
    }
  }, [playing, source, onChange]);

  useEffect(() => {
    if (element.current) element.current.volume = volume;
  }, [volume, source]);

  const events = {
    onPlay: () => {
      if (!playingRef.current) onChange("playing", true);
    },
    onPause: (event: SyntheticEvent<HTMLMediaElement>) => {
      if (playingRef.current && !event.currentTarget.ended) onChange("playing", false);
    },
    onEnded: () => {
      if (playingRef.current) onChange("playing", false);
    },
  };
  return { element, events };
}

export function Audio({ props, onChange }: HmiComponentProps) {
  const source = mediaSource(props.source);
  const label = readString(props, "label", "Audio");
  const { element, events } = useMediaControl(props, onChange, source);
  if (!source) {
    return (
      <div className="oip-audio" role="group" aria-label={label} data-empty="true">
        <span className="oip-media-empty">No audio</span>
      </div>
    );
  }
  return (
    <div className="oip-audio" data-source={source}>
      <audio
        ref={(node) => {
          element.current = node;
        }}
        aria-label={label}
        src={source}
        controls={readBoolean(props, "controls", true)}
        autoPlay={readBoolean(props, "autoplay", false)}
        loop={readBoolean(props, "loop", false)}
        muted={readBoolean(props, "muted", false)}
        preload="metadata"
        {...events}
      />
    </div>
  );
}

const VIDEO_FITS = new Set(["contain", "cover", "fill"]);

export function Video({ props, onChange }: HmiComponentProps) {
  const source = mediaSource(props.source);
  const label = readString(props, "label", "Video");
  const poster = allowedImageSource(readString(props, "poster")) ?? undefined;
  const fit = typeof props.fit === "string" && VIDEO_FITS.has(props.fit) ? props.fit : "contain";
  const { element, events } = useMediaControl(props, onChange, source);
  if (!source) {
    return (
      <div className="oip-video" role="group" aria-label={label} data-empty="true">
        <span className="oip-media-empty">No video</span>
      </div>
    );
  }
  return (
    <div className="oip-video" data-source={source}>
      <video
        ref={(node) => {
          element.current = node;
        }}
        aria-label={label}
        src={source}
        poster={poster}
        data-fit={fit}
        controls={readBoolean(props, "controls", true)}
        autoPlay={readBoolean(props, "autoplay", false)}
        loop={readBoolean(props, "loop", false)}
        muted={readBoolean(props, "muted", true)}
        playsInline
        preload="metadata"
        {...events}
      />
    </div>
  );
}
