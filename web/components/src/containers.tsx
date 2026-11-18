import { useEffect, useRef, useState } from "react";
import type { CSSProperties } from "react";
import { EmbeddedView } from "@oip/hmi-runtime";
import type { ContainerProps, HmiComponentProps, JsonSchema, LayoutKind } from "@oip/hmi-runtime";

/** C-15: embedding components (flex repeater, carousel, accordion) and the palette entries for the
 * breakpoint and split containers. Each embedding component shows project views through the runtime's
 * `EmbeddedView`, so embedded views share the session's tags, actions and style classes.
 *
 * Behavior is written from the public component pages, in our own words:
 */

type Json = Record<string, unknown>;

function isRecord(value: unknown): value is Json {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

/** A list prop: an array, or JSON text of an array (the inspector and string tags carry text). Anything else is empty. */
export function readList(value: unknown): unknown[] {
  if (Array.isArray(value)) return value;
  if (typeof value !== "string" || value.trim() === "") return [];
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

/** Why a list prop can't be read, or null when it is fine. Empty text and non-text values count as an empty list. */
export function listError(value: unknown): string | null {
  if (typeof value !== "string" || value.trim() === "") return null;
  try {
    return Array.isArray(JSON.parse(value)) ? null : "is not a JSON array";
  } catch {
    return "is not valid JSON";
  }
}

function ListError({ name, value }: { name: string; value: unknown }) {
  const error = listError(value);
  if (!error) return null;
  return (
    <div className="oip-unknown" role="alert">
      {name} {error}
    </div>
  );
}

function readText(value: unknown, fallback: string): string {
  return typeof value === "string" ? value : fallback;
}

function readBool(value: unknown, fallback: boolean): boolean {
  return typeof value === "boolean" ? value : fallback;
}

function readInt(value: unknown, fallback: number): number {
  const number = typeof value === "number" ? value : typeof value === "string" && value.trim() !== "" ? Number(value) : Number.NaN;
  return Number.isFinite(number) ? Math.trunc(number) : fallback;
}

function readParams(value: unknown): Json {
  if (isRecord(value)) return value;
  if (typeof value === "string" && value.trim() !== "") {
    try {
      const parsed: unknown = JSON.parse(value);
      if (isRecord(parsed)) return parsed;
    } catch {
      // Not JSON: no params.
    }
  }
  return {};
}

// ---------------------------------------------------------------- flex repeater

export interface FlexPosition {
  grow: number;
  shrink: number;
  basis: string;
}

export interface RepeaterInstance {
  key: string;
  /** Params passed to the repeated view: the instance's own fields plus `index`. */
  params: Json;
  position?: Partial<FlexPosition>;
  style?: CSSProperties;
}

const RESERVED_INSTANCE_KEYS = new Set(["instanceStyle", "instancePosition"]);

/** One entry per item of `instances`. Every key except `instanceStyle` and `instancePosition` is a view param.
 * Each instance also gets its position as `index`; an item that sets its own `index` keeps it.
 * A non-object item is passed as the `value` param.
 */
export function repeaterInstances(value: unknown): RepeaterInstance[] {
  return readList(value).map((entry, index) => {
    if (!isRecord(entry)) return { key: String(index), params: { index, value: entry } };
    const params: Json = { index };
    for (const [key, field] of Object.entries(entry)) if (!RESERVED_INSTANCE_KEYS.has(key)) params[key] = field;
    return {
      key: String(index),
      params,
      position: isRecord(entry.instancePosition) ? readPosition(entry.instancePosition) : undefined,
      style: isRecord(entry.instanceStyle) ? cssFrom(entry.instanceStyle) : undefined,
    };
  });
}

function readPosition(record: Json): Partial<FlexPosition> {
  const position: Partial<FlexPosition> = {};
  if (typeof record.grow === "number") position.grow = record.grow;
  if (typeof record.shrink === "number") position.shrink = record.shrink;
  if (typeof record.basis === "string" || typeof record.basis === "number") {
    position.basis = typeof record.basis === "number" ? `${record.basis}px` : record.basis;
  }
  return position;
}

/** Instance and element styles: the same small style bag as everywhere else (colors, type size, padding). */
function cssFrom(record: Json): CSSProperties {
  const css: CSSProperties = {};
  if (typeof record.color === "string") css.color = record.color;
  if (typeof record.background === "string") css.background = record.background;
  if (typeof record.fontSize === "number") css.fontSize = `${record.fontSize}px`;
  if (typeof record.fontWeight === "number") css.fontWeight = record.fontWeight;
  if (typeof record.padding === "number") css.padding = `${record.padding}px`;
  return css;
}

const DIRECTIONS = ["row", "row-reverse", "column", "column-reverse"] as const;
const WRAPS = ["nowrap", "wrap", "wrap-reverse"] as const;
const JUSTIFY = ["flex-start", "flex-end", "center", "space-between", "space-around", "space-evenly"] as const;
const ALIGN_ITEMS = ["flex-start", "flex-end", "center", "baseline", "stretch"] as const;
const ALIGN_CONTENT = ["flex-start", "flex-end", "center", "space-between", "space-around", "stretch"] as const;

function pick<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  return typeof value === "string" && (allowed as readonly string[]).includes(value) ? (value as T) : fallback;
}

export function FlexRepeater({ nodeId, props }: HmiComponentProps) {
  const path = readText(props.path, "");
  const instances = repeaterInstances(props.instances);
  const element = isRecord(props.elementPosition) ? readPosition(props.elementPosition) : {};
  const elementStyle = isRecord(props.elementStyle) ? cssFrom(props.elementStyle) : {};
  const useWidth = readBool(props.useDefaultViewWidth, true);
  const useHeight = readBool(props.useDefaultViewHeight, true);
  const style: CSSProperties = {
    display: "flex",
    flexDirection: pick(props.direction, DIRECTIONS, "row"),
    flexWrap: pick(props.wrap, WRAPS, "nowrap"),
    justifyContent: pick(props.justify, JUSTIFY, "flex-start"),
    alignItems: pick(props.alignItems, ALIGN_ITEMS, "flex-start"),
    alignContent: pick(props.alignContent, ALIGN_CONTENT, "flex-start"),
    overflow: pick(props.overflow, ["auto", "visible", "scroll", "hidden"] as const, "auto"),
  };
  return (
    <>
      <ListError name="Instances" value={props.instances} />
      <div
        className="oip-repeater"
        role="list"
        aria-label={readText(props.label, "Repeated views")}
        data-instance-count={instances.length}
        style={style}
      >
        {instances.map((instance) => {
          const position = { ...element, ...instance.position };
          return (
            <div
              key={instance.key}
              role="listitem"
              className="oip-repeater-item"
              data-instance-index={instance.key}
              style={{
                flexGrow: position.grow ?? 0,
                flexShrink: position.shrink ?? 0,
                flexBasis: position.basis ?? "auto",
                minWidth: 0,
                ...elementStyle,
                ...instance.style,
              }}
            >
              <EmbeddedView
                path={path}
                params={instance.params}
                instanceKey={`${nodeId}-${instance.key}`}
                useDefaultViewWidth={useWidth}
                useDefaultViewHeight={useHeight}
              />
            </div>
          );
        })}
      </div>
    </>
  );
}

// ---------------------------------------------------------------- carousel

export interface Slide {
  viewPath: string;
  viewParams: Json;
}

export function readSlides(value: unknown): Slide[] {
  return readList(value).flatMap((entry) => {
    if (typeof entry === "string") return [{ viewPath: entry, viewParams: {} }];
    if (!isRecord(entry)) return [];
    return [{ viewPath: readText(entry.viewPath, ""), viewParams: readParams(entry.viewParams) }];
  });
}

/** Moves `step` panes from `active` and wraps around the ends. An empty carousel stays at 0. */
export function stepPane(active: number, step: number, count: number): number {
  if (count <= 0) return 0;
  return (((active + step) % count) + count) % count;
}

export function clampPane(active: number, count: number): number {
  if (count <= 0) return 0;
  return Math.min(count - 1, Math.max(0, active));
}

export function Carousel({ nodeId, props, onChange }: HmiComponentProps) {
  const slides = readSlides(props.views);
  const label = readText(props.label, "Carousel");
  const reverse = readBool(props.reverse, false);
  const ordered = reverse ? [...slides].reverse() : slides;
  const fromProps = clampPane(readInt(props.activePane, 0), ordered.length);
  const [active, setActive] = useState(fromProps);
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  useEffect(() => setActive(fromProps), [fromProps]);
  const current = clampPane(active, ordered.length);
  const arrows = isRecord(props.arrows) ? readBool(props.arrows.enabled, true) : readBool(props.arrows, true);
  const dots = isRecord(props.dots) ? readBool(props.dots.enabled, true) : readBool(props.dots, true);
  const autoplay = isRecord(props.autoplay) ? props.autoplay : {};
  const autoplayOn = readBool(autoplay.enabled, false);
  const delay = Math.max(250, readInt(autoplay.transitionDelay, 3000));
  const pauseOnHover = readBool(autoplay.pauseOnHover, true);
  const paused = pauseOnHover && (hovered || focused);

  const latest = useRef({ current, count: ordered.length, onChange });
  latest.current = { current, count: ordered.length, onChange };

  function go(next: number) {
    const target = clampPane(next, ordered.length);
    setActive(target);
    onChange("activePane", target);
  }

  useEffect(() => {
    if (!autoplayOn || paused || ordered.length < 2) return;
    const timer = window.setInterval(() => {
      const { current: now, count, onChange: emit } = latest.current;
      const next = stepPane(now, 1, count);
      setActive(next);
      emit("activePane", next);
    }, delay);
    return () => window.clearInterval(timer);
  }, [autoplayOn, paused, delay, ordered.length]);

  const slide = ordered[current];
  return (
    <section
      className="oip-carousel"
      aria-roledescription="carousel"
      aria-label={label}
      data-active-pane={current}
      onPointerEnter={() => setHovered(true)}
      onPointerLeave={() => setHovered(false)}
      onFocus={() => setFocused(true)}
      onBlur={(event) => {
        // Focus moving between the carousel's own buttons keeps it paused.
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setFocused(false);
      }}
    >
      <ListError name="Views" value={props.views} />
      <div
        className="oip-carousel-pane"
        role="group"
        aria-roledescription="slide"
        aria-label={`${current + 1} of ${ordered.length}`}
        aria-live={autoplayOn && !paused ? "off" : "polite"}
      >
        {slide ? (
          <EmbeddedView
            key={`${current}-${slide.viewPath}`}
            path={slide.viewPath}
            params={slide.viewParams}
            instanceKey={`${nodeId}-pane-${current}`}
            useDefaultViewWidth={readBool(props.useDefaultViewWidth, false)}
            useDefaultViewHeight={readBool(props.useDefaultViewHeight, false)}
          />
        ) : (
          <p className="oip-carousel-empty">No views</p>
        )}
      </div>
      {arrows && ordered.length > 1 ? (
        <>
          <button type="button" className="oip-carousel-arrow" data-dir="previous" aria-label="Previous view" onClick={() => go(stepPane(current, -1, ordered.length))}>
            <svg viewBox="0 0 12 12" aria-hidden="true"><path d="M8 2 4 6l4 4" /></svg>
          </button>
          <button type="button" className="oip-carousel-arrow" data-dir="next" aria-label="Next view" onClick={() => go(stepPane(current, 1, ordered.length))}>
            <svg viewBox="0 0 12 12" aria-hidden="true"><path d="m4 2 4 4-4 4" /></svg>
          </button>
        </>
      ) : null}
      {dots && ordered.length > 1 ? (
        <div className="oip-carousel-dots">
          {ordered.map((_, index) => (
            <button
              key={index}
              type="button"
              className="oip-carousel-dot"
              aria-label={`Show view ${index + 1} of ${ordered.length}`}
              aria-current={index === current ? "true" : undefined}
              onClick={() => go(index)}
            />
          ))}
        </div>
      ) : null}
    </section>
  );
}

// ---------------------------------------------------------------- accordion

export interface AccordionItem {
  /** Position of this item in the raw `items` list, which may hold entries that are skipped. */
  rawIndex: number;
  expanded: boolean;
  headerText: string;
  headerView?: Slide;
  body: Slide & { height?: number };
}

export function readAccordionItems(value: unknown): AccordionItem[] {
  return readList(value).flatMap((entry, index) => {
    if (!isRecord(entry)) return [];
    const header = entry.header;
    const content = isRecord(header) && isRecord(header.content) ? header.content : isRecord(header) ? header : {};
    const headerText = typeof header === "string" ? header : readText(content.text, `Item ${index + 1}`);
    const headerView = content.type === "view" && typeof content.viewPath === "string"
      ? { viewPath: content.viewPath, viewParams: readParams(content.viewParams) }
      : undefined;
    const body = isRecord(entry.body) ? entry.body : {};
    return [{
      rawIndex: index,
      expanded: entry.expanded === true,
      headerText,
      headerView,
      body: {
        viewPath: readText(body.viewPath, ""),
        viewParams: readParams(body.viewParams),
        height: typeof body.height === "number" && body.height > 0 ? body.height : undefined,
      },
    }];
  });
}

/** Toggles one item. In `single` mode opening an item closes every other one. */
export function toggleAccordion(expanded: readonly boolean[], index: number, mode: "single" | "multiple"): boolean[] {
  const opening = !expanded[index];
  return expanded.map((open, i) => {
    if (i === index) return opening;
    return mode === "single" && opening ? false : open;
  });
}

export function Accordion({ nodeId, props, onChange }: HmiComponentProps) {
  const items = readAccordionItems(props.items);
  const mode = props.expansionMode === "single" ? "single" : "multiple";
  const fromProps = items.map((item) => item.expanded).join(",");
  const [expanded, setExpanded] = useState(() => items.map((item) => item.expanded));
  useEffect(() => setExpanded(items.map((item) => item.expanded)), [fromProps]);

  /** `index` is the position among the shown items; writes go back to each item's raw position. */
  function toggle(index: number) {
    const next = toggleAccordion(items.map((_, i) => expanded[i] === true), index, mode);
    setExpanded(next);
    const byRaw = new Map(items.map((item, i) => [item.rawIndex, next[i] === true]));
    const raw = readList(props.items);
    const updated = raw.map((entry, i) => (isRecord(entry) && byRaw.has(i) ? { ...entry, expanded: byRaw.get(i) } : entry));
    onChange("items", typeof props.items === "string" ? JSON.stringify(updated) : updated);
  }

  return (
    <div className="oip-accordion" aria-label={readText(props.label, "Accordion")} role="group" data-expansion-mode={mode}>
      <ListError name="Items" value={props.items} />
      {items.map((item, index) => {
        const open = expanded[index] === true;
        const id = `${nodeId}-accordion-${index}`;
        return (
          <div key={item.rawIndex} className="oip-accordion-item" data-expanded={open}>
            <div className="oip-accordion-header">
              <button type="button" id={`${id}-header`} aria-expanded={open} aria-controls={`${id}-body`} onClick={() => toggle(index)}>
                <svg className="oip-accordion-toggle" viewBox="0 0 12 12" aria-hidden="true"><path d="m4 2 4 4-4 4" /></svg>
                <span>{item.headerText}</span>
              </button>
              {item.headerView ? (
                <EmbeddedView
                  path={item.headerView.viewPath}
                  params={item.headerView.viewParams}
                  instanceKey={`${id}-head`}
                  useDefaultViewWidth={false}
                  useDefaultViewHeight={false}
                />
              ) : null}
            </div>
            <div id={`${id}-body`} role="region" aria-labelledby={`${id}-header`} className="oip-accordion-body" hidden={!open}>
              {open ? (
                <EmbeddedView
                  path={item.body.viewPath}
                  params={item.body.viewParams}
                  instanceKey={`${id}-body`}
                  useDefaultViewWidth={false}
                  useDefaultViewHeight={item.body.height === undefined}
                  style={item.body.height === undefined ? undefined : { height: item.body.height }}
                />
              ) : null}
            </div>
          </div>
        );
      })}
    </div>
  );
}

// ---------------------------------------------------------------- schemas

const SCHEMA = "https://json-schema.org/draft/2020-12/schema";

export const flexRepeaterSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "Flex repeater",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Accessible label", default: "Repeated views" },
    path: { type: "string", title: "View path", default: "" },
    instances: {
      type: "string",
      title: "Instances (JSON array of param objects; or bind an array)",
      default: "[]",
    },
    direction: { type: "string", title: "Direction", enum: [...DIRECTIONS], default: "row" },
    wrap: { type: "string", title: "Wrap", enum: [...WRAPS], default: "nowrap" },
    justify: { type: "string", title: "Justify", enum: [...JUSTIFY], default: "flex-start" },
    alignItems: { type: "string", title: "Align items", enum: [...ALIGN_ITEMS], default: "flex-start" },
    alignContent: { type: "string", title: "Align content", enum: [...ALIGN_CONTENT], default: "flex-start" },
    elementPosition: {
      type: "object",
      title: "Element position",
      properties: {
        grow: { type: "number", title: "Grow", minimum: 0, default: 0 },
        shrink: { type: "number", title: "Shrink", minimum: 0, default: 0 },
        basis: { type: "string", title: "Basis", default: "auto" },
      },
    },
    useDefaultViewWidth: { type: "boolean", title: "Use default view width", default: true },
    useDefaultViewHeight: { type: "boolean", title: "Use default view height", default: true },
  },
};

