import { act, fireEvent, render, renderHook, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GatewayError, type ProjectAccess, type ResourceLock, type ResourceRef } from "./gatewayClient";
import { ACCESS_REFRESH_MS, useProjectAccess, viewRef, type ProjectAccessClient, type ProjectAccessState } from "./projectAccess";
import { ProjectAccessBar, saveBlockedReason } from "./ProjectAccessBar";
import { useDesignerSession } from "./designerSession";

/** SEC-020: project permissions, protected views and resource locks as the Designer shows them. */

function accessDoc(overrides: Partial<ProjectAccess> = {}): ProjectAccess {
  return {
    project: "plant",
    user: "alice",
    allowed: { view: true, save: true, delete: true, protect: true },
    requiredRoles: { view: [], save: [], delete: [], protect: [] },
    breakLocks: false,
    protected: [],
    locks: [],
    ...overrides,
  };
}

function lockOf(ref: ResourceRef, user: string, mine = false, session?: string): ResourceLock {
  return { ...ref, path: `${ref.module}/${ref.kind}/${ref.name}`, user, acquiredAt: "2026-11-10T00:00:00Z", mine, ...(session ? { session } : {}) };
}

function fakeClient(doc: () => ProjectAccess) {
  return {
    projectAccess: vi.fn(async () => doc()),
    acquireLock: vi.fn(async (_project: string, ref: ResourceRef, session: string) => lockOf(ref, "alice", true, session)),
    releaseLock: vi.fn(async () => undefined),
    setProtected: vi.fn(async () => []),
  } satisfies ProjectAccessClient;
}

