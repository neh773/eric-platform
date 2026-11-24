import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiClient, type SessionState } from "./api";
import { ADMIN_SECTIONS } from "./sections";
import { TagsPage } from "./tagsPage";
import {
  buildRequest,
  expandPattern,
  fillColumn,
  nameProblems,
  parseNameList,
  patternProblems,
  requestKey,
  resizeTable,
  setCell,
} from "./udtWizard";

describe("UDT wizard pattern expansion", () => {
  it("expands {n} with start, step, and zero padding", () => {
    expect(expandPattern({ template: "Pump_{n}", start: 1, count: 3, step: 1, pad: 2 })).toEqual(["Pump_01", "Pump_02", "Pump_03"]);
    expect(expandPattern({ template: "T{n}-{n}", start: 10, count: 2, step: 5, pad: 0 })).toEqual(["T10-10", "T15-15"]);
    expect(expandPattern({ template: "M{n}", start: 0, count: 1, step: 1, pad: 3 })).toEqual(["M000"]);
  });

  it("reports bad patterns and expands them to nothing", () => {
    expect(patternProblems({ template: "Pump", start: 1, count: 2, step: 1, pad: 0 })).toContain("The pattern must contain {n}.");
    expect(patternProblems({ template: "P{n}", start: 1, count: 0, step: 1, pad: 0 })).toHaveLength(1);
    expect(patternProblems({ template: "P{n}", start: 1, count: 1001, step: 1, pad: 0 })).toHaveLength(1);
    expect(patternProblems({ template: "P{n}", start: -1, count: 2, step: 0, pad: 12 })).toHaveLength(3);
    expect(patternProblems({ template: "P{n}", start: 1, count: Number.NaN, step: 1, pad: 0 })).toHaveLength(1);
    expect(expandPattern({ template: "Pump", start: 1, count: 2, step: 1, pad: 0 })).toEqual([]);
  });

  it("parses an explicit list and flags bad or repeated names", () => {
    expect(parseNameList(" A \n\nB\r\nC ")).toEqual(["A", "B", "C"]);
    const problems = nameProblems(["Pump 1", "pump 1", "Bad/Name", "Tag.Value", "ok-2"]);
    expect(problems.get(1)).toBe("Same name as row 1.");
    expect(problems.has(2)).toBe(true);
    expect(problems.has(3)).toBe(true);
    expect(problems.has(0)).toBe(false);
    expect(problems.has(4)).toBe(false);
  });
});

describe("UDT wizard parameter table", () => {
  it("keeps rows when resized and edits cells immutably", () => {
    let table = resizeTable([], 2);
    expect(table).toEqual([{}, {}]);
    table = setCell(table, 1, "Device", "PLC2");
    const before = table;
    table = setCell(table, 0, "Device", "PLC1");
    expect(before[0]).toEqual({});
    table = resizeTable(table, 3);
    expect(table).toEqual([{ Device: "PLC1" }, { Device: "PLC2" }, {}]);
    expect(resizeTable(table, 1)).toEqual([{ Device: "PLC1" }]);
    expect(fillColumn(table, "Gain", "2")).toEqual([
      { Device: "PLC1", Gain: "2" },
      { Device: "PLC2", Gain: "2" },
      { Gain: "2" },
    ]);
  });

  it("builds one request row per name with only the filled cells", () => {
    const body = buildRequest({
      typeId: "Types/Pump",
      folder: "/Line1/",
      names: ["P1", "P2"],
      table: [{ Device: "PLC1", Gain: "" }, { Gain: "3", Stale: "x" }],
      parameters: ["Device", "Gain"],
      onCollision: "skip",
      dryRun: true,
    });
    expect(body).toEqual({
      typeId: "Types/Pump",
      provider: "default",
      folder: "Line1",
      onCollision: "skip",
      dryRun: true,
      rows: [
        { name: "P1", parameters: { Device: "PLC1" } },
        { name: "P2", parameters: { Gain: "3" } },
      ],
    });
    expect(requestKey(body)).toBe(requestKey({ ...body, dryRun: false }));
    expect(requestKey(body)).not.toBe(requestKey({ ...body, folder: "Line2" }));
  });
});

