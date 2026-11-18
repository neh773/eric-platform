/** State, palette and level helpers shared by the C-18 process symbols.
 *
 * Pump, valve and motor draw running and stopped in the theme's running and stopped colors by default
 * (`palette: "state"`). With `palette: "muted"`, and for the vessel, sensor and tank, normal operation is
 * drawn in greys from the theme and color is kept for abnormal states (fault, a level outside its limits).
 * Every color is a theme token, so a symbol follows the session theme (daylight / nightwatch).
 */

export type SymbolState = "running" | "stopped" | "transition" | "fault" | "unknown";

export const symbolStates: readonly SymbolState[] = ["running", "stopped", "transition", "fault", "unknown"];

/** Words accepted for each state, so a valve can be bound to `open` / `closed` and a motor to `on` / `off`. */
const aliases: Record<string, SymbolState> = {
  running: "running",
  run: "running",
  on: "running",
  open: "running",
  opened: "running",
  stopped: "stopped",
  stop: "stopped",
  off: "stopped",
  closed: "stopped",
  close: "stopped",
  transition: "transition",
  transitioning: "transition",
  starting: "transition",
  stopping: "transition",
  opening: "transition",
  closing: "transition",
  travel: "transition",
  fault: "fault",
  faulted: "fault",
  failed: "fault",
  trip: "fault",
  tripped: "fault",
  error: "fault",
  normal: "stopped",
  idle: "stopped",
};

/** Numeric state codes: 0 stopped, 1 running, 2 transition, 3 fault. */
const codes: readonly SymbolState[] = ["stopped", "running", "transition", "fault"];

/** Maps a bound value to a symbol state. Strings use the alias table (case-insensitive), booleans
 * map true to running and false to stopped, and integers use the numeric codes. Anything else is unknown.
 */
export function symbolState(value: unknown): SymbolState {
  if (typeof value === "boolean") return value ? "running" : "stopped";
  if (typeof value === "number") {
    return Number.isInteger(value) && value >= 0 && value < codes.length ? codes[value]! : "unknown";
  }
  if (typeof value === "string") {
    const key = value.trim().toLowerCase();
    if (/^\d+$/.test(key)) return symbolState(Number(key));
    return aliases[key] ?? "unknown";
  }
  return "unknown";
}

export interface SymbolVisual {
  /** Body fill. */
  fill: string;
  /** Outline color. */
  stroke: string;
  /** Outline width in symbol units (the symbols draw on a 100 by 100 grid). */
  strokeWidth: number;
  /** SVG dash pattern, empty for a solid outline. */
  dash: string;
  /** Badge drawn in the corner: none, `!` for a fault, `?` when the state is unknown. */
  badge: "" | "!" | "?";
  /** Whether the state is abnormal (the only states that use color). */
  abnormal: boolean;
}

/** The documented visual for each state.
 *
 * | state      | body              | outline                    | badge |
 * |------------|-------------------|----------------------------|-------|
 * | running    | filled, muted ink | ink, solid                 |       |
 * | stopped    | hollow (surface)  | muted ink, solid           |       |
 * | transition | half-tone (line)  | ink, dashed                |       |
 * | fault      | hollow (surface)  | alarm color, solid, heavy  | `!`   |
 * | unknown    | hollow (canvas)   | idle, dotted               | `?`   |
 */
export const symbolVisuals: Readonly<Record<SymbolState, SymbolVisual>> = {
  running: { fill: "var(--oip-color-muted)", stroke: "var(--oip-color-ink)", strokeWidth: 3, dash: "", badge: "", abnormal: false },
  stopped: { fill: "var(--oip-color-surface)", stroke: "var(--oip-color-muted)", strokeWidth: 3, dash: "", badge: "", abnormal: false },
  transition: { fill: "var(--oip-color-line)", stroke: "var(--oip-color-ink)", strokeWidth: 3, dash: "7 4", badge: "", abnormal: false },
  fault: { fill: "var(--oip-color-surface)", stroke: "var(--oip-color-alarm)", strokeWidth: 5, dash: "", badge: "!", abnormal: true },
  unknown: { fill: "var(--oip-color-canvas)", stroke: "var(--oip-color-idle)", strokeWidth: 3, dash: "2 4", badge: "?", abnormal: false },
};

export function symbolVisual(state: SymbolState): SymbolVisual {
  return symbolVisuals[state];
}

/** How pump, valve and motor bodies show running and stopped: in their state colors, or in the muted greys above. */
export type SymbolPalette = "state" | "muted";

/** Running and stopped in the `state` palette: a solid body in the theme's running or stopped color with an ink
 * outline, so either state reads at a glance. Fault stays a hollow body with the heavy alarm outline and its badge.
 */
export const stateColorVisuals: Readonly<Record<"running" | "stopped", SymbolVisual>> = {
  running: { fill: "var(--oip-color-running)", stroke: "var(--oip-color-ink)", strokeWidth: 3, dash: "", badge: "", abnormal: false },
  stopped: { fill: "var(--oip-color-stopped)", stroke: "var(--oip-color-ink)", strokeWidth: 3, dash: "", badge: "", abnormal: false },
};

/** The visual for an equipment symbol (pump, valve, motor). Anything but `muted` is the default `state` palette. */
export function equipmentVisual(state: SymbolState, palette: unknown): SymbolVisual {
  if (palette !== "muted" && (state === "running" || state === "stopped")) return stateColorVisuals[state];
  return symbolVisuals[state];
}

export const stateText: Readonly<Record<SymbolState, string>> = {
  running: "running",
  stopped: "stopped",
  transition: "in transition",
  fault: "fault",
  unknown: "state unknown",
};

/** A finite number, or a string holding one (tag values may arrive as text, as states do). Otherwise null. */
export function readLevelValue(value: unknown): number | null {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value === "string" && value.trim() !== "") {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

/** Level as a percentage of `min`..`max`, clamped to 0..100. A missing or non-numeric value is null. */
export function levelPercent(raw: unknown, min = 0, max = 100): number | null {
  const value = readLevelValue(raw);
  if (value === null) return null;
  const low = Number.isFinite(min) ? min : 0;
  const high = Number.isFinite(max) ? max : 100;
  if (high === low) return value >= high ? 100 : 0;
  const percent = ((value - low) / (high - low)) * 100;
  return Math.min(100, Math.max(0, percent));
}

export type LevelAlarm = "" | "high" | "low";

/** A level above `high` or below `low` (both in percent) is abnormal. The defaults 0 and 100 never trip,
 * because the level is already clamped to that range.
 */
export function levelAlarm(percent: number | null, low: number, high: number): LevelAlarm {
  if (percent === null) return "";
  if (percent > high) return "high";
  if (percent < low) return "low";
  return "";
}

/** Liquid fill: muted in the normal band, the warning token when a level limit is crossed. */
export function levelFill(alarm: LevelAlarm): string {
  return alarm ? "var(--oip-color-warning)" : "var(--oip-color-idle)";
}
