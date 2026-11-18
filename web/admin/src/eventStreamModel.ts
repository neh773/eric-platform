/**
 * Event stream and Kafka connection forms.
 * The form writes the stages a person sets on the screen. Settings the form does not
 * list (retry, partition, client properties) ride along in `extra` so a JSON edit survives a later form save.
 * Doc source: FEATURES MES-002, MES-003, MES-005, MES-006, MES-007, MES-008, MES-009.
 */

export type SourceType = "tag" | "http" | "kafka" | "listener";
export type HandlerType = "logger" | "tag" | "database" | "http" | "gateway" | "script" | "kafka";

export interface HandlerDraft {
  type: HandlerType;
  name: string;
  enabled: boolean;
  message: string;
  level: string;
  path: string;
  value: string;
  query: string;
  parameters: string;
  url: string;
  method: string;
  headers: string;
  body: string;
  source: string;
  connection: string;
  topic: string;
  key: string;
  extra: Record<string, unknown>;
}

export interface StreamDraft {
  enabled: boolean;
  encoder: string;
  sourceType: SourceType;
  paths: string;
  onValue: boolean;
  onQuality: boolean;
  onTimestamp: boolean;
  qualifiedValue: boolean;
  skipInitial: boolean;
  requireAuthentication: boolean;
  requireHttps: boolean;
  roles: string;
  connection: string;
  topics: string;
  groupId: string;
  offsetReset: string;
  sourceExtra: Record<string, unknown>;
  filterExpression: string;
  filterScript: string;
  transformExpression: string;
  transformScript: string;
  debounceMillis: string;
  maxWaitMillis: string;
  maxQueueSize: string;
  overflow: string;
  handlers: HandlerDraft[];
  errorScript: string;
  extra: Record<string, unknown>;
}

export interface KafkaConnectionDraft {
  enabled: boolean;
  bootstrapServers: string;
  securityProtocol: string;
  username: string;
  password: string;
}

export interface EventStreamView {
  name: string;
  enabled: boolean;
  source: string;
  valid: boolean;
  error: string;
  queued: number;
  eventsReceived: number;
  eventsDropped: number;
  eventsFiltered: number;
  eventsEmitted: number;
  handlerExecutions: number;
  handlerErrors: number;
  sourceConnected: boolean | null;
  sourceError: string;
}

const SOURCE_TYPES = new Set<SourceType>(["tag", "http", "kafka", "listener"]);
const HANDLER_TYPES = new Set<HandlerType>(["logger", "tag", "database", "http", "gateway", "script", "kafka"]);
const STREAM_OWNED = new Set(["enabled", "encoder", "source", "filter", "transform", "buffer", "handlers", "errorHandler"]);
const SOURCE_OWNED = new Set([
  "type", "paths", "value", "quality", "timestamp", "qualifiedValue", "skipInitial",
  "requireAuthentication", "requireHttps", "roles", "connection", "topics", "topic", "groupId", "offsetReset",
]);
const HANDLER_OWNED = new Set([
  "type", "name", "enabled", "level", "message", "path", "value", "query", "parameters",
  "url", "method", "headers", "body", "source", "connection", "topic", "key",
]);

export function emptyHandler(): HandlerDraft {
  return {
    type: "logger",
    name: "",
    enabled: true,
    message: "{event.data}",
    level: "info",
    path: "",
    value: "{event.data}",
    query: "",
    parameters: "",
    url: "",
    method: "POST",
    headers: "",
    body: "{event.data}",
    source: "",
    connection: "",
    topic: "",
    key: "",
    extra: {},
  };
}

export function emptyStream(): StreamDraft {
  return {
    enabled: true,
    encoder: "json",
    sourceType: "listener",
    paths: "",
    onValue: true,
    onQuality: true,
    onTimestamp: true,
    qualifiedValue: true,
    skipInitial: false,
    requireAuthentication: false,
    requireHttps: false,
    roles: "",
    connection: "",
    topics: "",
    groupId: "",
    offsetReset: "latest",
    sourceExtra: {},
    filterExpression: "",
    filterScript: "",
    transformExpression: "",
    transformScript: "",
    debounceMillis: "0",
    maxWaitMillis: "0",
    maxQueueSize: "0",
    overflow: "dropOldest",
    handlers: [emptyHandler()],
    errorScript: "",
    extra: {},
  };
}

