import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ApiClient, type SessionState } from "../api";
import { ADMIN_SECTIONS } from "../sections";
import { ReportWorkspace } from "./ReportWorkspace";

afterEach(() => cleanup());

const admin: SessionState = { username: "admin", roles: ["administrator"], permissions: ["gateway.write", "gateway.read", "tags.read"], csrfToken: "csrf-1" };
const section = ADMIN_SECTIONS.find((item) => item.id === "reports")!;

const catalog = {
  components: [
    { type: "text", label: "Text block", description: "", needsDataset: false, defaults: { type: "text", title: "Note", text: "Report @Report@" }, properties: [{ name: "title", label: "Title", kind: "string" }, { name: "text", label: "Text", kind: "text" }] },
    {
      type: "table", label: "Table", description: "", needsDataset: true,
      defaults: { type: "table", title: "Table", dataset: "", columns: [], summary: false, showHeader: true, zebra: true },
      properties: [
        { name: "title", label: "Title", kind: "string" },
        { name: "dataset", label: "Data source", kind: "dataset" },
        { name: "columns", label: "Columns", kind: "tableColumns" },
        { name: "maxRows", label: "Maximum rows", kind: "number" },
        { name: "showHeader", label: "Header row", kind: "boolean", default: true },
        { name: "zebra", label: "Shaded detail rows", kind: "boolean", default: true },
        { name: "summary", label: "Summary row", kind: "boolean", default: false },
      ],
    },
  ],
  report: [{ name: "page.size", label: "Page size", kind: "enum", options: ["A4", "A5", "Letter", "Legal"] }],
};

const stored = {
  apiVersion: "oip/v1",
  kind: "Report",
  metadata: { name: "daily", module: "reporting", version: 3 },
  spec: {
    title: "Daily",
    parameters: [{ name: "range", type: "timeRange", default: { last: "24h" } }],
    dataSources: [{ name: "rows", type: "namedQuery", query: "by-line", parameters: { start: { param: "range", field: "from" } } }],
    layout: { layoutVersion: 1, components: [] },
  },
};

const previewResult = {
  contractVersion: 1,
  report: { id: "reporting/gateway/Report/preview", name: "preview", title: "Daily", version: 0 },
  parameters: [{ name: "range", type: "timeRange", value: { from: 1761458400000, to: 1761544800000 } }],
  generatedAt: 1761544800000,
  datasets: [{ name: "rows", sourceType: "namedQuery", columns: [{ name: "line", type: "string" }, { name: "count", type: "number" }], rows: [["L1", 120]] }],
  layout: {},
};

async function fakeGateway() {
  const calls: { method: string; url: string; body?: unknown; csrf: string | null }[] = [];
  const client = new ApiClient({
    fetch: async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      const body = typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : undefined;
      calls.push({ method, url, body, csrf: new Headers(init?.headers).get("X-CSRF-Token") });
      const json = (status: number, data: unknown) => new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });
      if (url.endsWith("/login")) return json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-1" });
      if (method === "GET" && url === "/api/v1/config/reporting/gateway/Report") return json(200, { items: [stored] });
      if (method === "PUT" && url === "/api/v1/config/reporting/gateway/Report/daily") {
        const doc = body as { spec: unknown; metadata: { version: number } };
        return json(200, { ...stored, metadata: { ...stored.metadata, version: doc.metadata.version + 1 }, spec: doc.spec });
      }
      if (method === "GET" && url === "/api/v1/reports/components") return json(200, catalog);
      if (method === "GET" && url === "/api/v1/db/queries") return json(200, { items: [{ name: "by-line", connection: "plant", queryType: "query", sql: "SELECT 1", parameters: [{ name: "start", dataType: "long" }, { name: "line", dataType: "string" }], roles: [], enabled: true }] });
      if (method === "GET" && url === "/api/v1/db/connections") return json(200, { items: [] });
      if (method === "POST" && url === "/api/v1/reports/preview") return json(200, previewResult);
      if (method === "GET" && url.startsWith("/api/v1/reports/daily/status")) return json(200, { report: "daily", scheduled: true, cron: "0 6 * * *", timezone: "UTC", nextFireAt: 1761544800000, lastRunAt: null, lastStatus: null, lastError: null, consecutiveFailures: 0 });
      if (method === "GET" && url.startsWith("/api/v1/reports/daily/runs")) return json(200, { items: [] });
      return json(404, { detail: `unexpected ${method} ${url}` });
    },
  });
  await client.login("admin", "secret");
  calls.length = 0;
  return { client, calls };
}

