import { useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import type { AdminPageProps } from "./pages";
import { useLoad, useStatusApi } from "./statusHooks";

/**
 * The gateway home page (GAT-023): first-run steps until an administrator marks setup finished, then launch links for
 * the designer and the HMI, and the projects a session can open. The gateway filters the page by the signed-in
 * caller's permissions (`GET /api/v1/home`): no designer link without gateway.write, no projects without gateway.read.
 * The gateway's root URL redirects here.
 *
 * Concepts from the public home page description; text and layout are our own.
 */
export function HomePage({ section, client, session, onVisit }: AdminPageProps) {
  const api = useStatusApi(client);
  const home = useLoad(() => api.home(), [api], { enabled: Boolean(client), fallback: "Could not load the home page" });
  const sessions = useLoad(() => api.hmiSessions(), [api], { enabled: Boolean(client) && allows(session, "gateway.read"), fallback: "Could not load sessions" });
  const [error, setError] = useState<string | null>(null);
  const canWrite = allows(session, "gateway.write");
  const data = home.data;

  async function setComplete(complete: boolean) {
    setError(null);
    try {
      await api.setSetupComplete(complete);
      home.reload();
    } catch (err) {
      setError(errorMessage(err, "Could not save the setup state"));
    }
  }

  const setup = data?.setup ?? null;
  const showSetup = setup !== null && !setup.complete;

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      {home.error ? <p role="alert">{home.error}</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {!data && !home.error ? <p>Loading.</p> : null}
      {data ? (
        <p className="meta" data-testid="home-greeting">
          {data.systemName}, signed in as {data.user}.
        </p>
      ) : null}

      {showSetup && setup ? (
        <section aria-label="First-run steps" data-testid="first-run">
          <h2>Finish setting up this gateway</h2>
          <ol>
            {setup.steps.map((step) => (
              <li key={step.id} data-step={step.id} data-done={step.done ? "yes" : "no"}>
                <a
                  href={step.href}
                  onClick={(event) => {
                    if (!onVisit) return;
                    event.preventDefault();
                    onVisit(step.href);
                  }}
                >
                  {step.label}
                </a>{" "}
                <span className="meta">{step.done ? "(done)" : "(to do)"}</span>
              </li>
            ))}
          </ol>
          {canWrite ? (
            <div className="inline-form">
              <button type="button" onClick={() => void setComplete(true)}>
                Finish setup
              </button>
            </div>
          ) : null}
        </section>
      ) : null}

      {data ? (
        <section aria-label="Launch" data-testid="launch">
          <h2>Launch</h2>
          {data.links.length === 0 ? <p>Your roles do not include a client you can launch from here.</p> : null}
          <ul className="launch-links">
            {data.links.map((link) => (
              <li key={link.id}>
                <a href={link.href} data-launch={link.id}>
                  {link.label}
                </a>
              </li>
            ))}
          </ul>
          <h2>Projects</h2>
          {data.projectsHidden ? <p>Your roles do not include gateway.read, so projects are hidden.</p> : null}
          {!data.projectsHidden && data.projects.length === 0 ? <p>No project is ready to launch.</p> : null}
          {data.projects.length > 0 ? (
            <table className="data-table" aria-label="Launchable projects">
              <thead>
                <tr>
                  <th>Project</th>
                  <th>Description</th>
                  <th>Open</th>
                </tr>
              </thead>
              <tbody>
                {data.projects.map((project) => (
                  <tr key={project.name} data-project={project.name}>
                    <td>{project.title}</td>
                    <td>{project.description}</td>
                    <td className="row-actions">
                      <a href={project.hmi}>Open {project.name} session</a>
                      {project.designer ? <a href={project.designer}>Edit {project.name} in Control Canvas</a> : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
          {canWrite && data.projects.length > 0 ? <BrandForm projects={data.projects.map((project) => project.name)} onSave={(name, title) => api.updateProject(name, { title }).then(() => home.reload())} /> : null}
          {setup?.complete && canWrite ? (
            <div className="inline-form">
              <button type="button" onClick={() => void setComplete(false)}>
                Show the first-run steps again
              </button>
            </div>
          ) : null}
        </section>
      ) : null}
      {data ? (
        <section aria-label="Sessions">
          <h2>Sessions</h2>
          {sessions.error ? <p role="alert">{sessions.error}</p> : null}
          {sessions.data && sessions.data.length === 0 ? <p>No HMI session is open.</p> : null}
          {sessions.data && sessions.data.length > 0 ? (
            <table className="data-table" aria-label="HMI sessions">
              <thead>
                <tr>
                  <th>User</th>
                  <th>Project</th>
                  <th>Page</th>
                  <th>Views</th>
                  <th>Connected</th>
                </tr>
              </thead>
              <tbody>
                {sessions.data.map((row) => (
                  <tr key={row.id}>
                    <td>{row.user}</td>
                    <td>{row.project}</td>
                    <td>{row.page}</td>
                    <td>{row.views.join(", ")}</td>
                    <td>{row.connected ? "yes" : "no"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
        </section>
      ) : null}
    </article>
  );
}

function BrandForm({ projects, onSave }: { projects: string[]; onSave: (name: string, title: string) => Promise<unknown> }) {
  const [name, setName] = useState(projects[0] ?? "");
  const [title, setTitle] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setMessage(null);
    await onSave(name, title);
    setMessage(`Brand name for ${name} is saved.`);
  }
  return (
    <form aria-label="Brand" onSubmit={(event) => void submit(event)}>
      <h2>Brand</h2>
      <p>The project title is the name operators see when they launch a session.</p>
      <label>
        Project
        <select value={name} onChange={(event) => setName(event.target.value)}>
          {projects.map((project) => (
            <option key={project} value={project}>
              {project}
            </option>
          ))}
        </select>
      </label>
      <label>
        Brand name
        <input value={title} onChange={(event) => setTitle(event.target.value)} />
      </label>
      <button type="submit" disabled={!name || !title.trim()}>
        Save brand name
      </button>
      {message ? <p role="status">{message}</p> : null}
    </form>
  );
}
