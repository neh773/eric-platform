import { useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import type { GatewaySettingsState } from "./api";
import type { AdminPageProps } from "./pages";

const PROJECT_NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;

/**
 * Gateway-wide settings (GAT-035): the system name, the script message encoding, and the project an HMI client opens
 * when it is launched without one. Reading needs gateway.read; saving needs gateway.write.
 * Doc source: FEATURES GAT-035.
 */
export function GatewaySettingsPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write");
  const [current, setCurrent] = useState<GatewaySettingsState | null>(null);
  const [systemName, setSystemName] = useState("");
  const [encoding, setEncoding] = useState("json");
  const [defaultProject, setDefaultProject] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [pending, setPending] = useState(false);

  function load(next: GatewaySettingsState) {
    setCurrent(next);
    setSystemName(next.systemName);
    setEncoding(next.scriptMessageEncoding);
    setDefaultProject(next.launch.defaultProject);
  }

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .gatewaySettings()
      .then((next) => {
        if (!cancelled) load(next);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, "Could not load gateway settings"));
      });
    return () => {
      cancelled = true;
    };
  }, [client]);

  const nameProblem = systemName.includes("|") ? "The system name cannot contain '|'." : systemName.length > 63 ? "At most 63 characters." : null;
  const projectProblem = defaultProject && !PROJECT_NAME.test(defaultProject) ? "Use a project name: lowercase letters, digits and '-'." : null;

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!client || !canWrite || pending || nameProblem || projectProblem) return;
    setPending(true);
    setSaved(false);
    setError(null);
    try {
      load(await client.saveGatewaySettings({ systemName, scriptMessageEncoding: encoding, launch: { defaultProject } }));
      setSaved(true);
    } catch (err) {
      setError(errorMessage(err, "Could not save gateway settings"));
    } finally {
      setPending(false);
    }
  }

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      {!client ? <p role="alert">Gateway settings are unavailable.</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {!current && !error ? <p>Loading gateway settings.</p> : null}
      {current ? (
        <form aria-label="Gateway settings" className="stack-form" onSubmit={(event) => void save(event)}>
          <p data-testid="effective-system-name">
            This gateway is shown as <strong>{current.effectiveSystemName}</strong>.
          </p>
          <label>
            System name
            <input
              value={systemName}
              onChange={(event) => {
                setSystemName(event.target.value);
                setSaved(false);
              }}
              readOnly={!canWrite}
              placeholder="Host name when blank"
              maxLength={80}
            />
          </label>
          {nameProblem ? <p role="alert">{nameProblem}</p> : null}
          <fieldset>
            <legend>Script message encoding</legend>
            <label>
              Serialization format
              <select
                value={encoding}
                disabled={!canWrite}
                onChange={(event) => {
                  setEncoding(event.target.value);
                  setSaved(false);
                }}
              >
                {current.scriptMessageEncodings.map((item) => (
                  <option key={item} value={item}>
                    {item === "json" ? "JSON" : item}
                  </option>
                ))}
              </select>
            </label>
          </fieldset>
          <fieldset>
            <legend>Launch defaults</legend>
            <label>
              Default HMI project
              <input
                value={defaultProject}
                onChange={(event) => {
                  setDefaultProject(event.target.value.trim());
                  setSaved(false);
                }}
                readOnly={!canWrite}
                placeholder="None"
              />
            </label>
            <p className="hint">Opened when a client is launched at /hmi without a project.</p>
          </fieldset>
          {projectProblem ? <p role="alert">{projectProblem}</p> : null}
          {canWrite ? (
            <div className="schema-actions">
              <button type="submit" disabled={pending || Boolean(nameProblem) || Boolean(projectProblem)}>
                Save settings
              </button>
              {saved ? <p role="status">Saved.</p> : null}
            </div>
          ) : (
            <p className="read-only-note" data-testid="read-only-note">
              Read only. Changing gateway settings needs gateway.write.
            </p>
          )}
        </form>
      ) : null}
    </article>
  );
}
