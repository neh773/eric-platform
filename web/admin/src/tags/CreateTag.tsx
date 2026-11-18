import { useEffect, useRef, useState, type FormEvent } from "react";
import { errorMessage } from "../access";
import { ApiError, type ApiClient, type ResourceDocument } from "../api";
import type { JsonSchema } from "../schema";
import { browseDevice, createTag, listDevices, type DeviceInfo, type DeviceNode } from "./tagClient";
import { childPath, formatValue, parseTagPath, parseValueInput, pathProblems, resourceNameFor } from "./tagModel";

const NAME_ATTEMPTS = 5;

/** Types created here. UDT instances are created with the UDT wizard. */
const MANUAL_TYPES = ["memory", "expression", "reference", "derived", "opc", "folder"];

/** A 409 because the resource name is taken (worth another name), not because the tag path is. */
export function isNameTaken(err: unknown): boolean {
  return err instanceof ApiError && err.status === 409 && /already exists$/.test(err.message);
}

/**
 * Create a Tag resource, retrying with `-2`, `-3`, ... when the generated resource name is taken.
 * Any other refusal, such as a tag path another tag already uses, is thrown as it came.
 * The returned document's `spec.path` is the folder-relative path that was asked for.
 */
export async function createWithFreeName(client: ApiClient, provider: string, path: string, spec: Record<string, unknown>): Promise<ResourceDocument> {
  let last: unknown = null;
  for (let attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
    try {
      return await createTag(client, resourceNameFor(provider, path, attempt), spec);
    } catch (err) {
      last = err;
      if (!isNameTaken(err)) throw err;
    }
  }
  throw last instanceof Error ? last : new ApiError("No free resource name", 409);
}

function enumOf(schema: JsonSchema, key: string): string[] {
  const values = schema.properties?.[key]?.enum ?? [];
  return values.filter((v): v is string => typeof v === "string");
}

/**
 * Manual tag creation in the selected folder (TAG-004).
 */
