import { useEffect, useState, type FormEvent } from "react";
import type { GatewayClient, ProjectInfo } from "./gatewayClient";

/**
 * Project properties (DES-004, HMP-083, SEC-017): default tag provider, default database, session roles and levels,
 * and the theme and locale a new session starts with. Permissions (SEC-020): the required Designer roles to view,
 * save, delete and protect resources in this project, each a comma-separated list; empty lets every Designer user.
 */
const DESIGNER_ROLE_FIELDS = [
  ["designerViewRoles", "View"],
  ["designerSaveRoles", "Save"],
  ["designerDeleteRoles", "Delete"],
  ["designerProtectRoles", "Protect resources"],
] as const;

function roleList(value: FormDataEntryValue | null): string[] {
  return [...new Set(String(value ?? "").split(",").map((role) => role.trim()).filter(Boolean))];
}
export function ProjectSettingsPanel({ client, project }: { client: GatewayClient; project: string }) {
  const [info, setInfo] = useState<ProjectInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void client.projectInfo(project).then((next) => {
      if (!cancelled) setInfo(next);
    }).catch((failure: unknown) => {
      if (!cancelled) setError(failure instanceof Error ? failure.message : "Could not read the project");
    });
    return () => {
      cancelled = true;
    };
  }, [client, project]);

  if (!info) return <p className="oip-inspector-empty">{error ?? "Reading project settings…"}</p>;

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const roles = String(form.get("roles") ?? "").split(",").map((role) => role.trim()).filter(Boolean);
    const levels = [...new Set(String(form.get("levels") ?? "").split(",").map((level) => level.trim()).filter(Boolean))];
    setError(null);
    setSaved(null);
    try {
      const next = await client.patchProject(project, {
        defaultTagProvider: String(form.get("provider") ?? "default").trim() || "default",
        defaultDatabase: String(form.get("database") ?? "").trim(),
        sessionTheme: String(form.get("theme") ?? "").trim(),
        sessionLocale: String(form.get("locale") ?? "").trim(),
        touchscreenMode: form.get("touchscreen") === "on",
        sessionRoles: roles,
        sessionLevels: levels,
        sessionRequireAll: form.get("requireAll") === "on",
        ...Object.fromEntries(DESIGNER_ROLE_FIELDS.map(([field]) => [field, roleList(form.get(field))])),
      });
      setInfo(next);
      setSaved("Saved.");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not save the project");
    }
  }

  return (
    <form className="oip-project-settings" aria-label="Project settings" onSubmit={(event) => void save(event)}>
      <h3>Project</h3>
      <label>
        Default tag provider
        <input name="provider" aria-label="Default tag provider" defaultValue={info.defaultTagProvider ?? "default"} />
      </label>
      <label>
        Default database
        <input name="database" aria-label="Default database" defaultValue={info.defaultDatabase ?? ""} />
      </label>
      <label>
        Session roles
        <input name="roles" aria-label="Session roles" defaultValue={(info.sessionRoles ?? []).join(", ")} />
      </label>
      <label>
        Session security levels
        <input name="levels" aria-label="Session security levels" defaultValue={(info.sessionLevels ?? []).join(", ")} placeholder="Authenticated/Roles/operator" />
      </label>
      <label>
        <input type="checkbox" name="requireAll" aria-label="Require every session level" defaultChecked={info.sessionRequireAll === true} />
        Require every session level
      </label>
      <label>
        Session theme
        <input name="theme" aria-label="Session theme" defaultValue={info.sessionTheme ?? ""} placeholder="daylight" />
      </label>
      <label>
        Session locale
        <input name="locale" aria-label="Session locale" defaultValue={info.sessionLocale ?? ""} placeholder="en-US" />
      </label>
      <label>
        <input type="checkbox" name="touchscreen" aria-label="Touchscreen mode" defaultChecked={info.touchscreenMode === true} />
        Touchscreen mode
      </label>
      <p>Touchscreen mode opens an on-screen keyboard for inputs in a session of this project.</p>
      <fieldset>
        <legend>Permissions: required Control Canvas roles</legend>
        {DESIGNER_ROLE_FIELDS.map(([field, label]) => (
          <label key={field}>
            {label}
            <input name={field} aria-label={`${label} roles`} defaultValue={(info[field] ?? []).join(", ")} placeholder="any Control Canvas user" />
          </label>
        ))}
      </fieldset>
      <button type="submit">Save project settings</button>
      {saved ? <p role="status">{saved}</p> : null}
      {error ? <p role="alert">{error}</p> : null}
    </form>
  );
}
