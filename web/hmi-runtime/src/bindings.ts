import { tagPathsInExpression, evaluateExpression, type ExpressionScope } from "./expression";
import { collectNodeMap, walkNodes } from "./model";
import { applyTransforms, transformSteps, type TransformContext, type TransformStep } from "./transforms";
import type { TagUpdate } from "./tags";
import { translateTerm, type TranslationCatalog } from "./translate";
import type {
  Binding,
  MessageAction,
  NavigationAction,
  PopupAction,
  PropertySource,
  Quality,
  QualityCode,
  HistoryBinding,
  QueryBinding,
  QueryReturnFormat,
  TagBinding,
  ViewDocument,
  ViewNode,
} from "./types";

const PLACEHOLDER = /\{([A-Za-z0-9_]+)\}/g;

export interface ResolveContext {
  params: Record<string, unknown>;
  custom: Record<string, unknown>;
  nodes: Map<string, ViewNode>;
  liveProps: Map<string, Record<string, unknown>>;
}

export interface SessionFields {
  id: string;
  theme: string;
  locale: string;
  timeZoneId: string;
  auth: { authenticated: boolean; userName: string | null; roles: string[] };
  device: { type: string; id?: string; userAgent?: string; screenWidth?: number; screenHeight?: number; timeZone?: string };
  gateway: { connected: boolean };
  custom: Record<string, unknown>;
}

export interface PageFields {
  pageId: string;
  path: string;
  primaryView: string;
  title: string;
  urlParams: Record<string, string>;
}

export interface QueryCacheEntry {
  ok: boolean;
  value: unknown;
}

export interface BindingExtras {
  queries?: ReadonlyMap<string, QueryCacheEntry>;
  histories?: ReadonlyMap<string, QueryCacheEntry>;
  session?: SessionFields;
  page?: PageFields;
  scriptHost?: ScriptHost;
  /** Path of the view being evaluated; a script host uses it to find the view's own hook sources. */
  viewPath?: string;
  /** Changes when the script host has new answers; only here so memoized evaluation runs again. */
  scriptVersion?: number;
  selfId?: string;
  locale?: string;
  /** Terms by locale for the `translate` expression function (HMP-125). */
  translations?: TranslationCatalog;
  hasQuerySource?: boolean;
}

/**
 * Runs a view's script hooks. A host that answers asynchronously (the gateway, `GatewayScriptHost`) returns a
 * `ScriptPending` from [transform] until the answer arrives, then calls the [subscribe] listeners so the session
 * evaluates its bindings again; `ScriptUnavailable` when it cannot reach the gateway.
 */
export interface ScriptHost {
  transform(hook: string, value: unknown, quality: QualityCode, timestamp: number, nodeId: string, prop: string, viewPath?: string): unknown;
  action(
    hook: string,
    event: string,
    nodeId: string,
    viewPath: string,
    params: Record<string, unknown>,
    custom: Record<string, unknown>,
    /** DOM event data for `event.payload` ([eventPayload]); absent for view startup and shutdown. */
    payload?: unknown,
    /** Saved view nodes so the script can walk parents and children. */
    tree?: Array<{ id: string; name: string; type: string; parent: string | null; props: Record<string, unknown> }>,
  ): void | Promise<ScriptActionResult | void>;
  /**
   * Gives the host the session's properties for scripts (HMP-084): sent with each event call, and read by the gateway
   * script as `component.session`. The host calls [provider] when it sends, so a script sees the latest locale and zone.
   */
  setSessionProvider?(provider: () => Record<string, unknown>): void;
  /** Called when an asynchronous answer arrives. Returns the unsubscribe function. */
  subscribe?(listener: () => void): () => void;
}

/** What an event script asked the session to do once it finished. */
export interface ScriptActionResult {
  navigate?: NavigationAction[];
  popup?: PopupAction[];
  message?: MessageAction[];
  method?: MethodAction[];
  dock?: DockAction[];
  session?: SessionAction[];
}

/** A script wrote a session property. Only the locale, the time zone and the theme can be written (HMP-084). */
export interface SessionAction {
  kind: "session";
  prop: "locale" | "timeZoneId" | "theme";
  value: string;
}

