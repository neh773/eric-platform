import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { SessionState } from "../api";
import type { JsonSchema } from "../schema";
import { fakeGateway, json, sectionById, type Call } from "../statusTestKit";
import { tagNameForNode, uniqueTagNames } from "./CreateTag";
import { parseBrowseRow } from "./tagClient";
import {
  categorize,
  childPath,
  describeAccess,
  formCanEdit,
  fromAccessRule,
  leafName,
  parentPath,
  parseTagPath,
  parseValueInput,
  pathProblems,
  resourceNameFor,
  toAccessRule,
} from "./tagModel";
import { TagsSection, tagsTab } from "./TagsSection";
import { ancestors } from "./TagTree";

afterEach(() => {
  vi.unstubAllGlobals();
});

const bound = { anyOf: [{ type: "number" }, { type: "boolean" }, { type: "string" }, { type: "object" }] };
const permission = { anyOf: [{ type: "object" }, { type: "array" }, { type: "string" }, { type: "null" }] };

/** A trimmed copy of the gateway's Tag resource schema (TagConfigBinding.tagFields). */
const TAG_SCHEMA = {
  type: "object",
  required: ["path", "type"],
  properties: {
    provider: { type: "string", minLength: 1, default: "default" },
    path: { type: "string", minLength: 1 },
    type: { type: "string", enum: ["memory", "expression", "derived", "reference", "opc", "udt", "folder"] },
    dataType: { type: "string", enum: ["Int32", "Int64", "Float", "Double", "Boolean", "String", "DateTime", "Document", "Dataset"], default: "Double" },
    value: {},
    expression: { type: "string" },
    parameters: { type: "object", additionalProperties: true },
    deadband: {
      type: "object",
      properties: {
        mode: { type: "string", enum: ["off", "absolute", "percent"], default: "off" },
        value: { type: "number", default: 0 },
      },
    },
    scaling: { type: "object", properties: { mode: { type: "string", enum: ["none", "linear"], default: "none" } } },
    engUnit: { type: "string" },
    documentation: { type: "string" },
    alarms: { type: "array", items: { type: "object", required: ["name", "mode"], properties: { name: { type: "string" }, mode: { type: "string" }, setpointA: bound } } },
    readOnly: { type: "boolean", default: false },
    readPermissions: permission,
    writePermissions: permission,
    overrides: { type: "object", additionalProperties: {} },
  },
} as unknown as JsonSchema;

describe("tag paths and names", () => {
  it("splits, joins and walks provider paths", () => {
    expect(parseTagPath("[default]Line1/Pump")).toEqual({ provider: "default", path: "Line1/Pump" });
    expect(parseTagPath("Line1/Pump/")).toEqual({ provider: "default", path: "Line1/Pump" });
    expect(parseTagPath("[edge]")).toEqual({ provider: "edge", path: "" });
    expect(childPath("[default]", "A")).toBe("[default]A");
    expect(childPath("[default]A", "B/C")).toBe("[default]A/B/C");
    expect(parentPath("[default]A/B")).toBe("[default]A");
    expect(parentPath("[default]A")).toBe("[default]");
    expect(leafName("[default]A/B")).toBe("B");
    expect(ancestors("[default]A/B/C")).toEqual(["[default]", "[default]A", "[default]A/B"]);
  });

  it("rejects names the tag engine would refuse", () => {
    expect(pathProblems("")).toHaveLength(1);
    expect(pathProblems("a//b")).toHaveLength(1);
    expect(pathProblems("[x]a")).toHaveLength(1);
    expect(pathProblems(".hidden")).toHaveLength(1);
    expect(pathProblems("Folder/Tag 1")).toEqual([]);
  });

  it("derives DNS-label resource names and retries with a suffix", () => {
    expect(resourceNameFor("default", "Line1/Pump 01")).toBe("line1-pump-01");
    expect(resourceNameFor("edge", "Line1/Pump")).toBe("edge-line1-pump");
    expect(resourceNameFor("default", "Line1/Pump", 1)).toBe("line1-pump-2");
    expect(resourceNameFor("default", "Ω")).toMatch(/^tag-[0-9a-f]{8}$/);
    const long = resourceNameFor("default", `${"Very_Long_Folder_Name/".repeat(5)}Tag`);
    expect(long.length).toBeLessThanOrEqual(63);
    expect(long).toMatch(/^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/);
  });

  it("names a tag after a device node without characters a path cannot hold", () => {
    expect(tagNameForNode({ nodeId: "ns=2;s=A/B", name: "A/B", nodeClass: "Variable", readable: true, writable: true, hasChildren: false })).toBe("A_B");
    expect(tagNameForNode({ nodeId: "HR3", name: "HR3", nodeClass: "Variable", readable: true, writable: true, hasChildren: false })).toBe("HR3");
  });

  it("gives two device nodes with the same name different tag paths", () => {
    const node = (nodeId: string, name: string) => ({ nodeId, name, nodeClass: "Variable", readable: true, writable: true, hasChildren: false });
    expect(uniqueTagNames([node("ns=2;i=1", "Speed"), node("ns=2;i=2", "speed"), node("ns=2;i=3", "Speed"), node("ns=2;i=4", "Level")])).toEqual([
      "Speed",
      "speed_2",
      "Speed_3",
      "Level",
    ]);
  });
});

