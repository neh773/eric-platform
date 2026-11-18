import { useId, type ReactNode } from "react";
import type { HmiComponentProps } from "@oip/hmi-runtime";
import { fitSvgText, formatNumber, readBoolean, readNumber, readString } from "../read";
import {
  levelAlarm,
  levelFill,
  levelPercent,
  readLevelValue,
  stateText,
  equipmentVisual,
  symbolState,
  symbolVisual,
  type LevelAlarm,
  type SymbolState,
  type SymbolVisual,
} from "./state";

/** C-18 process symbols: pump, valve, motor, vessel, sensor and cylindrical tank.
 *
 * All artwork here is original: each symbol is built from plain rectangles, circles, arcs and
 * triangles on a 100 by 100 grid, drawn for this project. Nothing is traced from or derived from
 * another product's symbol library. Written from the public pages below; the code and wording are ours.
 *
 * Symbols palette:  -
 * Pump:             -
 * Valve:            -
 * Motor:            -
 * Vessel:           -
 * Cylindrical tank: -
 */

function bodyStyle(visual: SymbolVisual) {
  return {
    fill: visual.fill,
    stroke: visual.stroke,
    strokeWidth: visual.strokeWidth,
    strokeDasharray: visual.dash || undefined,
  };
}

/** Outline-only style for container symbols, whose interior holds the liquid. */
function outlineStyle(visual: SymbolVisual) {
  return { ...bodyStyle(visual), fill: "none" };
}

function Badge({ visual }: { visual: SymbolVisual }) {
  if (!visual.badge) return null;
  const fault = visual.badge === "!";
  return (
    <g className="oip-symbol-badge" data-badge={visual.badge}>
      <path
        d="M88 2 L98 12 L88 22 L78 12 Z"
        style={{
          fill: fault ? "var(--oip-color-alarm)" : "var(--oip-color-surface)",
          stroke: fault ? "var(--oip-color-alarm)" : "var(--oip-color-idle)",
          strokeWidth: 1.5,
        }}
      />
      <text
        x="88"
        y="16"
        textAnchor="middle"
        style={{ fill: fault ? "var(--oip-color-surface)" : "var(--oip-color-idle)" }}
      >
        {visual.badge}
      </text>
    </g>
  );
}

function SymbolFrame({
  kind,
  name,
  state,
  visual,
  children,
  data,
}: {
  kind: string;
  name: string;
  state: SymbolState;
  visual: SymbolVisual;
  children: ReactNode;
  data?: Record<string, string | undefined>;
}) {
  return (
    <div
      className={`oip-symbol oip-symbol-${kind}`}
      role="img"
      aria-label={name}
      data-state={state}
      data-abnormal={visual.abnormal || undefined}
      {...data}
    >
      <svg viewBox="0 0 100 100" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
        {children}
        <Badge visual={visual} />
      </svg>
    </div>
  );
}

function mirror(direction: string): string | undefined {
  return direction === "left" ? "translate(100 0) scale(-1 1)" : undefined;
}

/** A missing `state` prop takes the schema default; a present but unrecognized one is unknown. */
function readState(props: Record<string, unknown>, fallback: "stopped" | "normal" = "stopped"): SymbolState {
  return symbolState(props.state === undefined ? fallback : props.state);
}

export function Pump({ props }: HmiComponentProps) {
  const label = readString(props, "label", "Pump");
  const state = readState(props);
  const visual = equipmentVisual(state, props.palette);
  const style = bodyStyle(visual);
  return (
    <SymbolFrame kind="pump" name={`${label}: ${stateText[state]}`} state={state} visual={visual}>
      <g transform={mirror(readString(props, "direction", "right"))}>
        {/* Base, discharge nozzle, then the round casing on top so its outline closes the joints. */}
        <path className="oip-symbol-body" d="M28 74 L20 92 H68 L60 74 Z" style={style} />
        <rect className="oip-symbol-body" x="44" y="26" width="46" height="16" style={style} />
        <circle className="oip-symbol-body" cx="44" cy="54" r="27" style={style} />
        <circle className="oip-symbol-detail" cx="44" cy="54" r="6" style={{ ...style, fill: "none" }} />
      </g>
    </SymbolFrame>
  );
}

