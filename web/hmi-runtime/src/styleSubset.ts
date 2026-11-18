import type { CSSProperties } from "react";
import type { StyleProps } from "./types";

/**
 * The CSS subset a component's style (and a style class) may set (HMP-093, P4-07).
 *
 * A style is a small object of known properties, never free CSS text: each property has a type (a color, a pixel
 * length, a bounded number or one of a few keywords) and a value is checked against that type. Colors are hex,
 * a color keyword, `rgb()`/`rgba()`/`hsl()`/`hsla()` with numbers only, or a theme variable `var(--name)`. Nothing that
 * can load a resource (`url()`, `image-set()`, `@import`), run code (`expression()`), escape a declaration (`;`, `{`,
 * `}`, `\`, comments) or override everything (`!important`) gets through. The Studio's style editor, the view parser,
 * the style class parser and the gateway's save check all use these rules.
 *
 * Doc source: FEATURES HMP-093.
 */

export type StyleKey = keyof StyleProps;

export type StyleValueType =
  | { type: "color" }
  | { type: "length"; min?: number }
  | { type: "number"; min: number; max: number }
  | { type: "enum"; values: readonly string[] }
  | { type: "font" };

export interface StylePropertySpec {
  key: StyleKey;
  /** CSS property name, as typed in CSS text. */
  css: string;
  /** Other CSS names accepted in CSS text. */
  aliases?: readonly string[];
  title: string;
  value: StyleValueType;
}

export const STYLE_PROPERTIES: readonly StylePropertySpec[] = [
  { key: "color", css: "color", title: "Text color", value: { type: "color" } },
  { key: "background", css: "background-color", aliases: ["background"], title: "Background", value: { type: "color" } },
  { key: "fontSize", css: "font-size", title: "Font size", value: { type: "length", min: 1 } },
  { key: "fontWeight", css: "font-weight", title: "Font weight", value: { type: "number", min: 1, max: 1000 } },
  { key: "fontStyle", css: "font-style", title: "Font style", value: { type: "enum", values: ["normal", "italic"] } },
  { key: "fontFamily", css: "font-family", title: "Font family", value: { type: "font" } },
  { key: "textAlign", css: "text-align", title: "Text align", value: { type: "enum", values: ["left", "center", "right", "justify"] } },
  {
    key: "textDecoration",
    css: "text-decoration",
    title: "Text decoration",
    value: { type: "enum", values: ["none", "underline", "line-through", "overline"] },
  },
  {
    key: "textTransform",
    css: "text-transform",
    title: "Text transform",
    value: { type: "enum", values: ["none", "uppercase", "lowercase", "capitalize"] },
  },
  { key: "lineHeight", css: "line-height", title: "Line height", value: { type: "number", min: 0, max: 10 } },
  { key: "letterSpacing", css: "letter-spacing", title: "Letter spacing", value: { type: "length" } },
  { key: "padding", css: "padding", title: "Padding", value: { type: "length", min: 0 } },
  { key: "margin", css: "margin", title: "Margin", value: { type: "length" } },
  { key: "borderWidth", css: "border-width", title: "Border width", value: { type: "length", min: 0 } },
  {
    key: "borderStyle",
    css: "border-style",
    title: "Border style",
    value: { type: "enum", values: ["none", "solid", "dashed", "dotted", "double"] },
  },
  { key: "borderColor", css: "border-color", title: "Border color", value: { type: "color" } },
  { key: "borderRadius", css: "border-radius", title: "Border radius", value: { type: "length", min: 0 } },
  { key: "opacity", css: "opacity", title: "Opacity", value: { type: "number", min: 0, max: 1 } },
  { key: "overflow", css: "overflow", title: "Overflow", value: { type: "enum", values: ["visible", "hidden", "auto", "scroll"] } },
  {
    key: "cursor",
    css: "cursor",
    title: "Cursor",
    value: { type: "enum", values: ["auto", "default", "pointer", "not-allowed", "text", "crosshair", "move"] },
  },
];

const BY_KEY = new Map<string, StylePropertySpec>(STYLE_PROPERTIES.map((spec) => [spec.key, spec]));
const BY_CSS = new Map<string, StylePropertySpec>();
for (const spec of STYLE_PROPERTIES) {
  BY_CSS.set(spec.css, spec);
  for (const alias of spec.aliases ?? []) BY_CSS.set(alias, spec);
}

export const STYLE_KEYS: readonly StyleKey[] = STYLE_PROPERTIES.map((spec) => spec.key);

export function styleProperty(key: string): StylePropertySpec | undefined {
  return BY_KEY.get(key);
}