async function flush(ms: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe("useProjectAccess", () => {
  it("locks a view while it has unsaved edits and releases it when they are gone", async () => {
    const client = fakeClient(() => accessDoc());
    const main = viewRef("main");
    const { rerender } = renderHook(({ editing }) => useProjectAccess(client, "plant", "s-1", editing), {
      initialProps: { editing: [] as ResourceRef[] },
    });
    await flush(0);
    expect(client.acquireLock).not.toHaveBeenCalled();
    rerender({ editing: [main] });
    await flush(0);
    expect(client.acquireLock).toHaveBeenCalledWith("plant", main, "s-1");
    rerender({ editing: [main] });
    await flush(0);
    expect(client.acquireLock).toHaveBeenCalledTimes(1);
    rerender({ editing: [] });
    await flush(0);
    expect(client.releaseLock).toHaveBeenCalledWith("plant", main);
  });

  it("shows another Designer's lock and does not try to take it", async () => {
    const main = viewRef("main");
    const client = fakeClient(() => accessDoc({ locks: [lockOf(main, "bob")] }));
    const { result } = renderHook(() => useProjectAccess(client, "plant", "s-1", [main]));
    await flush(0);
    expect(result.current.lockedBy(main)?.user).toBe("bob");
    // The first attempt may race the first read; after it, a held lock is left alone.
    const calls = client.acquireLock.mock.calls.length;
    await flush(ACCESS_REFRESH_MS);
    expect(client.acquireLock.mock.calls.length).toBe(calls);
  });

  it("does not count its own session's lock as another Designer's", async () => {
    const main = viewRef("main");
    const client = fakeClient(() => accessDoc({ locks: [lockOf(main, "alice", true, "s-1")] }));
    const { result } = renderHook(() => useProjectAccess(client, "plant", "s-1", []));
    await flush(0);
    expect(result.current.lockedBy(main)).toBeNull();
    const other = renderHook(() => useProjectAccess(client, "plant", "s-2", []));
    await flush(0);
    expect(other.result.current.lockedBy(main)?.user).toBe("alice");
  });

  it("reports a refused lock other than a 423", async () => {
    const client = fakeClient(() => accessDoc());
    client.acquireLock.mockRejectedValueOnce(new GatewayError("save in project 'plant' needs one of the Designer roles lead", 403));
    const { result } = renderHook(() => useProjectAccess(client, "plant", "s-1", [viewRef("main")]));
    await flush(0);
    expect(result.current.error).toContain("lead");
  });
});

function state(doc: ProjectAccess, overrides: Partial<ProjectAccessState> = {}): ProjectAccessState {
  const path = (ref: ResourceRef) => `${ref.module}/${ref.kind}/${ref.name}`;
  return {
    access: doc,
    may: (action) => doc.allowed[action],
    isProtected: (ref) => doc.protected.includes(path(ref)),
    lockedBy: (ref) => doc.locks.find((lock) => lock.path === path(ref)) ?? null,
    protect: vi.fn(async () => undefined),
    breakLock: vi.fn(async () => undefined),
    error: null,
    ...overrides,
  };
}

describe("ProjectAccessBar", () => {
  const main = viewRef("main");

  it("says why the project cannot be saved", () => {
    const doc = accessDoc({ allowed: { view: true, save: false, delete: false, protect: false }, requiredRoles: { view: [], save: ["lead"], delete: [], protect: [] } });
    expect(saveBlockedReason(state(doc))).toContain("lead");
    render(<ProjectAccessBar state={state(doc)} resource={main} label="Main" />);
    expect(screen.getByText(/needs one of the Designer roles lead/)).toBeTruthy();
  });

  it("shows a locking error on a protected view without the protect roles", () => {
    const doc = accessDoc({
      allowed: { view: true, save: true, delete: true, protect: false },
      requiredRoles: { view: [], save: [], delete: [], protect: ["lead"] },
      protected: ["designer/View/main"],
    });
    render(<ProjectAccessBar state={state(doc)} resource={main} label="Main" />);
    expect(screen.getByRole("alert").textContent).toContain("Locking error: view Main is protected");
    expect(screen.queryByText("Unprotect")).toBeNull();
  });

  it("asks a protect-role holder how to go on with a protected view", () => {
    const doc = accessDoc({ protected: ["designer/View/main"] });
    const s = state(doc);
    render(<ProjectAccessBar state={s} resource={main} label="Main" />);
    fireEvent.click(screen.getByText("Unprotect"));
    expect(s.protect).toHaveBeenCalledWith(main, false);
    fireEvent.click(screen.getByText("Continue editing"));
    expect(screen.queryByText("Unprotect")).toBeNull();
  });

  it("offers Protect view, and Break lock to an administrator", () => {
    const doc = accessDoc({ breakLocks: true, locks: [lockOf(main, "bob")] });
    const s = state(doc);
    render(<ProjectAccessBar state={s} resource={main} label="Main" />);
    fireEvent.click(screen.getByText("Protect view"));
    expect(s.protect).toHaveBeenCalledWith(main, true);
    expect(screen.getByText(/bob is editing view Main/)).toBeTruthy();
    fireEvent.click(screen.getByText("Break lock"));
    expect(s.breakLock).toHaveBeenCalled();
  });

  it("offers no Break lock without users.admin", () => {
    render(<ProjectAccessBar state={state(accessDoc({ locks: [lockOf(main, "bob")] }))} resource={main} label="Main" />);
    expect(screen.queryByText("Break lock")).toBeNull();
  });
});

describe("useDesignerSession and locks", () => {
  it("returns the session id and sends it with saves until the session ends", async () => {
    const setDesignerSession = vi.fn();
    const client = {
      openDesignerSession: vi.fn(async () => ({ id: "s-9", heartbeatSeconds: 15 })),
      heartbeatDesignerSession: vi.fn(async () => undefined),
      closeDesignerSession: vi.fn(async () => undefined),
      setDesignerSession,
    };
    const { result, unmount } = renderHook(() => useDesignerSession(client, "plant"));
    await flush(0);
    expect(result.current).toBe("s-9");
    expect(setDesignerSession).toHaveBeenLastCalledWith("s-9");
    unmount();
    expect(setDesignerSession).toHaveBeenLastCalledWith(null);
  });
});

describe("ProjectSettingsPanel permissions", () => {
  it("saves the required Designer roles of each action", async () => {
    vi.useRealTimers();
    const { ProjectSettingsPanel } = await import("./ProjectSettingsPanel");
    const info = { name: "plant", designerSaveRoles: ["lead"] };
    const client = {
      projectInfo: vi.fn(async () => info),
      patchProject: vi.fn(async (_name: string, changes: Record<string, unknown>) => ({ ...info, ...changes })),
    };
    render(<ProjectSettingsPanel client={client as never} project="plant" />);
    const save = (await screen.findByLabelText("Save roles")) as HTMLInputElement;
    expect(save.value).toBe("lead");
    fireEvent.change(screen.getByLabelText("Protect resources roles"), { target: { value: "lead, admin, lead" } });
    fireEvent.submit(screen.getByRole("form", { name: "Project settings" }));
    await screen.findByText("Saved.");
    expect(client.patchProject).toHaveBeenCalledWith(
      "plant",
      expect.objectContaining({ designerSaveRoles: ["lead"], designerProtectRoles: ["lead", "admin"], designerViewRoles: [], designerDeleteRoles: [] }),
    );
  });
});