export function CreateTagForm({
  client,
  schema,
  folder,
  onCreated,
  onCancel,
}: {
  client: ApiClient;
  schema: JsonSchema;
  folder: string;
  onCreated: (fullPath: string, resource: string) => void;
  onCancel: () => void;
}) {
  const types = enumOf(schema, "type").filter((t) => MANUAL_TYPES.includes(t));
  const dataTypes = enumOf(schema, "dataType");
  const [name, setName] = useState("");
  const [type, setType] = useState(types.includes("memory") ? "memory" : (types[0] ?? "memory"));
  const [dataType, setDataType] = useState(dataTypes.includes("Double") ? "Double" : (dataTypes[0] ?? "Double"));
  const [value, setValue] = useState("");
  const [expression, setExpression] = useState("");
  const [sourcePath, setSourcePath] = useState("");
  const [opcServer, setOpcServer] = useState("");
  const [opcItemPath, setOpcItemPath] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const parsedFolder = parseTagPath(folder);
  const problems = name ? pathProblems(name) : [];

  async function submit(event: FormEvent) {
    event.preventDefault();
    const issues = pathProblems(name);
    if (issues.length > 0) {
      setError(issues[0] ?? "Invalid name");
      return;
    }
    const rel = parsedFolder.path ? `${parsedFolder.path}/${name.trim()}` : name.trim();
    const spec: Record<string, unknown> = { provider: parsedFolder.provider, path: rel, type };
    if (type !== "folder") spec.dataType = dataType;
    if (type === "memory" && value.trim() !== "") {
      const parsed = parseValueInput(value, dataType);
      if (parsed.error) {
        setError(parsed.error);
        return;
      }
      spec.value = parsed.value;
    }
    if (type === "expression") spec.expression = expression;
    if (type === "reference" || type === "derived") spec.sourcePath = sourcePath;
    if (type === "opc") {
      spec.opcServer = opcServer;
      spec.opcItemPath = opcItemPath;
    }
    setPending(true);
    setError(null);
    try {
      const doc = await createWithFreeName(client, parsedFolder.provider, rel, spec);
      onCreated(childPath(folder, name.trim()), doc.metadata.name);
    } catch (err) {
      setError(errorMessage(err, "Could not create the tag"));
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="stack-form" data-testid="create-tag" aria-label="New tag" onSubmit={(event) => void submit(event)}>
      <h2>New tag in {folder}</h2>
      <label>
        Name
        <input value={name} placeholder="Tag or Folder/Tag" onChange={(event) => setName(event.target.value)} />
      </label>
      {problems.map((problem) => (
        <small key={problem} role="alert">
          {problem}
        </small>
      ))}
      <label>
        Tag type
        <select aria-label="Tag type" value={type} onChange={(event) => setType(event.target.value)}>
          {types.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </label>
      {type !== "folder" ? (
        <label>
          Data type
          <select aria-label="Data type" value={dataType} onChange={(event) => setDataType(event.target.value)}>
            {dataTypes.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </label>
      ) : null}
      {type === "memory" ? (
        <label>
          Initial value
          <input value={value} onChange={(event) => setValue(event.target.value)} />
        </label>
      ) : null}
      {type === "expression" ? (
        <label>
          Expression
          <input value={expression} placeholder="{[.]Other} * 2" onChange={(event) => setExpression(event.target.value)} />
        </label>
      ) : null}
      {type === "reference" || type === "derived" ? (
        <label>
          Source tag path
          <input value={sourcePath} placeholder="[default]Folder/Tag" onChange={(event) => setSourcePath(event.target.value)} />
        </label>
      ) : null}
      {type === "opc" ? (
        <>
          <label>
            Device or connection
            <input value={opcServer} onChange={(event) => setOpcServer(event.target.value)} />
          </label>
          <label>
            Item path
            <input value={opcItemPath} onChange={(event) => setOpcItemPath(event.target.value)} />
          </label>
        </>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      <div className="inline-form">
        <button type="submit" disabled={pending || !name.trim() || problems.length > 0}>
          Create tag
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}

/**
 * One tag name per picked node, unique within the batch (case-insensitive, like tag paths), so two
 * nodes with the same display name do not ask for the same tag path.
 */
export function uniqueTagNames(nodes: DeviceNode[]): string[] {
  const used = new Set<string>();
  return nodes.map((node) => {
    const base = tagNameForNode(node);
    let name = base;
    for (let n = 2; used.has(name.toLowerCase()); n++) name = `${base}_${n}`;
    used.add(name.toLowerCase());
    return name;
  });
}

/** A tag name from a device node's display name: no brackets, slashes or leading dots. */
export function tagNameForNode(node: DeviceNode): string {
  const cleaned = node.name.replace(/[[\]/\\]+/g, "_").replace(/^\.+/, "").trim();
  return cleaned || node.nodeId.replace(/[^A-Za-z0-9_]+/g, "_");
}

/**
 * Create OPC tags from a device browse (TAG-020): pick a configured Modbus device or OPC UA connection,
 * walk its nodes, tick the variables to bring in, and create one tag per node in the selected folder.
 */
export function DeviceBrowseForm({
  client,
  schema,
  folder,
  onCreated,
  onCancel,
}: {
  client: ApiClient;
  schema: JsonSchema;
  folder: string;
  onCreated: (fullPaths: string[]) => void;
  onCancel: () => void;
}) {
  const dataTypes = enumOf(schema, "dataType");
  const [devices, setDevices] = useState<DeviceInfo[] | null>(null);
  const [device, setDevice] = useState("");
  const [trail, setTrail] = useState<{ nodeId: string; name: string }[]>([]);
  const [nodes, setNodes] = useState<DeviceNode[]>([]);
  const [more, setMore] = useState(false);
  const [picked, setPicked] = useState<Map<string, DeviceNode>>(() => new Map());
  const [dataType, setDataType] = useState(dataTypes.includes("Double") ? "Double" : (dataTypes[0] ?? "Double"));
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [pending, setPending] = useState(false);
  const nodeId = trail.length > 0 ? trail[trail.length - 1]!.nodeId : "";
  /** Bumped by every fresh browse, so a "Load more" answer for an older listing is dropped. */
  const listing = useRef(0);
  const moreInFlight = useRef(false);

  useEffect(() => {
    let cancelled = false;
    listDevices(client)
      .then((next) => {
        if (cancelled) return;
        setDevices(next);
        setDevice((current) => current || next[0]?.name || "");
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, "Could not list devices"));
      });
    return () => {
      cancelled = true;
    };
  }, [client]);

  useEffect(() => {
    if (!device) return;
    let cancelled = false;
    listing.current++;
    setLoading(true);
    setError(null);
    browseDevice(client, device, nodeId)
      .then((page) => {
        if (cancelled) return;
        setNodes(page.nodes);
        setMore(page.more);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setNodes([]);
        setMore(false);
        setError(errorMessage(err, "Device browse failed"));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [client, device, nodeId]);

  async function loadMore() {
    if (moreInFlight.current) return;
    moreInFlight.current = true;
    const ticket = listing.current;
    try {
      const page = await browseDevice(client, device, nodeId, nodes.length);
      if (ticket !== listing.current) return;
      setNodes((prev) => [...prev, ...page.nodes]);
      setMore(page.more);
    } catch (err) {
      if (ticket === listing.current) setError(errorMessage(err, "Device browse failed"));
    } finally {
      moreInFlight.current = false;
    }
  }

  function toggle(node: DeviceNode) {
    setPicked((prev) => {
      const next = new Map(prev);
      if (next.has(node.nodeId)) next.delete(node.nodeId);
      else next.set(node.nodeId, node);
      return next;
    });
  }

  async function create() {
    const parsedFolder = parseTagPath(folder);
    setPending(true);
    setError(null);
    const created: string[] = [];
    try {
      const nodes = [...picked.values()];
      const names = uniqueTagNames(nodes);
      for (const [index, node] of nodes.entries()) {
        const name = names[index]!;
        const rel = parsedFolder.path ? `${parsedFolder.path}/${name}` : name;
        await createWithFreeName(client, parsedFolder.provider, rel, {
          provider: parsedFolder.provider,
          path: rel,
          type: "opc",
          dataType,
          opcServer: device,
          opcItemPath: node.nodeId,
        });
        created.push(childPath(folder, name));
      }
      onCreated(created);
    } catch (err) {
      setError(`${errorMessage(err, "Could not create the tags")}${created.length ? ` (created ${created.length} before the failure)` : ""}`);
      if (created.length) onCreated(created);
    } finally {
      setPending(false);
    }
  }

  return (
    <section className="stack-form" data-testid="device-browse" aria-label="Create from device">
      <h2>Create from a device in {folder}</h2>
      {devices && devices.length === 0 ? <p>No Modbus devices or OPC UA connections are configured.</p> : null}
      {devices && devices.length > 0 ? (
        <label>
          Device
          <select
            aria-label="Device"
            value={device}
            onChange={(event) => {
              setDevice(event.target.value);
              setTrail([]);
              setPicked(new Map());
            }}
          >
            {devices.map((d) => (
              <option key={d.name} value={d.name}>
                {d.name} ({d.driver === "opcua" ? "OPC UA" : "Modbus"}, {d.state})
              </option>
            ))}
          </select>
        </label>
      ) : null}
      {device ? (
        <nav aria-label="Device path" className="inline-form">
          <button type="button" className="quiet" onClick={() => setTrail([])}>
            {device}
          </button>
          {trail.map((step, index) => (
            <button key={step.nodeId} type="button" className="quiet" onClick={() => setTrail(trail.slice(0, index + 1))}>
              / {step.name}
            </button>
          ))}
        </nav>
      ) : null}
      {loading ? <p>Browsing the device.</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {nodes.length > 0 ? (
        <table className="data-table" aria-label="Device nodes">
          <thead>
            <tr>
              <th>Pick</th>
              <th>Name</th>
              <th>Item path</th>
              <th>Value</th>
              <th>Quality</th>
            </tr>
          </thead>
          <tbody>
            {nodes.map((node) => (
              <tr key={node.nodeId}>
                <td>
                  {node.readable ? (
                    <input type="checkbox" aria-label={`Pick ${node.name}`} checked={picked.has(node.nodeId)} onChange={() => toggle(node)} />
                  ) : null}
                </td>
                <td>
                  {node.hasChildren ? (
                    <button type="button" className="quiet" onClick={() => setTrail([...trail, { nodeId: node.nodeId, name: node.name }])}>
                      {node.name} ▸
                    </button>
                  ) : (
                    node.name
                  )}
                </td>
                <td>
                  <code>{node.nodeId}</code>
                </td>
                <td>{node.quality !== undefined ? formatValue(node.value, node.quality) : ""}</td>
                <td>{node.quality ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {more ? (
        <button type="button" className="quiet" onClick={() => void loadMore()}>
          Load more nodes
        </button>
      ) : null}
      <label>
        Data type for new tags
        <select aria-label="Data type for new tags" value={dataType} onChange={(event) => setDataType(event.target.value)}>
          {dataTypes.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </label>
      <div className="inline-form">
        <button type="button" disabled={pending || picked.size === 0} onClick={() => void create()}>
          Create {picked.size} tag{picked.size === 1 ? "" : "s"}
        </button>
        <button type="button" className="quiet" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </section>
  );
}