export function emptyKafkaConnection(): KafkaConnectionDraft {
  return { enabled: true, bootstrapServers: "", securityProtocol: "PLAINTEXT", username: "", password: "" };
}

export function draftFromSpec(spec: Record<string, unknown>): StreamDraft {
  const source = asRecord(spec.source) ?? {};
  const filter = asRecord(spec.filter);
  const transform = asRecord(spec.transform);
  const buffer = asRecord(spec.buffer) ?? {};
  const error = asRecord(spec.errorHandler);
  const sourceType = isSourceType(source.type) ? source.type : "listener";
  const handlers = Array.isArray(spec.handlers) ? spec.handlers.map(handlerFromSpec) : [];
  return {
    enabled: spec.enabled !== false,
    encoder: typeof spec.encoder === "string" ? spec.encoder : "json",
    sourceType,
    paths: stringList(source.paths).join("\n"),
    onValue: source.value !== false,
    onQuality: source.quality !== false,
    onTimestamp: source.timestamp !== false,
    qualifiedValue: source.qualifiedValue !== false,
    skipInitial: source.skipInitial === true,
    requireAuthentication: source.requireAuthentication === true,
    requireHttps: source.requireHttps === true,
    roles: stringList(source.roles).join(", "),
    connection: text(source.connection),
    topics: stringList(source.topics).concat(text(source.topic) ? [text(source.topic)] : []).join("\n"),
    groupId: text(source.groupId),
    offsetReset: text(source.offsetReset) || "latest",
    sourceExtra: extras(source, SOURCE_OWNED),
    filterExpression: text(filter?.expression),
    filterScript: text(filter?.source),
    transformExpression: text(transform?.expression),
    transformScript: text(transform?.source),
    debounceMillis: numberText(buffer.debounceMillis),
    maxWaitMillis: numberText(buffer.maxWaitMillis),
    maxQueueSize: numberText(buffer.maxQueueSize),
    overflow: buffer.overflow === "dropNewest" ? "dropNewest" : "dropOldest",
    handlers: handlers.length > 0 ? handlers : [emptyHandler()],
    errorScript: text(error?.source),
    extra: extras(spec, STREAM_OWNED),
  };
}

export function specFromDraft(draft: StreamDraft): Record<string, unknown> {
  const source: Record<string, unknown> = { ...draft.sourceExtra, type: draft.sourceType };
  if (draft.sourceType === "tag") {
    source.paths = lines(draft.paths);
    source.value = draft.onValue;
    source.quality = draft.onQuality;
    source.timestamp = draft.onTimestamp;
    source.qualifiedValue = draft.qualifiedValue;
    source.skipInitial = draft.skipInitial;
  }
  if (draft.sourceType === "http") {
    source.requireAuthentication = draft.requireAuthentication;
    source.requireHttps = draft.requireHttps;
    const roles = commaList(draft.roles);
    if (roles.length > 0) source.roles = roles;
  }
  if (draft.sourceType === "kafka") {
    source.connection = draft.connection.trim();
    source.topics = lines(draft.topics);
    if (draft.groupId.trim()) source.groupId = draft.groupId.trim();
    source.offsetReset = draft.offsetReset || "latest";
  }
  const spec: Record<string, unknown> = {
    ...draft.extra,
    enabled: draft.enabled,
    encoder: draft.encoder || "json",
    source,
    buffer: {
      debounceMillis: numberOr(draft.debounceMillis, 0),
      maxWaitMillis: numberOr(draft.maxWaitMillis, 0),
      maxQueueSize: numberOr(draft.maxQueueSize, 0),
      overflow: draft.overflow === "dropNewest" ? "dropNewest" : "dropOldest",
    },
    handlers: draft.handlers.map(handlerSpec),
  };
  const filter = stage(draft.filterExpression, draft.filterScript);
  if (filter) spec.filter = filter;
  const transform = stage(draft.transformExpression, draft.transformScript);
  if (transform) spec.transform = transform;
  if (draft.errorScript.trim()) spec.errorHandler = { source: draft.errorScript };
  return spec;
}

