import { readFileSync } from "node:fs";
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { numericAxis, timeAxis } from "./axes";
import { formatCell, formatReportNumber, formatTimestamp } from "./format";
import { LayoutError, parseLayout } from "./layout";
import { contentWidth } from "./ReportPreview";
import { ReportPreview } from "./ReportPreview";
import type { ReportDefinitionSpec, ReportResult } from "./types";

/**
 * Report preview: the browser rendering of the layout the gateway turns into a PDF (gateway/report-render).
 * The table and chart fixtures are the same JSON files the PDF golden tests use, and the table cells are checked
 * against the text extracted from the golden PDF, so preview and PDF formatting cannot drift apart silently.
 */

const FIXTURES = "../gateway/report-render/src/test/resources";

function fixture(name: string): { definition: ReportDefinitionSpec; result: ReportResult } {
  const definition = JSON.parse(readFileSync(`${FIXTURES}/fixtures/${name}.definition.json`, "utf8")) as { spec: ReportDefinitionSpec };
  const result = JSON.parse(readFileSync(`${FIXTURES}/fixtures/${name}.result.json`, "utf8")) as ReportResult;
  return { definition: definition.spec, result };
}

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

describe("report formatting", () => {
  it("fills data keys in the preview the same way the PDF does", () => {
    render(
      <ReportPreview
        result={result({
          report: { id: "reporting/gateway/Report/daily", name: "daily", title: "Daily", version: 1 },
          parameters: [{ name: "Shift", type: "string", value: "A" }],
          datasets: [
            {
              name: "prod",
              sourceType: "sql",
              columns: [
                { name: "line", type: "string" },
                { name: "count", type: "number" },
              ],
              rows: [["L1", 5]],
            },
          ],
          layout: {
            header: { title: "Line @line@", subtitle: "Report @Report@" },
            components: [{ type: "text", text: "Shift @Shift@ keep @Missing@" }],
          },
        })}
      />,
    );
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Line L1");
    expect(screen.getByText("Report daily")).toBeTruthy();
    expect(screen.getByText("Shift A keep @Missing@")).toBeTruthy();
  });

  it("formats numbers like the PDF renderer", () => {
    expect(formatReportNumber(1234567.5, 2, true)).toBe("1,234,567.50");
    expect(formatReportNumber(1234567.5, undefined, false)).toBe("1234567.5");
    expect(formatReportNumber(1 / 3, undefined, false)).toBe("0.333");
    expect(formatReportNumber(2.5, 0, false)).toBe("3");
    expect(formatReportNumber(-2.5, 0, false)).toBe("-3");
    expect(formatReportNumber(-0.001, 2, false)).toBe("0.00");
    expect(formatReportNumber(-1000, 0, true)).toBe("-1,000");
    expect(formatReportNumber(120, undefined, true)).toBe("120");
    expect(formatReportNumber(1e-6, 6, false)).toBe("0.000001");
    expect(formatReportNumber(9.9996, 3, false)).toBe("10.000");
    expect(formatReportNumber(1.5e21, 0, false)).toBe("1500000000000000000000");
  });

  it("formats timestamps in the layout timezone", () => {
    const t = 1761544800123;
    expect(formatTimestamp(t, "yyyy-MM-dd HH:mm:ss", "UTC")).toBe("2025-10-27 06:00:00");
    expect(formatTimestamp(t, "dd/MM/yyyy HH:mm:ss.SSS", "Europe/Berlin")).toBe("27/10/2025 07:00:00.123");
    expect(formatTimestamp(t, "T HHh", "UTC")).toBe("T 06h");
  });

  it("formats cells by column type or explicit format", () => {
    const auto = { type: "auto" as const, grouping: false, nullText: "" };
    expect(formatCell(null, "number", { ...auto, nullText: "n/a" }, "UTC")).toBe("n/a");
    expect(formatCell(1761544800000, "timestamp", auto, "UTC")).toBe("2025-10-27 06:00:00");
    expect(formatCell(true, "boolean", auto, "UTC")).toBe("true");
    expect(formatCell(12, "number", { ...auto, type: "number", decimals: 1 }, "UTC")).toBe("12.0");
    expect(formatCell("abc", "number", { ...auto, type: "number" }, "UTC")).toBe("abc");
  });

  it("computes the same axis ticks as the PDF renderer", () => {
    expect(numericAxis(21.3, 188.9).ticks).toEqual([0, 50, 100, 150, 200]);
    expect(numericAxis(0.12, 0.47).decimals).toBe(1);
    const from = 1761458400000;
    const axis = timeAxis(from, from + 24 * 3_600_000, "UTC", 10);
    expect(axis.pattern).toBe("HH:mm");
    expect(axis.ticks).toHaveLength(9);
    expect(timeAxis(from + 60_000, from + 3 * 3_600_000, "Europe/Berlin", 6).ticks[0]).toBe(from + 30 * 60_000);
  });
});

