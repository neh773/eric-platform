import type { BarSpec, ComponentSpec, PieSpec, TimeSeriesSpec, XySpec, YAxisSpec } from "./layout";

/**
 * Data-key binding of chart settings, mirrored from gateway/report-render ChartBinding.kt (REP-059). A chart's
 * dataset, column names, series names, title and axis label may hold `@Name@` keys, and an axis min or max may be
 * one. An axis key that is not a number is reported in `problems` and the axis stays automatic.
 */

export interface BoundComponent {
  spec: ComponentSpec;
  problems: string[];
}

export function bindComponent(spec: ComponentSpec, fill: (text: string) => string): BoundComponent {
  const problems: string[] = [];
  const axis = (a: YAxisSpec, name: string): YAxisSpec => {
    const number = (key: string | undefined, fixed: number | undefined, which: string): number | undefined => {
      if (key === undefined) return fixed;
      const text = fill(key).trim();
      const value = text === "" ? Number.NaN : Number(text);
      if (Number.isFinite(value)) return value;
      problems.push(`${name}.${which} '${key}' is not a number (${text === key ? "unknown key" : `got '${text}'`}); using automatic`);
      return undefined;
    };
    return { ...a, label: a.label === undefined ? undefined : fill(a.label), min: number(a.minKey, a.min, "min"), max: number(a.maxKey, a.max, "max") };
  };
  const title = spec.title === undefined ? undefined : fill(spec.title);
  switch (spec.type) {
    case "timeSeries": {
      const out: TimeSeriesSpec = {
        ...spec,
        title,
        dataset: fill(spec.dataset),
        series: spec.series.map(fill),
        x: spec.x === undefined ? undefined : fill(spec.x),
        y: spec.y.map(fill),
        yAxis: axis(spec.yAxis, "yAxis"),
      };
      return { spec: out, problems };
    }
    case "bar": {
      const out: BarSpec = { ...spec, title, dataset: fill(spec.dataset), category: fill(spec.category), values: spec.values.map(fill), yAxis: axis(spec.yAxis, "yAxis") };
      return { spec: out, problems };
    }
    case "pie": {
      const out: PieSpec = { ...spec, title, dataset: fill(spec.dataset), category: fill(spec.category), value: fill(spec.value) };
      return { spec: out, problems };
    }
    case "xy": {
      const out: XySpec = { ...spec, title, dataset: fill(spec.dataset), x: fill(spec.x), y: spec.y.map(fill), xAxis: axis(spec.xAxis, "xAxis"), yAxis: axis(spec.yAxis, "yAxis") };
      return { spec: out, problems };
    }
    default:
      return { spec, problems };
  }
}

/** Only base64 PNG and JPEG data URIs, at most 4 MB decoded, are drawn; same rule as the PDF. */
export function imageSource(src: string): string | null {
  const m = /^data:image\/(png|jpeg|jpg);base64,([A-Za-z0-9+/=\s]+)$/.exec(src);
  if (!m) return null;
  const body = m[2]!.replace(/\s+/g, "");
  if (body.length === 0 || (body.length * 3) / 4 > 4 * 1024 * 1024) return null;
  return `data:image/${m[1] === "png" ? "png" : "jpeg"};base64,${body}`;
}
