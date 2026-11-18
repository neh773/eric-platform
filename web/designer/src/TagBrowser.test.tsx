import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { MemoryTagServer, TagClient, type TagUpdate } from "@oip/hmi-runtime";
import { MAX_LIVE_ROWS, TagBrowser, TagPickerDialog, type Browse, type RowObserverFactory } from "./TagBrowser";
import { TAG_DRAG_TYPE, decodeDraggedTag } from "./tagDrop";
import { GatewayError, GatewayUnreachable, type BrowseNode } from "./gatewayClient";
import { ROOT_PATH, TagSourcesContext, TransportLiveTags, gatewayBrowse, localBrowse, type LiveTags } from "./tagSource";
import { createTagRuntime } from "./runtime";

/** D-6: the Tags side tab. Lazy tree with paging, filter, unreadable rows, live values for rows in view, offline state. */

const tag = (path: string, extra: Partial<BrowseNode> = {}): BrowseNode => ({
  name: path.slice(path.lastIndexOf("/") + 1).replace(/^\[[^\]]+\]/, ""),
  path,
  kind: "tag",
  hasChildren: false,
  dataType: "double",
  ...extra,
});
const folder = (path: string, name: string): BrowseNode => ({ name, path, kind: "folder", hasChildren: true });

/** A fake visibility observer: rows count as in view only when a test says so. */
function fakeObserver() {
  const observed = new Set<Element>();
  let notify: ((entries: Array<{ target: Element; isIntersecting: boolean }>) => void) | null = null;
  const factory: RowObserverFactory = (onChange) => {
    notify = onChange;
    return {
      observe: (element) => observed.add(element),
      unobserve: (element) => observed.delete(element),
      disconnect: () => observed.clear(),
    };
  };
  function show(paths: string[], isIntersecting = true) {
    const entries = [...observed]
      .filter((element) => paths.includes((element as HTMLElement).dataset.tagPath ?? ""))
      .map((target) => ({ target, isIntersecting }));
    act(() => notify?.(entries));
  }
  function showAll() {
    act(() => notify?.([...observed].map((target) => ({ target, isIntersecting: true }))));
  }
  return { factory, show, showAll, observed };
}

/** Live values a test pushes by hand, with the set of watched paths. */
function fakeLive() {
  const listeners = new Map<string, Set<(update: TagUpdate) => void>>();
  const live: LiveTags = {
    subscribe(path, onUpdate) {
      if (!listeners.has(path)) listeners.set(path, new Set());
      listeners.get(path)!.add(onUpdate);
      return () => {
        listeners.get(path)?.delete(onUpdate);
        if (listeners.get(path)?.size === 0) listeners.delete(path);
      };
    },
  };
  return {
    live,
    watched: () => [...listeners.keys()].sort(),
    push(path: string, value: TagUpdate["value"], code: TagUpdate["code"] = "good", label?: string) {
      const quality = code === "good" ? "good" : code.startsWith("bad") ? "bad" : "uncertain";
      for (const listener of listeners.get(path) ?? []) listener({ path, value, quality, code, timestamp: Date.now(), label });
    },
  };
}

