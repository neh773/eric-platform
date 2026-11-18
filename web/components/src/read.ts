export function readString(props: Record<string, unknown>, key: string, fallback = ""): string {
  const value = props[key];
  return typeof value === "string" ? value : fallback;
}

/** Label text. A finite number is written out so a numeric binding is visible; anything else that is not text uses the fallback. */
export function labelText(props: Record<string, unknown>, key: string, fallback = ""): string {
  const value = props[key];
  if (typeof value === "string") return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return fallback;
}

export function readNumber(props: Record<string, unknown>, key: string, fallback: number): number {
  const value = props[key];
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

export function readBoolean(props: Record<string, unknown>, key: string, fallback: boolean): boolean {
  const value = props[key];
  return typeof value === "boolean" ? value : fallback;
}

const numberFormats = new Map<string, Intl.NumberFormat>();

/** Formats with a cached Intl.NumberFormat per locale and digit count; building one costs more than formatting. */
export function formatNumber(value: number, decimals: number, locale: string): string {
  const key = `${locale}|${decimals}`;
  let format = numberFormats.get(key);
  if (!format) {
    format = new Intl.NumberFormat(locale, { minimumFractionDigits: decimals, maximumFractionDigits: decimals, useGrouping: false });
    if (numberFormats.size >= 64) numberFormats.clear();
    numberFormats.set(key, format);
  }
  return format.format(value);
}

/** Average advance of a monospaced digit, as a fraction of the font size. Slightly generous, so a fit is never late. */
const MONO_ADVANCE = 0.62;

/**
 * Scale-to-fit for one line of SVG text set in the monospaced UI font: when [text] at [fontSize] would be wider than
 * [maxWidth] (both in the SVG's user units), returns `textLength` and `lengthAdjust` so the browser squeezes the
 * glyphs into [maxWidth]; otherwise returns no attributes and the text keeps its natural size.
 */
export function fitSvgText(text: string, fontSize: number, maxWidth: number): { textLength?: number; lengthAdjust?: "spacingAndGlyphs" } {
  if (text.length * fontSize * MONO_ADVANCE <= maxWidth) return {};
  return { textLength: maxWidth, lengthAdjust: "spacingAndGlyphs" };
}