describe("editor categories", () => {
  it("puts every schema property in exactly one category", () => {
    const groups = categorize(TAG_SCHEMA);
    const all = Object.values(groups).flat();
    expect(all.sort()).toEqual(Object.keys(TAG_SCHEMA.properties!).sort());
    expect(groups.numeric).toEqual(["deadband", "engUnit"]);
    expect(groups.scaling).toEqual(["scaling"]);
    expect(groups.alarms).toEqual(["alarms"]);
    expect(groups.security).toEqual(["readOnly", "readPermissions", "writePermissions"]);
    expect(groups.history).toEqual([]);
    expect(groups.scripting).toEqual([]);
    expect(groups.advanced).toEqual(["overrides"]);
  });

  it("sends shapes the shared form cannot draw to a JSON field", () => {
    const props = TAG_SCHEMA.properties!;
    expect(formCanEdit(props.deadband)).toBe(true);
    expect(formCanEdit(props.dataType)).toBe(true);
    expect(formCanEdit(props.value)).toBe(false);
    expect(formCanEdit(props.parameters)).toBe(false);
    expect(formCanEdit(props.alarms)).toBe(false);
    expect(formCanEdit(props.readPermissions)).toBe(false);
  });

  it("reads every permission shape and writes the object form", () => {
    expect(toAccessRule(undefined)).toEqual({ restricted: false, roles: [], zones: [] });
    expect(toAccessRule(null)).toEqual({ restricted: true, roles: [], zones: [] });
    expect(toAccessRule("administrator, operator")).toEqual({ restricted: true, roles: ["administrator", "operator"], zones: [] });
    expect(toAccessRule(["operator"])).toEqual({ restricted: true, roles: ["operator"], zones: [] });
    expect(toAccessRule({ roles: ["a"], zones: ["z"] })).toEqual({ restricted: true, roles: ["a"], zones: ["z"] });
    expect(fromAccessRule({ restricted: false, roles: ["a"], zones: [] })).toBeUndefined();
    expect(fromAccessRule({ restricted: true, roles: ["a"], zones: [] })).toEqual({ roles: ["a"], zones: [] });
    expect(describeAccess(toAccessRule([]))).toBe("Nobody");
    expect(describeAccess(toAccessRule(undefined))).toMatch(/Anyone/);
  });

  it("converts typed values for the tag's data type", () => {
    expect(parseValueInput("42", "Int32")).toEqual({ value: 42 });
    expect(parseValueInput("4.5", "Int32").error).toBeTruthy();
    expect(parseValueInput("2.5", "Double")).toEqual({ value: 2.5 });
    expect(parseValueInput("true", "Boolean")).toEqual({ value: true });
    expect(parseValueInput("maybe", "Boolean").error).toBeTruthy();
    expect(parseValueInput(" text ", "String")).toEqual({ value: " text " });
    expect(parseValueInput('{"a":1}', "Document")).toEqual({ value: { a: 1 } });
    expect(parseValueInput('{"columns":[{"name":"id","type":"Int32"}],"rows":[[1]]}', "Dataset")).toEqual({
      value: { columns: [{ name: "id", type: "Int32" }], rows: [[1]] },
    });
    expect(parseValueInput("not-json", "Dataset").error).toBeTruthy();
    expect(parseValueInput("[1]", "Dataset").error).toBeTruthy();
  });

  it("parses browse rows with and without values", () => {
    expect(parseBrowseRow({ name: "T", path: "[default]T", kind: "tag", hasChildren: false, dataType: "double", value: 1, quality: "Good", timestamp: "t", resource: "t" })).toEqual({
      name: "T",
      path: "[default]T",
      kind: "tag",
      hasChildren: false,
      dataType: "double",
      value: 1,
      quality: "Good",
      timestamp: "t",
      resource: "t",
    });
    expect(parseBrowseRow({ name: "S", path: "[default]S", kind: "tag", hasChildren: false })).not.toHaveProperty("quality");
  });

  it("reads the tab and UDT type from the query string", () => {
    expect(tagsTab("")).toEqual({ tab: "browser", type: "" });
    expect(tagsTab("?tab=udt&type=Types%2FPump")).toEqual({ tab: "udt", type: "Types/Pump" });
  });
});