export function kafkaSpec(draft: KafkaConnectionDraft): Record<string, unknown> {
  const spec: Record<string, unknown> = {
    enabled: draft.enabled,
    bootstrapServers: draft.bootstrapServers.trim(),
    securityProtocol: draft.securityProtocol || "PLAINTEXT",
  };
  if (draft.username.trim()) spec.username = draft.username.trim();
  if (draft.password.trim()) spec.password = draft.password.trim();
  return spec;
}

/** What the form must have before the gateway will accept the stream. */
export function streamProblem(name: string, draft: StreamDraft, creating: boolean): string | null {
  if (creating && !/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(name)) {
    return "Use a short lowercase name: letters, digits, and hyphens.";
  }
  if (draft.sourceType === "tag" && lines(draft.paths).length === 0) return "A tag source needs at least one tag path.";
  if (draft.sourceType === "tag" && lines(draft.paths).some((path) => path.includes("*") || path.includes("?"))) {
    return "Tag paths on an event stream cannot use wildcards.";
  }
  if (draft.sourceType === "kafka" && !draft.connection.trim()) return "A Kafka source needs a connection.";
  if (draft.sourceType === "kafka" && lines(draft.topics).length === 0) return "A Kafka source needs a topic.";
  for (const [index, handler] of draft.handlers.entries()) {
    const problem = handlerProblem(handler);
    if (problem) return `Handler ${index + 1}: ${problem}`;
  }
  return null;
}

