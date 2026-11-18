import { renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useDesignerSession, type DesignerSessionClient } from "./designerSession";
import { GatewayError } from "./gatewayClient";

function fakeClient(overrides: Partial<DesignerSessionClient> = {}) {
  let next = 1;
  return {
    openDesignerSession: vi.fn(async () => ({ id: `s-${next++}`, heartbeatSeconds: 15 })),
    heartbeatDesignerSession: vi.fn(async () => undefined),
    closeDesignerSession: vi.fn(async () => undefined),
    ...overrides,
  } satisfies DesignerSessionClient;
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe("useDesignerSession", () => {
  it("registers with the project, heartbeats on the gateway's period, and ends on unmount", async () => {
    const client = fakeClient();
    const { unmount } = renderHook(() => useDesignerSession(client, "plant"));
    await vi.advanceTimersByTimeAsync(0);
    expect(client.openDesignerSession).toHaveBeenCalledWith("plant");
    await vi.advanceTimersByTimeAsync(15_000);
    expect(client.heartbeatDesignerSession).toHaveBeenCalledWith("s-1");
    await vi.advanceTimersByTimeAsync(15_000);
    expect(client.heartbeatDesignerSession).toHaveBeenCalledTimes(2);
    unmount();
    expect(client.closeDesignerSession).toHaveBeenCalledWith("s-1");
    await vi.advanceTimersByTimeAsync(60_000);
    expect(client.heartbeatDesignerSession).toHaveBeenCalledTimes(2);
  });

  it("registers again when the gateway no longer has the session", async () => {
    const client = fakeClient({
      heartbeatDesignerSession: vi.fn(async () => {
        throw new GatewayError("no such Designer session", 404);
      }),
    });
    renderHook(() => useDesignerSession(client, "plant"));
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(15_000);
    expect(client.openDesignerSession).toHaveBeenCalledTimes(2);
  });

  it("stays quiet when the user may not open a session", async () => {
    const client = fakeClient({
      openDesignerSession: vi.fn(async () => {
        throw new GatewayError("denied", 403);
      }),
    });
    const { unmount } = renderHook(() => useDesignerSession(client, "plant"));
    await vi.advanceTimersByTimeAsync(0);
    unmount();
    expect(client.closeDesignerSession).not.toHaveBeenCalled();
  });

  it("does nothing without a gateway project", async () => {
    const client = fakeClient();
    renderHook(() => useDesignerSession(null, null));
    await vi.advanceTimersByTimeAsync(20_000);
    expect(client.openDesignerSession).not.toHaveBeenCalled();
  });
});