const admin: SessionState = {
  username: "admin",
  roles: ["administrator"],
  permissions: ["gateway.access", "gateway.read", "gateway.write", "tags.read", "tags.write"],
  csrfToken: "csrf-1",
};

const operator: SessionState = {
  username: "olga",
  roles: ["operator"],
  permissions: ["gateway.access", "gateway.read", "tags.read", "tags.write"],
  csrfToken: "csrf-1",
};

interface FakeTag {
  resource: string;
  version: number;
  spec: Record<string, unknown>;
}

function gateway(options: { denied?: string[]; conflictOnce?: boolean; writeQuality?: string } = {}) {
  const tags = new Map<string, FakeTag>([
    ["line-speed", { resource: "line-speed", version: 3, spec: { provider: "default", path: "Line/Speed", type: "memory", dataType: "Double", value: 1 } }],
    ["line-secret", { resource: "line-secret", version: 0, spec: { provider: "default", path: "Line/Secret", type: "memory", dataType: "Double", readPermissions: { roles: ["administrator"], zones: [] } } }],
  ]);
  const denied = new Set(options.denied ?? ["[default]Line/Secret"]);
  let conflict = options.conflictOnce ?? false;
  const puts: { ifMatch: string | null; body: Record<string, unknown> }[] = [];
  const posts: Record<string, unknown>[] = [];
  const kit = fakeGateway((call: Call) => {
    const body = call.body ? (JSON.parse(String(call.body)) as Record<string, unknown>) : {};
    if (call.url === "/api/v1/tags/providers") return json(200, { providers: [{ name: "default" }] });
    if (call.url === "/api/v1/schemas/tags/Tag") return json(200, { schema: TAG_SCHEMA });
    if (call.url === "/api/v1/tags/browse") {
      if (body.path === "[default]") {
        return json(200, { path: "[default]", offset: 0, more: false, nodes: [{ name: "Line", path: "[default]Line", kind: "folder", hasChildren: true }] });
      }
      if (body.path === "[default]Line") {
        const nodes = [...tags.values()].map((t) => {
          const path = `[default]${String(t.spec.path)}`;
          const row: Record<string, unknown> = { name: String(t.spec.path).split("/").pop(), path, kind: "tag", hasChildren: false, dataType: "double" };
          // Like the gateway, a row the caller may not read carries neither its value nor its resource.
          if (!denied.has(path)) Object.assign(row, { value: t.spec.value ?? 0, quality: "Good", timestamp: "2026-10-28T10:00:00Z", resource: t.resource });
          return row;
        });
        return json(200, { path: "[default]Line", offset: 0, more: false, nodes });
      }
      return json(200, { path: body.path, offset: 0, more: false, nodes: [] });
    }
    if (call.url.startsWith("/api/v1/tags/diagnostics")) {
      const tagPath = new URL(call.url, "http://localhost").searchParams.get("path") ?? "";
      return json(200, {
        path: tagPath,
        type: "memory",
        value: 4,
        quality: "Good",
        timestamp: "2026-10-28T10:00:00Z",
        sourceTimestamp: null,
        message: null,
        subscriberCount: 2,
        referencesIn: [{ path: "[default]Line/High", kind: "expression" }],
        referencesOut: [],
        tagGroup: {
          name: "default", mode: "direct", rateMillis: 1000, slowRateMillis: null, drivingExpression: null,
          valid: true, lastExecution: null, durationMillis: null, overruns: 0,
        },
        deadband: { mode: "absolute", limit: 0.5, evaluated: 3, published: 2 },
      });
    }
    if (call.url === "/api/v1/tags/read") {
      const paths = body.paths as string[];
      return json(200, {
        values: paths.map((path) =>
          denied.has(path)
            ? { path, value: null, quality: "Bad_AccessDenied", timestamp: "2026-10-28T10:00:00Z" }
            : { path, value: 7.5, quality: "Good", timestamp: "2026-10-28T10:00:00Z" },
        ),
      });
    }
    if (call.url === "/api/v1/tags/write") {
      const quality = options.writeQuality ?? "Good";
      return json(200, { results: [{ path: "x", quality, ok: quality === "Good" }] });
    }
    if (call.url === "/api/v1/config/tags/gateway/Tag" && call.method === "POST") {
      posts.push(body);
      const meta = body.metadata as { name: string };
      const spec = body.spec as Record<string, unknown>;
      if (tags.has(meta.name)) return json(409, { detail: `tags/gateway/Tag/${meta.name} already exists` });
      const holder = [...tags.values()].find((t) => String(t.spec.path).toLowerCase() === String(spec.path).toLowerCase());
      if (holder) return json(409, { detail: `Tag path [default]${String(spec.path)} is already used by tag '${holder.resource}'` });
      tags.set(meta.name, { resource: meta.name, version: 1, spec });
      return json(201, { apiVersion: "oip/v1", kind: "Tag", metadata: { name: meta.name, module: "tags", version: 1 }, spec });
    }
    const match = call.url.match(/^\/api\/v1\/config\/tags\/gateway\/Tag\/(.+)$/);
    if (match) {
      const tag = tags.get(decodeURIComponent(match[1]!));
      if (!tag) return json(404, { detail: "not found" });
      const doc = () => ({ apiVersion: "oip/v1", kind: "Tag", metadata: { name: tag.resource, module: "tags", version: tag.version }, spec: tag.spec });
      if (call.method === "GET") return json(200, doc());
      if (call.method === "PUT") {
        puts.push({ ifMatch: call.headers.get("If-Match"), body });
        if (conflict) {
          conflict = false;
          tag.version += 1;
          return json(409, { detail: "version conflict" });
        }
        tag.version += 1;
        tag.spec = body.spec as Record<string, unknown>;
        return json(200, doc());
      }
      if (call.method === "DELETE") {
        tags.delete(tag.resource);
        return new Response(null, { status: 204 });
      }
    }
    return undefined;
  });
  return { ...kit, puts, posts };
}

