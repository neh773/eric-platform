import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { fakeGateway } from "./fakeGateway.testutil";
import type { GatewayClient } from "./gatewayClient";
import { SaveControls, unsavedLabel } from "./PersistenceBar";
import { ProjectGate, type Opened } from "./ProjectGate";
import { SCRIPT_HOOKS_RESOURCE, loadScriptHooks, parseScriptHooks, saveScriptHooks } from "./persistence";
import { sampleProject } from "./sampleProject";
import { ScriptHooksPanel } from "./ScriptHooksPanel";
import { useSavedProject } from "./useSavedProject";

/** D-5: the project's script hooks are edited in the Scripts panel and saved with If-Match versions like the views. */

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
    },
    client,
    opened.project,
  );
  return (
    <div>
      <SaveControls saved={saved} offlineReason={null} />
      <ScriptHooksPanel hooks={saved.scriptHooks} onChange={saved.replaceScriptHooks} />
    </div>
  );
}

function openStudio(client: GatewayClient) {
  return render(
    <ProjectGate client={client} project="demo" starter={sampleProject.views}>
      {(opened) => <Harness client={client} opened={opened} />}
    </ProjectGate>,
  );
}

const status = () => screen.getByRole("status");

async function save(text: string | RegExp) {
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  await waitFor(() => expect(status()).toHaveTextContent(text));
}

function addHook(name: string, source: string) {
  fireEvent.change(screen.getByRole("textbox", { name: "New hook name" }), { target: { value: name } });
  fireEvent.click(screen.getByRole("button", { name: "Add hook" }));
  fireEvent.change(within(screen.getByRole("region", { name: `Hook ${name}` })).getByRole("textbox", { name: "Hook source" }), { target: { value: source } });
}

describe("project script hooks", () => {
  it("adds a hook, saves it to the ScriptHooks resource, and finds it after a reload", async () => {
    const { client, calls } = fakeGateway();
    const first = openStudio(client);
    await screen.findByTestId("script-hooks-panel");
    await save("All changes saved");
    expect(await loadScriptHooks(client, "demo")).toBeNull();

    fireEvent.change(screen.getByRole("textbox", { name: "New hook name" }), { target: { value: "1 bad" } });
    fireEvent.click(screen.getByRole("button", { name: "Add hook" }));
    expect(screen.getByRole("alert")).toHaveTextContent("starting with a letter");

    addHook("startPump", "actions.writeTag('[default]pump/run', True)");
    expect(status()).toHaveTextContent("Unsaved script hooks");
    await save("All changes saved");
    const stored = await loadScriptHooks(client, "demo");
    expect(stored).toEqual({ hooks: { startPump: { source: "actions.writeTag('[default]pump/run', True)" } }, ref: { name: SCRIPT_HOOKS_RESOURCE, version: 1 } });
    const put = calls.find((call) => call.method === "PUT" && call.url.endsWith("/ScriptHooks/script-hooks"));
    expect(put?.url).toBe("/api/v1/config/designer/project/demo/ScriptHooks/script-hooks");

    first.unmount();
    openStudio(client);
    await screen.findByTestId("script-hooks-panel");
    expect(screen.getByRole("button", { name: "startPump" })).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("textbox", { name: "Hook source" })).toHaveValue("actions.writeTag('[default]pump/run', True)");
    expect(status()).toHaveTextContent("All changes saved");

    fireEvent.click(screen.getByRole("button", { name: "Remove hook startPump" }));
    await save("All changes saved");
    expect((await loadScriptHooks(client, "demo"))?.hooks).toEqual({});
  });

  it("a stale save shows the conflict prompt; use the gateway copy, then keep mine", async () => {
    const { client } = fakeGateway();
    openStudio(client);
    await screen.findByTestId("script-hooks-panel");
    addHook("startPump", "pass");
    await save("All changes saved");

    // Another designer saves the project's hooks first.
    const stored = (await loadScriptHooks(client, "demo"))!;
    await saveScriptHooks(client, "demo", { startPump: { source: "theirs()" } }, stored.ref);

    fireEvent.change(screen.getByRole("textbox", { name: "Hook source" }), { target: { value: "mine()" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Save conflict" });
    expect(dialog).toHaveTextContent("script hooks were changed on the gateway");
    fireEvent.click(within(dialog).getByRole("button", { name: "Use gateway copy" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(screen.getByRole("textbox", { name: "Hook source" })).toHaveValue("theirs()");
    expect(status()).toHaveTextContent("All changes saved");

    const again = (await loadScriptHooks(client, "demo"))!;
    await saveScriptHooks(client, "demo", { startPump: { source: "theirs again()" } }, again.ref);
    fireEvent.change(screen.getByRole("textbox", { name: "Hook source" }), { target: { value: "mine wins()" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    const second = await screen.findByRole("alertdialog", { name: "Save conflict" });
    fireEvent.click(within(second).getByRole("button", { name: "Keep my changes" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    const final = (await loadScriptHooks(client, "demo"))!;
    expect(final.hooks.startPump?.source).toBe("mine wins()");
    expect(final.ref.version).toBe(4);
  });

  it("checks a stored hook set and labels unsaved hooks with the other edits", () => {
    expect(parseScriptHooks({ a: { source: "pass" } })).toEqual({ a: { source: "pass" } });
    expect(parseScriptHooks(undefined)).toEqual({});
    expect(() => parseScriptHooks({ a: {} })).toThrow("hook a has no source");
    expect(unsavedLabel(0, false, true)).toBe("Unsaved script hooks");
    expect(unsavedLabel(2, true, true)).toBe("2 unsaved views, style classes and script hooks");
    expect(unsavedLabel(1, true)).toBe("1 unsaved view and style classes");
    expect(unsavedLabel(0, true)).toBe("Unsaved style classes");
  });
});