async function open() {
  const { client, calls } = await fakeGateway();
  render(<ReportWorkspace section={section} client={client} session={admin} />);
  await screen.findByRole("tablist", { name: "Workspace tabs" });
  await waitFor(() => expect(calls.some((c) => c.url === "/api/v1/reports/components")).toBe(true));
  return { calls };
}

const tab = (name: string) => fireEvent.click(screen.getByRole("tab", { name }));
type Spec = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any
const savedSpec = (calls: { method: string; body?: unknown }[]) => (calls.filter((c) => c.method === "PUT").at(-1)!.body as { spec: Spec }).spec;

describe("report workspace", () => {
  it("has the five tabs, each showing its own panel", async () => {
    await open();
    expect(screen.getAllByRole("tab").map((t) => t.textContent)).toEqual(["Overview", "Data", "Design", "Preview", "Schedule"]);
    for (const name of ["Overview", "Data", "Design", "Preview", "Schedule"]) {
      tab(name);
      expect(screen.getByRole("tabpanel", { name })).toBeVisible();
      expect(screen.getAllByRole("tabpanel")).toHaveLength(1);
    }
  });

  it("sets page size and margins on the overview and saves them in the layout", async () => {
    const { calls } = await open();
    tab("Overview");
    fireEvent.change(screen.getByLabelText("Page size"), { target: { value: "Legal" } });
    fireEvent.change(screen.getByLabelText("Orientation"), { target: { value: "landscape" } });
    for (const side of ["top", "right", "bottom", "left"]) fireEvent.change(screen.getByLabelText(`Margin ${side}`), { target: { value: "60" } });
    expect(screen.getByRole("status").textContent).toBe("Unsaved changes");
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Saved"));
    expect(savedSpec(calls).layout).toEqual({ layoutVersion: 1, components: [], page: { size: "Legal", orientation: "landscape", margins: 60 } });
    expect(calls.find((c) => c.method === "PUT")!.csrf).toBe("csrf-1");
  });

  it("builds the palette from the catalog and keeps a text block's data keys", async () => {
    const { calls } = await open();
    expect(screen.getAllByRole("button", { name: /^Add / }).map((b) => b.textContent)).toEqual(["Add text block", "Add table"]);
    fireEvent.click(screen.getByRole("button", { name: "Add text block" }));
    const text = screen.getByLabelText("Text") as HTMLTextAreaElement;
    fireEvent.change(text, { target: { value: "Since @range@: " } });
    fireEvent.change(screen.getByLabelText("Data key"), { target: { value: "Date" } });
    fireEvent.click(screen.getByRole("button", { name: "Insert data key" }));
    expect((screen.getByLabelText("Text") as HTMLTextAreaElement).value).toBe("Since @range@: @Date@");
    expect(screen.getByTestId("report-page").textContent).toContain("Since @range@: @Date@");
    fireEvent.change(screen.getByLabelText("Text"), { target: { value: "Hello @nope@" } });
    expect(screen.getByText(/Not a known data key: @nope@/)).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Saved"));
    expect(savedSpec(calls).layout.components).toEqual([{ type: "text", title: "Note", text: "Hello @nope@" }]);
  });

  it("configures a table: data source, columns, header row and summary", async () => {
    const { calls } = await open();
    fireEvent.click(screen.getByRole("button", { name: "Add table" }));
    expect((screen.getByLabelText("Data source") as HTMLSelectElement).value).toBe("rows");
    fireEvent.click(screen.getByRole("button", { name: "Add column" }));
    fireEvent.change(screen.getByLabelText("Column 1 field"), { target: { value: "count" } });
    fireEvent.change(screen.getByLabelText("Column 1 label"), { target: { value: "Pieces" } });
    fireEvent.change(screen.getByLabelText("Column 1 format"), { target: { value: "number" } });
    fireEvent.change(screen.getByLabelText("Column 1 decimals"), { target: { value: "1" } });
    fireEvent.click(screen.getByLabelText("Column 1 digit grouping"));
    fireEvent.click(screen.getByLabelText("Header row"));
    fireEvent.click(screen.getByLabelText("Summary row"));
    fireEvent.change(screen.getByLabelText("Maximum rows"), { target: { value: "50" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Saved"));
    expect(savedSpec(calls).layout.components).toEqual([
      {
        type: "table", title: "Table", dataset: "rows", showHeader: false, zebra: true, summary: true, maxRows: 50,
        columns: [{ field: "count", label: "Pieces", format: { type: "number", decimals: 1, grouping: true } }],
      },
    ]);
  });

  it("adds an alarm journal data source with filters (REP-032)", async () => {
    const { calls } = await open();
    tab("Data");
    fireEvent.click(screen.getByRole("button", { name: "Add alarm journal" }));
    expect(screen.getByLabelText("Data source 2 name")).toHaveValue("alarms");
    fireEvent.change(screen.getByLabelText("Data source 2 range"), { target: { value: "range" } });
    fireEvent.change(screen.getByLabelText("Data source 2 lowest priority"), { target: { value: "medium" } });
    fireEvent.change(screen.getByLabelText("Data source 2 event types"), { target: { value: "active, ack" } });
    fireEvent.change(screen.getByLabelText("Data source 2 order"), { target: { value: "asc" } });
    fireEvent.change(screen.getByLabelText("Data source 2 row limit"), { target: { value: "250" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Saved"));
    expect(savedSpec(calls).dataSources[1]).toEqual({
      name: "alarms", type: "alarmJournal", range: { param: "range" }, minPriority: "medium", eventTypes: ["active", "ack"], order: "asc", limit: 250,
    });
  });

  it("edits parameters with an expression and a data source binding", async () => {
    const { calls } = await open();
    tab("Data");
    fireEvent.click(screen.getByRole("button", { name: "Add parameter" }));
    fireEvent.change(screen.getByLabelText("Parameter 2 name"), { target: { value: "day" } });
    fireEvent.change(screen.getByLabelText("Parameter 2 type"), { target: { value: "timestamp" } });
    fireEvent.change(screen.getByLabelText("Parameter 2 value from"), { target: { value: "expression" } });
    fireEvent.change(screen.getByLabelText("Parameter 2 expression"), { target: { value: "startOfDay(now) - 1d" } });
    // The named query's second parameter, line, is bound to a fixed value.
    fireEvent.change(screen.getByLabelText("Data source 1 line value"), { target: { value: "L1" } });
    fireEvent.change(screen.getByLabelText("Data source 1 start from"), { target: { value: "day" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Saved"));
    const spec = savedSpec(calls);
    expect(spec.parameters[1]).toEqual({ name: "day", type: "timestamp", expression: "startOfDay(now) - 1d" });
    expect(spec.dataSources[0].parameters).toEqual({ start: { param: "day" }, line: { value: "L1" } });
  });

  it("previews the draft on the gateway before it is saved and offers the real columns afterwards", async () => {
    const { calls } = await open();
    fireEvent.click(screen.getByRole("button", { name: "Add text block" }));
    fireEvent.change(screen.getByLabelText("Text"), { target: { value: "Line @rows.line@" } });
    tab("Preview");
    fireEvent.change(screen.getByLabelText("Preview range"), { target: { value: "2h" } });
    fireEvent.click(screen.getByRole("button", { name: "Run preview" }));
    const preview = await screen.findByTestId("report-preview");
    expect(within(preview).getByText("Line L1")).toBeVisible();
    const call = calls.find((c) => c.url === "/api/v1/reports/preview")!;
    const body = call.body as { spec: Spec; parameters: unknown };
    expect(body.parameters).toEqual({ range: { last: "2h" } });
    expect(body.spec.layout.components[0].text).toBe("Line @rows.line@");
    expect(calls.some((c) => c.method === "PUT")).toBe(false); // nothing was saved to preview
    // Back on Design, the text block offers the data columns the preview returned.
    tab("Design");
    expect(screen.getByRole("option", { name: "rows.count" })).toBeInTheDocument();
  });

  it("schedules the report with a cron expression and a save action", async () => {
    const { calls } = await open();
    tab("Schedule");
    fireEvent.click(screen.getByLabelText("Run on a schedule"));
    fireEvent.change(screen.getByLabelText("Schedule preset"), { target: { value: "0 6 * * 1" } });
    fireEvent.click(screen.getByRole("button", { name: "Add action" }));
    fireEvent.change(screen.getByLabelText("Scheduled range"), { target: { value: "7d" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Saved"));
    expect(savedSpec(calls).schedule).toEqual({ enabled: true, cron: "0 6 * * 1", actions: [{ type: "save" }], parameters: { range: { last: "7d" } } });
    expect(await screen.findByText(/Next run 2025-10-27/)).toBeVisible();
  });
});