describe("tag browser tree", () => {
  it("lists providers at the root, loads a folder only when expanded, and pages with Load more", async () => {
    const browse = vi.fn<Browse>(async (path, offset) => {
      if (path === ROOT_PATH) return { nodes: [folder("[default]", "default")], more: false };
      if (path === "[default]") return { nodes: [folder("[default]line", "line")], more: false };
      return { nodes: [tag(`[default]line/t${offset}`)], more: offset === 0 };
    });
    render(<TagBrowser sources={{ gateway: browse }} />);
    fireEvent.click(await screen.findByRole("treeitem", { name: /default/ }));
    const line = await screen.findByRole("treeitem", { name: /line/ });
    expect(browse).toHaveBeenCalledTimes(2);
    expect(line).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(line);
    await screen.findByText("t0");
    expect(browse).toHaveBeenLastCalledWith("[default]line", 0, 200);
    fireEvent.click(screen.getByRole("button", { name: "Load more" }));
    await screen.findByText("t1");
    expect(browse).toHaveBeenLastCalledWith("[default]line", 1, 200);
    expect(screen.queryByRole("button", { name: "Load more" })).toBeNull();

    // Collapsing and expanding again does not browse again.
    fireEvent.click(line);
    fireEvent.click(line);
    expect(browse).toHaveBeenCalledTimes(4);
  });

  it("puts the tag path and data type on the drag", async () => {
    render(<TagBrowser sources={{ gateway: async () => ({ nodes: [tag("[default]level")], more: false }) }} />);
    const row = await screen.findByRole("treeitem", { name: /level/ });
    const data = new Map<string, string>();
    fireEvent.dragStart(row, { dataTransfer: { setData: (type: string, value: string) => data.set(type, value), effectAllowed: "" } });
    expect(decodeDraggedTag(data.get(TAG_DRAG_TYPE)!)).toEqual({ path: "[default]level", dataType: "double" });
  });

  it("shows a browse refusal as an error, not as offline", async () => {
    render(<TagBrowser sources={{ gateway: async () => Promise.reject(new GatewayError("forbidden", 403)) }} />);
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("forbidden"));
    expect(screen.queryByTestId("tags-offline")).toBeNull();
  });

  it("filters loaded rows by name or path and opens folders that hold a match", async () => {
    const browse: Browse = async (path) => {
      if (path === ROOT_PATH) return { nodes: [folder("[default]a", "a"), tag("[default]pressure")], more: false };
      return { nodes: [tag("[default]a/level"), tag("[default]a/flow")], more: false };
    };
    render(<TagBrowser sources={{ gateway: browse }} />);
    fireEvent.click(await screen.findByRole("treeitem", { name: /a$/ }));
    await screen.findByText("flow");
    fireEvent.click(screen.getByRole("treeitem", { name: /a$/ })); // collapse

    fireEvent.change(screen.getByLabelText("Filter tags"), { target: { value: "LEV" } });
    expect(screen.getByText("level")).toBeInTheDocument();
    expect(screen.queryByText("flow")).toBeNull();
    expect(screen.queryByText("pressure")).toBeNull();
    expect(screen.getByRole("treeitem", { name: /a$/ })).toHaveAttribute("aria-expanded", "true");

    fireEvent.change(screen.getByLabelText("Filter tags"), { target: { value: "nothing-like-this" } });
    expect(screen.getByText("No loaded tag matches")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Filter tags"), { target: { value: "" } });
    expect(screen.getByText("pressure")).toBeInTheDocument();
  });

  it("shows unreadable rows greyed with no value or data type, and never watches them", async () => {
    const browse: Browse = async () => ({
      nodes: [tag("[default]open", { value: 4, quality: "Good" }), tag("[default]secret", { readable: false, dataType: undefined })],
      more: false,
    });
    const live = fakeLive();
    const observer = fakeObserver();
    render(<TagBrowser sources={{ gateway: browse, live: live.live }} observer={observer.factory} />);
    const secret = await screen.findByRole("treeitem", { name: /secret/ });
    observer.showAll();
    expect(secret).toHaveAttribute("data-readable", "false");
    expect(secret).toHaveAttribute("aria-disabled", "true");
    expect(within(secret).queryByText("double")).toBeNull();
    expect(secret.querySelector(".oip-tagtree-value")).toBeNull();
    expect(within(secret).getByText("no access")).toBeInTheDocument();
    expect(live.watched()).toEqual(["[default]open"]);
    const open = screen.getByRole("treeitem", { name: /open/ });
    expect(within(open).getByText("double")).toBeInTheDocument();
    expect(within(open).getByText("4")).toBeInTheDocument();
  });
});