export function kafkaProblem(name: string, draft: KafkaConnectionDraft, creating: boolean): string | null {
  if (creating && !/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(name)) {
    return "Use a short lowercase name: letters, digits, and hyphens.";
  }
  if (!draft.bootstrapServers.trim()) return "Bootstrap servers are required, for example broker:9092.";
  if (draft.password.trim() && !/^secret:\/\/[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(draft.password.trim())) {
    return "The password must be a secret reference, such as secret://kafka-password.";
  }
  if (draft.securityProtocol.startsWith("SASL") && !draft.username.trim()) {
    return "A SASL connection needs a username. Set the password as a secret reference.";
  }
  return null;
}

export function parseStreamView(value: unknown): EventStreamView {
  const body = asRecord(value) ?? {};
  const connected = body.sourceConnected;
  return {
    name: text(body.name),
    enabled: body.enabled === true,
    source: text(body.source),
    valid: body.valid === true,
    error: text(body.error),
    queued: numberOr(body.queued, 0),
    eventsReceived: numberOr(body.eventsReceived, 0),
    eventsDropped: numberOr(body.eventsDropped, 0),
    eventsFiltered: numberOr(body.eventsFiltered, 0),
    eventsEmitted: numberOr(body.eventsEmitted, 0),
    handlerExecutions: numberOr(body.handlerExecutions, 0),
    handlerErrors: numberOr(body.handlerErrors, 0),
    sourceConnected: typeof connected === "boolean" ? connected : null,
    sourceError: text(body.sourceError),
  };
}

function handlerFromSpec(value: unknown): HandlerDraft {
  const spec = asRecord(value) ?? {};
  const base = emptyHandler();
  return {
    ...base,
    type: isHandlerType(spec.type) ? spec.type : "logger",
    name: text(spec.name),
    enabled: spec.enabled !== false,
    message: typeof spec.message === "string" ? spec.message : base.message,
    level: text(spec.level) || "info",
    path: text(spec.path),
    value: typeof spec.value === "string" ? spec.value : base.value,
    query: text(spec.query),
    parameters: mapLines(spec.parameters),
    url: text(spec.url),
    method: text(spec.method) || "POST",
    headers: mapLines(spec.headers),
    body: typeof spec.body === "string" ? spec.body : base.body,
    source: text(spec.source),
    connection: text(spec.connection),
    topic: text(spec.topic),
    key: text(spec.key),
    extra: extras(spec, HANDLER_OWNED),
  };
}

function handlerSpec(handler: HandlerDraft): Record<string, unknown> {
  const spec: Record<string, unknown> = { ...handler.extra, type: handler.type, enabled: handler.enabled };
  if (handler.name.trim()) spec.name = handler.name.trim();
  if (handler.type === "logger") {
    spec.level = handler.level || "info";
    spec.message = handler.message;
  }
  if (handler.type === "tag") {
    spec.path = handler.path.trim();
    spec.value = handler.value;
  }
  if (handler.type === "database") {
    spec.query = handler.query.trim();
    const parameters = lineMap(handler.parameters);
    if (parameters) spec.parameters = parameters;
  }
  if (handler.type === "http") {
    spec.url = handler.url.trim();
    spec.method = handler.method || "POST";
    spec.body = handler.body;
    const headers = lineMap(handler.headers);
    if (headers) spec.headers = headers;
  }
  if (handler.type === "gateway") {
    spec.path = handler.path.trim();
    spec.body = handler.body;
  }
  if (handler.type === "script") spec.source = handler.source;
  if (handler.type === "kafka") {
    spec.connection = handler.connection.trim();
    spec.topic = handler.topic.trim();
    spec.value = handler.value;
    if (handler.key.trim()) spec.key = handler.key;
  }
  return spec;
}

function handlerProblem(handler: HandlerDraft): string | null {
  if (handler.type === "tag" && !handler.path.trim()) return "a tag write needs a tag path.";
  if (handler.type === "database" && !handler.query.trim()) return "a database handler needs a named query.";
  if (handler.type === "http" && !handler.url.trim()) return "an HTTP handler needs a URL.";
  if (handler.type === "gateway" && !handler.path.trim()) return "a gateway handler needs the event name.";
  if (handler.type === "script" && !handler.source.trim()) return "a script handler needs a script.";
  if (handler.type === "kafka" && !handler.connection.trim()) return "a Kafka handler needs a connection.";
  if (handler.type === "kafka" && !handler.topic.trim()) return "a Kafka handler needs a topic.";
  return null;
}

function stage(expression: string, source: string): Record<string, unknown> | undefined {
  const spec: Record<string, unknown> = {};
  if (expression.trim()) spec.expression = expression;
  if (source.trim()) spec.source = source;
  return Object.keys(spec).length > 0 ? spec : undefined;
}

function extras(spec: Record<string, unknown>, owned: Set<string>): Record<string, unknown> {
  const extra: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(spec)) {
    if (!owned.has(key)) extra[key] = value;
  }
  return extra;
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  return value as Record<string, unknown>;
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function stringList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.map((item) => String(item)).map((item) => item.trim()).filter(Boolean);
}

function lines(value: string): string[] {
  return value.split("\n").map((item) => item.trim()).filter(Boolean);
}

function commaList(value: string): string[] {
  return value.split(",").map((item) => item.trim()).filter(Boolean);
}

function numberText(value: unknown): string {
  return typeof value === "number" && Number.isFinite(value) ? String(value) : "0";
}

function numberOr(value: unknown, fallback: number): number {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim() !== "") {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return fallback;
}

function mapLines(value: unknown): string {
  const record = asRecord(value);
  if (!record) return "";
  return Object.entries(record).map(([key, item]) => `${key}=${typeof item === "string" ? item : String(item)}`).join("\n");
}

function lineMap(value: string): Record<string, string> | undefined {
  const out: Record<string, string> = {};
  for (const line of value.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const eq = trimmed.indexOf("=");
    if (eq <= 0) continue;
    out[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim();
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

function isSourceType(value: unknown): value is SourceType {
  return typeof value === "string" && SOURCE_TYPES.has(value as SourceType);
}

function isHandlerType(value: unknown): value is HandlerType {
  return typeof value === "string" && HANDLER_TYPES.has(value as HandlerType);
}
