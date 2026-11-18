/** Original ERIC icon set.
 *
 * Every glyph here was drawn for this project from plain geometry (lines, arcs, circles and rectangles)
 * on a 24 by 24 grid. None of it is traced from or derived from another icon library.
 * Strokes use `currentColor`, so the Icon component colors a glyph by setting CSS `color`.
 */

export type IconShape =
  | { kind: "path"; d: string; fill?: boolean }
  | { kind: "circle"; cx: number; cy: number; r: number; fill?: boolean }
  | { kind: "rect"; x: number; y: number; width: number; height: number; rx?: number; fill?: boolean };

export interface IconGlyph {
  title: string;
  shapes: IconShape[];
}

const dot = (cx: number, cy: number): IconShape => ({ kind: "circle", cx, cy, r: 1.1, fill: true });

export const iconSet = {
  circle: { title: "Circle", shapes: [{ kind: "circle", cx: 12, cy: 12, r: 8 }] },
  square: { title: "Square", shapes: [{ kind: "rect", x: 4, y: 4, width: 16, height: 16, rx: 2 }] },
  triangle: { title: "Triangle", shapes: [{ kind: "path", d: "M12 4 L20 19 H4 Z" }] },
  check: { title: "Check", shapes: [{ kind: "path", d: "M5 12.5 L10 17 L19 7" }] },
  cross: { title: "Cross", shapes: [{ kind: "path", d: "M6 6 L18 18 M18 6 L6 18" }] },
  plus: { title: "Plus", shapes: [{ kind: "path", d: "M12 5 V19 M5 12 H19" }] },
  minus: { title: "Minus", shapes: [{ kind: "path", d: "M5 12 H19" }] },
  "arrow-up": { title: "Arrow up", shapes: [{ kind: "path", d: "M12 19 V5 M6 11 L12 5 L18 11" }] },
  "arrow-down": { title: "Arrow down", shapes: [{ kind: "path", d: "M12 5 V19 M6 13 L12 19 L18 13" }] },
  "arrow-left": { title: "Arrow left", shapes: [{ kind: "path", d: "M19 12 H5 M11 6 L5 12 L11 18" }] },
  "arrow-right": { title: "Arrow right", shapes: [{ kind: "path", d: "M5 12 H19 M13 6 L19 12 L13 18" }] },
  home: { title: "Home", shapes: [{ kind: "path", d: "M4 11 L12 4 L20 11 M6 9.5 V20 H18 V9.5 M10 20 V14 H14 V20" }] },
  warning: {
    title: "Warning",
    shapes: [{ kind: "path", d: "M12 3.5 L21 19.5 H3 Z M12 9.5 V13.5" }, dot(12, 16.5)],
  },
  info: {
    title: "Info",
    shapes: [{ kind: "circle", cx: 12, cy: 12, r: 9 }, { kind: "path", d: "M12 11 V17" }, dot(12, 7.6)],
  },
  power: { title: "Power", shapes: [{ kind: "path", d: "M12 3 V11 M7.2 6.4 A7 7 0 1 0 16.8 6.4" }] },
  bell: {
    title: "Bell",
    shapes: [{ kind: "path", d: "M5.5 17 H18.5 L16.5 14.5 V11 A4.5 4.5 0 0 0 7.5 11 V14.5 Z M10.5 20 H13.5" }],
  },
  clock: {
    title: "Clock",
    shapes: [{ kind: "circle", cx: 12, cy: 12, r: 9 }, { kind: "path", d: "M12 7 V12 L15.5 14" }],
  },
  lock: {
    title: "Lock",
    shapes: [{ kind: "rect", x: 5, y: 11, width: 14, height: 9, rx: 2 }, { kind: "path", d: "M8 11 V8 A4 4 0 0 1 16 8 V11" }],
  },
  star: {
    title: "Star",
    shapes: [
      {
        kind: "path",
        d: "M12 3 L14.2 8.9 L20.6 9.2 L15.6 13.2 L17.3 19.3 L12 15.8 L6.7 19.3 L8.4 13.2 L3.4 9.2 L9.8 8.9 Z",
      },
    ],
  },
  sliders: {
    title: "Sliders",
    shapes: [
      { kind: "path", d: "M4 7 H20 M4 17 H20" },
      { kind: "circle", cx: 9, cy: 7, r: 2.2, fill: true },
      { kind: "circle", cx: 15, cy: 17, r: 2.2, fill: true },
    ],
  },
  pump: {
    title: "Pump",
    shapes: [
      { kind: "circle", cx: 11, cy: 12, r: 6 },
      { kind: "path", d: "M11 6 H20 V9.5 M7.5 17 L5.5 21 H16.5 L14.5 17" },
    ],
  },
  valve: {
    title: "Valve",
    shapes: [{ kind: "path", d: "M3 8 L12 13 L3 18 Z M21 8 L12 13 L21 18 Z M12 13 V5 M8.5 5 H15.5" }],
  },
  tank: {
    title: "Tank",
    shapes: [{ kind: "rect", x: 6, y: 3.5, width: 12, height: 17, rx: 3 }, { kind: "path", d: "M6 13 H18" }],
  },
  motor: {
    title: "Motor",
    shapes: [
      { kind: "rect", x: 3.5, y: 7, width: 13, height: 10, rx: 1.5 },
      { kind: "path", d: "M16.5 12 H21 M7 17 V20 M13 17 V20 M7 10 V14 M10 10 V14 M13 10 V14" },
    ],
  },
  drop: { title: "Drop", shapes: [{ kind: "path", d: "M12 3 L17.2 11 A6 6 0 1 1 6.8 11 Z" }] },
  bolt: { title: "Bolt", shapes: [{ kind: "path", d: "M13.5 3 L6 13.5 H11 L10 21 L18 10 H13 Z" }] },
  thermometer: {
    title: "Thermometer",
    shapes: [{ kind: "path", d: "M10 14.5 V5 A2 2 0 0 1 14 5 V14.5 A4 4 0 1 1 10 14.5 Z M12 9 V16" }],
  },
} satisfies Record<string, IconGlyph>;

export type IconName = keyof typeof iconSet;

export const iconNames = Object.keys(iconSet) as IconName[];

export function iconGlyph(name: string): IconGlyph | undefined {
  return Object.prototype.hasOwnProperty.call(iconSet, name) ? (iconSet as Record<string, IconGlyph>)[name] : undefined;
}