describe("tag browser live values", () => {
  it("watches only rows in view, and stops when a row scrolls away or its folder collapses", async () => {
    const browse: Browse = async (path) => {
      if (path === ROOT_PATH) return { nodes: [folder("[default]f", "f"), tag("[default]a"), tag("[default]b"), tag("[default]c")], more: false };
      return { nodes: [tag("[default]f/x")], more: false };
    };
    const live = fakeLive();
    const observer = fakeObserver();
    render(<TagBrowser sources={{ gateway: browse, live: live.live }} observer={observer.factory} />);
    await screen.findByText("c");
    expect(live.watched()).toEqual([]);

    observer.show(["[default]a", "[default]b"]);
    expect(live.watched()).toEqual(["[default]a", "[default]b"]);
    expect(screen.getByRole("tree", { name: "Tags" })).toHaveAttribute("data-live-count", "2");

    observer.show(["[default]a"], false);
    expect(live.watched()).toEqual(["[default]b"]);

    fireEvent.click(screen.getByRole("treeitem", { name: /f$/ }));
    await screen.findByText("x");
    observer.show(["[default]f/x"]);
    expect(live.watched()).toEqual(["[default]b", "[default]f/x"]);
    // Collapsing unmounts the row: it is no longer watched.
    fireEvent.click(screen.getByRole("treeitem", { name: /f$/ }));
    expect(live.watched()).toEqual(["[default]b"]);
  });

  it(`never watches more than ${MAX_LIVE_ROWS} rows`, async () => {
    const nodes = Array.from({ length: MAX_LIVE_ROWS + 50 }, (_, index) => tag(`[default]t${index}`));
    const live = fakeLive();
    const observer = fakeObserver();
    render(<TagBrowser sources={{ gateway: async () => ({ nodes, more: false }), live: live.live }} observer={observer.factory} />);
    await screen.findByText(`t${MAX_LIVE_ROWS + 49}`);
    observer.showAll();
    expect(live.watched()).toHaveLength(MAX_LIVE_ROWS);
  });

  it("shows the live value and its quality, and drops all watches on unmount", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const live = fakeLive();
      const observer = fakeObserver();
      const view = render(
        <TagBrowser sources={{ gateway: async () => ({ nodes: [tag("[default]level")], more: false }), live: live.live }} observer={observer.factory} />,
      );
      const row = await screen.findByRole("treeitem", { name: /level/ });
      observer.showAll();
      act(() => live.push("[default]level", 12.5, "good", "Good"));
      await act(async () => vi.advanceTimersByTimeAsync(250));
      expect(within(row).getByText("12.5")).toBeInTheDocument();
      expect(row.querySelector(".oip-quality-dot")).toHaveAttribute("data-quality", "good");
      act(() => live.push("[default]level", 12.5, "bad_not_connected", "Bad_NotConnected"));
      await act(async () => vi.advanceTimersByTimeAsync(250));
      expect(row.querySelector(".oip-quality-dot")).toHaveAttribute("data-quality", "bad");
      expect(row.querySelector(".oip-quality-dot")).toHaveAttribute("title", "Bad_NotConnected");
      view.unmount();
      expect(live.watched()).toEqual([]);
    } finally {
      vi.useRealTimers();
    }
  });

  it("subscribes through the Studio tag runtime, once per path", async () => {
    const runtime = createTagRuntime((server) => server.set("plant/level", 3));
    const live = new TransportLiveTags(runtime.openTransport);
    const seen: unknown[] = [];
    const offA = live.subscribe("plant/level", (update) => seen.push(update.value));
    const offB = live.subscribe("plant/level", () => {});
    expect(live.paths()).toEqual(["plant/level"]);
    await waitFor(() => expect(seen).toContain(3));
    runtime.server.set("plant/level", 4);
    await waitFor(() => expect(seen).toContain(4));
    offA();
    expect(live.paths()).toEqual(["plant/level"]);
    offB();
    expect(live.paths()).toEqual([]);
  });
});

