import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { fakeGateway } from "./fakeGateway.testutil";
import { PageConfigPanel } from "./PageConfigPanel";
import { ProjectGate, type Opened } from "./ProjectGate";
import { blankView } from "./persistence";
import { useSavedProject } from "./useSavedProject";

/** Page configuration is a project resource the Studio saves with the others. */

afterEach(cleanup);

describe("page configuration", () => {
  it("tracks the page configuration as its own unsaved resource and saves it", async () => {
    const { client, calls } = fakeGateway();
    const view = blankView("home", "Home");
    let savedRef: ReturnType<typeof useSavedProject> | null = null;
    function Probe({ opened }: { opened: Opened }) {
      if (opened.mode !== "gateway") throw new Error("expected a gateway project");
      const saved = useSavedProject(
        { name: opened.project, views: opened.views, drafts: opened.drafts, pageConfig: opened.pageConfig },
        client,
        opened.project,
      );
      savedRef = saved;
      return (
        <div>
          <PageConfigPanel spec={saved.pageConfig} views={saved.project.views} onChange={saved.replacePageConfig} />
          <button type="button" onClick={() => void saved.save()}>
            Save all pages
          </button>
          <p role="status">{saved.dirtyResources.map((item) => item.label).join(", ")}</p>
        </div>
      );
    }
    render(
      <ProjectGate client={client} project="demo" starter={[view]}>
        {(opened) => <Probe opened={opened} />}
      </ProjectGate>,
    );
    await screen.findByRole("button", { name: "Customize pages" });
    fireEvent.click(screen.getByRole("button", { name: "Customize pages" }));
    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("Page configuration"));
    fireEvent.click(screen.getByRole("button", { name: "Save all pages" }));
    await waitFor(() => expect(calls.some((call) => call.method === "PUT" && call.url.endsWith("/PageConfig/page-config"))).toBe(true));
    expect(savedRef).not.toBeNull();
    const panel = screen.getByRole("region", { name: "Page configuration" });
    expect(within(panel).getByRole("textbox", { name: "Page 1 URL" })).toHaveValue("/");
  });
});
