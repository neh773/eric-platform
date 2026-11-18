import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ApiClient, parseDbRunResult, type SessionState } from "./api";
import { DatabasesPage, formatCell, parameterValue } from "./databasesPage";
import { ADMIN_SECTIONS } from "./sections";

afterEach(() => cleanup());

const admin: SessionState = {
  username: "admin",
  roles: ["administrator"],
  permissions: ["gateway.write", "gateway.read"],
  csrfToken: "csrf-1",
};
const moduleAdmin: SessionState = { ...admin, permissions: ["gateway.write", "gateway.read", "modules.admin"] };
const operator: SessionState = { username: "olly", roles: ["operator"], permissions: ["gateway.read"], csrfToken: "csrf-1" };
const section = ADMIN_SECTIONS.find((item) => item.id === "databases")!;

const plant = {
  name: "plant",
  driver: "postgres",
  url: "jdbc:postgresql://db:5432/plant",
  username: "oip",
  enabled: true,
  defaultConnection: true,
  passwordSet: true,
  // A gateway must never send these; the page must not show them even if one did.
  password: "Leaked-Password-1",
  passwordRef: "secret://db-plant",
  status: "Faulted",
  error: "connection refused",
  activeConnections: 0,
  maxConnections: 8,
  queriesPerSecond: 0,
  validationQuery: "SELECT 1",
  allowBrowserWrites: false,
  usedBy: [
    { kind: "historyProvider", name: "plant-history" },
    { kind: "report", name: "shift" },
  ],
};

async function fakeGateway() {
  const calls: { method: string; url: string; csrf: string | null; body?: string }[] = [];
  // DAT-015: the journal diagnostics route, and the settings route that changes where the rows go.
  let journal: Record<string, unknown> = {
    queued: 0,
    retrying: false,
    lostRows: 0,
    storage: "built-in",
    connection: null,
    connectionStatus: "Valid",
    connectionError: null,
  };
  let drivers: Record<string, unknown>[] = [
    { id: "vendor", driverClass: "com.example.Driver", size: 2_621_440, sha256: "cd", uploadedAt: "2026-11-01T00:00:00Z", uploadedBy: "admin", usedBy: [] },
    { id: "busy", driverClass: "com.example.Busy", size: 1024, sha256: "ef", uploadedAt: "2026-11-01T00:00:00Z", uploadedBy: "admin", usedBy: ["plant"] },
  ];
  const client = new ApiClient({
    fetch: async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      const headers = new Headers(init?.headers);
      calls.push({ method, url, csrf: headers.get("X-CSRF-Token"), body: typeof init?.body === "string" ? init.body : undefined });
      const json = (status: number, body: unknown) =>
        new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
      if (url.endsWith("/login")) return json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-1" });
      if (method === "GET" && url === "/api/v1/db/connections") return json(200, { items: [plant] });
      if (method === "GET" && url === "/api/v1/db/queries") {
        return json(200, {
          items: [
            { name: "by-line", connection: "plant", queryType: "query", sql: "SELECT * FROM runs WHERE line = :line", parameters: [{ name: "line", dataType: "string" }], roles: [], enabled: true },
            {
              name: "adjust",
              connection: "plant",
              queryType: "procedure",
              procedure: "plant.adjust",
              parameters: [
                { name: "total", dataType: "integer", mode: "inout" },
                { name: "status", dataType: "string", mode: "out" },
              ],
              roles: [],
              enabled: true,
            },
          ],
        });
      }
      if (method === "GET" && url === "/api/v1/db/drivers") return json(200, { items: drivers, maxBytes: 134217728 });
      if (method === "POST" && url.startsWith("/api/v1/db/drivers?")) {
        const id = new URL(url, "http://x").searchParams.get("id")!;
        if (id === "bad") return json(422, { title: "Unprocessable", detail: "the upload is not a JAR (ZIP) file" });
        const stored = { id, driverClass: "oracle.jdbc.OracleDriver", size: 7_340_032, sha256: "ab", uploadedAt: "2026-11-09T00:00:00Z", uploadedBy: "admin", usedBy: [] };
        drivers = [...drivers, stored];
        return json(201, stored);
      }
      if (method === "DELETE" && url === "/api/v1/db/drivers/vendor") {
        drivers = drivers.filter((d) => d.id !== "vendor");
        return new Response(null, { status: 204 });
      }
      if (method === "GET" && url === "/api/v1/diagnostics/alarms/journal") return json(200, journal);
      if (method === "PUT" && url === "/api/v1/alarms/journal/settings") {
        const body = JSON.parse(String(init?.body)) as { connection: string | null };
        journal = body.connection
          ? { ...journal, storage: "connection", connection: body.connection, connectionStatus: "Faulted", connectionError: "connection refused", retrying: true, queued: 2 }
          : { ...journal, storage: "built-in", connection: null, connectionStatus: "Valid", connectionError: null, retrying: false, queued: 0 };
        return json(200, { maxAgeDays: null, connection: body.connection });
      }
      if (method === "POST" && url === "/api/v1/db/connections/plant/refresh") return json(200, { ...plant, status: "Valid", error: null });
      if (method === "POST" && url === "/api/v1/db/queries/by-line/execute") {
        return json(200, { type: "query", columns: ["id", "line"], rows: [[1, "L1"], [2, null]], cached: false, truncated: true });
      }
      if (method === "POST" && url === "/api/v1/db/queries/adjust/execute") {
        return json(200, { type: "procedure", returnValue: null, outputs: { total: 12, status: "ok" }, columns: [], rows: [], truncated: false });
      }
      if (method === "POST" && url === "/api/v1/db/connections/plant/browse") {
        const body = JSON.parse(String(init?.body)) as { sql: string };
        if (body.sql.startsWith("DELETE")) {
          return json(403, { title: "Forbidden", detail: "connection 'plant' does not allow write statements from the query browser" });
        }
        return json(200, { type: "query", columns: ["n"], rows: [[3]], truncated: false });
      }
      return json(404, { detail: "nope" });
    },
  });
  await client.login("admin", "secret");
  calls.length = 0;
  return { client, calls };
}