describe("tag browser offline", () => {
  it("browses the Studio's local tags when no gateway answers, with a retry", async () => {
    const server = new MemoryTagServer();
    server.set("plant/tank/level", 62.4);
    server.set("plant/pump/running", true);
    server.set("[default]mirrored", 1);
    let up = false;
    const gateway = vi.fn<Browse>(async () => {
      if (!up) throw new GatewayUnreachable("connection refused");
      return { nodes: [folder("[default]", "default")], more: false };
    });
    render(<TagBrowser sources={{ gateway, local: localBrowse(server) }} />);
    expect(await screen.findByTestId("tags-offline")).toHaveTextContent("Gateway not reachable. Showing the Studio's local tags.");
    fireEvent.click(await screen.findByRole("treeitem", { name: /plant/ }));
    fireEvent.click(await screen.findByRole("treeitem", { name: /tank/ }));
    const level = await screen.findByRole("treeitem", { name: /level/ });
    expect(within(level).getByText("double")).toBeInTheDocument();
    expect(screen.queryByText("mirrored")).toBeNull();

    up = true;
    fireEvent.click(within(screen.getByTestId("tags-offline")).getByRole("button", { name: "Retry" }));
    await screen.findByRole("treeitem", { name: /default/ });
    expect(screen.queryByTestId("tags-offline")).toBeNull();
  });

  it("shows Gateway not reachable with a retry when there is nothing local, and does not crash", async () => {
    const gateway = vi.fn<Browse>(async () => {
      throw new GatewayUnreachable("gateway not reachable (HTTP 502)");
    });
    render(<TagBrowser sources={{ gateway }} />);
    const banner = await screen.findByTestId("tags-offline");
    expect(banner).toHaveTextContent("Gateway not reachable.");
    expect(screen.queryByRole("tree")).toBeNull();
    fireEvent.click(within(banner).getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(gateway).toHaveBeenCalledTimes(2));
    expect(await screen.findByTestId("tags-offline")).toBeInTheDocument();
  });

  it("falls back when the gateway goes away while a folder loads", async () => {
    const server = new MemoryTagServer();
    server.set("local/a", 1);
    const gateway: Browse = async (path) => {
      if (path === ROOT_PATH) return { nodes: [folder("[default]", "default")], more: false };
      throw new GatewayUnreachable("gone");
    };
    render(<TagBrowser sources={{ gateway, local: localBrowse(server) }} />);
    fireEvent.click(await screen.findByRole("treeitem", { name: /default/ }));
    expect(await screen.findByTestId("tags-offline")).toBeInTheDocument();
    expect(await screen.findByRole("treeitem", { name: /local/ })).toBeInTheDocument();
  });
});

describe("tag sources", () => {
  it("lists gateway providers at the root and falls back to [default] when they cannot be listed", async () => {
    const client = {
      tagProviders: vi.fn(async () => ["default", "edge"]),
      browseTags: vi.fn(async () => ({ nodes: [], more: false })),
    };
    const browse = gatewayBrowse(client);
    expect((await browse(ROOT_PATH, 0, 200)).nodes.map((node) => node.path)).toEqual(["[default]", "[edge]"]);
    await browse("[edge]", 5, 10);
    expect(client.browseTags).toHaveBeenCalledWith("[edge]", 5, 10);
    client.tagProviders.mockRejectedValueOnce(new GatewayError("no", 403));
    expect((await browse(ROOT_PATH, 0, 200)).nodes.map((node) => node.path)).toEqual(["[default]"]);
    client.tagProviders.mockRejectedValueOnce(new GatewayUnreachable("down"));
    await expect(browse(ROOT_PATH, 0, 200)).rejects.toBeInstanceOf(GatewayUnreachable);
  });

  it("pages the local tree with folders first", async () => {
    const server = new MemoryTagServer();
    for (let index = 0; index < 5; index += 1) server.set(`bench/${index}`, 0);
    server.set("zeta", "z");
    server.set("alpha", true);
    const browse = localBrowse(server);
    const root = await browse(ROOT_PATH, 0, 200);
    expect(root.nodes.map((node) => [node.name, node.kind, node.dataType])).toEqual([
      ["bench", "folder", undefined],
      ["alpha", "tag", "boolean"],
      ["zeta", "tag", "string"],
    ]);
    const first = await browse("bench", 0, 2);
    expect(first.nodes.map((node) => node.path)).toEqual(["bench/0", "bench/1"]);
    expect(first.more).toBe(true);
    const last = await browse("bench", 4, 2);
    expect(last.nodes.map((node) => node.path)).toEqual(["bench/4"]);
    expect(last.more).toBe(false);
  });
});

