import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { fakeGateway } from "./fakeGateway.testutil";
import type { GatewayClient } from "./gatewayClient";
import { LoadErrorsNotice, SaveControls } from "./PersistenceBar";
import { ProjectGate, type Opened } from "./ProjectGate";
import { saveView, toResource } from "./persistence";
import { updateNode } from "./project";
import { sampleProject } from "./sampleProject";
import { sampleStyleClasses } from "./sampleStyleClasses";
import { useSavedProject } from "./useSavedProject";

/** Save edge cases: double saves, partial failures, unreadable stored items, and leaving with unsaved edits. */

afterEach(cleanup);

function Harness({ client, opened }: { client: GatewayClient; opened: Opened }) {
  if (opened.mode !== "gateway") throw new Error("expected a gateway project");
  const saved = useSavedProject(
    { name: opened.project, views: opened.views, drafts: opened.drafts, styleClasses: opened.styleClasses, styleClassesDraft: opened.styleClassesDraft },
    client,
    opened.project,
  );
  const edit = (path: string, text: string) => {
    const view = saved.project.views.find((candidate) => candidate.metadata.path === path)!;
    const first = view.spec.root.children.find((node) => node.kind === "component")!;
    saved.replaceView(updateNode(view, first.id, { props: { text } }));
  };
  return (
    <div>
      <LoadErrorsNotice errors={opened.loadErrors} />
      <SaveControls saved={saved} offlineReason={null} />
      <button type="button" onClick={() => edit("overview", "Edited overview")}>
        Edit overview
      </button>
      <button type="button" onClick={() => edit("pumps", "Edited pumps")}>
        Edit pumps
      </button>
      <ul aria-label="Views">
        {saved.project.views.map((view) => (
          <li key={view.metadata.path}>{view.metadata.path}</li>
        ))}
      </ul>
    </div>
  );
}

function openStudio(client: GatewayClient) {
  return render(
    <ProjectGate client={client} project="demo" starter={sampleProject.views} starterStyleClasses={sampleStyleClasses}>
      {(opened) => <Harness client={client} opened={opened} />}
    </ProjectGate>,
  );
}

const status = () => screen.getByRole("status");
const puts = (calls: Array<{ method: string }>) => calls.filter((call) => call.method === "PUT").length;

async function openSaved(client: GatewayClient) {
  openStudio(client);
  await screen.findByRole("button", { name: "Save" });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  await waitFor(() => expect(status()).toHaveTextContent("All changes saved"));
}

describe("saving", () => {
  it("a second save while one is running is ignored instead of conflicting with the first", async () => {
    const { client, calls } = fakeGateway();
    await openSaved(client);
    const before = puts(calls);

    fireEvent.click(screen.getByRole("button", { name: "Edit overview" }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    fireEvent.keyDown(window, { key: "s", ctrlKey: true });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(status()).toHaveTextContent("All changes saved"));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(puts(calls) - before).toBe(1);
  });

  it("keeps saving after a failure, then reports the failure together with conflicts", async () => {
    const { client, failPut } = fakeGateway();
    await openSaved(client);

    // Another designer saves Overview; the gateway refuses Pumps for another reason.
    const stored = await client.getResource("designer", "project/demo", "View", "overview");
    await saveView(client, "demo", sampleProject.views[0]!, { name: "overview", version: stored!.metadata.version });
    failPut.add("/api/v1/config/designer/project/demo/View/pumps");

    fireEvent.click(screen.getByRole("button", { name: "Edit pumps" }));
    fireEvent.click(screen.getByRole("button", { name: "Edit overview" }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Save conflict" });
    expect(dialog).toHaveTextContent("Overview");
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Also not saved: Pumps:");

    fireEvent.click(within(dialog).getByRole("button", { name: "Use gateway copy" }));
    await waitFor(() => expect(status()).toHaveTextContent(/Save failed: Pumps:/));
  });

  it("asks before leaving while views or style classes are unsaved", async () => {
    const { client } = fakeGateway();
    openStudio(client);
    await screen.findByRole("button", { name: "Save" });
    const leave = () => {
      const event = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    };
    // The starter views and style classes are unsaved drafts.
    expect(leave()).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(status()).toHaveTextContent("All changes saved"));
    expect(leave()).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Edit overview" }));
    expect(leave()).toBe(true);
  });
});

describe("unreadable stored items", () => {
  it("lists views and style classes that could not be opened, and the notice can be dismissed", async () => {
    const { client, store } = fakeGateway();
    const put = (url: string, doc: unknown) => store.set(url, doc as Parameters<typeof store.set>[1]);
    await client.session();
    const good = toResource(sampleProject.views[0]!, { name: "overview", version: 1 });
    put("/api/v1/config/designer/project/demo/View/overview", good);
    const bad = toResource(sampleProject.views[1]!, { name: "pumps", version: 1 });
    bad.spec.path = "Not a slug!";
    put("/api/v1/config/designer/project/demo/View/pumps", bad);
    put("/api/v1/config/designer/project/demo/StyleClasses/style-classes", {
      apiVersion: "oip/v1",
      kind: "StyleClasses",
      metadata: { name: "style-classes", module: "designer", version: 1 },
      spec: { classes: { "1bad": {} } },
    });

    openStudio(client);
    const notice = await screen.findByRole("alert", { name: "Could not open" });
    expect(notice).toHaveTextContent("2 stored items could not be opened");
    const items = within(notice).getAllByRole("listitem").map((item) => item.textContent);
    expect(items[0]).toMatch(/^pumps: /);
    expect(items[1]).toMatch(/^style classes: /);
    expect(within(screen.getByRole("list", { name: "Views" })).getAllByRole("listitem").map((item) => item.textContent)).toEqual(["overview"]);

    fireEvent.click(within(notice).getByRole("button", { name: "Dismiss" }));
    expect(screen.queryByRole("alert", { name: "Could not open" })).toBeNull();
  });
});
