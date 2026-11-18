import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { bindComponent, imageSource } from "./chartBinding";
import { LayoutError, parseLayout } from "./layout";
import { percentText, pieSlices, slicePath } from "./pie";
import { ReportPreview } from "./ReportPreview";
import { autoWeights, groupRows, textLength } from "./tableGroups";
import type { ReportDataset, ReportResult } from "./types";

/**
 * Preview of the report components added after the first catalog: grouped and auto-sized tables (REP-050, REP-016),
 * pie and XY charts (REP-004, REP-005), data-key bound chart settings (REP-059) and images (REP-023). The cases
 * mirror gateway/report-render ReportComponentsTest so the preview and the PDF follow the same rules.
 */

function result(overrides: Partial<ReportResult>): ReportResult {
  return {
    contractVersion: 1,
    report: { id: "reporting/gateway/Report/t", name: "t", title: "T", version: 1 },
    parameters: [],
    generatedAt: 1761544800000,
    datasets: [],
    ...overrides,
  };
}

const prod: ReportDataset = {
  name: "prod",
  sourceType: "sql",
  columns: [
    { name: "line", type: "string" },
    { name: "shift", type: "string" },
    { name: "count", type: "number" },
  ],
  rows: [
    ["L2", "A", 3],
    ["L1", "A", 5],
    ["L2", "B", 4],
    ["L1", "B", 7],
    ["L1", "C", 1],
  ],
};

const points: ReportDataset = {
  name: "pts",
  sourceType: "sql",
  columns: [
    { name: "speed", type: "number", unit: "rpm" },
    { name: "temp", type: "number", unit: "C" },
    { name: "load", type: "number" },
  ],
  rows: [
    [100, 20.5, 1],
    [200, 35, 2],
    [300, 51, null],
  ],
};

// 1x1 transparent PNG
const PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

describe("grouped table (REP-050)", () => {
  it("groups rows by first appearance and keeps row order", () => {
    const groups = groupRows(prod.rows, 0);
    expect(groups.map((g) => g.key)).toEqual(["L2", "L1"]);
    expect(groups.map((g) => g.rows.length)).toEqual([2, 3]);
    expect(groups[1]!.rows.map((r) => r[1])).toEqual(["A", "B", "C"]);
    const withNull = groupRows([[null, 1], [null, 2], ["x", 3]], 0);
    expect(withNull).toHaveLength(2);
    expect(withNull[0]!.key).toBeUndefined();
    expect(withNull[0]!.rows).toHaveLength(2);
    // the string "5" and the number 5 are different keys, as in the PDF
    expect(groupRows([["5"], [5]], 0)).toHaveLength(2);
  });

  it("shows group headings, subtotals and a grand total", () => {
    const { container } = render(
      <ReportPreview
        result={result({ datasets: [prod], layout: { components: [{ type: "table", dataset: "prod", groupBy: "line", groupSummary: true, summary: true }] } })}
      />,
    );
    const headings = [...container.querySelectorAll("tr.oip-report-group")].map((r) => r.textContent);
    expect(headings).toEqual(["line: L2 (2)", "line: L1 (3)"]);
    const totals = [...container.querySelectorAll("tr.oip-report-totals")].map((r) => r.textContent);
    expect(totals).toEqual(["Subtotal7", "Subtotal13", "Total20"]);
    // L2 group: heading, two detail rows, subtotal
    const rows = [...container.querySelectorAll("tbody tr")];
    expect(rows[0]!.className).toBe("oip-report-group");
    expect(rows[3]!.textContent).toBe("Subtotal7");
  });

  it("has no subtotals unless asked and notes a missing group column", () => {
    const plain = render(<ReportPreview result={result({ datasets: [prod], layout: { components: [{ type: "table", dataset: "prod", groupBy: "shift" }] } })} />);
    expect(plain.container.querySelectorAll("tr.oip-report-group")).toHaveLength(3);
    expect(plain.container.querySelectorAll("tr.oip-report-totals")).toHaveLength(0);
    plain.unmount();
    render(<ReportPreview result={result({ datasets: [prod], layout: { components: [{ type: "table", dataset: "prod", groupBy: "nope" }] } })} />);
    expect(screen.getByText(/has no column 'nope'/)).toBeInTheDocument();
  });
});