export interface DockAction {
  kind: "dock";
  mode: "open" | "close" | "toggle";
  id: string;
}

export interface MethodAction {
  kind: "method";
  /** `call` runs custom method [name] (HMV-114) of the component [nodeId] (or the view, for its root id) with [value] as its arguments. */
  method: "setProp" | "refresh" | "call";
  nodeId: string;
  name?: string;
  value?: unknown;
  /** How deep in nested custom method calls this one is. */
  depth?: number;
}

/** Named query execution. The runtime resolves parameters and return format; it does not run SQL.
 */
export interface NamedQueryRequest {
  path: string;
  parameters: Record<string, unknown>;
  returnFormat: QueryReturnFormat;
}

export interface QueryDataset {
  columns: string[];
  rows: unknown[][];
}

export interface NamedQuerySource {
  execute(request: NamedQueryRequest): QueryDataset | Promise<QueryDataset>;
  /** Runs an HTTP binding's request on the gateway (HMP-114). A source without it leaves HTTP bindings in the error state. */
  fetchHttp?(request: HttpRequest): Promise<HttpResult>;
}

export interface HttpRequest {
  source: string;
  path: string;
  query: Record<string, string | number | boolean>;
}

export type HttpResult = { ok: true; body: unknown } | { ok: false; error: string };

/** The value at a dotted path of names and array indexes; `undefined` (and not null) when a step is missing. */
export function pickJsonPath(body: unknown, jsonPath: string | undefined): unknown {
  if (!jsonPath) return body;
  let current: unknown = body;
  for (const step of jsonPath.split(".")) {
    if (Array.isArray(current) && /^\d+$/.test(step)) current = current[Number(step)];
    else if (current !== null && typeof current === "object" && Object.hasOwn(current, step)) current = (current as Record<string, unknown>)[step];
    else return undefined;
  }
  return current;
}

export interface HttpPollRequest extends HttpRequest {
  key: string;
  jsonPath?: string;
  pollMs: number;
}

/** Every enabled HTTP binding of the view, for the renderer to fetch. */
export function httpRequests(view: ViewDocument): HttpPollRequest[] {
  const requests: HttpPollRequest[] = [];
  const visit = (binding: Binding, key: string) => {
    if (binding.kind !== "http" || binding.enabled === false) return;
    requests.push({ key, source: binding.source, path: binding.path, query: binding.query ?? {}, ...(binding.jsonPath ? { jsonPath: binding.jsonPath } : {}), pollMs: binding.pollMs ?? 0 });
  };
  for (const [name, prop] of Object.entries(view.spec.custom)) if (prop.binding) visit(prop.binding, `custom.${name}`);
  for (const [name, param] of Object.entries(view.spec.params)) if (param.binding) visit(param.binding, `param.${name}`);
  walkNodes(view.spec.root, (node) => {
    if (node.kind !== "component" || !node.bindings) return;
    for (const [prop, binding] of Object.entries(node.bindings)) visit(binding, `${node.id}.${prop}`);
  });
  return requests;
}

export function resolveContext(view: ViewDocument): ResolveContext {
  const params: Record<string, unknown> = {};
  for (const [key, param] of Object.entries(view.spec.params)) params[key] = param.value;
  const custom: Record<string, unknown> = {};
  for (const [key, prop] of Object.entries(view.spec.custom)) custom[key] = prop.value;
  return { params, custom, nodes: collectNodeMap(view.spec.root), liveProps: new Map() };
}

/** Indirect tag paths replace `{name}` from a view param, a view custom property, or another component prop.
 */
export function resolveTagPath(binding: TagBinding, ctx: ResolveContext): string {
  if (binding.mode === "direct") return binding.path;
  return binding.path.replace(PLACEHOLDER, (_match, name: string) => {
    const ref = binding.references?.[name];
    if (!ref) return "";
    if (ref.source === "param") return text(ctx.params[ref.name]);
    if (ref.source === "custom") return text(ctx.custom[ref.name]);
    const node = ctx.nodes.get(ref.nodeId);
    if (!node || node.kind !== "component") return "";
    return text(readNodeProp(node, ref.prop, ctx.liveProps));
  });
}