describe("report layout", () => {
  it("rejects invalid layouts with a path", () => {
    expect(() => parseLayout({ layoutVersion: 2 })).toThrow(LayoutError);
    expect(() => parseLayout({ page: { size: "A3" } })).toThrow("page.size");
    expect(() => parseLayout({ timezone: "Mars/Olympus" })).toThrow("timezone");
    expect(() => parseLayout({ components: [{ type: "radar", dataset: "d" }] })).toThrow("components[0].type");
    expect(() => parseLayout({ components: [{ type: "bar", dataset: "d", values: ["v"] }] })).toThrow("category");
    expect(() => parseLayout({ components: [{ type: "table", dataset: "d", columns: [{ field: "a", format: { decimals: 11 } }] }] })).toThrow(
      "components[0].columns[0].format.decimals",
    );
    const ok = parseLayout({ components: [{ type: "table", dataset: "d", columns: ["a", { field: "b", align: "center" }] }] });
    expect(ok.components[0]).toMatchObject({ type: "table", columns: [{ field: "a" }, { field: "b", align: "center" }] });
  });

  it("shows an alert instead of throwing for a bad layout", () => {
    render(<ReportPreview result={result({ layout: { components: [{ type: "radar", dataset: "x" }] } })} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Invalid report layout");
  });
});

describe("ReportPreview", () => {
  it("renders the table report with the same cell text as the golden PDF", () => {
    const { definition, result: res } = fixture("table-report");
    render(<ReportPreview definition={definition} result={res} />);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Line 1 shift production");
    expect(screen.getByText(/range: 2025-10-26 07:00 to 2025-10-27 07:00/)).toBeInTheDocument();
    const table = screen.getByRole("table");
    const headers = within(table).getAllByRole("columnheader").map((h) => h.textContent);
    expect(headers).toEqual(["Hour", "Line", "Good (pcs)", "Scrap (pcs)", "Scrap rate (%)", "OEE", "Running"]);
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(48);
    const golden = readFileSync(`${FIXTURES}/golden/table-report.txt`, "utf8").replace(/\r\n/g, "\n").split("\n");
    for (const row of rows) {
      const cells = within(row).getAllByRole("cell").map((c) => c.textContent);
      expect(golden, `row ${cells.join(" ")}`).toContain(cells.join(" "));
    }
    expect(within(rows[7]!).getAllByRole("cell")[5]).toHaveTextContent("n/a");
    expect(within(rows[0]!).getAllByRole("cell")[2]).toHaveStyle({ textAlign: "right" });
  });

  it("renders the chart report as SVG line and bar charts", () => {
    const { definition, result: res } = fixture("chart-report");
    const { container } = render(<ReportPreview definition={definition} result={res} />);
    expect(screen.getByText("Warning: temps: 6 samples of [default]Line1/CoolerTemp are Bad_NotConnected")).toBeInTheDocument();
    const line = screen.getByRole("img", { name: "Oven and cooler temperature" });
    const paths = line.querySelectorAll("path[data-series]");
    expect([...paths].map((p) => p.getAttribute("data-series"))).toEqual(["Oven", "Cooler"]);
    // the six null samples split the cooler line into two runs
    expect((paths[1]!.getAttribute("d") ?? "").match(/M/g)).toHaveLength(2);
    expect([...line.querySelectorAll("[data-tick]")].map((g) => g.getAttribute("data-tick"))).toEqual(["0", "50", "100", "150", "200"]);
    expect(within(line as unknown as HTMLElement).getAllByText("06:00")).toHaveLength(2);
    expect(within(line as unknown as HTMLElement).getByText("degC")).toBeInTheDocument();

    const bar = screen.getByRole("img", { name: "Production by line" });
    const rects = bar.querySelectorAll("rect[data-category]");
    expect(rects).toHaveLength(8);
    expect(rects[0]).toHaveAttribute("data-category", "L1");
    expect(rects[0]).toHaveAttribute("data-value", "11850");
    expect(rects[0]!.getAttribute("fill")).not.toBe(rects[1]!.getAttribute("fill"));
    // the landscape A4 content width sets the chart proportions, as in the PDF
    expect(bar.getAttribute("viewBox")).toBe("0 0 761.89 200");
    expect(container.querySelectorAll(".oip-report-component")).toHaveLength(2);
  });

  it("falls back to one table per dataset and notes missing data", () => {
    const res = result({
      datasets: [
        {
          name: "prod",
          sourceType: "namedQuery",
          columns: [
            { name: "line", type: "string" },
            { name: "count", type: "number", unit: "pcs" },
          ],
          rows: [["L1", 5], ["L2", null]],
          truncated: true,
        },
      ],
    });
    const { unmount } = render(<ReportPreview result={res} />);
    expect(screen.getByRole("columnheader", { name: "count (pcs)" })).toBeInTheDocument();
    expect(screen.getByText("The data source was truncated by the backend row limit.")).toBeInTheDocument();
    unmount();

    render(
      <ReportPreview
        result={{
          ...res,
          layout: {
            components: [
              { type: "table", dataset: "missing" },
              { type: "table", dataset: "prod", columns: ["nope"] },
              { type: "timeSeries", dataset: "prod" },
              { type: "table", dataset: "prod", maxRows: 1 },
            ],
          },
        }}
      />,
    );
    expect(screen.getByText("Dataset 'missing' is not in this result.")).toBeInTheDocument();
    expect(screen.getByText(/has no column 'nope'/)).toBeInTheDocument();
    expect(screen.getByText(/has no timestamp column/)).toBeInTheDocument();
    expect(screen.getByText("Showing the first 1 of 2 rows.")).toBeInTheDocument();
  });

  it("charts large tag histories without spreading arrays", () => {
    const points: [number, number | null][] = Array.from({ length: 200_000 }, (_, i) => [1761458400000 + i * 1000, i % 1000]);
    const res = result({
      datasets: [{ name: "big", sourceType: "tagHistory", columns: [], rows: [], series: [{ tagPath: "a", points }] }],
      layout: { components: [{ type: "timeSeries", title: "Big", dataset: "big" }] },
    });
    render(<ReportPreview result={res} />);
    expect(screen.getByRole("img", { name: "Big" }).querySelector("path[data-series='a']")).not.toBeNull();
  });

  it("charts named-query rows with x and y columns", () => {
    const res = result({
      datasets: [
        {
          name: "q",
          sourceType: "namedQuery",
          columns: [
            { name: "t", type: "timestamp" },
            { name: "flow", type: "number", unit: "m3/h" },
          ],
          rows: [[1761458400000, 1], [1761462000000, 3], [1761465600000, 2]],
        },
      ],
      layout: { components: [{ type: "timeSeries", title: "Flow", dataset: "q", x: "t", y: ["flow"] }] },
    });
    render(<ReportPreview result={res} />);
    const chart = screen.getByRole("img", { name: "Flow" });
    expect(chart.querySelector("path[data-series='flow']")!.getAttribute("d")!.split("L")).toHaveLength(3);
    expect(within(chart as unknown as HTMLElement).getByText("flow (m3/h)")).toBeInTheDocument();
  });
});

describe("page setup and table options (FEATURES REP-010, REP-051)", () => {
  it("parses page sizes and margins like the gateway layout", () => {
    expect(parseLayout({}).page.margins).toEqual({ top: 40, right: 40, bottom: 40, left: 40 });
    expect(parseLayout({ page: { margins: 60 } }).page.margins).toEqual({ top: 60, right: 60, bottom: 60, left: 60 });
    expect(parseLayout({ page: { size: "legal", margins: { left: 70 } } }).page).toEqual({
      size: "Legal",
      orientation: "portrait",
      margins: { top: 40, right: 40, bottom: 40, left: 70 },
    });
    for (const bad of [{ margins: 5 }, { margins: 900 }, { margins: "wide" }, { margins: { top: 30, middle: 1 } }, { size: "A3" }, { size: "A5", margins: { left: 200, right: 200 } }]) {
      expect(() => parseLayout({ page: bad }), JSON.stringify(bad)).toThrow(LayoutError);
    }
  });

  it("sizes the preview content from the page and its margins", () => {
    expect(contentWidth(parseLayout({ page: { size: "Letter", margins: 56 } }))).toBeCloseTo(612 - 112);
    expect(contentWidth(parseLayout({ page: { size: "Legal", orientation: "landscape", margins: { left: 30, right: 50 } } }))).toBeCloseTo(1008 - 80);
  });

  it("leaves out the header row and shading when the table asks", () => {
    const ds = {
      name: "t",
      sourceType: "namedQuery" as const,
      columns: [{ name: "name", type: "string" as const }],
      rows: [["a"], ["b"], ["c"]],
    };
    const plain = render(<ReportPreview result={result({ datasets: [ds], layout: { components: [{ type: "table", dataset: "t" }] } })} />);
    expect(screen.getByRole("columnheader", { name: "name" })).toBeInTheDocument();
    expect(plain.container.querySelectorAll("tr[data-zebra='true']")).toHaveLength(1);
    plain.unmount();
    const bare = render(
      <ReportPreview result={result({ datasets: [ds], layout: { components: [{ type: "table", dataset: "t", showHeader: false, zebra: false }] } })} />,
    );
    expect(screen.queryByRole("columnheader")).toBeNull();
    expect(bare.container.querySelectorAll("tr[data-zebra='true']")).toHaveLength(0);
  });
});