export const carouselSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "Carousel",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Accessible label", default: "Carousel" },
    views: {
      type: "string",
      title: "Views (JSON array of {viewPath, viewParams}; or bind an array)",
      default: "[]",
    },
    activePane: { type: "integer", title: "Active pane", minimum: 0, default: 0 },
    arrows: { type: "object", title: "Arrows", properties: { enabled: { type: "boolean", title: "Show arrows", default: true } } },
    dots: { type: "object", title: "Dots", properties: { enabled: { type: "boolean", title: "Show dots", default: true } } },
    autoplay: {
      type: "object",
      title: "Autoplay",
      properties: {
        enabled: { type: "boolean", title: "Rotate automatically", default: false },
        transitionDelay: { type: "integer", title: "Delay (ms)", minimum: 250, default: 3000 },
        pauseOnHover: { type: "boolean", title: "Pause on hover or focus", default: true },
      },
    },
    reverse: { type: "boolean", title: "Reverse order", default: false },
    useDefaultViewWidth: { type: "boolean", title: "Use default view width", default: false },
    useDefaultViewHeight: { type: "boolean", title: "Use default view height", default: false },
  },
};

export const accordionSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "Accordion",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Accessible label", default: "Accordion" },
    expansionMode: { type: "string", title: "Expansion mode", enum: ["single", "multiple"], default: "multiple" },
    items: {
      type: "string",
      title: "Items (JSON array of {expanded, header, body}; or bind an array)",
      default: "[]",
    },
  },
};