export function collectSubscriptions(view: ViewDocument, scope?: Partial<Pick<ResolveContext, "params" | "custom" | "liveProps">>): string[] {
  const ctx = resolveContext(view);
  if (scope?.params) ctx.params = { ...ctx.params, ...scope.params };
  if (scope?.custom) ctx.custom = { ...ctx.custom, ...scope.custom };
  if (scope?.liveProps) ctx.liveProps = scope.liveProps;
  const paths = new Set<string>();
  for (const prop of Object.values(view.spec.custom)) {
    if (prop.binding) collectBindingPaths(prop.binding, ctx, paths);
  }
  for (const param of Object.values(view.spec.params)) {
    if (param.binding) collectBindingPaths(param.binding, ctx, paths);
  }
  walkNodes(view.spec.root, (node) => {
    if (node.kind !== "component" || !node.bindings) return;
    for (const binding of Object.values(node.bindings)) collectBindingPaths(binding, ctx, paths);
  });
  return [...paths].sort();
}

export interface ResolvedProps {
  props: Record<string, unknown>;
  quality: Quality;
  code: QualityCode;
  /** Set when the worst code came from a script transform with no script host. */
  reason?: "script_not_connected";
  /** The gateway's quality name of the worst binding (`Uncertain_LastKnownValue`, `Error_ExpressionEval`), when known. */
  label?: string;
}

/** Quality level the overlay names (TAG-002): good, uncertain, bad, or error (a gateway `Error_` quality). */
export type QualityLevel = "good" | "uncertain" | "bad" | "error";

export function qualityLevel(resolved: Pick<ResolvedProps, "code" | "label">): QualityLevel {
  if (resolved.code === "good") return "good";
  if (resolved.code.startsWith("bad") && resolved.label?.startsWith("Error")) return "error";
  return resolved.code.startsWith("bad") ? "bad" : "uncertain";
}

const LEVEL_NAMES: Record<QualityLevel, string> = { good: "Good", uncertain: "Uncertain", bad: "Bad", error: "Error" };

/** `LastKnownValue` -> `last known value`. */
function words(subcode: string): string {
  return subcode
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2")
    .toLowerCase();
}

/**
 * Text of a component's quality flag (TAG-002, HMP-111): the quality level, then the subcode when there is one
 * (`Uncertain: last known value`, `Error: expression eval`). Comms loss reads `Not connected`.
 */
export function qualityLabel(resolved: Pick<ResolvedProps, "code" | "reason" | "label">): string {
  if (resolved.reason === "script_not_connected") return "Script not connected";
  if (resolved.code === "bad_not_connected") return "Not connected";
  const level = LEVEL_NAMES[qualityLevel(resolved)];
  const label = resolved.label;
  const subcode = label?.includes("_") ? label.slice(label.indexOf("_") + 1) : resolved.code === "uncertain_initial" ? "InitialValue" : "";
  return subcode ? `${level}: ${words(subcode)}` : level;
}

function rank(code: QualityCode): number {
  if (code === "bad" || code === "bad_not_connected") return 2;
  if (code === "uncertain" || code === "uncertain_initial") return 1;
  return 0;
}

export function applyBindings(
  node: Extract<ViewNode, { kind: "component" }>,
  values: ReadonlyMap<string, TagUpdate>,
  ctx: ResolveContext,
  extra: BindingExtras = {},
): ResolvedProps {
  const overlay = ctx.liveProps.get(node.id);
  const props = { ...node.props, ...(overlay ?? {}) };
  let code: QualityCode = "good";
  let reason: ResolvedProps["reason"];
  let label: string | undefined;
  if (!node.bindings) return { props, quality: "good", code };
  for (const [propName, binding] of Object.entries(node.bindings)) {
    if (binding.enabled === false) continue;
    const resolved = evaluateBinding(binding, propName, node.id, values, ctx, extra);
    // Overlay opt-out (HMP-111): the value still applies, but its quality does not mark the component.
    if (binding.overlayOptOut === true) {
      if (resolved.applied) setProp(props, propName, resolved.value);
      continue;
    }
    if (!resolved.applied) {
      if (resolved.code !== "good" && rank(resolved.code) > rank(code)) {
        code = resolved.code;
        reason = resolved.reason;
        label = resolved.label;
      }
      continue;
    }
    setProp(props, propName, resolved.value);
    if (rank(resolved.code) > rank(code)) {
      code = resolved.code;
      reason = undefined;
      label = resolved.label;
    }
  }
  const quality: Quality = code === "good" ? "good" : code.startsWith("bad") ? "bad" : "uncertain";
  const out: ResolvedProps = { props, quality, code };
  if (reason) out.reason = reason;
  if (label && code !== "good") out.label = label;
  return out;
}

