import { createContext } from "react";
import { TagClient, type MemoryTagServer, type StringTransport, type TagUpdate, type TagValue } from "@oip/hmi-runtime";
import type { BrowseNode, BrowsePage, GatewayClient } from "./gatewayClient";
import { GatewayUnreachable } from "./gatewayClient";
import { isGatewayPath } from "./gatewayTags";

/**
 * Where the Designer's tag browser and tag picker read from.
 *
 * - `gateway`: the gateway's tag providers, folders and tags (`GET /api/v1/tags/providers`, then
 *   `POST /api/v1/tags/browse` one page at a time). Absent when the Studio opened without a gateway.
 * - `local`: the Studio's own tags (the sample tags the canvas runs on when there is no gateway), browsed as a tree
 *   split on `/`. Used when no gateway answers.
 * - `live`: current values for the rows in view, through the Studio's tag runtime. `[provider]` paths go over the
 *   gateway session socket by way of the gateway bridge (gatewayTags.ts); local paths come from the local server.
 */

/** One page of the children of [path]. The empty path is the root: providers for the gateway, top folders locally. */
export type Browse = (path: string, offset: number, limit: number) => Promise<BrowsePage>;

export const ROOT_PATH = "";

export interface LiveTags {
  /** Watch one path. [onUpdate] gets each new value; the returned function stops watching. */
  subscribe(path: string, onUpdate: (update: TagUpdate) => void): () => void;
}

export interface TagSources {
  gateway?: Browse;
  local?: Browse;
  live?: LiveTags;
}

export const TagSourcesContext = createContext<TagSources>({});

/** True when [error] means no gateway answered at all (as opposed to a refusal or a bad path). */
export function isUnreachable(error: unknown): boolean {
  return error instanceof GatewayUnreachable;
}

/** The gateway tree. The root lists the providers as folders; a provider that cannot be listed falls back to `[default]`. */
export function gatewayBrowse(client: Pick<GatewayClient, "browseTags" | "tagProviders">): Browse {
  return async (path, offset, limit) => {
    if (path !== ROOT_PATH) return client.browseTags(path, offset, limit);
    if (offset > 0) return { nodes: [], more: false };
    let names: string[];
    try {
      names = await client.tagProviders();
    } catch (error) {
      if (isUnreachable(error)) throw error;
      names = [];
    }
    if (names.length === 0) names = ["default"];
    return {
      nodes: names.map((name) => ({ name, path: `[${name}]`, kind: "folder", hasChildren: true })),
      more: false,
    };
  };
}

/** Data type name for a local value, in the gateway's spelling. */
export function localDataType(value: TagValue | undefined): string | undefined {
  if (typeof value === "number") return Number.isInteger(value) ? "int64" : "double";
  if (typeof value === "boolean") return "boolean";
  if (typeof value === "string") return "string";
  return undefined;
}

/**
 * The Studio's local tags as a tree. Paths are split on `/`; `[provider]` paths (gateway values the bridge mirrors
 * into the same server) are left out. The listing is read on every call, so tags added later show after a refresh.
 */
export function localBrowse(server: Pick<MemoryTagServer, "paths" | "get">): Browse {
  return async (path, offset, limit) => {
    const prefix = path === ROOT_PATH ? "" : `${path}/`;
    const seen = new Map<string, BrowseNode>();
    for (const full of server.paths()) {
      if (isGatewayPath(full) || !full.startsWith(prefix)) continue;
      const rest = full.slice(prefix.length);
      if (rest === "") continue;
      const slash = rest.indexOf("/");
      const name = slash < 0 ? rest : rest.slice(0, slash);
      const childPath = prefix + name;
      if (slash >= 0) {
        if (!seen.has(childPath) || seen.get(childPath)!.kind === "tag") {
          seen.set(childPath, { name, path: childPath, kind: "folder", hasChildren: true });
        }
      } else if (!seen.has(childPath)) {
        const current = server.get(full);
        seen.set(childPath, {
          name,
          path: childPath,
          kind: "tag",
          hasChildren: false,
          dataType: localDataType(current?.value),
          value: current?.value,
          quality: current?.quality,
        });
      }
    }
    const all = [...seen.values()].sort((a, b) => Number(b.hasChildren) - Number(a.hasChildren) || a.name.localeCompare(b.name));
    return { nodes: all.slice(offset, offset + limit), more: offset + limit < all.length };
  };
}

/**
 * Live values over a tag runtime transport. One client is opened on first use; each path is subscribed once
 * however many rows watch it, and unsubscribed when the last one stops.
 */
export class TransportLiveTags implements LiveTags {
  private client: TagClient | null = null;
  private handlers = new Map<string, { listeners: Set<(update: TagUpdate) => void>; off: () => void }>();

  constructor(private readonly open: () => StringTransport) {}

  subscribe(path: string, onUpdate: (update: TagUpdate) => void): () => void {
    const client = this.ensure();
    let entry = this.handlers.get(path);
    if (!entry) {
      entry = { listeners: new Set(), off: client.subscribe([path], () => {}) };
      this.handlers.set(path, entry);
    }
    entry.listeners.add(onUpdate);
    let active = true;
    return () => {
      if (!active) return;
      active = false;
      const current = this.handlers.get(path);
      if (!current) return;
      current.listeners.delete(onUpdate);
      if (current.listeners.size === 0) {
        current.off();
        this.handlers.delete(path);
      }
    };
  }

  /** Paths watched now (tests and the browser's live-row count). */
  paths(): string[] {
    return [...this.handlers.keys()];
  }

  private ensure(): TagClient {
    if (this.client) return this.client;
    const client = new TagClient(this.open());
    // One listener for the client: updates are routed to the rows watching each path.
    client.subscribe([], (updates) => {
      for (const update of updates) {
        const entry = this.handlers.get(update.path);
        if (entry) for (const listener of [...entry.listeners]) listener(update);
      }
    });
    this.client = client;
    return client;
  }
}