export const breakpointContainerSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "Breakpoint container",
  additionalProperties: false,
  properties: {
    breakpoint: { type: "number", title: "Breakpoint (px)", minimum: 0, default: 640 },
    determinant: { type: "string", title: "Compare", enum: ["width", "height"], default: "width" },
  },
};

export const splitContainerSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "Split container",
  additionalProperties: false,
  properties: {
    orientation: { type: "string", title: "Orientation", enum: ["horizontal", "vertical"], default: "horizontal" },
    split: {
      type: "object",
      title: "Divider",
      properties: {
        position: { type: "string", title: "Position (px or %)", default: "50%" },
        size: { type: "number", title: "Bar size (px)", minimum: 0, default: 6 },
        visible: { type: "boolean", title: "Visible", default: true },
        draggable: { type: "boolean", title: "Draggable", default: true },
        minFirst: { type: "number", title: "Minimum first pane (px)", minimum: 0, default: 0 },
        minSecond: { type: "number", title: "Minimum second pane (px)", minimum: 0, default: 0 },
      },
    },
  },
};

/** A container entry in the designer palette. Containers are view nodes with children, not registry components,
 * so the palette creates them with `layout` and two starter children.
 */
export interface ContainerModule {
  layout: Extract<LayoutKind, "breakpoint" | "split">;
  title: string;
  schema: JsonSchema;
  defaults: ContainerProps;
  /** Names of the two starter children, plus a breakpoint child's size. */
  children: { name: string; size?: "small" | "large" }[];
}

export const containerModules: ContainerModule[] = [
  {
    layout: "breakpoint",
    title: "Breakpoint container",
    schema: breakpointContainerSchema,
    defaults: { breakpoint: 640, determinant: "width" },
    children: [
      { name: "Small layout", size: "small" },
      { name: "Large layout", size: "large" },
    ],
  },
  {
    layout: "split",
    title: "Split container",
    schema: splitContainerSchema,
    defaults: {
      orientation: "horizontal",
      split: { position: "50%", size: 6, visible: true, draggable: true, minFirst: 0, minSecond: 0 },
    },
    children: [{ name: "First pane" }, { name: "Second pane" }],
  },
];
