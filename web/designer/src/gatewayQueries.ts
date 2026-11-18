import type { NamedQuerySource, QueryDataset } from "@oip/hmi-runtime";
import type { GatewayClient, NamedQueryBody } from "./gatewayClient";

/** Runs a view's named-query bindings on the gateway as the signed-in user, against [project]'s own queries first (PRO-001). */
export function gatewayQuerySource(client: GatewayClient, project?: string): NamedQuerySource {
  return {
    execute(request) {
      return client.executeNamedQuery(request.path, request.parameters, project).then(toDataset);
    },
    async fetchHttp(request) {
      if (!project) return { ok: false, error: "no project" };
      const answer = await client.fetchHttpSource(project, request.source, request.path, request.query);
      return answer.ok ? { ok: true, body: answer.body } : { ok: false, error: answer.error ?? "request failed" };
    },
  };
}

function toDataset(body: NamedQueryBody): QueryDataset {
  if (body.type === "scalar") return { columns: ["value"], rows: [[body.value ?? null]] };
  if (body.type === "update") return { columns: ["rowsAffected"], rows: [[body.rowsAffected ?? 0]] };
  return { columns: body.columns ?? [], rows: body.rows ?? [] };
}
