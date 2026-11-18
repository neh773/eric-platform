import { describe, expect, it } from "vitest";
import { GatewayClient, type ConfigResource } from "./gatewayClient";

// The gateway lists resources with read-only `scope` and `inheritedFrom` keys and refuses them on a write (422 unknown
// top-level keys), so a resource read from a list and saved back must not send them (DES-011, PRO-001).
describe("putResource", () => {
  it("sends only the writable keys of a resource it read from a list", async () => {
    let sent: Record<string, unknown> = {};
    const client = new GatewayClient(async (_input, init) => {
      sent = JSON.parse(String(init?.body)) as Record<string, unknown>;
      return new Response(JSON.stringify(sent), { status: 200, headers: { "Content-Type": "application/json" } });
    });
    const listed = {
      apiVersion: "oip/v1",
      kind: "ScriptModule",
      metadata: { name: "calc", module: "scripting", version: 1 },
      spec: { source: "x = 1" },
      scope: "project/plant-a",
      inheritedFrom: "base",
    } as ConfigResource<{ source: string }>;
    await client.putResource("project/plant-a", listed);
    expect(Object.keys(sent).sort()).toEqual(["apiVersion", "kind", "metadata", "spec"]);
  });
});
