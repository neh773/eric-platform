import { useId } from "react";
import { findNode, LEVEL_PATH_RE, securityIsEmpty, type ViewDocument, type ViewSecurity } from "@oip/hmi-runtime";

/**
 * Who may open a view, or receive a component or container, in a session (HMP-092, SEC-017): gateway roles and
 * security levels. The gateway applies it; a refused component or container is removed, with everything inside it,
 * before the session receives the view. Holding a level holds every level above it, so a requirement of
 * `Authenticated/Roles` is met by any role level.
 *
 * Doc source: FEATURES SEC-017.
 */

/** [security] with blank and repeated entries dropped, or undefined when it restricts nobody. */
export function cleanSecurity(security: ViewSecurity | undefined): ViewSecurity | undefined {
  if (!security) return undefined;
  const unique = (values: readonly string[] | undefined) => [...new Set((values ?? []).map((value) => value.trim()).filter(Boolean))];
  const next: ViewSecurity = { roles: unique(security.roles) };
  const levels = unique(security.levels);
  if (levels.length > 0) next.levels = levels;
  if (security.requireAll && levels.length > 1) next.requireAll = true;
  return securityIsEmpty(next) ? undefined : next;
}

/** The view with node [id]'s security replaced (removed when it restricts nobody). */
export function setNodeSecurity(view: ViewDocument, id: string, security: ViewSecurity | undefined): ViewDocument {
  const next = structuredClone(view);
  const node = findNode(next.spec.root, id);
  if (!node) throw new Error(`node ${id} not found`);
  const clean = cleanSecurity(security);
  if (clean) node.security = clean;
  else delete node.security;
  return next;
}

function split(text: string): string[] {
  return [...new Set(text.split(",").map((part) => part.trim()).filter(Boolean))];
}

export function SecurityEditor({
  label,
  rolesLabel,
  security,
  onChange,
}: {
  /** The region's accessible name ("View security", "Component security"). */
  label: string;
  rolesLabel: string;
  security: ViewSecurity | undefined;
  onChange: (security: ViewSecurity | undefined) => void;
}) {
  const rolesId = useId();
  const levelsId = useId();
  const roles = security?.roles ?? [];
  const levels = security?.levels ?? [];
  const badLevels = levels.filter((level) => !LEVEL_PATH_RE.test(level));
  const update = (next: Partial<ViewSecurity>) => onChange(cleanSecurity({ roles, levels, requireAll: security?.requireAll, ...next }));
  return (
    <section className="oip-view-section" aria-label={label}>
      <h3>Security</h3>
      <label htmlFor={rolesId}>{rolesLabel}</label>
      <input
        id={rolesId}
        defaultValue={roles.join(", ")}
        key={`roles-${roles.join(",")}`}
        placeholder="anyone with tag read access"
        onBlur={(event) => {
          const next = split(event.target.value);
          if (next.join(",") !== roles.join(",")) update({ roles: next });
        }}
      />
      <label htmlFor={levelsId}>Security levels</label>
      <input
        id={levelsId}
        defaultValue={levels.join(", ")}
        key={`levels-${levels.join(",")}`}
        placeholder="Authenticated/Roles/operator"
        onBlur={(event) => {
          const next = split(event.target.value);
          if (next.join(",") !== levels.join(",")) update({ levels: next });
        }}
      />
      {levels.length > 1 ? (
        <label className="oip-schema-check">
          <input type="checkbox" checked={security?.requireAll === true} onChange={(event) => update({ requireAll: event.target.checked })} />
          <span>Require every level</span>
        </label>
      ) : null}
      {badLevels.length > 0 ? <p role="alert">Not a security level path: {badLevels.join(", ")}</p> : null}
      <p className="oip-view-note">
        Comma-separated. A session user needs one of the roles and one of the levels (or every level); leave both empty for anyone.
      </p>
    </section>
  );
}
