import { describe, expect, it } from "vitest";
import { fakeGateway } from "./fakeGateway.testutil";
import { blankView, loadProjectViews, saveView, validProjectName } from "./persistence";
import { ProjectNameConflict, projectNameProblem, projectUrl, readRecentProjects, rememberProject, saveProjectAs, suggestedSaveAsName, type ProjectSnapshot } from "./projectFiles";

/**
 * Save As names and copies.
 * doc source: FEATURES DES-009
 * The public page prefixes "Copy_Of_". This gateway's project names are lowercase letters, digits, and '-',
 * so the offered name is "copy-of-" plus the current name, and a name already in use is refused.
 */

const view = blankView("line-1", "Line 1");

function snapshot(overrides: Partial<ProjectSnapshot> = {}): ProjectSnapshot {
  return {
    views: [{ view, ref: { name: "line-1", version: 1 }, dirty: false, inherited: false }],
    styleClasses: { classes: {}, themes: {}, ref: { name: "style-classes", version: 0 }, dirty: false },
    scriptHooks: { hooks: {}, ref: { name: "script-hooks", version: 0 }, dirty: false },
    pageConfig: null,
    ...overrides,
  };
}

describe("save-as names", () => {
  it("offers copy-of- plus the current name, and keeps the result a project name", () => {
    expect(suggestedSaveAsName("plant")).toBe("copy-of-plant");
    expect(suggestedSaveAsName("Copy_Of_Plant")).toBe("copy-of-copy-of-plant");
    const long = "a".repeat(63);
    const suggested = suggestedSaveAsName(long);
    expect(suggested.startsWith("copy-of-")).toBe(true);
    expect(suggested.length).toBeLessThanOrEqual(63);
    expect(validProjectName(suggested)).toBe(true);
  });

  it("refuses an empty name, spaces, uppercase, and underscores", () => {
    expect(projectNameProblem("  ")).toMatch(/Enter/);
    expect(projectNameProblem("Plant")).toMatch(/lowercase/);
    expect(projectNameProblem("copy_of_plant")).toMatch(/lowercase/);
    expect(projectNameProblem("Copy_Of_plant")).toMatch(/lowercase/);
    expect(projectNameProblem("plant")).toBeNull();
  });
});

describe("save as", () => {
  it("writes unsaved edits onto the copy and leaves the original unchanged", async () => {
    const { client, store } = fakeGateway();
    await client.session();
    const original = { ...view, metadata: { ...view.metadata, name: "Original" } };
    await saveView(client, "plant", original, { name: "line-1", version: 0 });
    const edited = { ...original, metadata: { ...original.metadata, name: "Edited" } };
    const before = structuredClone(store.get("/api/v1/config/designer/project/plant/View/line-1"));

    await saveProjectAs(client, "plant", "copy-of-plant", snapshot({ views: [{ view: edited, ref: { name: "line-1", version: 1 }, dirty: true, inherited: false }] }));

    expect(store.get("/api/v1/config/designer/project/plant/View/line-1")).toEqual(before);
    const copy = await loadProjectViews(client, "copy-of-plant");
    expect(copy.views.map((entry) => entry.view.metadata.name)).toEqual(["Edited"]);
    const originalLoaded = await loadProjectViews(client, "plant");
    expect(originalLoaded.views.map((entry) => entry.view.metadata.name)).toEqual(["Original"]);
  });

  it("refuses a name that is already a project and writes nothing", async () => {
    const { client, store } = fakeGateway();
    await client.session();
    await saveView(client, "plant", view, { name: "line-1", version: 0 });
    await saveView(client, "other", view, { name: "line-1", version: 0 });
    const before = [...store.keys()].sort();
    const error = await saveProjectAs(client, "plant", "other", snapshot()).catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ProjectNameConflict);
    expect((error as Error).message).toMatch(/already exists/);
    expect([...store.keys()].sort()).toEqual(before);
  });

  it("refuses the open project's own name", async () => {
    const { client } = fakeGateway();
    await client.session();
    await saveView(client, "plant", view, { name: "line-1", version: 0 });
    await expect(saveProjectAs(client, "plant", "plant", snapshot())).rejects.toThrow(/different name/);
  });

  it("creates the new project when the original was never saved", async () => {
    const { client } = fakeGateway();
    await client.session();
    await saveProjectAs(
      client,
      "draft",
      "packaging",
      snapshot({ views: [{ view, ref: { name: "line-1", version: 0 }, dirty: true, inherited: false }] }),
    );
    expect(await client.projectInfo("draft")).toBeNull();
    const copy = await loadProjectViews(client, "packaging");
    expect(copy.views.map((entry) => entry.view.metadata.path)).toEqual(["line-1"]);
  });
});

describe("recent projects", () => {
  it("keeps the newest names and drops anything that is not a project name", () => {
    const storage = new Map<string, string>();
    const store = {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => {
        storage.set(key, value);
      },
    };
    rememberProject(store, "plant");
    rememberProject(store, "Not A Name");
    rememberProject(store, "line");
    expect(readRecentProjects(store)).toEqual(["line", "plant"]);
    rememberProject(store, "plant");
    expect(readRecentProjects(store)).toEqual(["plant", "line"]);
  });
});

describe("project address", () => {
  it("keeps the Studio path so a gateway-served Studio opens the project in place", () => {
    expect(projectUrl("plant 2", "/studio/")).toBe("/studio/?project=plant%202");
    expect(projectUrl("demo", "/")).toBe("/?project=demo");
  });
});