describe("auto-sized table (REP-016)", () => {
  it("clamps weights to 4..40 characters", () => {
    expect(autoWeights([1, 12, 300])).toEqual([4, 12, 40]);
    expect(textLength("id", ["a", "123456789"])).toBe(9);
    expect(textLength("hello", [])).toBe(5);
  });

  it("sets column widths from the text", () => {
    const long = "z".repeat(38);
    const ds: ReportDataset = {
      name: "w",
      sourceType: "sql",
      columns: ["a", "b", "c", "d", "e"].map((name) => ({ name, type: "string" as const })),
      rows: [["1", "2", "3", long, long]],
    };
    const widths = (autoSize: boolean) => {
      const { container, unmount } = render(<ReportPreview result={result({ datasets: [ds], layout: { components: [{ type: "table", dataset: "w", autoSize }] } })} />);
      const out = [...container.querySelectorAll("col")].map((c) => parseFloat((c as HTMLElement).style.width));
      unmount();
      return out;
    };
    expect(widths(false)).toEqual([20, 20, 20, 20, 20]);
    const auto = widths(true);
    expect(auto[0]).toBeCloseTo((100 * 4) / 88, 1);
    expect(auto[3]).toBeCloseTo((100 * 38) / 88, 1);
  });
});

describe("pie chart (REP-004)", () => {
  it("ignores non-positive values and merges the smallest beyond 12 into Other", () => {
    expect(pieSlices([["a", 0], ["b", -1], ["c", null], ["d", Number.NaN]])).toEqual([]);
    const s = pieSlices([["a", 1], ["b", 3], ["c", 0]]);
    expect(s.map((x) => x.label)).toEqual(["a", "b"]);
    expect(s[0]!.fraction).toBeCloseTo(0.25);
    expect(percentText(0.25)).toBe("25.0%");
    expect(percentText(1 / 3)).toBe("33.3%");
    const many = pieSlices(Array.from({ length: 15 }, (_, i): [string, number] => [`c${i + 1}`, i + 1]));
    expect(many).toHaveLength(12);
    expect(many[11]).toMatchObject({ label: "Other", value: 10 });
    expect(many.slice(0, 2).map((x) => x.label)).toEqual(["c5", "c6"]);
    expect(many.reduce((sum, x) => sum + x.fraction, 0)).toBeCloseTo(1);
  });

  it("draws one path per slice with a percent legend", () => {
    const ds: ReportDataset = {
      name: "prod",
      sourceType: "sql",
      columns: [
        { name: "line", type: "string" },
        { name: "count", type: "number", unit: "pcs" },
      ],
      rows: [["L1", 1], ["L2", 3]],
    };
    render(<ReportPreview result={result({ datasets: [ds], layout: { components: [{ type: "pie", title: "Share", dataset: "prod", category: "line", value: "count" }] } })} />);
    const chart = screen.getByRole("img", { name: "Share" });
    expect([...chart.querySelectorAll("path[data-slice]")].map((p) => p.getAttribute("data-slice"))).toEqual(["L1", "L2"]);
    expect(within(chart as unknown as HTMLElement).getByText("L1 (25.0%) (pcs)")).toBeInTheDocument();
    expect(within(chart as unknown as HTMLElement).getByText("L2 (75.0%) (pcs)")).toBeInTheDocument();
  });

  it("draws a single slice as a full circle and can hide percentages", () => {
    const ds: ReportDataset = { name: "p", sourceType: "sql", columns: [{ name: "k", type: "string" }, { name: "v", type: "number" }], rows: [["only", 9]] };
    render(<ReportPreview result={result({ datasets: [ds], layout: { components: [{ type: "pie", dataset: "p", category: "k", value: "v", showPercent: false }] } })} />);
    const chart = screen.getByRole("img");
    expect(chart.querySelector("path[data-slice='only']")!.getAttribute("d")!.match(/A/g)).toHaveLength(2);
    expect(within(chart as unknown as HTMLElement).getByText("only")).toBeInTheDocument();
    expect(slicePath(0, 0, 10, 0, 0.25)).toBe("M0.00 0.00 L0.00 -10.00 A10.00 10.00 0 0 1 10.00 0.00 Z");
    expect(slicePath(0, 0, 10, 0, 0.75)).toContain("0 1 1");
  });

  it("notes missing columns and no data", () => {
    const ds: ReportDataset = { name: "p", sourceType: "sql", columns: [{ name: "k", type: "string" }, { name: "v", type: "number" }], rows: [["a", 0]] };
    render(
      <ReportPreview
        result={result({
          datasets: [ds],
          layout: { components: [{ type: "pie", dataset: "p", category: "k", value: "v" }, { type: "pie", dataset: "p", category: "k", value: "zz" }] },
        })}
      />,
    );
    expect(screen.getByText("No data to chart.")).toBeInTheDocument();
    expect(screen.getByText(/has no column 'zz'/)).toBeInTheDocument();
  });
});

