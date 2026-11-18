import { GatewayClient, type FetchLike } from "./gatewayClient";

const PROJECT_NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;

interface FakeProject {
  title: string;
  description: string;
  parent: string;
  inheritable: boolean;
  runnable: boolean;
}

/** In-memory stand-in for the gateway config API: PUT with metadata.version, 409 on a stale version. */
export function fakeGateway() {
  /** PUT URLs that answer 500, to test failures other than a conflict. */
  const failPut = new Set<string>();
  const store = new Map<string, Record<string, unknown> & { metadata: { version: number } }>();
  const projects = new Map<string, FakeProject>();
  const calls: Array<{ method: string; url: string; headers: Record<string, string>; body?: string }> = [];
  const fetcher: FetchLike = async (requested, init) => {
    const method = init?.method ?? "GET";
    // Inherited reads (`?inherited=true`) see the same resources: this fake stores one project at a time.
    const url = requested.split("?")[0]!;
    const rawBody = typeof init?.body === "string" ? init.body : undefined;
    calls.push({ method, url: requested, headers: (init?.headers ?? {}) as Record<string, string>, body: rawBody });
    const json = (status: number, body: unknown) =>
      new Response(JSON.stringify(body), { status, headers: { "content-type": status >= 400 ? "application/problem+json" : "application/json" } });
    if (url === "/api/v1/auth/session") return json(200, { username: "admin", csrfToken: "csrf-1", permissions: ["gateway.write", "designer.login"] });
    const projectAnswer = projectRoute(method, url, rawBody, store, projects, json);
    if (projectAnswer) return projectAnswer;
    const parts = url.split("/");
    if (method === "GET" && parts.length === 8) {
      return json(200, { items: [...store.entries()].filter(([key]) => key.startsWith(url + "/")).map(([, value]) => value) });
    }
    if (method === "GET") {
      const found = store.get(url);
      return found ? json(200, found) : json(404, { title: "Not Found" });
    }
    if (method === "PUT") {
      if (failPut.has(url)) return json(500, { title: "Internal Server Error", detail: "disk full" });
      const body = JSON.parse(String(init?.body)) as Record<string, unknown> & { metadata: { version: number } };
      const existing = store.get(url);
      const current = existing?.metadata.version ?? 0;
      if (body.metadata.version !== current) return json(409, { title: "Conflict", detail: "version mismatch" });
      const stored = { ...body, metadata: { ...body.metadata, version: current + 1 } };
      store.set(url, stored);
      return json(existing ? 200 : 201, stored);
    }
    return json(405, {});
  };
  return { store, calls, failPut, projects, fetcher, client: new GatewayClient(fetcher) };
}

function projectOfResource(url: string): string | null {
  const marker = "/project/";
  const at = url.indexOf(marker);
  if (at < 0) return null;
  const name = url.slice(at + marker.length).split("/")[0] ?? "";
  return name || null;
}

function knownProjects(store: Map<string, unknown>, projects: Map<string, FakeProject>): string[] {
  const names = new Set(projects.keys());
  for (const key of store.keys()) {
    const name = projectOfResource(key);
    if (name) names.add(name);
  }
  return [...names].sort();
}

function projectExists(name: string, store: Map<string, unknown>, projects: Map<string, FakeProject>): boolean {
  if (projects.has(name)) return true;
  for (const key of store.keys()) if (projectOfResource(key) === name) return true;
  return false;
}

function projectDocument(name: string, projects: Map<string, FakeProject>): Record<string, unknown> {
  const settings = projects.get(name);
  const parent = settings?.parent ?? "";
  const runnable = settings?.runnable ?? true;
  const enabled = true;
  return {
    name,
    title: settings?.title ?? "",
    description: settings?.description ?? "",
    parent: parent || null,
    enabled,
    inheritable: settings?.inheritable ?? false,
    runnable,
    launchable: enabled && runnable,
    inheritanceChain: parent ? [name, parent] : [name],
  };
}

function projectRoute(
  method: string,
  url: string,
  rawBody: string | undefined,
  store: Map<string, Record<string, unknown> & { metadata: { version: number } }>,
  projects: Map<string, FakeProject>,
  json: (status: number, body: unknown) => Response,
): Response | null {
  if (url === "/api/v1/projects" && method === "GET") {
    const names = knownProjects(store, projects);
    return json(200, { projects: names, items: names.map((name) => projectDocument(name, projects)) });
  }
  if (url === "/api/v1/projects" && method === "POST") {
    const body = JSON.parse(rawBody || "{}") as { name?: string; title?: string; description?: string; parent?: string; inheritable?: boolean; runnable?: boolean };
    const name = body.name ?? "";
    if (!PROJECT_NAME.test(name)) return json(422, { detail: `'${name}' is not a project name` });
    if (projectExists(name, store, projects)) return json(409, { detail: `project '${name}' already exists` });
    if (body.parent) {
      if (!projectExists(body.parent, store, projects)) return json(422, { detail: `parent project '${body.parent}' does not exist` });
      if (!projects.get(body.parent)?.inheritable) return json(422, { detail: `parent project '${body.parent}' is not inheritable` });
    }
    projects.set(name, {
      title: body.title ?? "",
      description: body.description ?? "",
      parent: body.parent ?? "",
      inheritable: body.inheritable === true,
      runnable: body.runnable !== false,
    });
    return json(201, projectDocument(name, projects));
  }
  const copy = url.match(/^\/api\/v1\/projects\/([^/]+)\/copy$/);
  if (copy && method === "POST") {
    const from = decodeURIComponent(copy[1]!);
    const body = JSON.parse(rawBody || "{}") as { name?: string; title?: string };
    const to = body.name ?? "";
    if (!PROJECT_NAME.test(to)) return json(422, { detail: `'${to}' is not a project name` });
    if (from === to) return json(422, { detail: "the new project name must differ" });
    if (!projectExists(from, store, projects)) return json(404, { title: "Not Found", detail: `project '${from}' not found` });
    if (projectExists(to, store, projects)) return json(409, { detail: `project '${to}' already exists` });
    for (const [key, value] of store) {
      if (projectOfResource(key) !== from) continue;
      const next = key.replace(`/project/${from}/`, `/project/${to}/`);
      const metadata = { ...value.metadata, version: 1 };
      store.set(next, { ...structuredClone(value), metadata });
    }
    const source = projects.get(from);
    projects.set(to, {
      title: body.title ?? source?.title ?? "",
      description: source?.description ?? "",
      parent: source?.parent ?? "",
      inheritable: source?.inheritable ?? false,
      runnable: source?.runnable ?? true,
    });
    return json(201, projectDocument(to, projects));
  }
  const one = url.match(/^\/api\/v1\/projects\/([^/]+)$/);
  if (one && method === "GET") {
    const name = decodeURIComponent(one[1]!);
    if (!projectExists(name, store, projects)) return json(404, { title: "Not Found" });
    return json(200, projectDocument(name, projects));
  }
  return null;
}

