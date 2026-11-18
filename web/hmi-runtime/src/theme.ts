import type { CSSProperties } from "react";
import { isStyleColor } from "./styleSubset";
import type { CustomTheme, CustomThemeSet, ThemeBrand } from "./types";

export type { CustomTheme, CustomThemeSet, ThemeBrand };

/** Session themes. Tokens are applied as CSS custom properties on the session root.
 * Writing `session.theme` swaps the set.
 *
 * Dark (Graphite) is the default. Light (Daylight) is the other appearance.
 * Saved names keep resolving: `nightwatch` and `process` are Dark, `daylight` is Light.
 * `process` is not a separate palette. See docs/ui/STYLE_GUIDE.md.
 */

export interface ThemeTokens {
  "color-canvas": string;
  "color-surface": string;
  "color-ink": string;
  "color-muted": string;
  "color-line": string;
  "color-accent": string;
  "color-accent-ink": string;
  "color-ok": string;
  "color-alarm": string;
  "color-warning": string;
  "color-idle": string;
  /** Pump, valve and motor bodies while running (open) and stopped (closed). */
  "color-running": string;
  "color-stopped": string;
  "font-ui": string;
  "font-mono": string;
  "radius": string;
}

const UI_FONT = '"Inter", "Segoe UI", system-ui, sans-serif';
const MONO_FONT = '"JetBrains Mono", ui-monospace, "Cascadia Code", "SFMono-Regular", monospace';

/** Graphite. Also the tokens for the legacy names `nightwatch` and `process`. */
const graphite: ThemeTokens = {
  "color-canvas": "#0b0d10",
  "color-surface": "#14171c",
  "color-ink": "#f2f4f7",
  "color-muted": "#a3abb8",
  "color-line": "#2a2f38",
  "color-accent": "#3d8bff",
  "color-accent-ink": "#ffffff",
  "color-ok": "#32d583",
  "color-alarm": "#ff453a",
  "color-warning": "#ff9f0a",
  "color-idle": "#6e7784",
  "color-running": "#f2f4f7",
  "color-stopped": "#5b6370",
  "font-ui": UI_FONT,
  "font-mono": MONO_FONT,
  "radius": "6px",
};

/** Daylight, the light appearance. */
const daylight: ThemeTokens = {
  "color-canvas": "#f4f5f7",
  "color-surface": "#ffffff",
  "color-ink": "#0b0d10",
  "color-muted": "#4a5260",
  "color-line": "#e1e4e8",
  "color-accent": "#0a66e0",
  "color-accent-ink": "#ffffff",
  "color-ok": "#11804b",
  "color-alarm": "#d70f1f",
  "color-warning": "#b54a00",
  "color-idle": "#6b7280",
  "color-running": "#0b0d10",
  "color-stopped": "#9aa1ac",
  "font-ui": UI_FONT,
  "font-mono": MONO_FONT,
  "radius": "6px",
};

export const themes: Record<string, ThemeTokens> = {
  daylight,
  nightwatch: graphite,
  /** Legacy name. Same tokens as Dark (`nightwatch`). Not a third appearance. */
  process: graphite,
};

/** Names a saved project may already store, mapped onto Dark or Light. */
export function canonicalTheme(name: string): string {
  if (name === "process" || name === "graphite" || name === "dark" || name === "nightwatch") return "nightwatch";
  if (name === "light" || name === "daylight") return "daylight";
  return name;
}

export const themeNames = Object.keys(themes);

