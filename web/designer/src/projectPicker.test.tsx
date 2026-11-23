import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { fakeGateway } from "./fakeGateway.testutil";
import type { GatewayClient } from "./gatewayClient";
import { ProjectGate, type Opened } from "./ProjectGate";
import { StudioFileMenu } from "./ProjectMenu";
import { RECENT_PROJECTS_KEY } from "./projectFiles";
import { blankView } from "./persistence";
import { useSavedProject } from "./useSavedProject";

/**
 * Open, create, and switch projects from the Designer.
 * doc source: FEATURES DES-011
 */

afterEach(() => {
  cleanup();
  window.localStorage.removeItem(RECENT_PROJECTS_KEY);
});

function Harness({ client, opened, onOpen }: { client: GatewayClient; opened: Opened; onOpen: (name: string) => void }) {
  if (opened.mode !== "gateway") throw new Error("expected a gateway project");
  const saved = useSavedProject(
    { name: opened.project, views: opened.views, drafts: opened.drafts, styleClasses: opened.styleClasses, styleClassesDraft: opened.styleClassesDraft, pageConfig: opened.pageConfig },
    client,
    opened.project,
  );
  return <StudioFileMenu saved={saved} client={client} project={opened.project} onOpenProject={onOpen} />;
}

describe("project picker", () => {
  it("creates a project with a title, description, and parent, then opens it", async () => {
    const { client, calls } = fakeGateway();
    await client.session();
    await client.createProject({ name: "shared", title: "Shared", inheritable: true });
    const opened: string[] = [];
    render(
      <ProjectGate client={client} project="shared" starter={[]}>
        {(openedProject) => <Harness client={client} opened={openedProject} onOpen={(name) => opened.push(name)} />}
      </ProjectGate>,
    );
    await screen.findByRole("button", { name: "File" });
    fireEvent.click(screen.getByRole("button", { name: "File" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "New project…" }));
    const dialog = await screen.findByRole("dialog", { name: "New project" });
    fireEvent.change(within(dialog).getByLabelText("Project name"), { target: { value: "Bad Name" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create project" }));
    expect(within(dialog).getByRole("alert").textContent).toMatch(/lowercase/);

    fireEvent.change(within(dialog).getByLabelText("Project name"), { target: { value: "packaging" } });
    fireEvent.change(within(dialog).getByLabelText("Title"), { target: { value: "Packaging line" } });
    fireEvent.change(within(dialog).getByLabelText("Description"), { target: { value: "North hall" } });
    fireEvent.change(within(dialog).getByLabelText("Parent project"), { target: { value: "shared" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create project" }));
    // The open project is empty and has nothing unsaved, so the new one opens without asking.
    await waitFor(() => expect(opened).toEqual(["packaging"]));
    expect(screen.queryByRole("alertdialog", { name: "Unsaved changes" })).toBeNull();
    const post = calls.find((call) => call.method === "POST" && call.url === "/api/v1/projects" && call.body?.includes("packaging"));
    expect(JSON.parse(post?.body ?? "{}")).toMatchObject({ name: "packaging", title: "Packaging line", description: "North hall", parent: "shared" });
  });

  it("lists recent projects and asks before leaving unsaved edits", async () => {
    const { client } = fakeGateway();
    await client.session();
    const stored = blankView("home", "Home");
    const opened: string[] = [];
    window.localStorage.setItem(RECENT_PROJECTS_KEY, JSON.stringify(["recent-one"]));
    render(
      <ProjectGate client={client} project="demo" starter={[stored]}>
        {(openedProject) => <Harness client={client} opened={openedProject} onOpen={(name) => opened.push(name)} />}
      </ProjectGate>,
    );
    await screen.findByRole("button", { name: "File" });
    fireEvent.click(screen.getByRole("button", { name: "File" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Open…" }));
    const dialog = await screen.findByRole("dialog", { name: "Open project" });
    expect(within(dialog).getByRole("list", { name: "Recent projects" }).textContent).toContain("recent-one");
    fireEvent.click(within(dialog).getByRole("button", { name: "recent-one" }));
    const leave = await screen.findByRole("alertdialog", { name: "Unsaved changes" });
    fireEvent.click(within(leave).getByRole("button", { name: "Stay" }));
    expect(opened).toEqual([]);
    fireEvent.click(screen.getByRole("button", { name: "File" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Open…" }));
    fireEvent.click(within(await screen.findByRole("dialog", { name: "Open project" })).getByRole("button", { name: "recent-one" }));
    fireEvent.click(within(await screen.findByRole("alertdialog", { name: "Unsaved changes" })).getByRole("button", { name: "Open anyway" }));
    expect(opened).toEqual(["recent-one"]);
  });
});
