import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { ConfigResource, GatewayClient } from "./gatewayClient";
import { LibrariesTool, ProjectQueriesTool } from "./ProjectModulesTool";

/** PRO-001 and DES-011: the Studio edits a project's own script library modules and named queries. */

function fakeClient() {
  const store = new Map<string, ConfigResource<Record<string, unknown>>>();
  const key = (scope: string, kind: string, name: string) => `${scope}|${kind}|${name}`;
  const calls: string[] = [];
  const client = {
    listResources: async (_module: string, scope: string, kind: string) =>
      [...store.entries()].filter(([k]) => k.startsWith(`${scope}|${kind}|`)).map(([, value]) => value),
    putResource: async (scope: string, resource: ConfigResource<Record<string, unknown>>) => {
      const at = key(scope, resource.kind, resource.metadata.name);
      const stored = { ...resource, metadata: { ...resource.metadata, version: (store.get(at)?.metadata.version ?? 0) + 1 } };
      store.set(at, stored);
      calls.push(`put ${at}`);
      return stored;
    },
    deleteResource: async (_module: string, scope: string, kind: string, name: string) => {
      store.delete(key(scope, kind, name));
      calls.push(`delete ${key(scope, kind, name)}`);
    },
    dbConnections: async () => [{ name: "plant" }],
  };
  return { client: client as unknown as GatewayClient, store, calls };
}

describe("project script libraries", () => {
  it("adds, saves and removes a module in the project's own scope", async () => {
    const { client, store, calls } = fakeClient();
    render(<LibrariesTool client={client} project="plant-a" />);
    expect(await screen.findByText("No script libraries in this project.")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("New library name"), { target: { value: "Bad_Name" } });
    fireEvent.click(screen.getByRole("button", { name: "Add library" }));
    expect((await screen.findByRole("alert")).textContent).toContain("lowercase letters");
    fireEvent.change(screen.getByLabelText("New library name"), { target: { value: "calc" } });
    fireEvent.click(screen.getByRole("button", { name: "Add library" }));
    const region = await screen.findByRole("region", { name: "Library calc" });
    fireEvent.change(within(region).getByLabelText("Library source"), { target: { value: "def answer():\n    return 'a'\n" } });
    fireEvent.click(within(region).getByRole("button", { name: "Save library" }));
    await waitFor(() => expect((store.get("project/plant-a|ScriptModule|calc")?.spec as { source: string }).source).toContain("return 'a'"));
    expect(calls.every((call) => call.includes("project/plant-a|"))).toBe(true);
    fireEvent.click(within(region).getByRole("button", { name: "Remove library calc" }));
    await waitFor(() => expect(store.size).toBe(0));
  });
});

describe("project named queries", () => {
  it("saves a query to the project scope with the chosen connection", async () => {
    const { client, store } = fakeClient();
    render(<ProjectQueriesTool client={client} project="plant-a" />);
    fireEvent.change(await screen.findByLabelText("New query name"), { target: { value: "who" } });
    fireEvent.click(screen.getByRole("button", { name: "Add query" }));
    const region = await screen.findByRole("region", { name: "Query who" });
    fireEvent.change(within(region).getByLabelText("Project query type"), { target: { value: "scalar" } });
    fireEvent.change(within(region).getByLabelText("Project query SQL"), { target: { value: "SELECT 'a'" } });
    fireEvent.click(within(region).getByRole("button", { name: "Save query" }));
    await waitFor(() => expect(store.get("project/plant-a|NamedQuery|who")?.spec).toMatchObject({ connection: "plant", queryType: "scalar", sql: "SELECT 'a'" }));
  });
});

describe("convert ad hoc SQL (DAT-035)", () => {
  it("creates the named query in the project scope and shows the rewritten script", async () => {
    const { client, store } = fakeClient();
    (client as unknown as Record<string, unknown>).convertToNamedQuery = async (body: { name: string; script: string; connection?: string }) => ({
      name: body.name,
      query: { connection: body.connection ?? "<default>", queryType: "query", sql: "SELECT 1 WHERE :p1 = 1", parameters: [{ name: "p1", dataType: "long" }] },
      call: `system.db.runNamedQuery("${body.name}", {"p1": 1})`,
      script: `rows = system.db.runNamedQuery("${body.name}", {"p1": 1})`,
    });
    render(<ProjectQueriesTool client={client} project="plant-a" />);
    fireEvent.change(await screen.findByLabelText("Ad hoc script"), { target: { value: 'rows = system.db.runPrepQuery("SELECT 1 WHERE ? = 1", [1], "plant")' } });
    fireEvent.change(screen.getByLabelText("Converted query name"), { target: { value: "by-one" } });
    fireEvent.click(screen.getByRole("button", { name: "Convert to named query" }));
    await waitFor(() => expect(store.get("project/plant-a|NamedQuery|by-one")?.spec).toMatchObject({ connection: "plant", queryType: "query", sql: "SELECT 1 WHERE :p1 = 1" }));
    expect((screen.getByLabelText("Rewritten script") as HTMLTextAreaElement).value).toBe('rows = system.db.runNamedQuery("by-one", {"p1": 1})');
  });
});
