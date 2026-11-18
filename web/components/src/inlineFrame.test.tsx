import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { FRAME_PERMISSIONS, frameSandbox, InlineFrame } from "./inlineFrame";
import { allowedComponentTypes, componentRegistry, defaultsFromSchema } from "./registry";

/** HMP-024: inline frame. doc source: FEATURES HMP-024 */

function mount(props: Record<string, unknown>) {
  return render(<InlineFrame nodeId="f" locale="en-US" onChange={() => {}} props={props} />);
}

describe("frameSandbox", () => {
  it("keeps only the allowed permissions, once each", () => {
    expect(frameSandbox("allow-scripts allow-forms allow-scripts")).toBe("allow-scripts allow-forms");
    expect(frameSandbox("  allow-popups   allow-downloads ")).toBe("allow-popups allow-downloads");
  });

  it("never grants same-origin access or top navigation, even asked", () => {
    expect(frameSandbox("allow-scripts allow-same-origin")).toBe("allow-scripts");
    expect(frameSandbox("allow-top-navigation allow-top-navigation-by-user-activation allow-same-origin")).toBe("");
    expect(frameSandbox("allow-scripts allow-orientation-lock allow-pointer-lock")).toBe("allow-scripts");
    expect(FRAME_PERMISSIONS).not.toContain("allow-same-origin");
    expect(FRAME_PERMISSIONS.some((token) => token.startsWith("allow-top-navigation"))).toBe(false);
  });

  it("starts from scripts and forms, and an empty value is a fully locked frame", () => {
    expect(frameSandbox(undefined)).toBe("allow-scripts allow-forms");
    expect(frameSandbox("")).toBe("");
  });
});

describe("InlineFrame", () => {
  it("frames an http(s) URL in a sandboxed frame with no referrer and no delegated features", () => {
    mount({ source: "https://vendor.test/dashboard", title: "Vendor dashboard" });
    const frame = screen.getByTitle("Vendor dashboard");
    expect(frame.tagName).toBe("IFRAME");
    expect(frame).toHaveAttribute("src", "https://vendor.test/dashboard");
    expect(frame).toHaveAttribute("sandbox", "allow-scripts allow-forms");
    expect(frame).toHaveAttribute("referrerpolicy", "no-referrer");
    expect(frame).toHaveAttribute("allow", "");
  });

  it("keeps the sandbox attribute even when the property is empty or asks for more than is allowed", () => {
    mount({ source: "/system/webdev/page.html", sandbox: "allow-same-origin allow-scripts" });
    const frame = screen.getByTitle("Embedded page");
    expect(frame.hasAttribute("sandbox")).toBe(true);
    expect(frame.getAttribute("sandbox")).toBe("allow-scripts");
  });

  it("frames nothing for a source the HMI's URL filter refuses", () => {
    for (const source of ["javascript:alert(1)", "data:text/html,<script>1</script>", "blob:http://x/y", "//evil.test/x", "page.html", "", 5]) {
      const { container, unmount } = mount({ source, title: "Refused" });
      expect(container.querySelector("iframe"), String(source)).toBeNull();
      expect(screen.getByRole("document", { name: "Refused" })).toHaveAttribute("data-empty", "true");
      unmount();
    }
  });

  it("is registered in the display palette with a sandboxed default", () => {
    const module = componentRegistry().get("inline-frame")!;
    expect(allowedComponentTypes().has("inline-frame")).toBe(true);
    expect(module.category).toBe("display");
    expect(defaultsFromSchema(module.schema)).toEqual({ source: "", title: "Embedded page", sandbox: "allow-scripts allow-forms" });
  });
});
