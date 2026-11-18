import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { fakeGateway } from "./fakeGateway.testutil";
import type { GatewayClient } from "./gatewayClient";
import { SaveControls } from "./PersistenceBar";
import { ProjectGate, type Opened } from "./ProjectGate";
import { StudioFileMenu } from "./ProjectMenu";
import { sampleProject } from "./sampleProject";
import { sampleStyleClasses } from "./sampleStyleClasses";
import { useSavedProject } from "./useSavedProject";

/**
 * Save one resource or every resource. Dirty state stays with the resources that were not saved.
 * doc source: FEATURES DES-009
 */

afterEach(cleanup);

function Harness({ client, opened }: { client: GatewayClient; opened: Opened }) {
  if (opened.mode !== "gateway") throw new Error("expected a gateway project");
  const saved = useSavedProject(
    {
      name: opened.project,
      views: opened.views,
      drafts: opened.drafts,
      styleClasses: opened.styleClasses,
      styleClassesDraft: opened.styleClassesDraft,
      scriptHooks: opened.scriptHooks,
      pageConfig: opened.pageConfig,
    },
    client,
    opened.project,
  );
  return (
    <div>
      <StudioFileMenu saved={saved} client={client} project={opened.project} onOpenProject={() => {}} />
      <SaveControls saved={saved} offlineReason={null} />
      <button type="button" onClick={() => saved.replaceStyleClasses({ alarm: { style: { color: "red" } } })}>
        Edit styles
      </button>
      <button
        type="button"
        onClick={() => {
          const view = saved.project.views[0]!;
          saved.replaceView({ ...view, metadata: { ...view.metadata, name: `${view.metadata.name} edited` } });
        }}
      >
        Edit view
      </button>
      <ul aria-label="Dirty">
        {saved.dirtyResources.map((item) => (
          <li key={item.id}>{item.label}</li>
        ))}
      </ul>
    </div>
  );
}

async function openSaved() {
  const gateway = fakeGateway();
  render(
    <ProjectGate client={gateway.client} project="demo" starter={sampleProject.views} starterStyleClasses={sampleStyleClasses}>
      {(opened) => <Harness client={gateway.client} opened={opened} />}
    </ProjectGate>,
  );
  await screen.findByRole("button", { name: "Save" });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("All changes saved"));
  return gateway;
}

describe("save one and save all", () => {
  it("saves the checked resource and leaves the others dirty", async () => {
    const { calls } = await openSaved();
    const before = calls.filter((call) => call.method === "PUT").length;
    fireEvent.click(screen.getByRole("button", { name: "Edit styles" }));
    fireEvent.click(screen.getByRole("button", { name: "Edit view" }));
    fireEvent.click(screen.getByRole("button", { name: "File" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Save…" }));
    const dialog = await screen.findByRole("dialog", { name: "Save" });
    const list = within(dialog).getByRole("list", { name: "Unsaved resources" });
    const boxes = within(list).getAllByRole("checkbox");
    expect(boxes.map((box) => box.closest("label")?.textContent)).toEqual(["Overview edited", "Style classes"]);
    fireEvent.click(boxes[0]!);
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("1 unsaved view"));
    expect(calls.filter((call) => call.method === "PUT").length - before).toBe(1);
    expect(calls.filter((call) => call.method === "PUT").at(-1)?.url).toContain("/StyleClasses/");
    expect(screen.getByRole("list", { name: "Dirty" }).textContent).toBe("Overview edited");
  });

  it("save all writes every dirty resource and clears each one", async () => {
    const { calls } = await openSaved();
    fireEvent.click(screen.getByRole("button", { name: "Edit styles" }));
    expect(within(screen.getByRole("list", { name: "Dirty" })).getByText("Style classes")).toBeTruthy();
    const before = calls.filter((call) => call.method === "PUT").length;
    fireEvent.click(screen.getByRole("button", { name: "File" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Save all" }));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("All changes saved"));
    expect(calls.filter((call) => call.method === "PUT").length - before).toBe(1);
    expect(screen.getByRole("list", { name: "Dirty" }).textContent).toBe("");
  });

  it("save as validates the name, refuses a conflict, and does not change the original", async () => {
    const { client, calls, store } = await openSaved();
    await client.createProject({ name: "taken", title: "Taken" });
    const original = structuredClone(store.get("/api/v1/config/designer/project/demo/View/overview"));
    fireEvent.click(screen.getByRole("button", { name: "File" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Save as…" }));
    const dialog = await screen.findByRole("dialog", { name: "Save as" });
    const input = within(dialog).getByRole("textbox", { name: "New project name" });
    expect(input).toHaveValue("copy-of-demo");

    fireEvent.change(input, { target: { value: "Copy_Of_demo" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save copy" }));
    expect(within(dialog).getByRole("alert").textContent).toMatch(/lowercase/);
    expect(calls.some((call) => call.method === "POST" && call.url.includes("/copy"))).toBe(false);

    fireEvent.change(input, { target: { value: "taken" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save copy" }));
    await waitFor(() => expect(within(dialog).getByRole("alert").textContent).toMatch(/already exists/));
    expect(store.get("/api/v1/config/designer/project/demo/View/overview")).toEqual(original);
    expect(store.has("/api/v1/config/designer/project/taken/View/overview")).toBe(false);
  });
});
