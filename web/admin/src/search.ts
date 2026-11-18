import { ADMIN_SECTIONS, type AdminSection, type ConfigHit } from "./sections";

export function matchSections(query: string, sections: readonly AdminSection[] = ADMIN_SECTIONS): AdminSection[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [];
  return sections.filter((section) => {
    if (section.label.toLowerCase().includes(needle)) return true;
    if (section.id.toLowerCase().includes(needle)) return true;
    return section.keywords.some((word) => word.toLowerCase().includes(needle));
  });
}

export function matchResources(query: string, resources: readonly ConfigHit[]): ConfigHit[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [];
  return resources
    .filter((hit) =>
      hit.kind.toLowerCase().includes(needle) ||
      hit.name.toLowerCase().includes(needle) ||
      hit.module.toLowerCase().includes(needle) ||
      hit.scope.toLowerCase().includes(needle),
    )
    .slice(0, 20);
}

/**
 * Index config resources by identity only.
 * `spec` is dropped on purpose: user resources carry a password hash, and search must not keep it.
 */
export function parseConfigHits(body: unknown): ConfigHit[] {
  if (!body || typeof body !== "object") return [];
  const items = (body as { items?: unknown }).items;
  if (!Array.isArray(items)) return [];
  const hits: ConfigHit[] = [];
  for (const item of items) {
    if (!item || typeof item !== "object") continue;
    const record = item as Record<string, unknown>;
    const metadata = record.metadata;
    if (!metadata || typeof metadata !== "object") continue;
    const meta = metadata as Record<string, unknown>;
    const kind = typeof record.kind === "string" ? record.kind : "";
    const name = typeof meta.name === "string" ? meta.name : "";
    const moduleName = typeof meta.module === "string" ? meta.module : "";
    const scope = typeof record.scope === "string" ? record.scope : "";
    if (!kind || !name) continue;
    hits.push({ kind, name, module: moduleName, scope });
  }
  return hits;
}