describe("Databases page", () => {
  it("lists connections without the password and tests one with the CSRF token", async () => {
    const { client, calls } = await fakeGateway();
    render(<DatabasesPage section={section} client={client} session={admin} />);
    const table = await screen.findByRole("table", { name: "Database connections" });
    expect(table.textContent).toContain("jdbc:postgresql://db:5432/plant");
    expect(screen.getByTestId("db-status-plant").textContent).toContain("connection refused");
    expect(table.textContent).toContain("Stored");
    expect(document.body.textContent).not.toContain("Leaked-Password-1");
    expect(document.body.textContent).not.toContain("secret://");

    fireEvent.click(screen.getByRole("button", { name: "Test plant" }));
    expect(await screen.findByText("Connection plant is valid.")).toBeVisible();
    expect(screen.getByTestId("db-status-plant").textContent).toBe("Valid");
    const post = calls.find((c) => c.method === "POST");
    expect(post?.url).toBe("/api/v1/db/connections/plant/refresh");
    expect(post?.csrf).toBe("csrf-1");
  });

  it("test-runs a named query with typed parameters and shows rows", async () => {
    const { client, calls } = await fakeGateway();
    render(<DatabasesPage section={section} client={client} session={admin} />);
    const queries = await screen.findByRole("table", { name: "Named queries" });
    expect(queries.textContent).toContain("call plant.adjust");
    expect(queries.textContent).toContain("total: integer (inout)");
    fireEvent.click(screen.getByRole("button", { name: "Test by-line" }));
    fireEvent.change(screen.getByLabelText("line (string)"), { target: { value: "L1" } });
    fireEvent.click(screen.getByRole("button", { name: "Run by-line" }));
    const result = await screen.findByRole("table", { name: "Named query result" });
    expect(within(result).getAllByRole("row")).toHaveLength(3);
    expect(result.textContent).toContain("NULL");
    expect(screen.getByText(/row limit reached/)).toBeVisible();
    expect(JSON.parse(calls.find((c) => c.url.endsWith("/execute"))!.body!)).toEqual({ parameters: { line: "L1" } });
  });

  it("shows procedure outputs and only asks for in and inout values", async () => {
    const { client, calls } = await fakeGateway();
    render(<DatabasesPage section={section} client={client} session={admin} />);
    fireEvent.click(await screen.findByRole("button", { name: "Test adjust" }));
    expect(screen.queryByLabelText("status (string)")).toBeNull();
    fireEvent.change(screen.getByLabelText("total (integer)"), { target: { value: "10" } });
    fireEvent.click(screen.getByRole("button", { name: "Run adjust" }));
    const result = await screen.findByRole("table", { name: "Named query result" });
    expect(result.textContent).toContain("total12");
    expect(result.textContent).toContain("statusok");
    expect(JSON.parse(calls.find((c) => c.url.endsWith("/adjust/execute"))!.body!)).toEqual({ parameters: { total: "10" } });
  });

  it("runs the query browser and shows the refusal for a write", async () => {
    const { client, calls } = await fakeGateway();
    render(<DatabasesPage section={section} client={client} session={admin} />);
    const browser = await screen.findByRole("form", { name: "Query browser" });
    expect(browser.textContent).toContain("Statements that change data are refused");
    fireEvent.change(within(browser).getByLabelText("SQL statement"), { target: { value: "SELECT COUNT(*) AS n FROM runs" } });
    fireEvent.click(within(browser).getByRole("button", { name: "Run statement" }));
    expect((await screen.findByRole("table", { name: "Query browser result" })).textContent).toContain("3");
    const sent = calls.find((c) => c.url.endsWith("/browse"))!;
    expect(sent.csrf).toBe("csrf-1");
    expect(JSON.parse(sent.body!)).toEqual({ sql: "SELECT COUNT(*) AS n FROM runs", maxRows: 500 });

    fireEvent.change(within(browser).getByLabelText("SQL statement"), { target: { value: "DELETE FROM runs" } });
    fireEvent.click(within(browser).getByRole("button", { name: "Run statement" }));
    expect((await within(browser).findByRole("alert")).textContent).toContain("does not allow write statements");
  });

  it("hides the query browser and the test action without gateway.write", async () => {
    const { client } = await fakeGateway();
    render(<DatabasesPage section={section} client={client} session={operator} />);
    expect(await screen.findByRole("table", { name: "Database connections" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Test plant" })).toBeNull();
    expect(screen.queryByRole("form", { name: "Query browser" })).toBeNull();
  });

  it("lists what uses each connection and moves the alarm journal onto one, showing its fault", async () => {
    const { client, calls } = await fakeGateway();
    render(<DatabasesPage section={section} client={client} session={admin} />);
    expect((await screen.findByTestId("db-used-by-plant")).textContent).toBe("History provider plant-history, Report shift");
    expect((await screen.findByTestId("journal-storage-status")).textContent).toBe("Built-in journal file: Valid");

    const form = screen.getByRole("form", { name: "Change journal storage" });
    fireEvent.change(within(form).getByLabelText("Journal connection"), { target: { value: "plant" } });
    fireEvent.click(within(form).getByRole("button", { name: "Save journal storage" }));
    await screen.findByText("Connection plant: Faulted; 2 row(s) waiting to be written (connection refused)");
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.url).toBe("/api/v1/alarms/journal/settings");
    expect(put.csrf).toBe("csrf-1");
    expect(JSON.parse(put.body!)).toEqual({ connection: "plant" });

    fireEvent.change(within(form).getByLabelText("Journal connection"), { target: { value: "" } });
    fireEvent.click(within(form).getByRole("button", { name: "Save journal storage" }));
    await screen.findByText("Built-in journal file: Valid");
    expect(JSON.parse(calls.filter((c) => c.method === "PUT")[1]!.body!)).toEqual({ connection: null });
  });

  it("shows the journal status read-only without gateway.write", async () => {
    const { client } = await fakeGateway();
    render(<DatabasesPage section={section} client={client} session={operator} />);
    // The status element shows a loading line first, so wait for the loaded text rather than the element.
    await waitFor(() => expect(screen.getByTestId("journal-storage-status").textContent).toBe("Built-in journal file: Valid"));
    expect(screen.queryByRole("form", { name: "Change journal storage" })).toBeNull();
  });

  it("lists uploaded JDBC drivers read-only without modules.admin", async () => {
    const { client } = await fakeGateway();
    render(<DatabasesPage section={section} client={client} session={admin} />);
    const table = await screen.findByRole("table", { name: "Uploaded JDBC drivers" });
    expect(table.textContent).toContain("com.example.Driver");
    expect(table.textContent).toContain("2.5 MiB");
    expect(screen.getByTestId("driver-used-by-busy").textContent).toBe("plant");
    expect(screen.queryByRole("form", { name: "Upload a JDBC driver" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Remove vendor" })).toBeNull();
  });

  it("uploads a driver JAR with the CSRF token and removes an unused driver", async () => {
    const { client, calls } = await fakeGateway();
    render(<DatabasesPage section={section} client={client} session={moduleAdmin} />);
    const form = await screen.findByRole("form", { name: "Upload a JDBC driver" });
    const upload = within(form).getByRole("button", { name: "Upload driver" });
    expect((upload as HTMLButtonElement).disabled).toBe(true);
    const jar = new File([new Uint8Array([0x50, 0x4b, 3, 4])], "ojdbc.jar", { type: "application/java-archive" });
    fireEvent.change(within(form).getByLabelText("Driver JAR"), { target: { files: [jar] } });
    fireEvent.change(within(form).getByLabelText("Driver id"), { target: { value: "bad" } });
    fireEvent.click(upload);
    expect(await screen.findByText("the upload is not a JAR (ZIP) file")).toBeVisible();

    fireEvent.change(within(form).getByLabelText("Driver id"), { target: { value: "oracle-23" } });
    fireEvent.click(upload);
    expect(await screen.findByText("Driver oracle-23 stored (oracle.jdbc.OracleDriver).")).toBeVisible();
    const post = calls.filter((c) => c.method === "POST" && c.url.startsWith("/api/v1/db/drivers?")).pop()!;
    expect(post.url).toBe("/api/v1/db/drivers?id=oracle-23");
    expect(post.csrf).toBe("csrf-1");

    // A driver a connection uses cannot be removed from here; an unused one can.
    expect((screen.getByRole("button", { name: "Remove busy" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Remove vendor" }));
    expect(await screen.findByText("Driver vendor removed.")).toBeVisible();
    expect(calls.find((c) => c.method === "DELETE")!.csrf).toBe("csrf-1");
  });

  it("formats cells, parameters, and results", () => {
    expect(formatCell(null)).toBe("NULL");
    expect(formatCell({ a: 1 })).toBe('{"a":1}');
    expect(parameterValue("")).toBeNull();
    expect(parameterValue("12")).toBe("12");
    expect(parseDbRunResult({ type: "update", rowsAffected: 2 })).toEqual({ type: "update", rowsAffected: 2 });
    expect(() => parseDbRunResult({ type: "other" })).toThrow();
  });
});
