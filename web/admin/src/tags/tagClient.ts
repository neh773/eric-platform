import { ApiError, configItemPath, parseResource, problemError, type ApiClient, type ResourceDocument } from "../api";
import type { JsonSchema } from "../schema";

/**
 * Tag browser and editor calls (C-16).
 *
 * Values come from the caller-aware tag routes (POST /api/v1/tags/browse, /read, /write): a tag whose read
 * permissions do not admit the caller comes back without a value (browse) or as Bad_AccessDenied (read).
 * Configuration goes through the config store (`tags/gateway/Tag/<resource>`), so every edit bumps
 * `metadata.version` and a stale version is a 409.
 */

export const TAGS_MODULE = "tags";
export const TAG_KIND = "Tag";
export const TAG_SCOPE = "gateway";
export const ACCESS_DENIED = "Bad_AccessDenied";

export type NodeKind = "tag" | "folder" | "udt_instance" | "udt_definition";

export interface BrowseRow {
  name: string;
  path: string;
  kind: NodeKind;
  hasChildren: boolean;
  dataType?: string;
  /** Absent when the caller may not read the tag. */
  value?: unknown;
  quality?: string;
  timestamp?: string;
  /** Config resource name that defines this node, when it is a stored Tag resource. */
  resource?: string;
}

export interface BrowsePage {
  path: string;
  offset: number;
  nodes: BrowseRow[];
  more: boolean;
}

export interface TagValue {
  path: string;
  value: unknown;
  quality: string;
  timestamp: string;
}

export interface TagWriteResult {
  path: string;
  quality: string;
  ok: boolean;
  message?: string;
}

export interface TagProviderInfo {
  name: string;
}

export interface DeviceInfo {
  name: string;
  driver: "modbus" | "opcua";
  state: string;
}

export interface DeviceNode {
  nodeId: string;
  name: string;
  nodeClass: string;
  readable: boolean;
  writable: boolean;
  hasChildren: boolean;
  value?: unknown;
  quality?: string;
}

export interface DeviceBrowsePage {
  device: string;
  nodeId: string;
  nodes: DeviceNode[];
  more: boolean;
}