/**
 * Sets a bound property: a dotted name (`elements.level.height`) sets that path inside the nested object, copying
 * each object on the way so the authored props are not changed; a plain name sets the property itself.
 */
function setProp(props: Record<string, unknown>, propName: string, value: unknown): void {
  if (propName.includes(".")) setPath(props, propName, value);
  else props[propName] = value;
}

export interface EvaluatedBinding {
  applied: boolean;
  value: unknown;
  code: QualityCode;
  timestamp: number;
  reason?: "script_not_connected";
  /** The gateway's quality name of the source value, when it came from a gateway tag. */
  label?: string;
}

export function evaluateBinding(
  binding: Binding,
  propName: string,
  nodeId: string,
  values: ReadonlyMap<string, TagUpdate>,
  ctx: ResolveContext,
  extra: BindingExtras = {},
): EvaluatedBinding {
  if (binding.enabled === false) return { applied: false, value: undefined, code: "good", timestamp: 0 };
  if (binding.kind === "tag") return finish(readTag(binding, ctx, values), binding, propName, nodeId, ctx, extra);
  if (binding.kind === "property") {
    const read = readProperty(binding.source, ctx, extra, nodeId);
    return finish({ value: read.value, code: read.missing ? "uncertain" : "good", timestamp: Date.now(), present: !read.missing }, binding, propName, nodeId, ctx, extra);
  }
  if (binding.kind === "expression") return finish(readExpression(binding.expression, ctx, values, extra, nodeId), binding, propName, nodeId, ctx, extra);
  if (binding.kind === "expression-structure") return finish(readExpressionStructure(binding.expressions, ctx, values, extra, nodeId), binding, propName, nodeId, ctx, extra);
  if (binding.kind === "history") return finish(readHistory(`${nodeId}.${propName}`, extra), binding, propName, nodeId, ctx, extra);
  return finish(readQuery(`${nodeId}.${propName}`, extra), binding, propName, nodeId, ctx, extra);
}

/** Designer preview of one binding: the value before any transform, each transform step, and the final value. */
export interface BindingPreview {
  source: EvaluatedBinding;
  steps: TransformStep[];
  result: EvaluatedBinding;
}

export function previewBinding(
  binding: Binding,
  propName: string,
  nodeId: string,
  values: ReadonlyMap<string, TagUpdate>,
  ctx: ResolveContext,
  extra: BindingExtras = {},
): BindingPreview {
  const source = evaluateBinding({ ...binding, transforms: undefined }, propName, nodeId, values, ctx, extra);
  const result = evaluateBinding(binding, propName, nodeId, values, ctx, extra);
  const steps = source.applied
    ? transformSteps(source.value, binding.transforms, transformContext(source, propName, nodeId, ctx, extra))
    : [];
  return { source, steps, result };
}

/** Shortest poll interval of the enabled expression bindings given (HMP-118); 0 when none polls. */
export function expressionPollMs(bindings: Iterable<Binding | undefined>): number {
  let shortest = 0;
  for (const binding of bindings) {
    if (!binding || (binding.kind !== "expression" && binding.kind !== "expression-structure") || binding.enabled === false || !binding.pollMs) continue;
    if (shortest === 0 || binding.pollMs < shortest) shortest = binding.pollMs;
  }
  return shortest;
}

export function bindingForProp(node: Extract<ViewNode, { kind: "component" }>, prop: string): Binding | undefined {
  const binding = node.bindings?.[prop];
  if (!binding || binding.enabled === false) return undefined;
  return binding;
}

