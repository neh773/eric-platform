import { ResourceList } from "./configPage";
import type { AdminPageProps } from "./pages";
import { PipelineStatusPanel } from "./pipelineStatus";

/** Config module each service page lists. Matches the modules the gateway registers those kinds under. */
export const SERVICE_MODULES: Record<string, { module: string; lede: string }> = {
  historian: { module: "historian", lede: "History providers and tag history settings. Open one to edit it." },
  alarming: { module: "alarm", lede: "Notification profiles, pipelines, rosters, schedules and contacts. Open one to edit it." },
};

/**
 * A Services page (GAT-036): the config resources of one service module, each opening the schema editor on the
 * Config page. Doc source: FEATURES GAT-036.
 */
export function ServiceConfigPage({ section, client, onVisit }: AdminPageProps) {
  const service = SERVICE_MODULES[section.id];
  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      {service ? <p className="lede">{service.lede}</p> : null}
      {section.id === "alarming" ? <PipelineStatusPanel client={client} /> : null}
      <ResourceList client={client} onVisit={onVisit} moduleName={service?.module ?? section.id} />
    </article>
  );
}