const FONT_TOKEN = /^[A-Za-z0-9 ,'"-]{1,200}$/;
const RADIUS = /^\d+(?:\.\d+)?px$/;

export class ThemeError extends Error {}

/** A project theme extends daylight or nightwatch. Unknown names fall back to Dark. */
export function resolveTheme(name: string, custom?: CustomThemeSet): ThemeTokens {
  const canonical = canonicalTheme(name);
  if (themes[canonical]) return themes[canonical]!;
  const theme = custom?.[name];
  if (!theme || (theme.extends !== "daylight" && theme.extends !== "nightwatch")) return themes.nightwatch!;
  return { ...themes[theme.extends]!, ...validTokens(theme.tokens) };
}

function validTokens(tokens: Record<string, string>): Partial<ThemeTokens> {
  const out: Partial<ThemeTokens> = {};
  for (const [key, value] of Object.entries(tokens)) {
    if (!isThemeToken(key, value)) continue;
    out[key as keyof ThemeTokens] = value;
  }
  return out;
}

export function isThemeToken(key: string, value: string): boolean {
  if (!(key in themes.daylight!)) return false;
  if (key.startsWith("color-")) return isStyleColor(value);
  if (key.startsWith("font-")) return FONT_TOKEN.test(value);
  if (key === "radius") return RADIUS.test(value);
  return false;
}

const THEME_NAME = /^[A-Za-z][A-Za-z0-9_-]*$/;

/** Project themes from a style-class resource. An empty object is fine. A bad entry throws. */
export function parseCustomThemes(input: unknown): CustomThemeSet {
  if (input === undefined || input === null) return {};
  if (typeof input !== "object" || Array.isArray(input)) throw new ThemeError("themes must be an object");
  const entries = Object.entries(input as Record<string, unknown>);
  if (entries.length > 32) throw new ThemeError("a project has at most 32 themes");
  const set: CustomThemeSet = {};
  for (const [name, raw] of entries) {
    if (!THEME_NAME.test(name) || name === "daylight" || name === "nightwatch") throw new ThemeError(`theme name "${name}" is not valid`);
    if (typeof raw !== "object" || raw === null || Array.isArray(raw)) throw new ThemeError(`themes.${name} must be an object`);
    const record = raw as Record<string, unknown>;
    if (record.extends !== "daylight" && record.extends !== "nightwatch") throw new ThemeError(`themes.${name}.extends must be daylight or nightwatch`);
    const tokens = record.tokens;
    if (typeof tokens !== "object" || tokens === null || Array.isArray(tokens)) throw new ThemeError(`themes.${name}.tokens must be an object`);
    const parsed: Record<string, string> = {};
    for (const [key, value] of Object.entries(tokens as Record<string, unknown>)) {
      if (typeof value !== "string" || !isThemeToken(key, value)) throw new ThemeError(`themes.${name}.tokens.${key} is not a theme token`);
      parsed[key] = value;
    }
    const theme: CustomTheme = { extends: record.extends, tokens: parsed };
    if (record.brand !== undefined) theme.brand = parseBrand(record.brand, `themes.${name}.brand`);
    set[name] = theme;
  }
  return set;
}

/**
 * An image the brand bar may load: http(s), site-relative, or `data:image/*`. Any other scheme, a protocol-relative
 * `//host`, or text with a space or control character is refused.
 */
export function brandImageSource(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const source = value.trim();
  if (!source || source.length > 4096 || /[\s\\]/.test(source) || [...source].some((char) => char.charCodeAt(0) < 32)) return null;
  const scheme = /^([a-z][a-z0-9+.-]*):/i.exec(source);
  if (!scheme) return source.startsWith("//") ? null : source;
  const name = scheme[1]!.toLowerCase();
  if (name === "http" || name === "https") return source;
  if (name === "data" && /^data:image\/(?:png|jpeg|gif|webp|svg\+xml)[;,]/i.test(source)) return source;
  return null;
}

const BRAND_TEXT_LIMIT = 120;

function parseBrand(input: unknown, where: string): ThemeBrand {
  if (typeof input !== "object" || input === null || Array.isArray(input)) throw new ThemeError(`${where} must be an object`);
  const record = input as Record<string, unknown>;
  const brand: ThemeBrand = {};
  for (const key of Object.keys(record)) {
    if (!["name", "logo", "logoAlt", "graphic"].includes(key)) throw new ThemeError(`${where}.${key} is not a brand field`);
  }
  for (const key of ["name", "logoAlt"] as const) {
    const value = record[key];
    if (value === undefined) continue;
    if (typeof value !== "string" || value.length > BRAND_TEXT_LIMIT) throw new ThemeError(`${where}.${key} must be text of at most ${BRAND_TEXT_LIMIT} characters`);
    if (value.trim() !== "") brand[key] = value;
  }
  for (const key of ["logo", "graphic"] as const) {
    const value = record[key];
    if (value === undefined || value === "") continue;
    const source = brandImageSource(value);
    if (!source) throw new ThemeError(`${where}.${key} must be an http(s), site-relative or data:image source`);
    brand[key] = source;
  }
  return brand;
}

/** The brand of theme [name], or null when it is a built-in theme or its theme has no brand. */
export function themeBrand(name: string, custom?: CustomThemeSet): ThemeBrand | null {
  const brand = custom?.[name]?.brand;
  if (!brand) return null;
  const logo = brandImageSource(brand.logo);
  const graphic = brandImageSource(brand.graphic);
  const out: ThemeBrand = {};
  if (brand.name) out.name = brand.name;
  if (logo) out.logo = logo;
  if (brand.logoAlt) out.logoAlt = brand.logoAlt;
  if (graphic) out.graphic = graphic;
  return Object.keys(out).length > 0 ? out : null;
}

export function themeStyle(name: string, custom?: CustomThemeSet): CSSProperties {
  const tokens = resolveTheme(name, custom);
  const style: Record<string, string> = {};
  for (const [key, value] of Object.entries(tokens)) style[`--oip-${key}`] = value;
  return style as CSSProperties;
}