export function formatQueryResult(data: QueryDataset, format: QueryReturnFormat): unknown {
  if (format === "dataset") return data;
  if (format === "scalar") return data.rows[0]?.[0] ?? null;
  return data.rows.map((row) => Object.fromEntries(data.columns.map((column, index) => [column, row[index]])));
}

export interface HistoryRequest {
  key: string;
  path: string;
  rangeMs: number;
  intervalMs: number;
  aggregate: string;
  pollMs: number;
}

/** `GET /api/v1/history/aggregate` for one history binding, from `now - rangeMs` through `now`. */
export function historyAggregateUrl(request: Pick<HistoryRequest, "path" | "rangeMs" | "intervalMs" | "aggregate">, now: number): string {
  const query = new URLSearchParams({
    tagPath: request.path,
    from: String(now - request.rangeMs),
    to: String(now),
    interval: String(request.intervalMs),
    aggregate: request.aggregate,
  });
  return `/api/v1/history/aggregate?${query.toString()}`;
}

/** Windows of a history answer, or a failure when the tag was refused or the body is not that answer. */
export function parseHistoryWindows(body: unknown): { ok: true; value: unknown[] } | { ok: false; value: null } {
  if (!body || typeof body !== "object" || Array.isArray(body)) return { ok: false, value: null };
  const record = body as Record<string, unknown>;
  if (typeof record.quality === "string" && !/^good/i.test(record.quality)) return { ok: false, value: null };
  if (!Array.isArray(record.windows)) return { ok: false, value: null };
  return { ok: true, value: record.windows.filter((row) => row !== null && typeof row === "object" && !Array.isArray(row)) };
}

export function historyRequests(view: ViewDocument): HistoryRequest[] {
  const requests: HistoryRequest[] = [];
  const visit = (binding: Binding, key: string) => {
    if (binding.kind !== "history" || binding.enabled === false) return;
    requests.push(historyRequest(binding, key));
  };
  for (const [name, prop] of Object.entries(view.spec.custom)) {
    if (prop.binding) visit(prop.binding, `custom.${name}`);
  }
  for (const [name, param] of Object.entries(view.spec.params)) {
    if (param.binding) visit(param.binding, `param.${name}`);
  }
  walkNodes(view.spec.root, (node) => {
    if (node.kind !== "component" || !node.bindings) return;
    for (const [prop, binding] of Object.entries(node.bindings)) visit(binding, `${node.id}.${prop}`);
  });
  return requests;
}

function historyRequest(binding: HistoryBinding, key: string): HistoryRequest {
  return {
    key,
    path: binding.path,
    rangeMs: binding.rangeMs,
    intervalMs: binding.intervalMs,
    aggregate: binding.aggregate ?? "Average",
    pollMs: binding.pollMs ?? 0,
  };
}

export function queryRequests(view: ViewDocument, ctx: ResolveContext, values: ReadonlyMap<string, TagUpdate>, extra: BindingExtras = {}): Array<NamedQueryRequest & { key: string; pollMs: number }> {
  const requests: Array<NamedQueryRequest & { key: string; pollMs: number }> = [];
  const scope = expressionScope(ctx, values, extra, "");
  const visit = (binding: Binding, key: string) => {
    if (binding.kind !== "query" || binding.enabled === false) return;
    requests.push({
      key,
      path: binding.path,
      parameters: resolveQueryParameters(binding, scope),
      returnFormat: binding.returnFormat ?? "auto",
      pollMs: binding.pollMs ?? 0,
    });
  };
  for (const [name, prop] of Object.entries(view.spec.custom)) {
    if (prop.binding) visit(prop.binding, `custom.${name}`);
  }
  for (const [name, param] of Object.entries(view.spec.params)) {
    if (param.binding) visit(param.binding, `param.${name}`);
  }
  walkNodes(view.spec.root, (node) => {
    if (node.kind !== "component" || !node.bindings) return;
    for (const [prop, binding] of Object.entries(node.bindings)) visit(binding, `${node.id}.${prop}`);
  });
  return requests;
}