describe("tag browser keyboard", () => {
  const browse: Browse = async (path) => {
    if (path === ROOT_PATH) return { nodes: [folder("[default]f", "f"), tag("[default]a"), tag("[default]b")], more: false };
    return { nodes: [tag("[default]f/x"), tag("[default]f/y")], more: false };
  };
  const byPath = (path: string) => document.querySelector<HTMLElement>(`[data-tag-path='${path}']`)!;
  const tabStops = () => screen.getAllByRole("treeitem").filter((item) => item.tabIndex === 0);

  it("is one tab stop with Up, Down, Home, End, Right, Left and Enter", async () => {
    const picked = vi.fn();
    render(<TagBrowser sources={{ gateway: browse }} onPick={picked} />);
    const f = await screen.findByRole("treeitem", { name: /f$/ });
    expect(tabStops()).toEqual([f]);
    f.focus();
    fireEvent.keyDown(f, { key: "ArrowRight" });
    await screen.findByText("x");
    expect(f).toHaveAttribute("aria-expanded", "true");
    fireEvent.keyDown(f, { key: "ArrowRight" });
    const x = byPath("[default]f/x");
    expect(x).toHaveFocus();
    expect(tabStops()).toEqual([x]);
    fireEvent.keyDown(x, { key: "ArrowDown" });
    expect(byPath("[default]f/y")).toHaveFocus();
    fireEvent.keyDown(document.activeElement!, { key: "End" });
    const b = byPath("[default]b");
    expect(b).toHaveFocus();
    fireEvent.keyDown(b, { key: "ArrowUp" });
    expect(byPath("[default]a")).toHaveFocus();
    fireEvent.keyDown(document.activeElement!, { key: "Home" });
    expect(f).toHaveFocus();
    fireEvent.keyDown(f, { key: "ArrowDown" });
    // Left on a row inside a folder moves to the folder; Left on the open folder closes it.
    fireEvent.keyDown(document.activeElement!, { key: "ArrowLeft" });
    expect(f).toHaveFocus();
    fireEvent.keyDown(f, { key: "ArrowLeft" });
    expect(f).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText("x")).toBeNull();
    fireEvent.keyDown(f, { key: "End" });
    fireEvent.keyDown(document.activeElement!, { key: "Enter" });
    expect(picked).toHaveBeenCalledWith({ path: "[default]b", dataType: "double" });
  });

  it("opens and closes folders with Enter in the side tab, and reaches Load more", async () => {
    const paged: Browse = async (path, offset) =>
      path === ROOT_PATH ? { nodes: [tag(`[default]t${offset}`)], more: offset === 0 } : { nodes: [], more: false };
    render(<TagBrowser sources={{ gateway: paged }} />);
    const t0 = await screen.findByRole("treeitem", { name: /t0/ });
    t0.focus();
    fireEvent.keyDown(t0, { key: "ArrowDown" });
    const more = screen.getByRole("button", { name: "Load more" });
    expect(more).toHaveFocus();
    expect(more.tabIndex).toBe(0);
    fireEvent.keyDown(more, { key: "Enter" });
    await screen.findByText("t1");
  });

  it("loads a folder again when it is reopened after its load failed", async () => {
    let fail = true;
    const flaky: Browse = async (path) => {
      if (path === ROOT_PATH) return { nodes: [folder("[default]f", "f")], more: false };
      if (fail) throw new GatewayError("busy", 503);
      return { nodes: [tag("[default]f/x")], more: false };
    };
    render(<TagBrowser sources={{ gateway: flaky }} />);
    const f = await screen.findByRole("treeitem", { name: /f$/ });
    fireEvent.click(f);
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("busy"));
    fail = false;
    fireEvent.click(f);
    fireEvent.click(f);
    await screen.findByText("x");
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("tag picker dialog", () => {
  it("keeps Tab and Shift+Tab inside, closes on Escape from anywhere, and returns focus", async () => {
    const opener = document.createElement("button");
    document.body.appendChild(opener);
    const returnFocus = { current: opener };
    const onClose = vi.fn();
    render(
      <TagSourcesContext.Provider value={{ gateway: browse1 }}>
        <TagPickerDialog onPick={() => {}} onClose={onClose} returnFocus={returnFocus} />
      </TagSourcesContext.Provider>,
    );
    const dialog = screen.getByRole("dialog", { name: "Pick a tag" });
    await within(dialog).findByRole("treeitem", { name: /level/ });
    const filterBox = within(dialog).getByLabelText("Filter tags");
    expect(filterBox).toHaveFocus();
    const stops = [within(dialog).getByRole("button", { name: "Close tag picker" }), within(dialog).getByRole("button", { name: "Refresh" }), filterBox, within(dialog).getByRole("treeitem", { name: /level/ })];
    fireEvent.keyDown(filterBox, { key: "Tab" });
    expect(stops[3]).toHaveFocus();
    fireEvent.keyDown(stops[3]!, { key: "Tab" });
    expect(stops[0]).toHaveFocus();
    fireEvent.keyDown(stops[0]!, { key: "Tab", shiftKey: true });
    expect(stops[3]).toHaveFocus();
    // Focus that leaves is brought back.
    act(() => opener.focus());
    expect(filterBox).toHaveFocus();
    fireEvent.keyDown(document.body, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
    opener.remove();
  });

  it("puts focus on the close button when there is no filter box", async () => {
    const down: Browse = async () => {
      throw new GatewayUnreachable("down");
    };
    render(
      <TagSourcesContext.Provider value={{ gateway: down }}>
        <TagPickerDialog onPick={() => {}} onClose={() => {}} />
      </TagSourcesContext.Provider>,
    );
    const dialog = screen.getByRole("dialog", { name: "Pick a tag" });
    await within(dialog).findByTestId("tags-offline");
    expect(within(dialog).queryByLabelText("Filter tags")).toBeNull();
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "Close tag picker" })).toHaveFocus());
  });
});