describe("xy chart (REP-005)", () => {
  it("draws markers, joined lines and axis labels", () => {
    render(
      <ReportPreview
        result={result({ datasets: [points], layout: { components: [{ type: "xy", title: "Curve", dataset: "pts", x: "speed", y: ["temp", "load"], connect: true }] } })}
      />,
    );
    const chart = screen.getByRole("img", { name: "Curve" });
    expect([...chart.querySelectorAll("g[data-series]")].map((g) => g.getAttribute("data-series"))).toEqual(["temp", "load"]);
    // "load" has a null in the third row: two markers, one joined line
    expect(chart.querySelectorAll("g[data-series='temp'] rect")).toHaveLength(3);
    expect(chart.querySelectorAll("g[data-series='load'] rect")).toHaveLength(2);
    expect(chart.querySelectorAll("g[data-series='temp'] path")).toHaveLength(1);
    const view = within(chart as unknown as HTMLElement);
    expect(view.getByText("temp (C)")).toBeInTheDocument();
    expect(view.getByText("rpm")).toBeInTheDocument();
    expect(view.getByText("300")).toBeInTheDocument();
  });

  it("is a scatter by default and honours fixed axis bounds", () => {
    render(
      <ReportPreview
        result={result({
          datasets: [points],
          layout: { components: [{ type: "xy", title: "S", dataset: "pts", x: "speed", y: ["temp"], xAxis: { min: 0, max: 1000 }, yAxis: { min: 0, max: 100 } }] },
        })}
      />,
    );
    const chart = screen.getByRole("img", { name: "S" });
    expect(chart.querySelectorAll("path[stroke]")).toHaveLength(0);
    expect(within(chart as unknown as HTMLElement).getByText("1000")).toBeInTheDocument();
    expect([...chart.querySelectorAll("[data-tick]")].map((g) => g.getAttribute("data-tick"))).toContain("100");
  });

  it("notes missing columns and non-numeric data", () => {
    const words: ReportDataset = { name: "w", sourceType: "sql", columns: [{ name: "a", type: "string" }, { name: "b", type: "string" }], rows: [["x", "y"]] };
    render(
      <ReportPreview
        result={result({
          datasets: [points, words],
          layout: {
            components: [
              { type: "xy", dataset: "pts", x: "nope", y: ["temp"] },
              { type: "xy", dataset: "pts", x: "speed", y: ["nope"] },
              { type: "xy", dataset: "w", x: "a", y: ["b"] },
            ],
          },
        })}
      />,
    );
    expect(screen.getAllByText(/has no column/)).toHaveLength(2);
    expect(screen.getByText("No data to chart.")).toBeInTheDocument();
  });
});