async function json(client: ApiClient, method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<unknown> {
  const res = await client.request(method, path, body, headers);
  if (!res.ok) throw await problemError(res);
  if (res.status === 204) return null;
  return res.json();
}

function obj(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new ApiError("Response was incomplete", 500);
  return value as Record<string, unknown>;
}

function list(body: unknown, key: string): unknown[] {
  const value = obj(body)[key];
  if (!Array.isArray(value)) throw new ApiError("Response was incomplete", 500);
  return value;
}

function text(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

const KINDS: readonly NodeKind[] = ["tag", "folder", "udt_instance", "udt_definition"];

export function parseBrowseRow(value: unknown): BrowseRow {
  const row = obj(value);
  const kind = KINDS.includes(row.kind as NodeKind) ? (row.kind as NodeKind) : "folder";
  const out: BrowseRow = {
    name: text(row.name),
    path: text(row.path),
    kind,
    hasChildren: row.hasChildren === true,
  };
  if (typeof row.dataType === "string") out.dataType = row.dataType;
  if ("quality" in row && typeof row.quality === "string") {
    out.value = row.value;
    out.quality = row.quality;
    out.timestamp = text(row.timestamp);
  }
  if (typeof row.resource === "string" && row.resource) out.resource = row.resource;
  return out;
}

export async function browse(client: ApiClient, path: string, offset = 0, limit = 100): Promise<BrowsePage> {
  const body = obj(await json(client, "POST", "/api/v1/tags/browse", { path, offset, limit }));
  return {
    path: text(body.path, path),
    offset: typeof body.offset === "number" ? body.offset : offset,
    nodes: list(body, "nodes").map(parseBrowseRow),
    more: body.more === true,
  };
}

export async function readValues(client: ApiClient, paths: readonly string[]): Promise<TagValue[]> {
  if (paths.length === 0) return [];
  const body = await json(client, "POST", "/api/v1/tags/read", { paths });
  return list(body, "values").map((item) => {
    const row = obj(item);
    return { path: text(row.path), value: row.value, quality: text(row.quality), timestamp: text(row.timestamp) };
  });
}

export async function writeValue(client: ApiClient, path: string, value: unknown): Promise<TagWriteResult> {
  const body = await json(client, "POST", "/api/v1/tags/write", { writes: [{ path, value }] });
  const row = obj(list(body, "results")[0]);
  const out: TagWriteResult = { path: text(row.path, path), quality: text(row.quality), ok: row.ok === true };
  if (typeof row.message === "string") out.message = row.message;
  return out;
}

export async function listProviders(client: ApiClient): Promise<TagProviderInfo[]> {
  const body = await json(client, "GET", "/api/v1/tags/providers");
  return list(body, "providers").map((item) => ({ name: text(obj(item).name) })).filter((p) => p.name);
}

export async function listDevices(client: ApiClient): Promise<DeviceInfo[]> {
  const body = await json(client, "GET", "/api/v1/tags/devices");
  return list(body, "devices").map((item) => {
    const row = obj(item);
    return { name: text(row.name), driver: row.driver === "opcua" ? "opcua" : "modbus", state: text(row.state) };
  });
}

export async function browseDevice(client: ApiClient, device: string, nodeId: string, offset = 0): Promise<DeviceBrowsePage> {
  const body = obj(await json(client, "POST", "/api/v1/tags/devices/browse", { device, nodeId, offset }));
  return {
    device: text(body.device, device),
    nodeId: text(body.nodeId, nodeId),
    more: body.more === true,
    nodes: list(body, "nodes").map((item) => {
      const row = obj(item);
      const node: DeviceNode = {
        nodeId: text(row.nodeId),
        name: text(row.name, text(row.nodeId)),
        nodeClass: text(row.nodeClass),
        readable: row.readable === true,
        writable: row.writable === true,
        hasChildren: row.hasChildren === true,
      };
      if (typeof row.quality === "string") {
        node.value = row.value;
        node.quality = row.quality;
      }
      return node;
    }),
  };
}

export async function tagSchema(client: ApiClient): Promise<JsonSchema> {
  return client.getSchema(TAGS_MODULE, TAG_KIND);
}

export async function getTag(client: ApiClient, resource: string): Promise<ResourceDocument> {
  return client.getResource(TAGS_MODULE, TAG_SCOPE, TAG_KIND, resource);
}

/** Create a new Tag resource. 409 when the resource name is taken. */
export async function createTag(client: ApiClient, resource: string, spec: Record<string, unknown>): Promise<ResourceDocument> {
  const body = await json(client, "POST", `/api/v1/config/${TAGS_MODULE}/${TAG_SCOPE}/${TAG_KIND}`, {
    apiVersion: "oip/v1",
    kind: TAG_KIND,
    metadata: { name: resource, module: TAGS_MODULE, version: 0 },
    spec,
  });
  return parseResource(body);
}

/** Replace a Tag resource at its loaded version. A stale version is a 409 (ApiError.status). */
export async function saveTag(client: ApiClient, doc: ResourceDocument): Promise<ResourceDocument> {
  return client.putResource(TAGS_MODULE, TAG_SCOPE, TAG_KIND, doc.metadata.name, doc);
}

export async function deleteTag(client: ApiClient, resource: string, version: number): Promise<void> {
  await json(client, "DELETE", configItemPath(TAGS_MODULE, TAG_SCOPE, TAG_KIND, resource), undefined, { "If-Match": String(version) });
}

/** One row of referencesIn or referencesOut. */
export interface TagLink {
  path: string;
  kind: string;
}

/**
 * Tag diagnostics (TAG-003).
 */
export interface TagDiagnostics {
  path: string;
  type: string;
  value: unknown;
  quality: string;
  timestamp: string;
  sourceTimestamp: string | null;
  message: string | null;
  subscriberCount: number;
  referencesIn: TagLink[];
  referencesOut: TagLink[];
  tagGroup: {
    name: string;
    mode: string;
    rateMillis: number;
    slowRateMillis: number | null;
    drivingExpression: string | null;
    valid: boolean;
    lastExecution: string | null;
    durationMillis: number | null;
    overruns: number;
  };
  deadband: {
    mode: string;
    limit: number;
    evaluated: number;
    published: number;
  };
}

/** GET /api/v1/tags/diagnostics. Requires tags.read. */
export async function fetchDiagnostics(client: ApiClient, path: string): Promise<TagDiagnostics> {
  const body = obj(await json(client, "GET", `/api/v1/tags/diagnostics?path=${encodeURIComponent(path)}`));
  return {
    path: text(body.path, path),
    type: text(body.type),
    value: body.value,
    quality: text(body.quality),
    timestamp: text(body.timestamp),
    sourceTimestamp: typeof body.sourceTimestamp === "string" ? body.sourceTimestamp : null,
    message: typeof body.message === "string" ? body.message : null,
    subscriberCount: typeof body.subscriberCount === "number" ? body.subscriberCount : 0,
    referencesIn: links(body.referencesIn),
    referencesOut: links(body.referencesOut),
    tagGroup: parseGroup(obj(body.tagGroup)),
    deadband: parseDeadband(obj(body.deadband)),
  };
}

function links(value: unknown): TagLink[] {
  if (!Array.isArray(value)) return [];
  return value.map((item) => {
    const row = obj(item);
    return { path: text(row.path), kind: text(row.kind) };
  });
}

function parseGroup(row: Record<string, unknown>): TagDiagnostics["tagGroup"] {
  return {
    name: text(row.name),
    mode: text(row.mode),
    rateMillis: typeof row.rateMillis === "number" ? row.rateMillis : 0,
    slowRateMillis: typeof row.slowRateMillis === "number" ? row.slowRateMillis : null,
    drivingExpression: typeof row.drivingExpression === "string" ? row.drivingExpression : null,
    valid: row.valid === true,
    lastExecution: typeof row.lastExecution === "string" ? row.lastExecution : null,
    durationMillis: typeof row.durationMillis === "number" ? row.durationMillis : null,
    overruns: typeof row.overruns === "number" ? row.overruns : 0,
  };
}

function parseDeadband(row: Record<string, unknown>): TagDiagnostics["deadband"] {
  return {
    mode: text(row.mode),
    limit: typeof row.limit === "number" ? row.limit : 0,
    evaluated: typeof row.evaluated === "number" ? row.evaluated : 0,
    published: typeof row.published === "number" ? row.published : 0,
  };
}
