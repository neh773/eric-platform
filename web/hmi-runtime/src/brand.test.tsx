import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { parseView } from "./model";
import { createSession } from "./session";
import { createLinkedTransports, MemoryTagServer, type StringTransport } from "./tags";
import { brandImageSource, parseCustomThemes, themeBrand, themeStyle } from "./theme";
import type { ComponentRegistry, CustomThemeSet, ViewDocument } from "./types";
import { ViewRenderer } from "./ViewRenderer";

/** HMP-085: brand customization (logo, colors, graphic) carried by a project theme. doc source: FEATURES HMP-085 */

const BRAND = {
  extends: "daylight",
  tokens: { "color-accent": "#aa3300", "color-canvas": "#fff8f0" },
  brand: { name: "Acme Water", logo: "/system/webdev/acme/logo.svg", logoAlt: "Acme logo", graphic: "https://cdn.acme.test/banner.png" },
};

describe("brand image sources", () => {
  it("allow http(s), site-relative and data:image, and refuse everything else", () => {
    for (const ok of ["/logo.png", "https://cdn.test/a.svg", "http://cdn.test/a.png", "data:image/png;base64,AAAA", "logo.png"]) {
      expect(brandImageSource(ok), ok).toBe(ok);
    }
    for (const bad of ["javascript:alert(1)", "data:text/html,<script>1</script>", "data:image/foo;base64,AA", "blob:http://x/y", "//evil.test/a.png", "file:///etc/passwd", "/a b.png", "/a\\b.png", "", "  ", 5, null]) {
      expect(brandImageSource(bad), String(bad)).toBeNull();
    }
  });
});

describe("theme brand", () => {
  it("parses a brand beside the colors and exposes it by theme name", () => {
    const themes = parseCustomThemes({ acme: BRAND });
    expect(themes.acme!.brand).toEqual(BRAND.brand);
    expect(themeBrand("acme", themes)).toEqual(BRAND.brand);
    expect((themeStyle("acme", themes) as Record<string, string>)["--oip-color-accent"]).toBe("#aa3300");
    expect(themeBrand("daylight", themes)).toBeNull();
    expect(themeBrand("acme", undefined)).toBeNull();
  });

  it("refuses an unsafe image or an unknown brand field, and drops empty fields", () => {
    expect(() => parseCustomThemes({ acme: { ...BRAND, brand: { logo: "javascript:alert(1)" } } })).toThrow(/logo/);
    expect(() => parseCustomThemes({ acme: { ...BRAND, brand: { graphic: "data:text/html,x" } } })).toThrow(/graphic/);
    expect(() => parseCustomThemes({ acme: { ...BRAND, brand: { colour: "red" } } })).toThrow(/not a brand field/);
    expect(() => parseCustomThemes({ acme: { ...BRAND, brand: "Acme" } })).toThrow(/object/);
    expect(parseCustomThemes({ acme: { ...BRAND, brand: { name: "", logo: "" } } }).acme!.brand).toEqual({});
    expect(themeBrand("acme", parseCustomThemes({ acme: { ...BRAND, brand: { name: "", logo: "" } } }))).toBeNull();
  });
});

const registry: ComponentRegistry = { get: () => undefined, list: () => [] };

function emptyView(): ViewDocument {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Home", path: "home" },
    spec: { width: 100, height: 40, params: {}, root: { id: "root", kind: "container", name: "Root", layout: "flex", props: { direction: "row" }, children: [] } },
  });
}

let server: MemoryTagServer;
let transport: StringTransport;
beforeEach(() => {
  server = new MemoryTagServer();
  const [client, serverSide] = createLinkedTransports();
  server.connect(serverSide);
  transport = client;
});
afterEach(() => server.close());

function session(theme: string, themes: CustomThemeSet | undefined, designMode = false) {
  return (
    <ViewRenderer view={emptyView()} registry={registry} transport={transport} session={createSession({ id: "s", theme, locale: "en-US" })} themes={themes} designMode={designMode} />
  );
}

describe("session brand bar", () => {
  const themes = parseCustomThemes({ acme: BRAND });

  it("shows the logo, name and graphic of the session theme, with the theme's colors", () => {
    const { container } = render(session("acme", themes));
    const logo = screen.getByRole("img", { name: "Acme logo" });
    expect(logo).toHaveAttribute("src", "/system/webdev/acme/logo.svg");
    expect(screen.getByText("Acme Water")).toBeInTheDocument();
    expect(container.querySelector(".oip-brand-graphic")).toHaveAttribute("src", "https://cdn.acme.test/banner.png");
    expect(container.querySelector(".oip-brand-graphic")).toHaveAttribute("aria-hidden", "true");
    expect((container.querySelector(".oip-session") as HTMLElement).style.getPropertyValue("--oip-color-accent")).toBe("#aa3300");
  });

  it("shows no bar on a theme without a brand, and none in the designer canvas", () => {
    const plain = render(session("daylight", themes));
    expect(plain.container.querySelector(".oip-brand")).toBeNull();
    plain.unmount();
    const design = render(session("acme", themes, true));
    expect(design.container.querySelector(".oip-brand")).toBeNull();
  });

  it("follows the session theme when it changes", () => {
    const { container, rerender } = render(session("daylight", themes));
    expect(container.querySelector(".oip-brand")).toBeNull();
    rerender(session("acme", themes));
    expect(container.querySelector(".oip-brand")).not.toBeNull();
  });
});