export function sessionPath(session: SessionFields, path: string): { value: unknown; writable: boolean } {
  if (path === "id") return { value: session.id, writable: false };
  if (path === "theme") return { value: session.theme, writable: true };
  if (path === "locale") return { value: session.locale, writable: true };
  if (path === "timeZoneId") return { value: session.timeZoneId, writable: true };
  if (path === "auth.authenticated") return { value: session.auth.authenticated, writable: false };
  if (path === "auth.userName") return { value: session.auth.userName, writable: false };
  if (path === "auth.roles") return { value: [...session.auth.roles], writable: false };
  if (path === "device.type") return { value: session.device.type, writable: false };
  if (path === "device.id") return { value: session.device.id ?? "", writable: false };
  if (path === "device.userAgent") return { value: session.device.userAgent ?? "", writable: false };
  if (path === "device.screenWidth") return { value: session.device.screenWidth ?? 0, writable: false };
  if (path === "device.screenHeight") return { value: session.device.screenHeight ?? 0, writable: false };
  if (path === "device.timeZone") return { value: session.device.timeZone ?? "", writable: false };
  if (path === "gateway.connected") return { value: session.gateway.connected, writable: false };
  if (path.startsWith("custom.")) return { value: session.custom[path.slice("custom.".length)], writable: true };
  return { value: undefined, writable: false };
}

export function pagePath(page: PageFields, path: string): { value: unknown; writable: boolean } {
  if (path === "pageId") return { value: page.pageId, writable: false };
  if (path === "path") return { value: page.path, writable: false };
  if (path === "primaryView") return { value: page.primaryView, writable: false };
  if (path === "title") return { value: page.title, writable: true };
  if (path.startsWith("urlParams.")) return { value: page.urlParams[path.slice("urlParams.".length)], writable: false };
  return { value: undefined, writable: false };
}

function finish(
  read: { value: unknown; code: QualityCode; timestamp: number; present: boolean; label?: string },
  binding: Binding,
  propName: string,
  nodeId: string,
  ctx: ResolveContext,
  extra: BindingExtras,
): EvaluatedBinding {
  // The gateway's quality name stays with the value while a transform keeps the source's quality code.
  const labelFor = (code: QualityCode) => (read.label && code === read.code ? { label: read.label } : {});
  if (!read.present) return { applied: false, value: undefined, code: read.code, timestamp: read.timestamp, ...labelFor(read.code) };
  const transforms = "transforms" in binding ? binding.transforms : undefined;
  const transformed = applyTransforms(read.value, transforms, transformContext(read, propName, nodeId, ctx, extra));
  if (transformed.failed) {
    const failed: EvaluatedBinding = { applied: false, value: undefined, code: transformed.quality, timestamp: read.timestamp };
    if (transformed.reason) failed.reason = transformed.reason;
    return failed;
  }
  return { applied: true, value: transformed.value, code: transformed.quality, timestamp: read.timestamp, ...labelFor(transformed.quality) };
}

function transformContext(
  read: { value: unknown; code: QualityCode; timestamp: number },
  propName: string,
  nodeId: string,
  ctx: ResolveContext,
  extra: BindingExtras,
): TransformContext {
  return {
    locale: extra.locale ?? extra.session?.locale ?? "en-US",
    timeZone: extra.session?.timeZoneId ?? "UTC",
    quality: read.code,
    timestamp: read.timestamp,
    scope: expressionScope(ctx, new Map(), extra, nodeId),
    script: extra.scriptHost
      ? (hook, value, quality, timestamp) => extra.scriptHost!.transform(hook, value, quality, timestamp, nodeId, propName, extra.viewPath)
      : undefined,
  };
}

function readTag(binding: TagBinding, ctx: ResolveContext, values: ReadonlyMap<string, TagUpdate>) {
  const path = resolveTagPath(binding, ctx);
  const update = path ? values.get(path) : undefined;
  if (!update) return { value: undefined, code: "good" as QualityCode, timestamp: 0, present: false };
  if (update.code === "uncertain_initial" && binding.publishInitialUncertain) {
    return { value: undefined, code: "good" as QualityCode, timestamp: update.timestamp, present: false };
  }
  return { value: update.value, code: update.code, timestamp: update.timestamp, present: true, ...(update.label ? { label: update.label } : {}) };
}

