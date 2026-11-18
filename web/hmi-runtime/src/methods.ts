import type { Action, MethodDef } from "./types";

/**
 * Custom methods (HMV-114): resolving a call's arguments against the method's declared parameters, and putting the
 * arguments into an action's values. Pure functions; the renderer runs the result.
 */

/** Nested calls (a method calling a method) stop at this depth, so a method that calls itself ends. */
export const MAX_METHOD_DEPTH = 8;

export const METHOD_NAME = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
export const METHOD_PARAM_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

/**
 * The arguments a method runs with: each declared parameter takes the given value, or its default (null with none).
 * A value given for a parameter the method does not declare is ignored.
 */
export function resolveMethodArgs(def: MethodDef, given: unknown): Record<string, unknown> {
  const supplied = isRecord(given) ? given : {};
  const args: Record<string, unknown> = {};
  for (const param of def.params ?? []) {
    if (Object.prototype.hasOwnProperty.call(supplied, param.name) && supplied[param.name] !== undefined) args[param.name] = supplied[param.name];
    else args[param.name] = param.default === undefined ? null : param.default;
  }
  return args;
}

const WHOLE = /^\{\{([A-Za-z_][A-Za-z0-9_]*)\}\}$/;
const INSIDE = /\{\{([A-Za-z_][A-Za-z0-9_]*)\}\}/g;

/** Text with `{{name}}` replaced. The whole text being one placeholder gives the argument as it is (any type). */
export function substituteText(text: string, args: Record<string, unknown>): unknown {
  const whole = WHOLE.exec(text);
  if (whole) return Object.prototype.hasOwnProperty.call(args, whole[1]!) ? args[whole[1]!] : text;
  return text.replace(INSIDE, (match, name: string) => {
    if (!Object.prototype.hasOwnProperty.call(args, name)) return match;
    const value = args[name];
    return value !== null && typeof value === "object" ? JSON.stringify(value) : String(value ?? "");
  });
}

function substituteDeep(value: unknown, args: Record<string, unknown>): unknown {
  if (typeof value === "string") return substituteText(value, args);
  if (Array.isArray(value)) return value.map((entry) => substituteDeep(entry, args));
  if (isRecord(value)) return Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, substituteDeep(entry, args)]));
  return value;
}

/**
 * [action] with the arguments put into its values: a tag write's value, the params of a navigation or popup, a
 * popup's title, a message's payload, a nested call's args. Paths, view names, URLs, message types and script hooks are
 * never substituted, so an argument cannot choose where an action goes.
 */
export function bindMethodArgs(action: Action, args: Record<string, unknown>): Action {
  switch (action.kind) {
    case "tag-write": {
      const value = typeof action.value === "string" ? substituteText(action.value, args) : action.value;
      const scalar = typeof value === "string" || typeof value === "number" || typeof value === "boolean" || value === null;
      return { ...action, value: scalar ? value : JSON.stringify(value) };
    }
    case "navigation":
      return action.params ? { ...action, params: substituteDeep(action.params, args) as Record<string, unknown> } : action;
    case "popup": {
      const next = { ...action };
      if (action.params) next.params = substituteDeep(action.params, args) as Record<string, unknown>;
      if (action.title !== undefined) next.title = String(substituteText(action.title, args));
      return next;
    }
    case "message":
      return action.payload ? { ...action, payload: substituteDeep(action.payload, args) as Record<string, unknown> } : action;
    case "call-method":
      return action.args ? { ...action, args: substituteDeep(action.args, args) as Record<string, unknown> } : action;
    default:
      return action;
  }
}
