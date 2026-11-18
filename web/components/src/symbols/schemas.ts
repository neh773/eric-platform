import type { JsonSchema } from "@oip/hmi-runtime";

/** Prop schemas for the C-18 process symbols. Props are flat scalars so the inspector can edit them.
 * Symbols palette: -
 */

const draft = "https://json-schema.org/draft/2020-12/schema";

const state = (fallback: string) => ({
  type: "string" as const,
  title: "State (running, stopped, transition, fault; or 0-3, true/false)",
  default: fallback,
});

/** Pump, valve and motor: running and stopped in the theme's state colors, or in muted greys. */
const palette = { type: "string" as const, title: "Colors", enum: ["state", "muted"], default: "state" };

const levelProps = {
  level: { type: "number" as const, title: "Level", default: 0 },
  min: { type: "number" as const, title: "Level at empty", default: 0 },
  max: { type: "number" as const, title: "Level at full", default: 100 },
  alarmLow: { type: "number" as const, title: "Low level limit (%)", minimum: 0, maximum: 100, default: 0 },
  alarmHigh: { type: "number" as const, title: "High level limit (%)", minimum: 0, maximum: 100, default: 100 },
  showLevel: { type: "boolean" as const, title: "Show level text", default: true },
};

/** - */
export const pumpSchema: JsonSchema = {
  $schema: draft,
  type: "object",
  title: "Pump",
  additionalProperties: false,
  properties: {
    state: state("stopped"),
    label: { type: "string", title: "Label", default: "Pump" },
    palette,
    direction: { type: "string", title: "Discharge side", enum: ["right", "left"], default: "right" },
  },
};

/** - */
export const valveSchema: JsonSchema = {
  $schema: draft,
  type: "object",
  title: "Valve",
  additionalProperties: false,
  properties: {
    state: { ...state("stopped"), title: "State (open, closed, transition, fault; or 0-3, true/false)" },
    label: { type: "string", title: "Label", default: "Valve" },
    palette,
    orientation: { type: "string", title: "Orientation", enum: ["horizontal", "vertical"], default: "horizontal" },
    actuator: { type: "boolean", title: "Show actuator", default: true },
  },
};

/** - */
export const motorSchema: JsonSchema = {
  $schema: draft,
  type: "object",
  title: "Motor",
  additionalProperties: false,
  properties: {
    state: state("stopped"),
    label: { type: "string", title: "Label", default: "Motor" },
    palette,
    direction: { type: "string", title: "Shaft side", enum: ["right", "left"], default: "right" },
  },
};

/** - */
export const vesselSchema: JsonSchema = {
  $schema: draft,
  type: "object",
  title: "Vessel",
  additionalProperties: false,
  properties: {
    state: state("normal"),
    label: { type: "string", title: "Label", default: "Vessel" },
    ...levelProps,
  },
};

/** Instrument bubble. The symbols palette page lists a sensor symbol alongside pump, valve, motor and vessel. */
export const sensorSchema: JsonSchema = {
  $schema: draft,
  type: "object",
  title: "Sensor",
  additionalProperties: false,
  properties: {
    state: state("normal"),
    label: { type: "string", title: "Label", default: "Sensor" },
    tag: { type: "string", title: "Function letters (for example PT, TT, FT)", default: "PT" },
    value: { type: "number", title: "Value" },
    unit: { type: "string", title: "Unit", default: "" },
    decimals: { type: "integer", title: "Decimals", minimum: 0, maximum: 6, default: 1 },
  },
};

/** - */
export const cylindricalTankSchema: JsonSchema = {
  $schema: draft,
  type: "object",
  title: "Cylindrical tank",
  additionalProperties: false,
  properties: {
    state: state("normal"),
    label: { type: "string", title: "Label", default: "Tank" },
    ...levelProps,
    unit: { type: "string", title: "Level unit (empty shows percent)", default: "" },
    decimals: { type: "integer", title: "Decimals", minimum: 0, maximum: 6, default: 0 },
  },
};