describe("data-key bound chart settings (REP-059)", () => {
  const fillWith = (values: Record<string, string>) => (text: string) => text.replace(/@([A-Za-z_][A-Za-z0-9_]*)@/g, (whole, name: string) => values[name] ?? whole);

  it("fills dataset, columns, title and axis settings", () => {
    const layout = parseLayout({
      components: [{ type: "xy", dataset: "@Source@", title: "Temp at @Plant@", x: "speed", y: ["@Col@"], yAxis: { min: 0, max: "@Limit@", label: "@Plant@ degrees" } }],
    });
    const { spec, problems } = bindComponent(layout.components[0]!, fillWith({ Source: "pts", Col: "temp", Limit: "500", Plant: "North" }));
    expect(problems).toEqual([]);
    expect(spec).toMatchObject({ dataset: "pts", title: "Temp at North", y: ["temp"], yAxis: { min: 0, max: 500, label: "North degrees" } });
  });

  it("renders a chart bound to parameters", () => {
    render(
      <ReportPreview
        result={result({
          datasets: [points],
          parameters: [
            { name: "Source", type: "string", value: "pts" },
            { name: "Col", type: "string", value: "temp" },
            { name: "Limit", type: "number", value: 500 },
          ],
          layout: { components: [{ type: "xy", title: "T", dataset: "@Source@", x: "speed", y: ["@Col@"], yAxis: { min: 0, max: "@Limit@" } }] },
        })}
      />,
    );
    const chart = screen.getByRole("img", { name: "T" });
    expect([...chart.querySelectorAll("[data-tick]")].map((g) => g.getAttribute("data-tick"))).toEqual(["0", "100", "200", "300", "400", "500"]);
    expect(within(chart as unknown as HTMLElement).getByText("temp (C)")).toBeInTheDocument();
  });

  it("reads an axis limit from the first row of a column", () => {
    const cfg: ReportDataset = { name: "cfg", sourceType: "sql", columns: [{ name: "hi", type: "number" }], rows: [[250]] };
    const bars: ReportDataset = { name: "b", sourceType: "sql", columns: [{ name: "k", type: "string" }, { name: "v", type: "number" }], rows: [["a", 10], ["b", 20]] };
    render(
      <ReportPreview
        result={result({ datasets: [cfg, bars], layout: { components: [{ type: "bar", title: "B", dataset: "b", category: "k", values: ["v"], yAxis: { min: 0, max: "@hi@" } }] } })}
      />,
    );
    expect([...screen.getByRole("img", { name: "B" }).querySelectorAll("[data-tick]")].map((g) => g.getAttribute("data-tick"))).toContain("200");
  });

  it("falls back to an automatic axis with a note when a key is not a number", () => {
    const bars: ReportDataset = { name: "b", sourceType: "sql", columns: [{ name: "k", type: "string" }, { name: "v", type: "number" }], rows: [["a", 10]] };
    render(<ReportPreview result={result({ datasets: [bars], layout: { components: [{ type: "bar", dataset: "b", category: "k", values: ["v"], yAxis: { max: "@Nope@" } }] } })} />);
    expect(screen.getByText("yAxis.max '@Nope@' is not a number (unknown key); using automatic")).toBeInTheDocument();
  });

  it("keeps numeric axis limits next to key limits when parsing", () => {
    const layout = parseLayout({ components: [{ type: "bar", dataset: "d", category: "k", values: ["v"], yAxis: { min: 1, max: "@M@" } }] });
    expect(layout.components[0]).toMatchObject({ yAxis: { min: 1, max: undefined, maxKey: "@M@" } });
  });
});