const browse1: Browse = async () => ({ nodes: [tag("[default]level")], more: false });

describe("tag browser sources and observer", () => {
  it("moves every watch to a new live source", async () => {
    const first = fakeLive();
    const second = fakeLive();
    const observer = fakeObserver();
    const sources = { gateway: browse1, live: first.live };
    const view = render(<TagBrowser sources={sources} observer={observer.factory} />);
    await screen.findByRole("treeitem", { name: /level/ });
    observer.showAll();
    expect(first.watched()).toEqual(["[default]level"]);
    view.rerender(<TagBrowser sources={{ ...sources, live: second.live }} observer={observer.factory} />);
    expect(first.watched()).toEqual([]);
    expect(second.watched()).toEqual(["[default]level"]);
  });

  it("rebuilds the visibility observer when the tree remounts", async () => {
    let up = false;
    const gateway: Browse = async () => {
      if (!up) throw new GatewayUnreachable("down");
      return { nodes: [tag("[default]level")], more: false };
    };
    const live = fakeLive();
    const roots: Element[] = [];
    const observer = fakeObserver();
    const factory: RowObserverFactory = (onChange, root) => {
      roots.push(root);
      return observer.factory(onChange, root);
    };
    render(<TagBrowser sources={{ gateway, live: live.live }} observer={factory} />);
    await screen.findByTestId("tags-offline");
    const before = roots.length;
    up = true;
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await screen.findByRole("treeitem", { name: /level/ });
    expect(roots.length).toBeGreaterThan(before);
    expect(roots[roots.length - 1]).toBe(screen.getByRole("tree"));
    observer.showAll();
    expect(live.watched()).toEqual(["[default]level"]);
  });

  it("leaves the canvas subscribed when the browser stops watching a shared gateway path", async () => {
    let level = 1;
    const runtime = createTagRuntime(
      () => {},
      async (paths) => paths.map((path) => ({ path, value: level, quality: "Good", timestamp: new Date().toISOString() })),
    );
    runtime.bridge!.setOnline(true);
    const canvasClient = new TagClient(runtime.transport);
    const seen: unknown[] = [];
    canvasClient.subscribe(["[default]shared"], (updates) => {
      for (const update of updates) if (update.path === "[default]shared" && update.quality === "good") seen.push(update.value);
    });
    const browser = new TransportLiveTags(runtime.openTransport);
    const off = browser.subscribe("[default]shared", () => {});
    await waitFor(() => expect(runtime.bridge!.paths()).toEqual(["[default]shared"]));
    await runtime.bridge!.poll();
    await waitFor(() => expect(seen).toContain(1));

    off();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(runtime.bridge!.paths()).toEqual(["[default]shared"]);
    level = 2;
    await runtime.bridge!.poll();
    await waitFor(() => expect(seen).toContain(2));
    runtime.bridge!.dispose();
    runtime.server.close();
  });
});
