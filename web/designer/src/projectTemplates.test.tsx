import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createLinkedTransports, createSession, PageSession } from "@oip/hmi-runtime";
import { componentRegistry } from "@oip/components";
import { fakeGateway } from "./fakeGateway.testutil";
import { buildHmiProject, type PageConfigSpec } from "./hmiProject";
import type { ConfigResource } from "./gatewayClient";
import type { ViewSpec } from "./persistence";
import { applyProjectTemplate, PROJECT_TEMPLATES, templateContent } from "./projectTemplates";
import { ProjectGate } from "./ProjectGate";
import { StudioFileMenu } from "./ProjectMenu";
import { useSavedProject } from "./useSavedProject";
import type { Opened } from "./ProjectGate";
import type { GatewayClient } from "./gatewayClient";

/** PRO-005: each new-project template creates a project that opens at /hmi with its navigation. */

afterEach(cleanup);

function storedResources(store: Map<string, unknown>, project: string) {
  const views: ConfigResource<ViewSpec>[] = [];
  let config: ConfigResource<PageConfigSpec> | null = null;
  for (const [url, value] of store) {
    if (!url.includes(`/project/${project}/`)) continue;
    if (url.includes("/View/")) views.push(value as ConfigResource<ViewSpec>);
    if (url.endsWith("/PageConfig/page-config")) config = value as ConfigResource<PageConfigSpec>;
  }
  return { views, config };
}

describe("project templates", () => {
  it("offers Blank, Web nav and Menu nav", () => {
    expect(PROJECT_TEMPLATES.map((template) => template.label)).toEqual(["Blank", "Web nav", "Menu nav"]);
  });

  it("blank writes nothing", async () => {
    const { client, store } = fakeGateway();
    await client.session();
    await applyProjectTemplate(client, "plain", "blank");
    expect(store.size).toBe(0);
    expect(templateContent("blank")).toEqual({ views: [], pageConfig: null });
  });

  it.each([
    ["web-nav", "menubar", "top", "nav-header"],
    ["menu-nav", "tree", "left", "nav-menu"],
  ] as const)("%s creates a project that opens at /hmi with its navigation", async (template, _role, edge, dockView) => {
    const { client, store } = fakeGateway();
    await client.session();
    await applyProjectTemplate(client, "demo", template);
    const { views, config } = storedResources(store, "demo");
    expect(config).not.toBeNull();
    expect(config!.spec.sharedDocks).toEqual([expect.objectContaining({ edge, view: dockView })]);

    const loaded = buildHmiProject("demo", views, config, null);
    expect(loaded).not.toBeNull();
    expect(loaded!.errors).toEqual([]);
    expect(loaded!.pagesFrom).toBe("config");
    expect(loaded!.project.pages.map((page) => page.spec.url)).toEqual(["/", "/overview", "/alarms"]);
    expect(loaded!.project.sharedDocks?.[0]).toMatchObject({ edge, view: dockView });

    const [transport] = createLinkedTransports();
    render(
      <PageSession project={loaded!.project} registry={componentRegistry()} transport={transport} session={createSession({ id: "t", locale: "en-US" })} location="/overview" basePath="/hmi/demo" />,
    );
    const link = (await screen.findByText("Alarms", { selector: ".oip-nav-label" })).closest("a");
    expect(link).toHaveAttribute("href", "/hmi/demo/alarms");
  });

  it("is chosen in the New project dialog", async () => {
    const { client, store } = fakeGateway();
    await client.session();
    function Harness({ opened }: { opened: Opened }) {
      if (opened.mode !== "gateway") throw new Error("expected a gateway project");
      const saved = useSavedProject({ name: opened.project, views: opened.views, drafts: opened.drafts, pageConfig: opened.pageConfig }, client as GatewayClient, opened.project);
      return <StudioFileMenu saved={saved} client={client} project={opened.project} onOpenProject={() => undefined} />;
    }
    render(
      <ProjectGate client={client} project="seed" starter={[]}>
        {(opened) => <Harness opened={opened} />}
      </ProjectGate>,
    );
    fireEvent.click(await screen.findByRole("button", { name: "File" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "New project…" }));
    const dialog = await screen.findByRole("dialog", { name: "New project" });
    fireEvent.change(within(dialog).getByLabelText("Project name"), { target: { value: "menus" } });
    fireEvent.change(within(dialog).getByLabelText("Template"), { target: { value: "menu-nav" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create project" }));
    await waitFor(() => expect(storedResources(store, "menus").config).not.toBeNull());
    expect(storedResources(store, "menus").views.map((view) => view.spec.path).sort()).toEqual(["alarms", "home", "nav-menu", "overview"]);
  });
});
