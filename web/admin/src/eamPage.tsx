import { useCallback, useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import {
  TASK_KINDS,
  eamApi,
  formatMillis,
  splitNames,
  type EamAgent,
  type EamEvent,
  type EamRoleName,
  type EamRun,
  type EamStatus,
  type EamTask,
  type PendingCertificate,
} from "./eamClient";
import type { AdminPageProps } from "./pages";

/** How often the page refreshes agent status, runs, and events. */
export const EAM_POLL_MS = 2_000;

/**
 * Enterprise administration: this gateway's role, and on a controller the agents, certificates waiting for
 * approval, tasks, runs, inventory download, and agent events. An agent shows its controller connection.
 * Doc source: FEATURES EAM-001, EAM-005, EAM-006, EAM-014.
 */
export function EamPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write");
  const canRead = allows(session, "gateway.read");
  const [status, setStatus] = useState<EamStatus | null>(null);
  const [agents, setAgents] = useState<EamAgent[]>([]);
  const [pending, setPending] = useState<PendingCertificate[]>([]);
  const [tasks, setTasks] = useState<EamTask[]>([]);
  const [runs, setRuns] = useState<EamRun[]>([]);
  const [events, setEvents] = useState<EamEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const reload = useCallback(async () => {
    if (!client) return;
    try {
      const next = await eamApi.status(client);
      setStatus(next);
      if (next.role === "controller") {
        const [a, t, r, e] = await Promise.all([eamApi.agents(client), eamApi.tasks(client), eamApi.runs(client), eamApi.events(client)]);
        setAgents(a.agents);
        setPending(a.pendingCertificates);
        setTasks(t.tasks);
        setRuns(r.runs);
        setEvents(e.events);
      }
      setError(null);
    } catch (err) {
      setError(errorMessage(err, "Could not load enterprise administration status"));
    }
  }, [client]);

  useEffect(() => {
    if (!canRead) return;
    void reload();
    const timer = setInterval(() => void reload(), EAM_POLL_MS);
    return () => clearInterval(timer);
  }, [reload, canRead]);

  async function act(label: string, action: () => Promise<unknown>) {
    setError(null);
    setNotice(null);
    try {
      await action();
      setNotice(label);
      await reload();
    } catch (err) {
      setError(errorMessage(err, "The request failed"));
    }
  }

  if (!canRead) {
    return (
      <article data-section={section.id}>
        <h1>{section.label}</h1>
        <p>Enterprise administration needs gateway.read.</p>
      </article>
    );
  }

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      <p data-testid="eam-role">
        {status ? `This gateway's role: ${status.role}.` : "Loading enterprise administration status."}
      </p>
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {status && client && canWrite ? (
        <RoleForm status={status} onSave={(next) => act("Settings saved.", () => eamApi.saveSettings(client, next))} />
      ) : null}
      {status?.role === "agent" && status.agent ? (
        <section aria-label="Controller connection" data-testid="eam-agent">
          <h2>Controller</h2>
          <dl className="meta">
            <dt>State</dt>
            <dd data-testid="eam-agent-state">{status.agent.state}</dd>
            <dt>Connection</dt>
            <dd>{status.agent.connection ?? "None"}</dd>
            <dt>Controller</dt>
            <dd>{status.agent.controllerName ?? "Unknown"}</dd>
            <dt>Last check-in</dt>
            <dd>{formatMillis(status.agent.lastCheckInMillis)}</dd>
            {status.agent.lastError ? (
              <>
                <dt>Last error</dt>
                <dd>{status.agent.lastError}</dd>
              </>
            ) : null}
          </dl>
          {client && canWrite ? (
            <button type="button" onClick={() => act("Checked in.", () => eamApi.checkIn(client))}>
              Check in now
            </button>
          ) : null}
        </section>
      ) : null}
      {status?.role === "controller" && client ? (
        <>
          {status.controller ? (
            <p data-testid="eam-controller-summary">
              {status.controller.online} of {status.controller.agents} agents online. Gateway network port{" "}
              {status.controller.listenPort ?? "not listening"}.
            </p>
          ) : null}
          <section aria-label="Certificates waiting for approval">
            <h2>Certificates waiting for approval</h2>
            {pending.length === 0 ? (
              <p>No agent certificate is waiting.</p>
            ) : (
              <table aria-label="Pending certificates">
                <thead>
                  <tr>
                    <th>Subject</th>
                    <th>Fingerprint</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {pending.map((cert) => (
                    <tr key={cert.fingerprint}>
                      <td>{cert.subject}</td>
                      <td>
                        <code>{cert.fingerprint.slice(0, 16)}…</code>
                      </td>
                      <td>
                        {canWrite ? (
                          <button
                            type="button"
                            onClick={() => act("Certificate approved.", () => eamApi.approveCertificate(client, cert.fingerprint))}
                          >
                            Approve certificate
                          </button>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
          <section aria-label="Agents">
            <h2>Agents</h2>
            <p>
              <a href="/api/v1/eam/inventory?format=csv" download>
                Download inventory (CSV)
              </a>
            </p>
            {agents.length === 0 ? (
              <p>No agent has checked in.</p>
            ) : (
              <table aria-label="Agents">
                <thead>
                  <tr>
                    <th>Agent</th>
                    <th>Status</th>
                    <th>Group</th>
                    <th>Version</th>
                    <th>Last check-in</th>
                    <th>Queued</th>
                    <th>Failed</th>
                    <th>Thresholds</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {agents.map((agent) => (
                    <tr key={agent.name} data-testid={`eam-agent-${agent.name}`}>
                      <td>{agent.name}</td>
                      <td data-testid={`eam-status-${agent.name}`}>{agent.status}</td>
                      <td>{agent.group || "—"}</td>
                      <td>{agent.version ?? "—"}</td>
                      <td>{formatMillis(agent.lastCheckInMillis)}</td>
                      <td>{agent.queued}</td>
                      <td>{agent.failed}</td>
                      <td>{agent.thresholds.join(", ") || "—"}</td>
                      <td>
                        {canWrite && !agent.approved ? (
                          <button type="button" onClick={() => act(`Approved ${agent.name}.`, () => eamApi.approveAgent(client, agent.name))}>
                            Approve {agent.name}
                          </button>
                        ) : null}
                        {canWrite && agent.status === "replacement-pending" ? (
                          <button type="button" onClick={() => act(`Recovering ${agent.name}.`, () => eamApi.recoverAgent(client, agent.name))}>
                            Recover {agent.name}
                          </button>
                        ) : null}
                        {canWrite && agent.approved ? (
                          <button
                            type="button"
                            onClick={() =>
                              act(agent.maintenance ? "Maintenance ended." : "Maintenance started.", () =>
                                eamApi.updateAgent(client, agent.name, { maintenance: !agent.maintenance }),
                              )
                            }
                          >
                            {agent.maintenance ? `End maintenance for ${agent.name}` : `Start maintenance for ${agent.name}`}
                          </button>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
          <section aria-label="Tasks">
            <h2>Tasks</h2>
            {canWrite ? <TaskForm onCreate={(task) => act(`Task ${task.id} created.`, () => eamApi.createTask(client, task))} /> : null}
            {tasks.length === 0 ? (
              <p>No task is defined.</p>
            ) : (
              <ul aria-label="Task definitions">
                {tasks.map((task) => (
                  <li key={task.id}>
                    <strong>{task.id}</strong> {task.kind} on {[...task.agents, ...task.groups.map((g) => `group ${g}`)].join(", ")}
                    {task.schedule ? ` (schedule ${task.schedule})` : ""}{" "}
                    {canWrite ? (
                      <button type="button" onClick={() => act(`Task ${task.id} queued.`, () => eamApi.runTask(client, task.id))}>
                        Run {task.id}
                      </button>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
            <table aria-label="Task runs">
              <thead>
                <tr>
                  <th>Task</th>
                  <th>Agent</th>
                  <th>Kind</th>
                  <th>State</th>
                  <th>Attempts</th>
                  <th>Message</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => (
                  <tr key={run.id} data-testid={`eam-run-${run.task}-${run.agent}`}>
                    <td>{run.task}</td>
                    <td>{run.agent}</td>
                    <td>{run.kind}</td>
                    <td data-testid="eam-run-state">{run.state}</td>
                    <td>
                      {run.attempts}/{run.maxAttempts}
                    </td>
                    <td>{run.message}</td>
                    <td>
                      {canWrite && (run.state === "failed" || run.state === "cancelled") ? (
                        <button type="button" onClick={() => act("Run queued again.", () => eamApi.retryRun(client, run.id))}>
                          Retry
                        </button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
          <section aria-label="Agent events">
            <h2>Agent events</h2>
            <ul data-testid="eam-events">
              {events.map((event, index) => (
                <li key={`${event.timeMillis}-${index}`}>
                  {formatMillis(event.timeMillis)} {event.agent || "controller"}: {event.kind} {event.detail}
                </li>
              ))}
            </ul>
          </section>
        </>
      ) : null}
    </article>
  );
}

function RoleForm({ status, onSave }: { status: EamStatus; onSave: (next: Partial<EamStatus["settings"]>) => void }) {
  const [role, setRole] = useState<EamRoleName>(status.settings.role);
  const [controller, setController] = useState(status.settings.controller);
  const [backup, setBackup] = useState(status.settings.backupController);
  const [group, setGroup] = useState(status.settings.group);

  function submit(event: FormEvent) {
    event.preventDefault();
    onSave(role === "agent" ? { role, controller, backupController: backup, group } : { role });
  }

  return (
    <form aria-label="Enterprise administration role" onSubmit={submit} data-testid="eam-role-form">
      <label>
        Role{" "}
        <select value={role} onChange={(e) => setRole(e.target.value as EamRoleName)}>
          <option value="none">None</option>
          <option value="controller">Controller</option>
          <option value="agent">Agent</option>
        </select>
      </label>
      {role === "agent" ? (
        <>
          <label>
            Controller connection <input value={controller} onChange={(e) => setController(e.target.value)} />
          </label>
          <label>
            Backup controller connection <input value={backup} onChange={(e) => setBackup(e.target.value)} />
          </label>
          <label>
            Requested group <input value={group} onChange={(e) => setGroup(e.target.value)} />
          </label>
        </>
      ) : null}
      <button type="submit">Save role</button>
    </form>
  );
}

function TaskForm({ onCreate }: { onCreate: (task: { id: string; kind: string; agents: string[]; groups: string[]; schedule: string; params?: Record<string, unknown> }) => void }) {
  const [id, setId] = useState("");
  const [kind, setKind] = useState<string>("backup");
  const [agents, setAgents] = useState("");
  const [groups, setGroups] = useState("");
  const [schedule, setSchedule] = useState("");
  const [project, setProject] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    onCreate({
      id: id.trim(),
      kind,
      agents: splitNames(agents),
      groups: splitNames(groups),
      schedule: schedule.trim(),
      params: kind === "send-project" ? { project: project.trim() } : undefined,
    });
  }

  return (
    <form aria-label="New task" onSubmit={submit} data-testid="eam-task-form">
      <label>
        Task id <input value={id} onChange={(e) => setId(e.target.value)} required />
      </label>
      <label>
        Kind{" "}
        <select value={kind} onChange={(e) => setKind(e.target.value)}>
          {TASK_KINDS.map((k) => (
            <option key={k} value={k}>
              {k}
            </option>
          ))}
        </select>
      </label>
      <label>
        Agents <input value={agents} onChange={(e) => setAgents(e.target.value)} placeholder="site-a, site-b" />
      </label>
      <label>
        Groups <input value={groups} onChange={(e) => setGroups(e.target.value)} />
      </label>
      <label>
        Schedule (cron, UTC) <input value={schedule} onChange={(e) => setSchedule(e.target.value)} placeholder="0 2 * * *" />
      </label>
      {kind === "send-project" ? (
        <label>
          Project <input value={project} onChange={(e) => setProject(e.target.value)} />
        </label>
      ) : null}
      <button type="submit">Create task</button>
    </form>
  );
}
