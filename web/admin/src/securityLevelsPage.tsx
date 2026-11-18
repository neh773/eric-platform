import { useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import { ApiError, type LevelTrialResult, type ResourceDocument } from "./api";
import type { AdminPageProps } from "./pages";
import { canWriteSecuritySettings, SECURITY_MODULE } from "./zonesPage";

export const LEVELS_KIND = "SecurityLevels";
export const LEVELS_RESOURCE = "levels";
export const BUILT_IN_LEVELS = ["Public", "Authenticated"] as const;

export const CONDITION_TYPES = ["attribute", "role", "username", "zone", "address", "secure", "time"] as const;
export const OPERATORS = ["equals", "contains", "startsWith", "endsWith", "present"] as const;
export const DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"] as const;

const SEGMENT = "[A-Za-z0-9][A-Za-z0-9 _.-]{0,62}[A-Za-z0-9_.-]|[A-Za-z0-9]";
/** The gateway's level path pattern: up to eight `/`-separated names below Public. */
export const LEVEL_PATH = new RegExp(`^(${SEGMENT})(/(${SEGMENT})){0,7}$`);

export interface LevelDef {
  path: string;
  description?: string;
}

export interface LevelConditionDef {
  type: (typeof CONDITION_TYPES)[number];
  name?: string;
  operator?: (typeof OPERATORS)[number];
  value?: string;
  days?: string[];
  from?: string;
  to?: string;
  timeZone?: string;
  negate?: boolean;
}

export interface LevelRuleDef {
  name: string;
  description?: string;
  enabled?: boolean;
  sources?: string[];
  match?: "all" | "any";
  conditions?: LevelConditionDef[];
  grant: string[];
}

/** One person placed in levels directly, whatever the rules decide (SEC-019). */
export interface UserGrantDef {
  source: string;
  username: string;
  grant: string[];
}

export interface LevelSpec {
  levels: LevelDef[];
  rules: LevelRuleDef[];
  userGrants: UserGrantDef[];
}

/** The spec as stored, with anything unreadable dropped so the editor never shows a value it cannot save back. */
export function readLevelSpec(spec: Record<string, unknown>): LevelSpec {
  const list = (value: unknown) => (Array.isArray(value) ? (value as unknown[]) : []);
  const obj = (value: unknown) => (value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : null);
  const texts = (value: unknown) => list(value).filter((v): v is string => typeof v === "string");
  const levels = list(spec.levels)
    .map(obj)
    .filter((l): l is Record<string, unknown> => !!l && typeof l.path === "string")
    .map((l) => ({ path: l.path as string, ...(typeof l.description === "string" && l.description ? { description: l.description } : {}) }));
  const rules = list(spec.rules)
    .map(obj)
    .filter((r): r is Record<string, unknown> => !!r && typeof r.name === "string")
    .map((r) => ({
      ...r,
      name: r.name as string,
      grant: texts(r.grant),
      sources: r.sources === undefined ? undefined : texts(r.sources),
      conditions: list(r.conditions)
        .map(obj)
        .filter((c): c is Record<string, unknown> => !!c && typeof c.type === "string")
        .map((c) => ({ ...c }) as unknown as LevelConditionDef),
    })) as LevelRuleDef[];
  const userGrants = list(spec.userGrants)
    .map(obj)
    .filter((g): g is Record<string, unknown> => !!g && typeof g.username === "string" && g.username.trim() !== "")
    .map((g) => ({
      source: typeof g.source === "string" && g.source.trim() !== "" ? g.source : "internal",
      username: (g.username as string).trim(),
      grant: texts(g.grant),
    }));
  return { levels, rules, userGrants };
}

/** Every defined level with the levels above it, sorted, so the tree shows parents a path implies. */
export function levelTree(levels: LevelDef[]): { path: string; depth: number; defined: boolean; description?: string }[] {
  const defined = new Map(levels.map((l) => [l.path, l]));
  const all = new Set<string>();
  for (const { path } of levels) {
    const parts = path.split("/");
    for (let i = 1; i <= parts.length; i++) all.add(parts.slice(0, i).join("/"));
  }
  for (const builtIn of BUILT_IN_LEVELS) all.delete(builtIn);
  return [...all]
    .sort((a, b) => a.localeCompare(b))
    .map((path) => ({ path, depth: path.split("/").length, defined: defined.has(path), description: defined.get(path)?.description }));
}

/** The levels a rule may grant: every defined level and every parent a defined level implies, built-ins aside. */
export function grantable(levels: LevelDef[]): string[] {
  return levelTree(levels).map((row) => row.path);
}

function newCondition(type: LevelConditionDef["type"]): LevelConditionDef {
  switch (type) {
    case "attribute":
      return { type, name: "", operator: "equals", value: "" };
    case "username":
      return { type, operator: "equals", value: "" };
    case "time":
      return { type, days: [], from: "08:00", to: "17:00", timeZone: "UTC" };
    case "secure":
      return { type };
    default:
      return { type, value: "" };
  }
}

function commaList(text: string): string[] {
  return text
    .split(",")
    .map((part) => part.trim())
    .filter((part) => part.length > 0);
}

/**
 * Security levels and the rules that grant them at login (SEC-016, SEC-017).
 *
 * Levels form a tree below Public. Every caller holds Public and every signed-in caller holds Authenticated; holding a
 * level also holds every level above it, so a view that requires a parent level opens for a holder of any level below
 * it. A level named `Authenticated/Roles/<role>` is held by every user with that role. Rules run at each login (a
 * password, an identity provider, a provider token or an API key) and grant levels from identity-provider attributes,
 * the user name and roles, and the request: source address, security zone, a secure connection, and the time.
 * Editing needs the three security administration permissions with gateway.write; trying the rules needs users.admin.
 * Doc source: FEATURES SEC-016.
 * Doc source: FEATURES SEC-017.
 */
export function SecurityLevelsPage({ section, client, session }: AdminPageProps) {
  const canWrite = canWriteSecuritySettings(session);
  const canTry = allows(session, "users.admin");
  const [doc, setDoc] = useState<ResourceDocument | null>(null);
  const [spec, setSpec] = useState<LevelSpec | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [problems, setProblems] = useState<string[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);
  const [mode, setMode] = useState<"form" | "json">("form");
  const [jsonText, setJsonText] = useState("");

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .getResource(SECURITY_MODULE, "gateway", LEVELS_KIND, LEVELS_RESOURCE)
      .then((next) => {
        if (cancelled) return;
        setDoc(next);
        setSpec(readLevelSpec(next.spec));
        setError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 404) {
          setDoc(null);
          setSpec({ levels: [], rules: [], userGrants: [] });
          setError(null);
          return;
        }
        setSpec(null);
        setError(errorMessage(err, "Could not load the security levels"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, reloadToken]);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!client || !spec || pending) return;
    setPending(true);
    setError(null);
    setProblems([]);
    setNotice(null);
    let body: Record<string, unknown>;
    if (mode === "json") {
      try {
        body = parseLevelJson(jsonText);
      } catch (err) {
        setError(err instanceof Error ? err.message : "The JSON could not be read.");
        setPending(false);
        return;
      }
    } else {
      body = spec as unknown as Record<string, unknown>;
    }
    try {
      if (doc) await client.putResource(SECURITY_MODULE, "gateway", LEVELS_KIND, LEVELS_RESOURCE, { ...doc, spec: body });
      else await client.createResource(SECURITY_MODULE, "gateway", LEVELS_KIND, LEVELS_RESOURCE, body);
      setNotice("Security levels saved. They apply from the next login.");
      setReloadToken((token) => token + 1);
    } catch (err) {
      if (err instanceof ApiError && err.status === 422) setProblems(err.errors.length > 0 ? err.errors : [err.message]);
      setError(errorMessage(err, "Could not save the security levels"));
    } finally {
      setPending(false);
    }
  }

  const update = (next: Partial<LevelSpec>) => {
    setSpec((current) => (current ? { ...current, ...next } : current));
    setNotice(null);
  };

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p className="lede">
        Levels form a tree below Public. Everyone holds Public, every signed-in user holds Authenticated, and holding a level
        also holds every level above it. Rules grant levels at each login.
      </p>
      {!client ? <p role="alert">Security levels are unavailable.</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {problems.length > 0 ? (
        <ul aria-label="Problems" className="problem-list">
          {problems.map((problem) => (
            <li key={problem}>{problem}</li>
          ))}
        </ul>
      ) : null}
      {notice ? <p role="status">{notice}</p> : null}
      {client && !spec && !error ? <p>Loading security levels.</p> : null}
      {spec ? (
        <form aria-label="Security levels and rules" className="stack-form" onSubmit={(event) => void save(event)}>
          <div className="row-actions">
            <button
              type="button"
              className="quiet"
              onClick={() => {
                if (mode === "json") {
                  try {
                    const parsed = parseLevelJson(jsonText);
                    setSpec(readLevelSpec(parsed));
                    setMode("form");
                    setError(null);
                  } catch (err) {
                    setError(err instanceof Error ? err.message : "The JSON could not be read.");
                  }
                  return;
                }
                setJsonText(JSON.stringify(spec, null, 2));
                setMode("json");
              }}
            >
              {mode === "json" ? "Edit as form" : "Edit as JSON"}
            </button>
          </div>
          {mode === "json" ? (
            <label>
              Levels JSON
              <textarea className="json-editor" aria-label="Levels JSON" value={jsonText} onChange={(event) => setJsonText(event.target.value)} />
            </label>
          ) : (
            <>
              <LevelEditor levels={spec.levels} canWrite={canWrite} onChange={(levels) => update({ levels })} />
              <RulesEditor rules={spec.rules} levels={spec.levels} canWrite={canWrite} onChange={(rules) => update({ rules })} />
              <UserGrantsEditor grants={spec.userGrants} levels={spec.levels} canWrite={canWrite} onChange={(userGrants) => update({ userGrants })} />
            </>
          )}
          {canWrite ? (
            <button type="submit" disabled={pending}>
              Save levels and rules
            </button>
          ) : (
            <p>Changing levels and rules needs gateway.write, users.admin, roles.admin and apikeys.admin.</p>
          )}
        </form>
      ) : null}
      {client && canTry ? <TryRules client={client} /> : null}
    </article>
  );
}