const HEX = /^#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
const KEYWORD = /^[A-Za-z]{3,30}$/;
const FUNCTION_COLOR = /^(?:rgba?|hsla?)\(\s*[0-9.,%\s/+-]*(?:deg[0-9.,%\s/+-]*)?\)$/i;
const THEME_VAR = /^var\(\s*--[A-Za-z0-9_-]{1,64}\s*\)$/;
const FONT = /^[A-Za-z0-9 ,'"-]{1,200}$/;
/** Text that never belongs in a style value. */
const FORBIDDEN = /[;{}<>\\@]|\/\*|!important|url\s*\(|expression\s*\(|image-set\s*\(|javascript:/i;

export function isStyleColor(value: string): boolean {
  const text = value.trim();
  if (FORBIDDEN.test(text)) return false;
  return HEX.test(text) || KEYWORD.test(text) || FUNCTION_COLOR.test(text) || THEME_VAR.test(text);
}

/** Why [value] is not allowed for [spec], or null when it is. */
export function styleValueProblem(spec: StylePropertySpec, value: unknown): string | null {
  const kind = spec.value;
  if (kind.type === "color") {
    if (typeof value !== "string" || !isStyleColor(value)) return `${spec.css} must be a color (hex, a color name, rgb(), hsl() or var(--theme-variable))`;
    return null;
  }
  if (kind.type === "font") {
    if (typeof value !== "string" || FORBIDDEN.test(value) || !(FONT.test(value.trim()) || THEME_VAR.test(value.trim()))) {
      return `${spec.css} must be font names (letters, digits, spaces, commas, quotes and -) or var(--theme-variable)`;
    }
    return null;
  }
  if (kind.type === "enum") {
    if (typeof value !== "string" || !kind.values.includes(value)) return `${spec.css} must be one of ${kind.values.join(", ")}`;
    return null;
  }
  if (typeof value !== "number" || !Number.isFinite(value)) return `${spec.css} must be a number`;
  if (kind.type === "length") {
    if (kind.min !== undefined && value < kind.min) return `${spec.css} must be at least ${kind.min}px`;
    return null;
  }
  if (value < kind.min || value > kind.max) return `${spec.css} must be from ${kind.min} to ${kind.max}`;
  return null;
}

export class StyleError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "StyleError";
  }
}

/**
 * A stored style object checked against the subset. Throws [StyleError] (with [where] in the message) for a property
 * outside it or a value of the wrong type. Enum and color text is trimmed.
 */
export function parseStyleObject(input: unknown, where: string): StyleProps {
  if (input === undefined) return {};
  if (input === null || typeof input !== "object" || Array.isArray(input)) throw new StyleError(`${where} must be an object`);
  const style: Record<string, unknown> = {};
  for (const [key, raw] of Object.entries(input as Record<string, unknown>)) {
    const spec = BY_KEY.get(key);
    if (!spec) throw new StyleError(`${where}.${key} is not a supported style property`);
    const value = typeof raw === "string" ? raw.trim() : raw;
    const problem = styleValueProblem(spec, value);
    if (problem) throw new StyleError(`${where}.${key}: ${problem}`);
    style[key] = value;
  }
  return style as StyleProps;
}

export interface CssTextResult {
  style: StyleProps;
  /** One message per declaration that was refused; the accepted ones are in [style]. */
  errors: string[];
}

/**
 * Reads CSS declaration text (`color: #c00; border-radius: 4px`) into a style. Each declaration is checked on its own:
 * one outside the subset is refused with a message, the rest are kept. Lengths take a plain number or `px`; other
 * units are refused. Text with braces, comments, backslashes, `@` rules or `<` is refused as a whole.
 */
export function cssTextToStyle(text: string): CssTextResult {
  const errors: string[] = [];
  const style: Record<string, unknown> = {};
  if (/[{}<>\\@]|\/\*/.test(text)) return { style: {}, errors: ["CSS text may hold only declarations (name: value;), not rules, comments, @ or < >"] };
  for (const part of text.split(";")) {
    const declaration = part.trim();
    if (!declaration) continue;
    const colon = declaration.indexOf(":");
    if (colon <= 0) {
      errors.push(`"${declaration}" is not a declaration (name: value)`);
      continue;
    }
    const name = declaration.slice(0, colon).trim().toLowerCase();
    const rawValue = declaration.slice(colon + 1).trim();
    const spec = BY_CSS.get(name);
    if (!spec) {
      errors.push(`${name} is not a supported style property`);
      continue;
    }
    if (FORBIDDEN.test(rawValue)) {
      errors.push(`${name}: "${rawValue}" is not allowed`);
      continue;
    }
    let value: unknown = rawValue;
    if (spec.value.type === "length" || spec.value.type === "number") {
      const match = spec.value.type === "length" ? /^(-?\d+(?:\.\d+)?)(px)?$/i.exec(rawValue) : /^(-?\d+(?:\.\d+)?)$/.exec(rawValue);
      if (!match) {
        errors.push(spec.value.type === "length" ? `${name} takes a number of pixels (such as 4px)` : `${name} takes a number`);
        continue;
      }
      value = Number(match[1]);
    } else if (spec.value.type === "enum") {
      value = rawValue.toLowerCase();
    }
    const problem = styleValueProblem(spec, value);
    if (problem) {
      errors.push(problem);
      continue;
    }
    style[spec.key] = value;
  }
  return { style: style as StyleProps, errors };
}

/** CSS declaration text for a style, in [STYLE_PROPERTIES] order. */
export function styleToCssText(style: StyleProps | undefined): string {
  if (!style) return "";
  const parts: string[] = [];
  for (const spec of STYLE_PROPERTIES) {
    const value = style[spec.key];
    if (value === undefined) continue;
    parts.push(`${spec.css}: ${spec.value.type === "length" ? `${String(value)}px` : String(value)}`);
  }
  return parts.join("; ");
}

/** React inline style for a (checked) style. Lengths are pixels. */
export function styleSubsetToCss(style: StyleProps | undefined): CSSProperties {
  if (!style) return {};
  const css: Record<string, unknown> = {};
  for (const spec of STYLE_PROPERTIES) {
    const value = style[spec.key];
    if (value === undefined) continue;
    const name = spec.key === "background" ? "background" : spec.key;
    css[name] = spec.value.type === "length" ? `${String(value)}px` : value;
  }
  return css as CSSProperties;
}

/** Properties [patch] sets replace those in [base]; an `undefined` in [patch] leaves the base value. */
export function mergeStyle(base: StyleProps, patch: StyleProps | undefined): StyleProps {
  if (!patch) return base;
  const next: Record<string, unknown> = { ...base };
  for (const key of STYLE_KEYS) {
    const value = patch[key];
    if (value !== undefined) next[key] = value;
  }
  return next as StyleProps;
}