function readProperty(source: PropertySource, ctx: ResolveContext, extra: BindingExtras, selfId: string) {
  if (source.scope === "param") return { value: ctx.params[source.name], missing: false };
  if (source.scope === "custom") return { value: ctx.custom[source.name], missing: false };
  if (source.scope === "prop") {
    const node = ctx.nodes.get(source.nodeId);
    if (!node || node.kind !== "component") return { value: undefined, missing: true };
    return { value: readNodeProp(node, source.prop, ctx.liveProps), missing: false };
  }
  if (source.scope === "session") {
    if (!extra.session) return { value: undefined, missing: true };
    return { value: sessionPath(extra.session, source.path).value, missing: false };
  }
  if (!extra.page) return { value: undefined, missing: true };
  void selfId;
  return { value: pagePath(extra.page, source.path).value, missing: false };
}

function readExpression(expression: string, ctx: ResolveContext, values: ReadonlyMap<string, TagUpdate>, extra: BindingExtras, nodeId: string) {
  try {
    const scope = expressionScope(ctx, values, extra, nodeId);
    let code: QualityCode = "good";
    let label: string | undefined;
    let timestamp = Date.now();
    for (const path of tagPathsInExpression(expression)) {
      const update = values.get(path);
      if (!update) continue;
      timestamp = update.timestamp;
      if (rank(update.code) > rank(code)) {
        code = update.code;
        label = update.label;
      }
    }
    return { value: evaluateExpression(expression, scope), code, timestamp, present: true, ...(label ? { label } : {}) };
  } catch {
    return { value: undefined, code: "bad" as QualityCode, timestamp: Date.now(), present: false };
  }
}

/** HMP-112: each named expression evaluates on its own; a failing one is `null` and the rest still answer. */
function readExpressionStructure(
  expressions: Record<string, string>,
  ctx: ResolveContext,
  values: ReadonlyMap<string, TagUpdate>,
  extra: BindingExtras,
  nodeId: string,
) {
  const result: Record<string, unknown> = {};
  let code: QualityCode = "good";
  let label: string | undefined;
  let timestamp = Date.now();
  for (const [key, expression] of Object.entries(expressions)) {
    const part = readExpression(expression, ctx, values, extra, nodeId);
    result[key] = part.present && part.value !== undefined ? part.value : null;
    if (part.present) {
      if (rank(part.code) > rank(code)) {
        code = part.code;
        label = part.label;
        timestamp = part.timestamp;
      }
    }
  }
  return { value: result, code, timestamp, present: true, ...(label ? { label } : {}) };
}

function readHistory(key: string, extra: BindingExtras) {
  const cached = extra.histories?.get(key);
  if (!cached) return { value: undefined, code: "uncertain" as QualityCode, timestamp: Date.now(), present: false };
  if (!cached.ok) return { value: undefined, code: "bad" as QualityCode, timestamp: Date.now(), present: false };
  return { value: cached.value, code: "good" as QualityCode, timestamp: Date.now(), present: true };
}

function readQuery(key: string, extra: BindingExtras) {
  const cached = extra.queries?.get(key);
  if (!cached) {
    if (extra.hasQuerySource === false) return { value: undefined, code: "bad" as QualityCode, timestamp: Date.now(), present: false };
    return { value: undefined, code: "uncertain" as QualityCode, timestamp: Date.now(), present: false };
  }
  if (!cached.ok) return { value: undefined, code: "bad" as QualityCode, timestamp: Date.now(), present: false };
  return { value: cached.value, code: "good" as QualityCode, timestamp: Date.now(), present: true };
}

