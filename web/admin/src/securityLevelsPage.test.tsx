import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ApiClient, type SessionState } from "./api";
import { ADMIN_SECTIONS } from "./sections";
import { grantable, LEVEL_PATH, levelTree, readLevelSpec, SecurityLevelsPage } from "./securityLevelsPage";

afterEach(() => cleanup());

// Doc source: FEATURES SEC-016.
// Doc source: FEATURES SEC-017.
const SECURITY_ADMIN = ["gateway.write", "gateway.read", "users.admin", "roles.admin", "apikeys.admin"];
const admin: SessionState = { username: "admin", roles: ["administrator"], permissions: SECURITY_ADMIN, csrfToken: "csrf-1" };
const reader: SessionState = { username: "olly", roles: ["operator"], permissions: ["gateway.read"], csrfToken: "csrf-1" };
const section = ADMIN_SECTIONS.find((item) => item.id === "security-levels")!;
const URL = "/api/v1/config/org.oip.security/gateway/SecurityLevels/levels";

function stored(spec: Record<string, unknown>, version = 3) {
  return { apiVersion: "oip/v1", kind: "SecurityLevels", metadata: { name: "levels", module: "org.oip.security", version }, spec };
}

async function fakeGateway(initial: Record<string, unknown> | null, saveStatus = 200) {
  const calls: { method: string; url: string; csrf: string | null; ifMatch: string | null; body?: string }[] = [];
  let doc = initial ? stored(initial) : null;
  const client = new ApiClient({
    fetch: async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      const headers = new Headers(init?.headers);
      const body = typeof init?.body === "string" ? init.body : undefined;
      calls.push({ method, url, csrf: headers.get("X-CSRF-Token"), ifMatch: headers.get("If-Match"), body });
      const json = (status: number, value: unknown, type = "application/json") => new Response(JSON.stringify(value), { status, headers: { "Content-Type": type } });
      if (url.endsWith("/login")) return json(200, { username: "admin", roles: ["administrator"], csrfToken: "csrf-1" });
      if (url === URL && method === "GET") return doc ? json(200, doc) : json(404, { title: "Not Found", status: 404, detail: "no such resource" }, "application/problem+json");
      if (url === URL && method === "PUT") {
        if (saveStatus === 422) {
          return json(422, { title: "Unprocessable", status: 422, detail: "invalid", errors: ["rule 'x' grants 'Nowhere', which is not a level"] }, "application/problem+json");
        }
        const sent = JSON.parse(body!) as { spec: Record<string, unknown> };
        doc = stored(sent.spec, (doc?.metadata.version ?? 0) + 1);
        return json(200, doc);
      }
      if (url === "/api/v1/security/levels/evaluate" && method === "POST") {
        return json(200, { levels: ["Authenticated", "Plant", "Plant/Night", "Public"], rules: ["night"], zone: "ControlRoom" });
      }
      return json(404, { detail: "nope" });
    },
  });
  await client.login("admin", "secret");
  calls.length = 0;
  return { client, calls };
}

describe("security level helpers", () => {
  it("shows parents a path implies and treats them as grantable", () => {
    const tree = levelTree([{ path: "Authenticated/Roles/operator" }, { path: "Plant/Night" }]);
    expect(tree.map((r) => [r.path, r.defined])).toEqual([
      ["Authenticated/Roles", false],
      ["Authenticated/Roles/operator", true],
      ["Plant", false],
      ["Plant/Night", true],
    ]);
    expect(grantable([{ path: "Plant/Night" }])).toEqual(["Plant", "Plant/Night"]);
  });

  it("accepts the gateway's path pattern only", () => {
    expect(LEVEL_PATH.test("Authenticated/Roles/operator")).toBe(true);
    expect(LEVEL_PATH.test("Control Room/Shift A")).toBe(true);
    expect(LEVEL_PATH.test("/leading")).toBe(false);
    expect(LEVEL_PATH.test("a/b/c/d/e/f/g/h/i")).toBe(false);
  });

  it("drops what it cannot read back", () => {
    expect(readLevelSpec({ levels: [{ path: "A" }, { nope: 1 }, "x"], rules: [{ name: "r", grant: ["A", 4] }, { grant: [] }], userGrants: [{ username: "vic", grant: ["A", 2] }, { grant: [] }] })).toEqual({
      levels: [{ path: "A" }],
      rules: [{ name: "r", grant: ["A"], sources: undefined, conditions: [] }],
      userGrants: [{ source: "internal", username: "vic", grant: ["A"] }],
    });
  });
});

