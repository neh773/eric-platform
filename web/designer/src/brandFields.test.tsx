import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import type { CustomThemeSet } from "@oip/hmi-runtime";
import { StyleClassesPanel } from "./StyleClassesPanel";

/** HMP-085: the Studio edits a theme's brand beside its colors. doc source: FEATURES HMP-085 */

function Harness({ seen }: { seen: CustomThemeSet[] }) {
  const [themes, setThemes] = useState<CustomThemeSet>({ acme: { extends: "daylight", tokens: {} } });
  return (
    <StyleClassesPanel
      styleClasses={{}}
      onChange={() => {}}
      themes={themes}
      onThemes={(next) => {
        seen.push(next);
        setThemes(next);
      }}
    />
  );
}

describe("theme brand fields", () => {
  it("sets the name, logo, description and graphic, and the accent and background colors", () => {
    const seen: CustomThemeSet[] = [];
    render(<Harness seen={seen} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Brand name for acme" }), { target: { value: "Acme Water" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Logo image for acme" }), { target: { value: "/system/webdev/acme/logo.svg" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Logo description for acme" }), { target: { value: "Acme logo" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Header graphic for acme" }), { target: { value: "https://cdn.acme.test/b.png" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Accent color for acme" }), { target: { value: "#aa3300" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Background color for acme" }), { target: { value: "#fff8f0" } });
    expect(seen.at(-1)!.acme).toEqual({
      extends: "daylight",
      tokens: { "color-accent": "#aa3300", "color-canvas": "#fff8f0" },
      brand: { name: "Acme Water", logo: "/system/webdev/acme/logo.svg", logoAlt: "Acme logo", graphic: "https://cdn.acme.test/b.png" },
    });
  });

  it("refuses a logo that is not an allowed image source, and says why", () => {
    const seen: CustomThemeSet[] = [];
    render(<Harness seen={seen} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Logo image for acme" }), { target: { value: "javascript:alert(1)" } });
    expect(screen.getByRole("alert")).toHaveTextContent("Logo image must be an http(s), site-relative or data:image source");
    expect(seen).toEqual([]);
  });

  it("drops the brand when every field is cleared", () => {
    const seen: CustomThemeSet[] = [];
    render(<Harness seen={seen} />);
    const name = screen.getByRole("textbox", { name: "Brand name for acme" });
    fireEvent.change(name, { target: { value: "Acme" } });
    fireEvent.change(name, { target: { value: "" } });
    expect(seen.at(-1)!.acme).toEqual({ extends: "daylight", tokens: {} });
  });
});
