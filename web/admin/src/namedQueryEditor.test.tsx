import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ApiClient, type SessionState } from "./api";
import { DatabasesPage } from "./databasesPage";
import { draftFromDetail, fieldsFromDraft } from "./namedQueryEditor";
import { ADMIN_SECTIONS } from "./sections";

afterEach(() => cleanup());

const admin: SessionState = { username: "admin", roles: ["administrator"], permissions: ["gateway.write", "gateway.read"], csrfToken: "csrf-1" };
const operator: SessionState = { username: "olly", roles: ["operator"], permissions: ["gateway.read"], csrfToken: "csrf-1" };
const section = ADMIN_SECTIONS.find((item) => item.id === "databases")!;

const plant = {
  name: "plant", driver: "sqlite", url: "jdbc:sqlite::memory:", username: "", description: "", enabled: true, defaultConnection: true,
  passwordSet: false, status: "Valid", error: null, activeConnections: 0, maxConnections: 8, queriesPerSecond: 0, validationQuery: "SELECT 1", allowBrowserWrites: false,
};

function detail(over: Record<string, unknown> = {}) {
  return {
    name: "by-line", description: "Production by line", enabled: true, connection: "plant", queryType: "query",
    sql: "SELECT line FROM runs WHERE line = :line", parameters: [{ name: "line", dataType: "string" }], roles: ["operator"],
    cacheEnabled: false, cacheTtlMillis: 0, maxRows: 10000, version: 4, ...over,
  };
}

async function fakeGateway() {
  const calls: { method: string; url: string; body?: unknown; ifMatch: string | null }[] = [];
  let stored = detail();
  let items = [stored];
  const client = new ApiClient({
    fetch: async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      const headers = new Headers(init?.headers);
      const body = typeof init?.body === "string" ? (JSON.parse(init.body) as Record<string, unknown>) : undefined;
      calls.push({ method, url, body, ifMatch: headers.get("If-Match") });
      const json = (status: number, data: unknown) => new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });
      if (url.endsWith("/login")) return json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-1" });
      if (method === "GET" && url === "/api/v1/db/connections") return json(200, { items: [plant] });
      if (method === "GET" && url === "/api/v1/db/queries") return json(200, { items });
      if (method === "GET" && url === "/api/v1/db/queries/by-line") return json(200, stored);
      if (method === "PUT" && url === "/api/v1/db/queries/by-line") {
        stored = { ...detail(), ...body, version: stored.version + 1 };
        items = [stored];
        return json(200, stored);
      }
      if (method === "POST" && url === "/api/v1/db/queries") {
        const created = { ...detail(), ...body, version: 1 };
        items = [stored, created as typeof stored];
        return json(201, created);
      }
      if (method === "DELETE" && url === "/api/v1/db/queries/by-line") {
        items = [];
        return new Response(null, { status: 204 });
      }
      if (method === "POST" && url === "/api/v1/db/queries/by-line/execute") return json(200, { type: "query", columns: ["line"], rows: [["L1"]], truncated: false });
      return json(404, { detail: `unexpected ${method} ${url}` });
    },
  });
  await client.login("admin", "secret");
  calls.length = 0;
  return { client, calls };
}