const operator: SessionState = {
  username: "op",
  roles: ["operator"],
  permissions: ["gateway.read", "tags.read", "tags.write"],
  csrfToken: "csrf-1",
};

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("tags page", () => {
  it("previews, shows row errors, and creates only after a clean preview", async () => {
    const posts: Record<string, unknown>[] = [];
    let failNext = true;
    const client = new ApiClient({
      fetch: async (input, init) => {
        const url = String(input);
        if (url.endsWith("/auth/session")) return json(200, { principal: "op", roles: ["operator"], permissions: [], csrfToken: "csrf-1" });
        if (url.includes("/tags/udts")) {
          return json(200, {
            provider: "default",
            types: [{ path: "Types/Pump", parameters: [{ name: "Device", dataType: "String", default: null }] }],
          });
        }
        const body = JSON.parse(String(init?.body)) as { dryRun: boolean; rows: { name: string }[] };
        posts.push(body);
        const rows = body.rows.map((row, index) => ({
          index,
          requestedName: row.name,
          name: row.name,
          path: `[default]${row.name}`,
          resource: row.name.toLowerCase(),
          status: failNext && index === 1 ? "error" : "create",
          errors: failNext && index === 1 ? ["parameter 'Device' is required (the UDT has no default)"] : [],
          parameters: {},
        }));
        if (failNext) {
          failNext = false;
          return json(422, { type: "urn:oip:problem:validation", status: 422, detail: "nothing was created", rows, requestErrors: [] });
        }
        return json(body.dryRun ? 200 : 201, { dryRun: body.dryRun, applied: !body.dryRun, created: rows.length, skipped: 0, requestErrors: [], rows });
      },
    });
    await client.session();
    const section = ADMIN_SECTIONS.find((item) => item.id === "tags")!;
    render(<TagsPage section={section} client={client} session={operator} />);

    await screen.findByRole("option", { name: "Types/Pump" });
    fireEvent.change(screen.getByLabelText("Name pattern"), { target: { value: "Pump_{n}" } });
    fireEvent.change(screen.getByLabelText("Count"), { target: { value: "2" } });
    fireEvent.change(await screen.findByLabelText("Device for Pump_01"), { target: { value: "PLC1" } });

    const create = screen.getByRole("button", { name: /^Create/ });
    expect(create).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Preview" }));
    const plan = await screen.findByTestId("udt-plan");
    await waitFor(() => expect(within(plan).getByText(/parameter 'Device' is required/)).toBeVisible());
    expect(screen.getByRole("alert")).toHaveTextContent("Nothing was created");
    expect(create).toBeDisabled();

    fireEvent.change(screen.getByLabelText("Device for Pump_02"), { target: { value: "PLC2" } });
    fireEvent.click(screen.getByRole("button", { name: "Preview" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Create 2 instances" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Create 2 instances" }));
    await screen.findByText("Created 2 instances.");

    expect(posts.map((p) => p.dryRun)).toEqual([true, true, false]);
    expect(posts[2]).toMatchObject({
      typeId: "Types/Pump",
      onCollision: "fail",
      rows: [
        { name: "Pump_01", parameters: { Device: "PLC1" } },
        { name: "Pump_02", parameters: { Device: "PLC2" } },
      ],
    });
  });

  it("hides the wizard actions without tags.write", async () => {
    const client = new ApiClient({
      fetch: async () => json(200, { provider: "default", types: [{ path: "Types/Pump", parameters: [] }] }),
    });
    const viewer: SessionState = { username: "v", roles: ["viewer"], permissions: ["tags.read"], csrfToken: "" };
    render(<TagsPage section={ADMIN_SECTIONS.find((item) => item.id === "tags")!} client={client} session={viewer} />);
    await screen.findByRole("option", { name: "Types/Pump" });
    expect(screen.getByTestId("udt-wizard-readonly")).toHaveTextContent("tags.write");
    expect(screen.queryByRole("button", { name: "Preview" })).toBeNull();
  });

  it("explains when the selected UDT has no members and does not call preview", async () => {
    let posted = 0;
    const client = new ApiClient({
      fetch: async (input) => {
        const url = String(input);
        if (url.includes("/tags/udts")) {
          return json(200, { provider: "default", types: [{ path: "Types/Empty", parameters: [], memberCount: 0 }] });
        }
        posted += 1;
        return json(500, { detail: "should not be called" });
      },
    });
    render(<TagsPage section={ADMIN_SECTIONS.find((item) => item.id === "tags")!} client={client} session={operator} />);
    await screen.findByRole("option", { name: "Types/Empty" });
    expect(screen.getByTestId("udt-no-members")).toHaveTextContent("This UDT has no members. Add members to the definition first.");
    expect(screen.getByRole("button", { name: "Preview" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /^Create/ })).toBeDisabled();
    expect(screen.queryByTestId("udt-plan")).toBeNull();
    expect(posted).toBe(0);
  });

  it("shows an error when preview fails without a plan", async () => {
    const client = new ApiClient({
      fetch: async (input, init) => {
        const url = String(input);
        if (url.endsWith("/auth/session")) return json(200, { principal: "op", roles: ["operator"], permissions: [], csrfToken: "csrf-1" });
        if (url.includes("/tags/udts")) {
          return json(200, { provider: "default", types: [{ path: "Types/Pump", parameters: [], memberCount: 1 }] });
        }
        if (init?.method === "POST") return json(500, { detail: "gateway is unavailable" });
        return json(404, {});
      },
    });
    await client.session();
    render(<TagsPage section={ADMIN_SECTIONS.find((item) => item.id === "tags")!} client={client} session={operator} />);
    await screen.findByRole("option", { name: "Types/Pump" });
    fireEvent.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("gateway is unavailable");
    expect(screen.queryByTestId("udt-plan")).toBeNull();
    expect(screen.getByRole("button", { name: /^Create/ })).toBeDisabled();
  });
});