export function Valve({ props }: HmiComponentProps) {
  const label = readString(props, "label", "Valve");
  const state = readState(props);
  const visual = equipmentVisual(state, props.palette);
  const style = bodyStyle(visual);
  const vertical = readString(props, "orientation", "horizontal") === "vertical";
  const actuator = readBoolean(props, "actuator", true);
  const words: Record<SymbolState, string> = { ...stateText, running: "open", stopped: "closed" };
  return (
    <SymbolFrame kind="valve" name={`${label}: ${words[state]}`} state={state} visual={visual} data={{ "data-orientation": vertical ? "vertical" : "horizontal" }}>
      <g transform={vertical ? "rotate(90 50 62)" : undefined}>
        {actuator ? (
          <>
            <path className="oip-symbol-detail" d="M50 62 V34" style={{ ...style, fill: "none" }} />
            <path className="oip-symbol-body" d="M30 34 A20 20 0 0 1 70 34 Z" style={style} />
          </>
        ) : null}
        <path className="oip-symbol-body" d="M8 42 L50 62 L8 82 Z" style={style} />
        <path className="oip-symbol-body" d="M92 42 L50 62 L92 82 Z" style={style} />
      </g>
    </SymbolFrame>
  );
}

export function Motor({ props }: HmiComponentProps) {
  const label = readString(props, "label", "Motor");
  const state = readState(props);
  const visual = equipmentVisual(state, props.palette);
  const style = bodyStyle(visual);
  const line = { ...style, fill: "none", strokeWidth: Math.max(1.5, visual.strokeWidth - 1.5) };
  return (
    <SymbolFrame kind="motor" name={`${label}: ${stateText[state]}`} state={state} visual={visual}>
      <g transform={mirror(readString(props, "direction", "right"))}>
        <rect className="oip-symbol-body" x="22" y="70" width="12" height="10" style={style} />
        <rect className="oip-symbol-body" x="52" y="70" width="12" height="10" style={style} />
        <rect className="oip-symbol-body" x="80" y="45" width="14" height="10" style={style} />
        <rect className="oip-symbol-body" x="70" y="37" width="10" height="26" style={style} />
        <rect className="oip-symbol-body" x="12" y="28" width="58" height="44" rx="6" style={style} />
        <path className="oip-symbol-detail" d="M26 36 V64 M36 36 V64 M46 36 V64 M56 36 V64" style={line} />
      </g>
    </SymbolFrame>
  );
}

interface LevelReading {
  percent: number | null;
  alarm: LevelAlarm;
}

function readLevel(props: Record<string, unknown>): LevelReading {
  const percent = levelPercent(props.level, readNumber(props, "min", 0), readNumber(props, "max", 100));
  const alarm = levelAlarm(percent, readNumber(props, "alarmLow", 0), readNumber(props, "alarmHigh", 100));
  return { percent, alarm };
}

/** Accessible name for containers: the level first, then any abnormal or uncertain state. */
function containerName(label: string, levelText: string, alarm: LevelAlarm, state: SymbolState): string {
  const parts = [`${label}: level ${levelText}`];
  if (alarm) parts.push(`${alarm} level`);
  if (state === "fault" || state === "transition" || state === "unknown") parts.push(stateText[state]);
  return parts.join(", ");
}

function levelData(reading: LevelReading) {
  return {
    "data-level": reading.percent === null ? "" : reading.percent.toFixed(1),
    "data-alarm": reading.alarm || undefined,
  };
}

export function Vessel({ props, locale }: HmiComponentProps) {
  const label = readString(props, "label", "Vessel");
  const state = readState(props, "normal");
  const visual = symbolVisual(state);
  const reading = readLevel(props);
  const showLevel = readBoolean(props, "showLevel", true);
  const clip = `oip-vessel-${useId().replace(/:/g, "")}`;
  const top = 8;
  const height = 80;
  const liquidY = reading.percent === null ? top + height : top + height - (height * reading.percent) / 100;
  const levelText = reading.percent === null ? "no value" : `${formatNumber(reading.percent, 0, locale)}%`;
  return (
    <SymbolFrame kind="vessel" name={containerName(label, levelText, reading.alarm, state)} state={state} visual={visual} data={levelData(reading)}>
      <defs>
        <clipPath id={clip}>
          <rect x="26" y={top} width="48" height={height} rx="22" />
        </clipPath>
      </defs>
      <path className="oip-symbol-detail" d="M34 84 L30 97 M66 84 L70 97" style={{ ...outlineStyle(visual) }} />
      <rect x="26" y={top} width="48" height={height} rx="22" style={{ fill: "var(--oip-color-surface)", stroke: "none" }} />
      <rect
        className="oip-symbol-liquid"
        x="20"
        y={liquidY}
        width="60"
        height={top + height - liquidY}
        clipPath={`url(#${clip})`}
        style={{ fill: levelFill(reading.alarm) }}
      />
      <rect className="oip-symbol-body" x="26" y={top} width="48" height={height} rx="22" style={outlineStyle(visual)} />
      {showLevel && reading.percent !== null ? (
        <text className="oip-symbol-text" x="50" y="52" textAnchor="middle" {...fitSvgText(levelText, 13, 44)}>
          {levelText}
        </text>
      ) : null}
    </SymbolFrame>
  );
}

