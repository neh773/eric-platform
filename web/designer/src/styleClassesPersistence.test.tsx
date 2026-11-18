import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { StyleClassSet } from "@oip/hmi-runtime";
import { fakeGateway } from "./fakeGateway.testutil";
import type { GatewayClient } from "./gatewayClient";
import { SaveControls } from "./PersistenceBar";
import { ProjectGate, type Opened } from "./ProjectGate";
import { STYLE_CLASSES_RESOURCE, loadStyleClasses, saveStyleClasses } from "./persistence";
import { sampleProject } from "./sampleProject";
import { sampleStyleClasses } from "./sampleStyleClasses";
import { useSavedProject } from "./useSavedProject";

/** M4.2 + M4.4: project style classes are saved to the gateway with the views and survive a reload. */

afterEach(cleanup);

function Harness({ client, opened }: { client: GatewayClient; opened: Opened }) {
  if (opened.mode !== "gateway") throw new Error("expected a gateway project");
  const saved = useSavedProject(
    { name: opened.project, views: opened.views, drafts: opened.drafts, styleClasses: opened.styleClasses, styleClassesDraft: opened.styleClassesDraft },
    client,
    opened.project,
  );
  const styleClasses = saved.project.styleClasses ?? {};
  const recolor = (color: string) => {
    const heading = styleClasses.heading ?? { style: {} };
    saved.replaceStyleClasses({ ...styleClasses, heading: { style: { ...heading.style, color } } });
  };
  return (
    <div>
      <SaveControls saved={saved} offlineReason={null} />
      <button type="button" onClick={() => recolor("#ff00aa")}>
        Pink heading
      </button>
      <pre data-testid="classes">{JSON.stringify(styleClasses)}</pre>
    </div>
  );
}

function openStudio(client: GatewayClient, project = "demo") {
  return render(
    <ProjectGate client={client} project={project} starter={sampleProject.views} starterStyleClasses={sampleStyleClasses}>
      {(opened) => <Harness client={client} opened={opened} />}
    </ProjectGate>,
  );
}

function shownClasses(): StyleClassSet {
  return JSON.parse(screen.getByTestId("classes").textContent ?? "{}") as StyleClassSet;
}

async function saveAndWait(text: string | RegExp) {
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(text));
}

describe("style classes on the gateway", () => {
  it("an empty project starts with the starter classes unsaved, and saved edits survive a reload", async () => {
    const { client } = fakeGateway();
    const first = openStudio(client);
    await screen.findByTestId("classes");
    expect(shownClasses()).toEqual(sampleStyleClasses);
    expect(screen.getByRole("status")).toHaveTextContent("3 unsaved views and style classes");

    fireEvent.click(screen.getByRole("button", { name: "Pink heading" }));
    await saveAndWait("All changes saved");
    expect((await loadStyleClasses(client, "demo"))?.styleClasses.heading?.style.color).toBe("#ff00aa");

    first.unmount();
    openStudio(client);
    await screen.findByTestId("classes");
    expect(shownClasses().heading?.style).toMatchObject({ color: "#ff00aa", fontSize: 20 });
    expect(screen.getByRole("status")).toHaveTextContent("All changes saved");

    // A style-class-only edit is reported and saved on its own.
    fireEvent.click(screen.getByRole("button", { name: "Pink heading" }));
    expect(screen.getByRole("status")).toHaveTextContent("Unsaved style classes");
    await saveAndWait("All changes saved");
    expect((await loadStyleClasses(client, "demo"))?.ref.version).toBe(2);
  });

  it("a stale style-class save shows the conflict prompt; keep mine or use the gateway copy", async () => {
    const { client } = fakeGateway();
    openStudio(client);
    await screen.findByTestId("classes");
    await saveAndWait("All changes saved");

    // Another designer saves the project's style classes first.
    const stored = (await loadStyleClasses(client, "demo"))!;
    await saveStyleClasses(client, "demo", { ...stored.styleClasses, heading: { style: { color: "#0000ff" } } }, stored.ref);

    fireEvent.click(screen.getByRole("button", { name: "Pink heading" }));
    expect(screen.getByRole("status")).toHaveTextContent("Unsaved style classes");
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Save conflict" });
    expect(dialog).toHaveTextContent("style classes were changed on the gateway");

    fireEvent.click(within(dialog).getByRole("button", { name: "Use gateway copy" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(shownClasses().heading?.style).toEqual({ color: "#0000ff" });
    expect(screen.getByRole("status")).toHaveTextContent("All changes saved");

    // Round two: another save lands first again, and this time the Designer keeps its own edit.
    const again = (await loadStyleClasses(client, "demo"))!;
    await saveStyleClasses(client, "demo", { ...again.styleClasses, heading: { style: { color: "#00ff00" } } }, again.ref);
    fireEvent.click(screen.getByRole("button", { name: "Pink heading" }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    const second = await screen.findByRole("alertdialog", { name: "Save conflict" });
    fireEvent.click(within(second).getByRole("button", { name: "Keep my changes" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    const final = (await loadStyleClasses(client, "demo"))!;
    expect(final.styleClasses.heading?.style.color).toBe("#ff00aa");
    expect(final.ref).toEqual({ name: STYLE_CLASSES_RESOURCE, version: 4 });
    expect(screen.getByRole("status")).toHaveTextContent("All changes saved");
  });
});