describe("Security levels page", () => {
  it("is a security section", () => {
    expect(section.group).toBe("security");
  });

  it("builds a tree and a rule and saves them with the loaded version", async () => {
    const { client, calls } = await fakeGateway({ levels: [{ path: "Authenticated/Roles/operator" }], rules: [] });
    render(<SecurityLevelsPage section={section} client={client} session={admin} />);
    const tree = await screen.findByRole("list", { name: "Security level tree" });
    expect(tree.textContent).toContain("Public");
    expect(tree.textContent).toContain("held by role operator");

    fireEvent.change(screen.getByLabelText("New level path"), { target: { value: "Plant/Night" } });
    fireEvent.click(screen.getByRole("button", { name: "Add level" }));
    expect(within(tree).getByText("Plant/Night")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "Add rule" }));
    const rule = screen.getByRole("group", { name: "Rule rule-1" });
    fireEvent.click(within(rule).getByLabelText("Plant/Night"));
    fireEvent.change(within(rule).getByLabelText("Sources"), { target: { value: "corp, internal" } });
    fireEvent.click(within(rule).getByRole("button", { name: "Add condition to rule-1" }));
    const condition = screen.getByRole("group", { name: "Condition 1 of rule-1" });
    fireEvent.change(within(condition).getByLabelText("Type"), { target: { value: "time" } });
    fireEvent.click(within(condition).getByLabelText("mon"));
    fireEvent.change(within(condition).getByLabelText("From"), { target: { value: "22:00" } });
    fireEvent.change(within(condition).getByLabelText("To"), { target: { value: "06:00" } });

    fireEvent.click(screen.getByRole("button", { name: "Save levels and rules" }));
    expect(await screen.findByText("Security levels saved. They apply from the next login.")).toBeVisible();
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.csrf).toBe("csrf-1");
    expect(put.ifMatch).toBe("3");
    const sent = JSON.parse(put.body!) as { spec: { levels: unknown; rules: unknown } };
    expect(sent.spec.levels).toEqual([{ path: "Authenticated/Roles/operator" }, { path: "Plant/Night" }]);
    expect(sent.spec.rules).toEqual([
      {
        name: "rule-1",
        enabled: true,
        sources: ["corp", "internal"],
        match: "all",
        conditions: [{ type: "time", days: ["mon"], from: "22:00", to: "06:00", timeZone: "UTC" }],
        grant: ["Plant/Night"],
      },
    ]);
  });

  it("refuses a built-in or malformed level before saving", async () => {
    const { client } = await fakeGateway(null);
    render(<SecurityLevelsPage section={section} client={client} session={admin} />);
    const input = await screen.findByLabelText("New level path");
    fireEvent.change(input, { target: { value: "Authenticated" } });
    expect(screen.getByRole("alert").textContent).toContain("built in");
    expect(screen.getByRole("button", { name: "Add level" })).toBeDisabled();
    fireEvent.change(input, { target: { value: "bad//path" } });
    expect(screen.getByRole("button", { name: "Add level" })).toBeDisabled();
  });

  it("shows the gateway's problems when it refuses the rules", async () => {
    const { client } = await fakeGateway({ levels: [], rules: [] }, 422);
    render(<SecurityLevelsPage section={section} client={client} session={admin} />);
    fireEvent.click(await screen.findByRole("button", { name: "Save levels and rules" }));
    const problems = await screen.findByRole("list", { name: "Problems" });
    expect(problems.textContent).toContain("Nowhere");
  });

  it("tries the saved rules over entered facts", async () => {
    const { client, calls } = await fakeGateway({ levels: [{ path: "Plant/Night" }], rules: [] });
    render(<SecurityLevelsPage section={section} client={client} session={admin} />);
    const form = await screen.findByRole("form", { name: "Try the rules" });
    fireEvent.change(within(form).getByLabelText("Login source"), { target: { value: "corp" } });
    fireEvent.change(within(form).getByLabelText("Source address"), { target: { value: "10.1.2.3" } });
    fireEvent.change(within(form).getByLabelText("Attributes (one name=value per line)"), { target: { value: "groups=ops\ngroups=night" } });
    fireEvent.click(within(form).getByRole("button", { name: "Try rules" }));
    await waitFor(() => expect(screen.getByTestId("trial-levels").textContent).toBe("Authenticated, Plant, Plant/Night, Public"));
    expect(screen.getByTestId("trial-rules").textContent).toBe("night");
    const sent = JSON.parse(calls.find((c) => c.url === "/api/v1/security/levels/evaluate")!.body!) as Record<string, unknown>;
    expect(sent).toMatchObject({ source: "corp", address: "10.1.2.3", attributes: { groups: ["ops", "night"] } });
  });

  it("is read only without the security administration permissions", async () => {
    const { client } = await fakeGateway({ levels: [{ path: "Plant" }], rules: [{ name: "r", grant: ["Plant"] }] });
    render(<SecurityLevelsPage section={section} client={client} session={reader} />);
    expect(within(await screen.findByRole("list", { name: "Security level tree" })).getByText("Plant")).toBeVisible();
    expect(screen.getByRole("checkbox", { name: "Plant" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Save levels and rules" })).toBeNull();
    expect(screen.queryByRole("form", { name: "Try the rules" })).toBeNull();
  });

  it("saves a user grant and keeps it in the JSON view", async () => {
    const { client, calls } = await fakeGateway({ levels: [{ path: "Plant/Night" }], rules: [], userGrants: [] });
    render(<SecurityLevelsPage section={section} client={client} session={admin} />);
    const grants = await screen.findByRole("region", { name: "User grants" });
    fireEvent.change(within(grants).getByLabelText("Grant username"), { target: { value: "vic" } });
    fireEvent.click(within(grants).getByLabelText("Plant/Night"));
    fireEvent.click(within(grants).getByRole("button", { name: "Add user grant" }));
    fireEvent.click(screen.getByRole("button", { name: "Save levels and rules" }));
    expect(await screen.findByText("Security levels saved. They apply from the next login.")).toBeVisible();
    const sent = JSON.parse(calls.find((call) => call.method === "PUT")!.body!) as { spec: { userGrants: unknown } };
    expect(sent.spec.userGrants).toEqual([{ source: "internal", username: "vic", grant: ["Plant/Night"] }]);
    fireEvent.click(screen.getByRole("button", { name: "Edit as JSON" }));
    expect((screen.getByLabelText("Levels JSON") as HTMLTextAreaElement).value).toContain("\"username\": \"vic\"");
  });
});