export function Sensor({ props, locale }: HmiComponentProps) {
  const label = readString(props, "label", "Sensor");
  const state = readState(props, "normal");
  const visual = symbolVisual(state);
  const style = bodyStyle(visual);
  const tag = readString(props, "tag", "PT").slice(0, 4);
  const unit = readString(props, "unit");
  const decimals = Math.min(6, Math.max(0, Math.round(readNumber(props, "decimals", 1))));
  const raw = props.value;
  const value = typeof raw === "number" && Number.isFinite(raw) ? raw : null;
  const shown = value === null ? "—" : `${formatNumber(value, decimals, locale)}${unit ? ` ${unit}` : ""}`;
  const parts = [`${label}: ${value === null ? "no value" : shown}`];
  if (state === "fault" || state === "transition" || state === "unknown") parts.push(stateText[state]);
  return (
    <SymbolFrame kind="sensor" name={parts.join(", ")} state={state} visual={visual}>
      {/* A field instrument bubble: the circle carries the state outline, the interior stays light for the letters. */}
      <path className="oip-symbol-detail" d="M50 66 V98" style={{ ...style, fill: "none" }} />
      <circle className="oip-symbol-body" cx="50" cy="36" r="28" style={{ ...style, fill: "var(--oip-color-surface)" }} />
      <text className="oip-symbol-text oip-symbol-tag" x="50" y="32" textAnchor="middle">
        {tag}
      </text>
      <text className="oip-symbol-text oip-symbol-value" x="50" y="48" textAnchor="middle" {...fitSvgText(shown, 11, 48)}>
        {shown}
      </text>
    </SymbolFrame>
  );
}

export function CylindricalTank({ props, locale }: HmiComponentProps) {
  const label = readString(props, "label", "Tank");
  const state = readState(props, "normal");
  const visual = symbolVisual(state);
  const outline = outlineStyle(visual);
  const reading = readLevel(props);
  const showLevel = readBoolean(props, "showLevel", true);
  const unit = readString(props, "unit");
  const decimals = Math.min(6, Math.max(0, Math.round(readNumber(props, "decimals", 0))));
  const clip = `oip-tank-${useId().replace(/:/g, "")}`;
  const top = 16;
  const bottom = 84;
  const liquidY = reading.percent === null ? bottom : bottom - ((bottom - top) * reading.percent) / 100;
  const rawLevel = readLevelValue(props.level);
  const levelText =
    reading.percent === null
      ? "no value"
      : unit && rawLevel !== null
        ? `${formatNumber(rawLevel, decimals, locale)} ${unit}`
        : `${formatNumber(reading.percent, decimals, locale)}%`;
  const shell = "M16 16 V84 A34 8 0 0 0 84 84 V16 A34 8 0 0 0 16 16 Z";
  return (
    <SymbolFrame kind="cylindrical-tank" name={containerName(label, levelText, reading.alarm, state)} state={state} visual={visual} data={levelData(reading)}>
      <defs>
        <clipPath id={clip}>
          <path d={shell} />
        </clipPath>
      </defs>
      <path d={shell} style={{ fill: "var(--oip-color-surface)", stroke: "none" }} />
      {reading.percent !== null && reading.percent > 0 ? (
        <g clipPath={`url(#${clip})`}>
          <rect className="oip-symbol-liquid" x="10" y={liquidY} width="80" height={bottom + 10 - liquidY} style={{ fill: levelFill(reading.alarm) }} />
          <ellipse
            className="oip-symbol-surface"
            cx="50"
            cy={liquidY}
            rx="34"
            ry="8"
            style={{ fill: levelFill(reading.alarm), stroke: "var(--oip-color-surface)", strokeWidth: 1, opacity: 0.85 }}
          />
        </g>
      ) : null}
      <path className="oip-symbol-body" d="M16 16 V84 A34 8 0 0 0 84 84 V16" style={outline} />
      <ellipse className="oip-symbol-body" cx="50" cy="16" rx="34" ry="8" style={outline} />
      {showLevel && reading.percent !== null ? (
        <text className="oip-symbol-text" x="50" y="56" textAnchor="middle" {...fitSvgText(levelText, 13, 64)}>
          {levelText}
        </text>
      ) : null}
    </SymbolFrame>
  );
}
