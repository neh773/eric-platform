import { describe, expect, it } from "vitest";
import { GatewayClient } from "./gatewayClient";
import { loadProjectViews } from "./persistence";

/**
 * Opening a project lists its views without a noticeable wait. 500 views is the budget for the project browser.
 * doc source: FEATURES DES-011
 */

function storedView(index: number): Record<string, unknown> {
  const name = `v${index}`;
  return {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name, module: "designer", version: 1 },
    spec: {
      path: name,
      title: `View ${index}`,
      document: {
        params: {},
        width: 680,
        height: 360,
        root: { id: "root", kind: "container", name: "Root", layout: "coordinate", props: { mode: "fixed" }, children: [] },
      },
    },
  };
}

describe("listing a large project", () => {
  it("lists 500 views in under 300 ms", async () => {
    const views = Array.from({ length: 500 }, (_, index) => storedView(index));
    const client = new GatewayClient(async (url) => {
      if (url === "/api/v1/auth/session") {
        return new Response(JSON.stringify({ username: "admin", csrfToken: "c" }), { status: 200, headers: { "content-type": "application/json" } });
      }
      return new Response(JSON.stringify({ items: views }), { status: 200, headers: { "content-type": "application/json" } });
    });
    const started = performance.now();
    const loaded = await loadProjectViews(client, "wide");
    const elapsed = performance.now() - started;
    expect(loaded.errors).toEqual([]);
    expect(loaded.views).toHaveLength(500);
    expect(loaded.views[0]?.view.metadata.path).toBe("v0");
    expect(loaded.views.find((entry) => entry.view.metadata.path === "v499")?.view.metadata.name).toBe("View 499");
    expect(elapsed).toBeLessThan(300);
  });
});