function expressionScope(ctx: ResolveContext, values: ReadonlyMap<string, TagUpdate>, extra: BindingExtras, selfId: string): ExpressionScope {
  return {
    translate: (term, locale) => translateTerm(extra.translations, locale ?? extra.session?.locale ?? extra.locale ?? "en-US", term),
    resolve(ref: string) {
      if (ref === "value") return undefined;
      if (ref.startsWith("tag:")) {
        const update = values.get(ref.slice(4));
        return update ? update.value : null;
      }
      if (ref.startsWith("view.params.")) return ctx.params[ref.slice("view.params.".length)];
      if (ref.startsWith("view.custom.")) return ctx.custom[ref.slice("view.custom.".length)];
      if (ref.startsWith("session.props.") && extra.session) return sessionPath(extra.session, ref.slice("session.props.".length)).value;
      if (ref.startsWith("page.props.") && extra.page) return pagePath(extra.page, ref.slice("page.props.".length)).value;
      const prop = /^(?:this|([A-Za-z0-9_-]+))\.props\.(.+)$/.exec(ref);
      if (!prop) return null;
      const nodeId = prop[1] ?? extra.selfId ?? selfId;
      const node = ctx.nodes.get(nodeId);
      if (!node || node.kind !== "component") return null;
      return readNodeProp(node, prop[2]!, ctx.liveProps);
    },
  };
}

export function readNodeProp(node: Extract<ViewNode, { kind: "component" }>, path: string, liveProps: ReadonlyMap<string, Record<string, unknown>>): unknown {
  const overlay = liveProps.get(node.id);
  // Custom category (HMP-127): `custom.<name>` reads the node's custom properties, with any value written there since.
  if (path.startsWith("custom.")) {
    const written = overlay?.custom;
    const custom = written && typeof written === "object" ? { ...(node.custom ?? {}), ...(written as Record<string, unknown>) } : (node.custom ?? {});
    return getPath(custom, path.slice("custom.".length));
  }
  const props = overlay ? { ...node.props, ...overlay } : node.props;
  return getPath(props, path);
}

export function getPath(value: unknown, path: string): unknown {
  let current = value;
  for (const part of path.split(".")) {
    if (current === null || typeof current !== "object") return undefined;
    current = (current as Record<string, unknown>)[part];
  }
  return current;
}

export function setPath(target: Record<string, unknown>, path: string, value: unknown): void {
  const parts = path.split(".");
  let current = target;
  for (let index = 0; index < parts.length - 1; index += 1) {
    const part = parts[index]!;
    const next = current[part];
    if (next === null || typeof next !== "object" || Array.isArray(next)) {
      const created: Record<string, unknown> = {};
      current[part] = created;
      current = created;
    } else {
      const copy = { ...(next as Record<string, unknown>) };
      current[part] = copy;
      current = copy;
    }
  }
  current[parts[parts.length - 1]!] = value;
}

function resolveQueryParameters(binding: QueryBinding, scope: ExpressionScope): Record<string, unknown> {
  const parameters: Record<string, unknown> = {};
  for (const [name, parameter] of Object.entries(binding.parameters ?? {})) {
    if ("expression" in parameter) {
      try {
        parameters[name] = evaluateExpression(parameter.expression, scope);
      } catch {
        parameters[name] = null;
      }
    } else {
      parameters[name] = parameter.value;
    }
  }
  return parameters;
}

function collectBindingPaths(binding: Binding, ctx: ResolveContext, paths: Set<string>): void {
  if (binding.enabled === false) return;
  if (binding.kind === "tag") {
    const path = resolveTagPath(binding, ctx);
    if (path) paths.add(path);
  } else if (binding.kind === "expression") {
    for (const path of tagPathsInExpression(binding.expression)) paths.add(path);
  } else if (binding.kind === "expression-structure") {
    for (const expression of Object.values(binding.expressions)) {
      for (const path of tagPathsInExpression(expression)) paths.add(path);
    }
  } else if (binding.kind === "query") {
    for (const parameter of Object.values(binding.parameters ?? {})) {
      if ("expression" in parameter) for (const path of tagPathsInExpression(parameter.expression)) paths.add(path);
    }
  }
  const transforms = "transforms" in binding ? binding.transforms : undefined;
  for (const transform of transforms ?? []) {
    if (transform.kind === "expression") {
      for (const path of tagPathsInExpression(transform.expression)) paths.add(path);
    }
    if (transform.kind === "map") {
      for (const entry of transform.entries) {
        if (entry.input.type === "expression") for (const path of tagPathsInExpression(entry.input.expression)) paths.add(path);
        if (entry.output.type === "expression") for (const path of tagPathsInExpression(entry.output.expression)) paths.add(path);
      }
    }
  }
}

function text(value: unknown): string {
  return value == null ? "" : String(value);
}