function LevelEditor({ levels, canWrite, onChange }: { levels: LevelDef[]; canWrite: boolean; onChange: (levels: LevelDef[]) => void }) {
  const [draft, setDraft] = useState("");
  const [description, setDescription] = useState("");
  const path = draft.trim().replace(/^\/+|\/+$/g, "");
  const builtIn = (BUILT_IN_LEVELS as readonly string[]).includes(path);
  const duplicate = levels.some((l) => l.path === path);
  const valid = path.length > 0 && LEVEL_PATH.test(path) && !builtIn && !duplicate;
  const rows = levelTree(levels);

  function add() {
    if (!valid) return;
    onChange([...levels, description.trim() ? { path, description: description.trim() } : { path }]);
    setDraft("");
    setDescription("");
  }

  return (
    <section aria-label="Level tree">
      <h2>Levels</h2>
      <ul className="level-tree" aria-label="Security level tree">
        <li data-depth={0}>
          <strong>Public</strong> <span className="muted">built in: every caller</span>
        </li>
        <li data-depth={1}>
          <strong>Authenticated</strong> <span className="muted">built in: every signed-in caller</span>
        </li>
        {rows.map((row) => (
          <li key={row.path} data-depth={row.depth} data-testid="level-row">
            <code>{row.path}</code>
            {row.description ? <span className="muted"> {row.description}</span> : null}
            {!row.defined ? <span className="muted"> (implied by a level below it)</span> : null}
            {row.path.startsWith("Authenticated/Roles/") && row.depth === 3 ? <span className="muted"> held by role {row.path.split("/")[2]}</span> : null}
            {canWrite && row.defined ? (
              <button type="button" className="quiet" onClick={() => onChange(levels.filter((l) => l.path !== row.path))}>
                Remove {row.path}
              </button>
            ) : null}
          </li>
        ))}
      </ul>
      {canWrite ? (
        <div className="inline-form">
          <label>
            New level path
            <input value={draft} placeholder="Authenticated/Roles/operator" onChange={(event) => setDraft(event.target.value)} />
          </label>
          <label>
            Description
            <input value={description} onChange={(event) => setDescription(event.target.value)} />
          </label>
          <button type="button" disabled={!valid} onClick={add}>
            Add level
          </button>
          {path && !valid ? (
            <p role="alert">
              {builtIn
                ? `${path} is built in.`
                : duplicate
                  ? `${path} is already defined.`
                  : "Use up to eight names separated by '/', each of letters, digits, spaces, '.', '_' or '-'."}
            </p>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}

function UserGrantsEditor({
  grants,
  levels,
  canWrite,
  onChange,
}: {
  grants: UserGrantDef[];
  levels: LevelDef[];
  canWrite: boolean;
  onChange: (grants: UserGrantDef[]) => void;
}) {
  const options = grantable(levels);
  const [username, setUsername] = useState("");
  const [source, setSource] = useState("internal");
  const [selected, setSelected] = useState<string[]>([]);
  const name = username.trim();
  const sourceName = source.trim() || "internal";
  const duplicate = grants.some((grant) => grant.username === name && grant.source === sourceName);
  const sourceOk = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(sourceName);
  const valid = name.length > 0 && sourceOk && selected.length > 0 && !duplicate;

  function add() {
    if (!valid) return;
    onChange([...grants, { source: sourceName, username: name, grant: selected }]);
    setUsername("");
    setSelected([]);
  }

  return (
    <section aria-label="User grants">
      <h2>User grants</h2>
      <p>A grant puts one user in the chosen levels at the next login, whether or not a rule would. The source is the user source name, usually internal.</p>
      {grants.length === 0 ? <p>No user grants.</p> : null}
      <ul>
        {grants.map((grant) => (
          <li key={`${grant.source}:${grant.username}`}>
            <strong>{grant.username}</strong> <span className="muted">from {grant.source}</span> {grant.grant.join(", ")}
            {canWrite ? (
              <button type="button" className="quiet" onClick={() => onChange(grants.filter((item) => item !== grant))}>
                Remove grant for {grant.username}
              </button>
            ) : null}
          </li>
        ))}
      </ul>
      {canWrite ? (
        <div className="inline-form">
          <label>
            Grant username
            <input aria-label="Grant username" value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="off" />
          </label>
          <label>
            Grant source
            <input aria-label="Grant source" value={source} onChange={(event) => setSource(event.target.value)} />
          </label>
          <fieldset>
            <legend>Levels for this user</legend>
            {options.length === 0 ? <p>Add a level before granting one.</p> : null}
            {options.map((path) => (
              <label key={path}>
                <input
                  type="checkbox"
                  checked={selected.includes(path)}
                  onChange={(event) => setSelected((current) => (event.target.checked ? [...current, path] : current.filter((item) => item !== path)))}
                />
                {path}
              </label>
            ))}
          </fieldset>
          <button type="button" disabled={!valid} onClick={add}>Add user grant</button>
          {name && !valid ? (
            <p role="alert">{duplicate ? `${name} already has a grant from ${sourceName}.` : !sourceOk ? "The source name must be lowercase letters, digits, and hyphens." : "Choose at least one level."}</p>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}

function parseLevelJson(text: string): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(text) as unknown;
  } catch {
    throw new Error("The JSON could not be read.");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("The levels JSON must be an object.");
  return value as Record<string, unknown>;
}

function RulesEditor({
  rules,
  levels,
  canWrite,
  onChange,
}: {
  rules: LevelRuleDef[];
  levels: LevelDef[];
  canWrite: boolean;
  onChange: (rules: LevelRuleDef[]) => void;
}) {
  const options = grantable(levels);
  const set = (index: number, next: LevelRuleDef) => onChange(rules.map((rule, i) => (i === index ? next : rule)));

  function addRule() {
    const taken = new Set(rules.map((r) => r.name));
    let n = rules.length + 1;
    while (taken.has(`rule-${n}`)) n++;
    onChange([...rules, { name: `rule-${n}`, enabled: true, sources: ["*"], match: "all", conditions: [], grant: [] }]);
  }

  return (
    <section aria-label="Level rules">
      <h2>Rules</h2>
      <p>
        A rule applies to logins from its sources: <code>*</code> for every login, <code>internal</code> for a password login,{" "}
        <code>api-key</code> for an API key, or an identity provider's name. When its conditions hold (all of them, or any one), the
        login holds the levels it grants. A condition that cannot be evaluated never holds.
      </p>
      {rules.length === 0 ? <p>No rules. Logins hold Public, Authenticated and the role levels the tree defines.</p> : null}
      {rules.map((rule, index) => (
        <RuleEditor
          key={index}
          rule={rule}
          options={options}
          canWrite={canWrite}
          onChange={(next) => set(index, next)}
          onRemove={() => onChange(rules.filter((_, i) => i !== index))}
        />
      ))}
      {canWrite ? (
        <button type="button" onClick={addRule}>
          Add rule
        </button>
      ) : null}
    </section>
  );
}

function RuleEditor({
  rule,
  options,
  canWrite,
  onChange,
  onRemove,
}: {
  rule: LevelRuleDef;
  options: string[];
  canWrite: boolean;
  onChange: (rule: LevelRuleDef) => void;
  onRemove: () => void;
}) {
  const [sources, setSources] = useState((rule.sources ?? ["*"]).join(", "));
  const conditions = rule.conditions ?? [];
  const setCondition = (index: number, next: LevelConditionDef) =>
    onChange({ ...rule, conditions: conditions.map((c, i) => (i === index ? next : c)) });
  const missing = rule.grant.filter((g) => !options.includes(g));

  return (
    <fieldset className="rule-card" aria-label={`Rule ${rule.name}`} disabled={!canWrite}>
      <legend>Rule {rule.name}</legend>
      <div className="inline-form">
        <label>
          Rule name
          <input value={rule.name} onChange={(event) => onChange({ ...rule, name: event.target.value.trim() })} />
        </label>
        <label>
          <input type="checkbox" checked={rule.enabled !== false} onChange={(event) => onChange({ ...rule, enabled: event.target.checked })} />
          Enabled
        </label>
        <label>
          Sources
          <input
            value={sources}
            onChange={(event) => {
              setSources(event.target.value);
              const list = commaList(event.target.value);
              onChange({ ...rule, sources: list.length > 0 ? list : ["*"] });
            }}
          />
        </label>
        <label>
          Match
          <select value={rule.match ?? "all"} onChange={(event) => onChange({ ...rule, match: event.target.value === "any" ? "any" : "all" })}>
            <option value="all">All conditions</option>
            <option value="any">Any condition</option>
          </select>
        </label>
      </div>
      <fieldset aria-label={`Levels rule ${rule.name} grants`}>
        <legend>Grants</legend>
        {options.length === 0 ? <p>Define a level first.</p> : null}
        {options.map((level) => (
          <label key={level}>
            <input
              type="checkbox"
              checked={rule.grant.includes(level)}
              onChange={(event) =>
                onChange({ ...rule, grant: event.target.checked ? [...rule.grant, level] : rule.grant.filter((g) => g !== level) })
              }
            />
            {level}
          </label>
        ))}
        {missing.map((level) => (
          <p key={level} role="alert">
            Grants {level}, which the tree no longer defines; it grants nothing until the level is added back.
          </p>
        ))}
      </fieldset>
      <h3>Conditions</h3>
      {conditions.length === 0 ? <p>No conditions: the rule holds for every login from its sources.</p> : null}
      {conditions.map((condition, index) => (
        <ConditionEditor
          key={index}
          index={index}
          rule={rule.name}
          condition={condition}
          onChange={(next) => setCondition(index, next)}
          onRemove={() => onChange({ ...rule, conditions: conditions.filter((_, i) => i !== index) })}
        />
      ))}
      {canWrite ? (
        <div className="inline-form">
          <button type="button" onClick={() => onChange({ ...rule, conditions: [...conditions, newCondition("attribute")] })}>
            Add condition to {rule.name}
          </button>
          <button type="button" className="quiet" onClick={onRemove}>
            Remove rule {rule.name}
          </button>
        </div>
      ) : null}
    </fieldset>
  );
}

function ConditionEditor({
  index,
  rule,
  condition,
  onChange,
  onRemove,
}: {
  index: number;
  rule: string;
  condition: LevelConditionDef;
  onChange: (condition: LevelConditionDef) => void;
  onRemove: () => void;
}) {
  const n = index + 1;
  const type = condition.type;
  const usesOperator = type === "attribute" || type === "username";
  const usesValue = type !== "secure" && type !== "time" && !(usesOperator && condition.operator === "present");
  const valueLabel = type === "address" ? "Address or range" : type === "role" ? "Role" : type === "zone" ? "Zone" : "Value";
  return (
    <div className="inline-form condition-row" role="group" aria-label={`Condition ${n} of ${rule}`}>
      <label>
        Type
        <select
          value={type}
          onChange={(event) => onChange({ ...newCondition(event.target.value as LevelConditionDef["type"]), negate: condition.negate })}
        >
          {CONDITION_TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </label>
      {type === "attribute" ? (
        <label>
          Attribute
          <input value={condition.name ?? ""} placeholder="groups" onChange={(event) => onChange({ ...condition, name: event.target.value })} />
        </label>
      ) : null}
      {usesOperator ? (
        <label>
          Operator
          <select
            value={condition.operator ?? "equals"}
            onChange={(event) => onChange({ ...condition, operator: event.target.value as LevelConditionDef["operator"] })}
          >
            {OPERATORS.map((o) => (
              <option key={o} value={o}>
                {o}
              </option>
            ))}
          </select>
        </label>
      ) : null}
      {usesValue ? (
        <label>
          {valueLabel}
          <input
            value={condition.value ?? ""}
            placeholder={type === "address" ? "10.0.0.0/8" : ""}
            onChange={(event) => onChange({ ...condition, value: event.target.value })}
          />
        </label>
      ) : null}
      {type === "time" ? (
        <>
          <fieldset aria-label={`Days of condition ${n} of ${rule}`}>
            <legend>Days (none: every day)</legend>
            {DAYS.map((day) => (
              <label key={day}>
                <input
                  type="checkbox"
                  checked={(condition.days ?? []).includes(day)}
                  onChange={(event) => {
                    const days = condition.days ?? [];
                    onChange({ ...condition, days: event.target.checked ? DAYS.filter((d) => d === day || days.includes(d)) : days.filter((d) => d !== day) });
                  }}
                />
                {day}
              </label>
            ))}
          </fieldset>
          <label>
            From
            <input type="time" value={condition.from ?? "00:00"} onChange={(event) => onChange({ ...condition, from: event.target.value })} />
          </label>
          <label>
            To
            <input type="time" value={condition.to ?? "00:00"} onChange={(event) => onChange({ ...condition, to: event.target.value })} />
          </label>
          <label>
            Time zone
            <input value={condition.timeZone ?? "UTC"} onChange={(event) => onChange({ ...condition, timeZone: event.target.value })} />
          </label>
        </>
      ) : null}
      <label>
        <input type="checkbox" checked={condition.negate === true} onChange={(event) => onChange({ ...condition, negate: event.target.checked })} />
        Negate
      </label>
      <button type="button" className="quiet" onClick={onRemove}>
        Remove condition {n} of {rule}
      </button>
    </div>
  );
}

function TryRules({ client }: { client: NonNullable<AdminPageProps["client"]> }) {
  const [source, setSource] = useState("internal");
  const [username, setUsername] = useState("");
  const [roles, setRoles] = useState("");
  const [attributes, setAttributes] = useState("");
  const [address, setAddress] = useState("");
  const [at, setAt] = useState("");
  const [secure, setSecure] = useState(false);
  const [result, setResult] = useState<LevelTrialResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function run(event: FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      const attrs: Record<string, string[]> = {};
      for (const line of attributes.split("\n")) {
        const eq = line.indexOf("=");
        if (eq <= 0) continue;
        const key = line.slice(0, eq).trim();
        if (!key) continue;
        (attrs[key] ??= []).push(line.slice(eq + 1).trim());
      }
      const instant = at.trim() ? new Date(at).toISOString() : undefined;
      setResult(
        await client.evaluateSecurityLevels({
          source: source.trim() || "internal",
          username: username.trim(),
          roles: commaList(roles),
          attributes: attrs,
          ...(address.trim() ? { address: address.trim() } : {}),
          ...(instant ? { at: instant } : {}),
          secure,
        }),
      );
    } catch (err) {
      setResult(null);
      setError(errorMessage(err, "Could not try the rules"));
    } finally {
      setPending(false);
    }
  }

  return (
    <form aria-label="Try the rules" className="stack-form" onSubmit={(event) => void run(event)}>
      <h2>Try the rules</h2>
      <p>Runs the saved rules over the facts below without signing anyone in.</p>
      <div className="inline-form">
        <label>
          Login source
          <input value={source} onChange={(event) => setSource(event.target.value)} />
        </label>
        <label>
          User name
          <input value={username} onChange={(event) => setUsername(event.target.value)} />
        </label>
        <label>
          Roles (comma separated)
          <input value={roles} onChange={(event) => setRoles(event.target.value)} />
        </label>
        <label>
          Source address
          <input value={address} placeholder="10.1.2.3" onChange={(event) => setAddress(event.target.value)} />
        </label>
        <label>
          Time
          <input type="datetime-local" value={at} onChange={(event) => setAt(event.target.value)} />
        </label>
        <label>
          <input type="checkbox" checked={secure} onChange={(event) => setSecure(event.target.checked)} />
          Secure connection
        </label>
      </div>
      <label>
        Attributes (one name=value per line)
        <textarea rows={3} value={attributes} placeholder="groups=plant-ops" onChange={(event) => setAttributes(event.target.value)} />
      </label>
      <button type="submit" disabled={pending}>
        Try rules
      </button>
      {error ? <p role="alert">{error}</p> : null}
      {result ? (
        <dl aria-label="Trial result" className="facts">
          <dt>Levels</dt>
          <dd data-testid="trial-levels">{result.levels.join(", ")}</dd>
          <dt>Rules that matched</dt>
          <dd data-testid="trial-rules">{result.rules.length > 0 ? result.rules.join(", ") : "None"}</dd>
          <dt>Zone</dt>
          <dd>{result.zone || "Default"}</dd>
        </dl>
      ) : null}
    </form>
  );
}