async function openLine() {
  fireEvent.click(await screen.findByRole("button", { name: "Expand Line" }));
}

describe("tag browser and editor", () => {
  it("shows live values, hides a value the caller may not read, and saves a deadband with the loaded version", async () => {
    const kit = gateway();
    await kit.signIn();
    render(<TagsSection section={sectionById("tags")} client={kit.client} session={admin} search="" />);
    await openLine();
    const speed = await screen.findByRole("button", { name: "Speed" });
    const secretRow = (await screen.findByRole("button", { name: "Secret" })).closest("tr")!;
    await waitFor(() => expect(within(secretRow).getByTestId("tag-value")).toHaveTextContent("Access denied"));
    const speedRow = speed.closest("tr")!;
    await waitFor(() => expect(within(speedRow).getByTestId("tag-value")).toHaveTextContent("7.5"));

    fireEvent.click(speed);
    const editor = await screen.findByTestId("tag-editor");
    await waitFor(() => expect(within(editor).getByTestId("tag-version")).toHaveTextContent("version 3"));
    fireEvent.click(within(editor).getByRole("tab", { name: "Numeric" }));
    fireEvent.change(within(editor).getByLabelText("Value"), { target: { value: "0.5" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(within(editor).getByTestId("tag-version")).toHaveTextContent("version 4"));
    expect(kit.puts[0]!.ifMatch).toBe("3");
    expect((kit.puts[0]!.body.spec as Record<string, unknown>).deadband).toEqual({ value: 0.5 });
  });

  it("offers a reload when the tag changed elsewhere", async () => {
    const kit = gateway({ conflictOnce: true });
    await kit.signIn();
    render(<TagsSection section={sectionById("tags")} client={kit.client} session={admin} search="" />);
    await openLine();
    fireEvent.click(await screen.findByRole("button", { name: "Speed" }));
    const editor = await screen.findByTestId("tag-editor");
    await waitFor(() => expect(within(editor).getByTestId("tag-version")).toHaveTextContent("version 3"));
    fireEvent.change(within(editor).getByLabelText("Documentation"), { target: { value: "belt speed" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
    const banner = await within(editor).findByTestId("version-conflict");
    fireEvent.click(within(banner).getByRole("button", { name: "Reload" }));
    await waitFor(() => expect(within(editor).getByTestId("tag-version")).toHaveTextContent("version 4"));
    expect(within(editor).queryByTestId("version-conflict")).toBeNull();
  });

  it("moves a tag by changing its path and deletes it at the loaded version", async () => {
    const kit = gateway();
    await kit.signIn();
    render(<TagsSection section={sectionById("tags")} client={kit.client} session={admin} search="" />);
    await openLine();
    fireEvent.click(await screen.findByRole("button", { name: "Speed" }));
    const editor = await screen.findByTestId("tag-editor");
    await waitFor(() => expect(within(editor).getByTestId("tag-version")).toHaveTextContent("version 3"));
    fireEvent.change(within(editor).getByLabelText("New tag path"), { target: { value: "Line/Rate" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Move" }));
    await screen.findByText("Moved to [default]Line/Rate.");
    expect((kit.puts[0]!.body.spec as Record<string, unknown>).path).toBe("Line/Rate");

    fireEvent.click(within(await screen.findByTestId("tag-editor")).getByRole("button", { name: "Delete tag" }));
    fireEvent.click(within(screen.getByTestId("tag-editor")).getByRole("button", { name: "Confirm delete" }));
    await screen.findByText("Deleted [default]Line/Rate.");
    const del = kit.calls.find((c) => c.method === "DELETE")!;
    expect(del.headers.get("If-Match")).toBe("4");
  });

  it("shows a refused write and keeps configuration read-only without gateway.write", async () => {
    const kit = gateway({ writeQuality: "Bad_NotWritable" });
    await kit.signIn();
    render(<TagsSection section={sectionById("tags")} client={kit.client} session={operator} search="" />);
    expect(await screen.findByTestId("tag-browser-readonly")).toHaveTextContent("gateway.write");
    expect(screen.queryByRole("button", { name: "New tag" })).toBeNull();
    await openLine();
    fireEvent.click(await screen.findByRole("button", { name: "Speed" }));
    const editor = await screen.findByTestId("tag-editor");
    expect(await within(editor).findByTestId("tag-editor-readonly")).toBeVisible();
    expect(within(editor).queryByRole("button", { name: "Save" })).toBeNull();
    fireEvent.change(within(editor).getByLabelText("Value to write"), { target: { value: "3" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Write" }));
    expect(await within(editor).findByTestId("tag-write-result")).toHaveTextContent("Write refused: Bad_NotWritable");
  });

  it("shows Access denied and no configuration for a tag the caller may not read", async () => {
    const kit = gateway();
    await kit.signIn();
    render(<TagsSection section={sectionById("tags")} client={kit.client} session={operator} search="" />);
    await openLine();
    fireEvent.click(await screen.findByRole("button", { name: "Secret" }));
    const detail = await screen.findByRole("region", { name: "Tag value" });
    await waitFor(() => expect(within(detail).getByTestId("tag-live-value")).toHaveTextContent("Access denied"));
    expect(within(detail).getByText(/read permissions do not admit you/)).toBeVisible();
    expect(screen.queryByTestId("tag-editor")).toBeNull();
    expect(kit.calls.some((c) => c.url.includes("/config/tags/gateway/Tag/line-secret"))).toBe(false);
  });

  it("shows a taken tag path on create instead of retrying under another resource name", async () => {
    const kit = gateway();
    await kit.signIn();
    render(<TagsSection section={sectionById("tags")} client={kit.client} session={admin} search="" />);
    fireEvent.click(await screen.findByRole("button", { name: "Line" }));
    fireEvent.click(await screen.findByRole("button", { name: "New tag" }));
    const form = await screen.findByTestId("create-tag");
    fireEvent.change(within(form).getByLabelText("Name"), { target: { value: "speed" } });
    fireEvent.click(within(form).getByRole("button", { name: "Create tag" }));
    expect(await within(form).findByRole("alert")).toHaveTextContent("Tag path [default]Line/speed is already used by tag 'line-speed'");
    // "line-speed" is taken as a name, so one retry as "line-speed-2"; the path refusal then stops it.
    expect(kit.posts.map((p) => (p.metadata as { name: string }).name)).toEqual(["line-speed", "line-speed-2"]);
  });

  it("retries a taken resource name with a suffix", async () => {
    const kit = gateway();
    await kit.signIn();
    render(<TagsSection section={sectionById("tags")} client={kit.client} session={admin} search="" />);
    fireEvent.click(await screen.findByRole("button", { name: "Line" }));
    fireEvent.click(await screen.findByRole("button", { name: "New tag" }));
    const form = await screen.findByTestId("create-tag");
    // "Line/Speed-" maps to the resource name "line-speed", which is taken; the path is free.
    fireEvent.change(within(form).getByLabelText("Name"), { target: { value: "Speed-" } });
    fireEvent.click(within(form).getByRole("button", { name: "Create tag" }));
    await screen.findByText("Created [default]Line/Speed-.");
    expect(kit.posts.map((p) => (p.metadata as { name: string }).name)).toEqual(["line-speed", "line-speed-2"]);
  });

  it("puts typed roles back to the stored ones on Discard, so a later save does not write them", async () => {
    const kit = gateway({ denied: [] });
    await kit.signIn();
    render(<TagsSection section={sectionById("tags")} client={kit.client} session={admin} search="" />);
    await openLine();
    fireEvent.click(await screen.findByRole("button", { name: "Secret" }));
    const editor = await screen.findByTestId("tag-editor");
    await waitFor(() => expect(within(editor).getByTestId("tag-version")).toHaveTextContent("version 0"));
    fireEvent.click(within(editor).getByRole("tab", { name: "Security" }));
    const roles = () => within(editor).getByLabelText("Read permissions roles") as HTMLInputElement;
    fireEvent.change(roles(), { target: { value: "operator" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Discard changes" }));
    await waitFor(() => expect(roles().value).toBe("administrator"));

    // Toggling another field afterwards saves the stored roles, not the discarded text.
    fireEvent.click(within(editor).getByRole("checkbox", { name: "Restrict write permissions" }));
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(kit.puts).toHaveLength(1));
    expect((kit.puts[0]!.body.spec as Record<string, unknown>).readPermissions).toEqual({ roles: ["administrator"], zones: [] });
  });

  it("opens tag diagnostics from the browser and shows the tag's counters", async () => {
    const kit = gateway();
    await kit.signIn();
    render(<TagsSection section={sectionById("tags")} client={kit.client} session={admin} search="" />);
    await openLine();
    fireEvent.click(await screen.findByRole("button", { name: "Diagnostics for [default]Line/Speed" }));
    const panel = await screen.findByTestId("tag-diagnostics");
    await waitFor(() => expect(within(panel).getByTestId("diag-value")).toHaveTextContent("4"));
    expect(within(panel).getByTestId("diag-quality")).toHaveTextContent("Good");
    expect(within(panel).getByTestId("diag-subscribers")).toHaveTextContent("2");
    expect(within(panel).getByTestId("diag-references-in")).toHaveTextContent("expression");
    expect(within(panel).getByTestId("diag-references-in")).toHaveTextContent("[default]Line/High");
    expect(within(panel).getByTestId("diag-evaluated")).toHaveTextContent("3");
    expect(within(panel).getByTestId("diag-published")).toHaveTextContent("2");
    expect(within(panel).getByTestId("diag-overruns")).toHaveTextContent("0");
    expect(kit.calls.some((c) => c.url.startsWith("/api/v1/tags/diagnostics?path="))).toBe(true);
  });

  it("clears invalid JSON and its error on Discard, so Save works again", async () => {
    const kit = gateway();
    await kit.signIn();
    render(<TagsSection section={sectionById("tags")} client={kit.client} session={admin} search="" />);
    await openLine();
    fireEvent.click(await screen.findByRole("button", { name: "Speed" }));
    const editor = await screen.findByTestId("tag-editor");
    await waitFor(() => expect(within(editor).getByTestId("tag-version")).toHaveTextContent("version 3"));
    fireEvent.click(within(editor).getByRole("tab", { name: "Value" }));
    const field = () => within(editor).getByLabelText("value (JSON)") as HTMLTextAreaElement;
    fireEvent.change(field(), { target: { value: "{not json" } });
    const discard = within(editor).getByRole("button", { name: "Discard changes" });
    expect(discard).toBeEnabled();
    fireEvent.click(discard);
    await waitFor(() => expect(field().value).toBe("1"));

    fireEvent.click(within(editor).getByRole("tab", { name: "Basic" }));
    fireEvent.change(within(editor).getByLabelText("Documentation"), { target: { value: "belt speed" } });
    const save = within(editor).getByRole("button", { name: "Save" });
    expect(save).toBeEnabled();
    fireEvent.click(save);
    await waitFor(() => expect(within(editor).getByTestId("tag-version")).toHaveTextContent("version 4"));
  });
});