describe("named query editor (DAT-031)", () => {
  it("edits the settings of a query, saves with the loaded version and test-runs the saved query", async () => {
    const { client, calls } = await fakeGateway();
    render(<DatabasesPage section={section} client={client} session={admin} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit by-line" }));
    const editor = await screen.findByRole("form", { name: "Named query editor" });
    await waitFor(() => expect((within(editor).getByLabelText("Query SQL") as HTMLTextAreaElement).value).toContain("SELECT line"));
    expect(within(editor).getByRole("button", { name: "Save query" })).toBeDisabled(); // nothing changed yet

    fireEvent.change(within(editor).getByLabelText("Query SQL"), { target: { value: "SELECT line, count FROM runs WHERE line = :line" } });
    fireEvent.click(within(editor).getByLabelText("Cache results"));
    fireEvent.change(within(editor).getByLabelText("Cache time to live (ms)"), { target: { value: "30000" } });
    fireEvent.change(within(editor).getByLabelText("Maximum rows"), { target: { value: "500" } });
    fireEvent.change(within(editor).getByLabelText("Query roles"), { target: { value: "operator, supervisor" } });
    expect(within(editor).getByRole("button", { name: "Test run" })).toBeDisabled(); // a test run uses the saved query
    expect(within(editor).getByText("Unsaved changes")).toBeVisible();

    fireEvent.click(within(editor).getByRole("button", { name: "Save query" }));
    await screen.findByText("Saved by-line.");
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.ifMatch).toBe("4");
    expect(put.body).toEqual({
      name: "by-line", description: "Production by line", enabled: true, connection: "plant", queryType: "query",
      sql: "SELECT line, count FROM runs WHERE line = :line", parameters: [{ name: "line", dataType: "string" }],
      roles: ["operator", "supervisor"], cacheEnabled: true, cacheTtlMillis: 30000, maxRows: 500,
    });

    fireEvent.click(within(editor).getByRole("button", { name: "Test run" }));
    fireEvent.change(await screen.findByLabelText("line (string)"), { target: { value: "L1" } });
    fireEvent.click(screen.getByRole("button", { name: "Run by-line" }));
    expect((await screen.findByRole("table", { name: "Named query result" })).textContent).toContain("L1");
    expect(calls.find((c) => c.url.endsWith("/execute"))!.body).toEqual({ parameters: { line: "L1" } });
  });

  it("creates a query, detects its parameters from the SQL and then edits it in place", async () => {
    const { client, calls } = await fakeGateway();
    render(<DatabasesPage section={section} client={client} session={admin} />);
    fireEvent.click(await screen.findByRole("button", { name: "New named query" }));
    const editor = await screen.findByRole("form", { name: "Named query editor" });
    expect(within(editor).getByRole("button", { name: "Save query" })).toBeDisabled(); // no name yet
    fireEvent.change(within(editor).getByLabelText("Query name"), { target: { value: "by-day" } });
    fireEvent.change(within(editor).getByLabelText("Query connection"), { target: { value: "plant" } });
    fireEvent.change(within(editor).getByLabelText("Query SQL"), { target: { value: "SELECT * FROM runs WHERE day = :day AND line = :line" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Detect parameters from SQL" }));
    fireEvent.change(within(editor).getByLabelText("Query parameter 1 type"), { target: { value: "datetime" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Save query" }));
    await screen.findByText("Saved by-day.");
    const post = calls.find((c) => c.method === "POST")!;
    expect(post.body).toMatchObject({
      name: "by-day", connection: "plant", queryType: "query",
      parameters: [{ name: "day", dataType: "datetime" }, { name: "line", dataType: "string" }],
    });
    expect(post.ifMatch).toBeNull();
    expect(await screen.findByRole("heading", { name: "Edit by-day" })).toBeVisible();
  });

  it("deletes a query with its version", async () => {
    const { client, calls } = await fakeGateway();
    render(<DatabasesPage section={section} client={client} session={admin} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit by-line" }));
    const editor = await screen.findByRole("form", { name: "Named query editor" });
    await waitFor(() => expect(within(editor).getByRole("button", { name: "Delete query" })).toBeEnabled());
    await waitFor(() => expect((within(editor).getByLabelText("Query SQL") as HTMLTextAreaElement).value).not.toBe(""));
    fireEvent.click(within(editor).getByRole("button", { name: "Delete query" }));
    await waitFor(() => expect(screen.queryByRole("form", { name: "Named query editor" })).toBeNull());
    expect(calls.find((c) => c.method === "DELETE")!.ifMatch).toBe("4");
    expect(await screen.findByText("No named queries.")).toBeVisible();
  });

  it("is not offered without gateway.write", async () => {
    const { client } = await fakeGateway();
    render(<DatabasesPage section={section} client={client} session={operator} />);
    expect(await screen.findByRole("button", { name: "Test by-line" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Edit by-line" })).toBeNull();
    expect(screen.queryByRole("button", { name: "New named query" })).toBeNull();
  });

  it("maps a procedure's settings both ways", () => {
    const draft = draftFromDetail({ name: "adjust", connection: "plant", queryType: "procedure", procedure: "plant.adjust", routine: "function", returnType: "integer", parameters: [{ name: "total", dataType: "integer", mode: "inout" }, { name: "status", dataType: "string", mode: "out" }], roles: [], maxRows: 100 });
    expect(fieldsFromDraft(draft)).toEqual({
      description: "", enabled: true, connection: "plant", queryType: "procedure",
      parameters: [{ name: "total", dataType: "integer", mode: "inout" }, { name: "status", dataType: "string", mode: "out" }],
      roles: [], cacheEnabled: false, cacheTtlMillis: 0, maxRows: 100, procedure: "plant.adjust", routine: "function", returnType: "integer",
    });
  });
});
