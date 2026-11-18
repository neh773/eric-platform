import { describe, expect, it } from "vitest";
import { SessionError, createSession } from "./session";
import { themeStyle, themes } from "./theme";

describe("session and themes", () => {
  it("keeps a BCP-47 locale and rejects a tag that is not one", () => {
    const session = createSession({
      id: "s1",
      locale: "en-US",
      timeZoneId: "America/Chicago",
      theme: "nightwatch",
      auth: { authenticated: true, userName: "ada", roles: ["operator"] },
    });
    expect(session.locale).toBe("en-US");
    expect(session.timeZoneId).toBe("America/Chicago");
    expect(session.auth.userName).toBe("ada");
    expect(() => createSession({ id: "s2", locale: "english" })).toThrow(SessionError);
  });

  it("switches CSS variables when the session theme changes", () => {
    const day = themeStyle("daylight") as Record<string, string>;
    const night = themeStyle("nightwatch") as Record<string, string>;
    expect(day["--oip-color-canvas"]).toBe(themes.daylight["color-canvas"]);
    expect(night["--oip-color-canvas"]).toBe("#0b0d10");
    expect(day["--oip-color-canvas"]).not.toBe(night["--oip-color-canvas"]);
    const fallback = themeStyle("missing") as Record<string, string>;
    expect(fallback["--oip-color-ink"]).toBe(themes.nightwatch["color-ink"]);
    expect(createSession({ id: "s3" }).theme).toBe("nightwatch");
  });
});
