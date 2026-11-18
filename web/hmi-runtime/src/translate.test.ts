import { describe, expect, it } from "vitest";
import { evaluateBinding, resolveContext } from "./bindings";
import { evaluateExpression } from "./expression";
import { parseView } from "./model";
import { createSession } from "./session";
import { translateTerm } from "./translate";

/** HMP-125 (doc source: FEATURES HMP-125): HMI text uses translation terms and follows the session locale. */
const catalog = {
  "en-US": { Start: "Start", Stop: "Stop" },
  es: { Start: "Iniciar", Stop: "Parar" },
  "es-MX": { Stop: "Detener" },
};

describe("translateTerm", () => {
  it("tries the exact tag then the language then gives the term back", () => {
    expect(translateTerm(catalog, "es-MX", "Stop")).toBe("Detener");
    expect(translateTerm(catalog, "es-MX", "Start")).toBe("Iniciar");
    expect(translateTerm(catalog, "ES", "Start")).toBe("Iniciar");
    expect(translateTerm(catalog, "fr-FR", "Start")).toBe("Start");
    expect(translateTerm(undefined, "es", "Start")).toBe("Start");
    expect(translateTerm(catalog, "es", "Missing")).toBe("Missing");
  });
});

describe("translate expression function", () => {
  const scope = (locale: string) => ({ resolve: () => null, translate: (term: string, tag?: string) => translateTerm(catalog, tag ?? locale, term) });

  it("translates in the session locale or an explicit one", () => {
    expect(evaluateExpression('translate("Start")', scope("es"))).toBe("Iniciar");
    expect(evaluateExpression('translate("Start", "en-US")', scope("es"))).toBe("Start");
    expect(evaluateExpression('translate("Start") + "!"', scope("es-MX"))).toBe("Iniciar!");
    expect(() => evaluateExpression("translate()", scope("es"))).toThrow();
  });

  it("switches language when the session locale changes", () => {
    const view = parseView({
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "v", path: "v" },
      spec: { width: 10, height: 10, params: {}, custom: {}, root: { id: "root", kind: "container", name: "Root", layout: "flex", props: {}, children: [] } },
    });
    const binding = { kind: "expression", expression: 'translate("Stop")' } as const;
    const read = (locale: string) =>
      evaluateBinding(binding, "text", "n", new Map(), resolveContext(view), { session: createSession({ id: "s", locale }), translations: catalog }).value;
    expect(read("en-US")).toBe("Stop");
    expect(read("es")).toBe("Parar");
    expect(read("es-MX")).toBe("Detener");
  });
});

describe("HMP-101 session time zone defaults to the gateway's", () => {
  it("uses the session zone else the gateway zone else UTC", () => {
    expect(createSession({ id: "a", gatewayTimeZone: "Asia/Tokyo" }).timeZoneId).toBe("Asia/Tokyo");
    expect(createSession({ id: "b", gatewayTimeZone: "Asia/Tokyo", timeZoneId: "America/Chicago" }).timeZoneId).toBe("America/Chicago");
    expect(createSession({ id: "c", gatewayTimeZone: "Not/AZone" }).timeZoneId).toBe("UTC");
    expect(createSession({ id: "d" }).timeZoneId).toBe("UTC");
  });
});