describe("image (REP-023)", () => {
  it("shows a data URI picture with size and alignment", () => {
    const { container } = render(<ReportPreview result={result({ layout: { components: [{ type: "image", title: "Logo", src: PNG, width: 120, align: "center", alt: "Plant logo" }] } })} />);
    const img = screen.getByRole("img", { name: "Plant logo" });
    expect(img).toHaveAttribute("src", PNG);
    expect(img).toHaveAttribute("width", "120");
    expect(container.querySelector(".oip-report-image")).toHaveStyle({ justifyContent: "center" });
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent("Logo");
  });

  it("takes the picture from a parameter or a column cell", () => {
    const brand: ReportDataset = { name: "brand", sourceType: "sql", columns: [{ name: "logo", type: "string" }], rows: [[PNG]] };
    const fromParam = render(
      <ReportPreview result={result({ parameters: [{ name: "Logo", type: "string", value: PNG }], layout: { components: [{ type: "image", src: "@Logo@", alt: "p" }] } })} />,
    );
    expect(screen.getByRole("img", { name: "p" })).toHaveAttribute("src", PNG);
    fromParam.unmount();
    render(<ReportPreview result={result({ datasets: [brand], layout: { components: [{ type: "image", src: "@logo@", alt: "c" }] } })} />);
    expect(screen.getByRole("img", { name: "c" })).toHaveAttribute("src", PNG);
  });

  it("notes a missing key and a source that is not a picture", () => {
    render(
      <ReportPreview
        result={result({
          parameters: [{ name: "Text", type: "string", value: "hello" }],
          layout: { components: [{ type: "image", src: "@Missing@" }, { type: "image", src: "@Text@" }] },
        })}
      />,
    );
    expect(screen.getByText("Image skipped: data key @Missing@ has no value.")).toBeInTheDocument();
    expect(screen.getByText("Image skipped: the source is not a base64 PNG or JPEG data URI.")).toBeInTheDocument();
  });

  it("accepts only base64 PNG and JPEG data URIs", () => {
    expect(imageSource(PNG)).toBe(PNG);
    expect(imageSource("data:image/jpg;base64,AAAA")).toBe("data:image/jpeg;base64,AAAA");
    expect(imageSource("data:image/gif;base64,R0lGODlh")).toBeNull();
    expect(imageSource("data:image/svg+xml;base64,PHN2Zz4=")).toBeNull();
    expect(imageSource("data:image/png;base64,***")).toBeNull();
    expect(imageSource("https://example.invalid/logo.png")).toBeNull();
    expect(imageSource("data:image/png;base64,")).toBeNull();
  });
});

describe("layout validation of the new components", () => {
  it("rejects incomplete pie, xy and image settings", () => {
    const bad = (c: unknown) => expect(() => parseLayout({ components: [c] }), JSON.stringify(c)).toThrow(LayoutError);
    bad({ type: "pie", dataset: "d", category: "k" });
    bad({ type: "pie", dataset: "d", value: "v" });
    bad({ type: "xy", dataset: "d", y: ["v"] });
    bad({ type: "xy", dataset: "d", x: "x" });
    bad({ type: "image" });
    bad({ type: "image", src: "https://example.invalid/a.png" });
    bad({ type: "image", src: "@K@", width: 2 });
    bad({ type: "image", src: "@K@", align: "middle" });
    const ok = parseLayout({
      components: [
        { type: "pie", dataset: "d", category: "k", value: "v" },
        { type: "xy", dataset: "d", x: "x", y: ["a", "b"], connect: true },
        { type: "image", src: "@K@" },
        { type: "table", dataset: "d", groupBy: "g", groupSummary: true, autoSize: true },
      ],
    });
    expect(ok.components).toHaveLength(4);
    expect(ok.components[1]).toMatchObject({ connect: true });
    expect(ok.components[3]).toMatchObject({ groupBy: "g", groupSummary: true, autoSize: true });
  });
});
