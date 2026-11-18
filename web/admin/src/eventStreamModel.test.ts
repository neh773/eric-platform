import { describe, expect, it } from "vitest";
import { draftFromSpec, emptyStream, kafkaProblem, specFromDraft, streamProblem } from "./eventStreamModel";

describe("event stream form", () => {
  it("keeps JSON-only settings when the form changes a field", () => {
    const spec = {
      encoder: "json",
      source: { type: "kafka", connection: "plant", topics: ["ticks"], partition: 0, pollTimeoutMillis: 50 },
      handlers: [{ type: "logger", message: "a", retryCount: 3, failure: "retry" }],
    };
    const draft = draftFromSpec(spec);
    draft.handlers[0] = { ...draft.handlers[0], message: "b" };
    const next = specFromDraft(draft);
    expect(next.source).toMatchObject({ type: "kafka", connection: "plant", partition: 0, pollTimeoutMillis: 50 });
    expect(next.handlers).toEqual([
      expect.objectContaining({ type: "logger", message: "b", retryCount: 3, failure: "retry" }),
    ]);
  });

  it("requires a tag path, a kafka topic, and a secret reference", () => {
    const draft = emptyStream();
    draft.sourceType = "tag";
    expect(streamProblem("tank-level", draft, true)).toMatch(/tag path/);
    draft.sourceType = "kafka";
    draft.connection = "plant";
    expect(streamProblem("tank-level", draft, true)).toMatch(/topic/);
    expect(streamProblem("Tank", draft, true)).toMatch(/lowercase/);
    expect(kafkaProblem("plant", { enabled: true, bootstrapServers: "broker:9092", securityProtocol: "PLAINTEXT", username: "", password: "hunter2" }, true)).toMatch(/secret/);
    expect(kafkaProblem("plant", { enabled: true, bootstrapServers: "broker:9092", securityProtocol: "PLAINTEXT", username: "", password: "secret://kafka" }, true)).toBeNull();
  });
});
