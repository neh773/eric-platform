import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { NavigationDrawer } from "./drawer";
import { allowedComponentTypes, componentRegistry } from "./registry";

/** HMP-109: self-hiding navigation drawer. doc source: FEATURES HMP-109 */

const ITEMS = "Home = /, Plant > Pumps = /pumps, Docs = https://example.com/docs, Bad = javascript:alert(1)";

/** Holds `open` and `selected` the way a session does, so the drawer's own writes take effect. */
function Harness({ navigate, extra = {}, nodeClick }: { navigate?: (path: string) => void; extra?: Record<string, unknown>; nodeClick?: () => void }) {
  const [state, setState] = useState<Record<string, unknown>>({ open: false });
  return (
    <div onClick={nodeClick}>
      <NavigationDrawer
        nodeId="d"
        locale="en-US"
        navigate={navigate}
        onChange={(prop, value) => setState((current) => ({ ...current, [prop]: value }))}
        props={{ items: ITEMS, ...extra, ...state }}
      />
    </div>
  );
}

describe("NavigationDrawer", () => {
  it("is closed at first: only the menu button, with its state", () => {
    render(<Harness />);
    const button = screen.getByRole("button", { name: "Open navigation" });
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("navigation")).toBeNull();
  });

  it("opens over the page from the chosen edge and moves focus into the drawer", () => {
    render(<Harness extra={{ edge: "right", width: 300 }} />);
    fireEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    const drawer = screen.getByRole("navigation", { name: "Navigation" });
    expect(drawer).toHaveAttribute("data-edge", "right");
    expect(drawer.style.width).toBe("300px");
    expect(screen.getByRole("button", { name: "Open navigation" })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("link", { name: "Home" })).toHaveFocus();
    expect(screen.getByText("Plant")).toBeInTheDocument();
  });

  it("hides itself after a destination is chosen, and moves the session there", () => {
    const navigate = vi.fn();
    render(<Harness navigate={navigate} />);
    fireEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    fireEvent.click(screen.getByRole("link", { name: "Pumps" }));
    expect(navigate).toHaveBeenCalledWith("/pumps");
    expect(screen.queryByRole("navigation")).toBeNull();
  });

  it("stays open after a choice when autoHide is off, until it is closed with its button", () => {
    render(<Harness navigate={() => {}} extra={{ autoHide: false }} />);
    fireEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    fireEvent.click(screen.getByRole("link", { name: "Home" }));
    expect(screen.getByRole("navigation")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Close drawer" }));
    expect(screen.queryByRole("navigation")).toBeNull();
  });

  it("hides on Escape and returns focus to the menu button", () => {
    render(<Harness />);
    const button = screen.getByRole("button", { name: "Open navigation" });
    fireEvent.click(button);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("navigation")).toBeNull();
    expect(button).toHaveFocus();
  });

  it("hides when the area outside the drawer is pressed", () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    fireEvent.click(screen.getByRole("button", { name: "Close navigation" }));
    expect(screen.queryByRole("navigation")).toBeNull();
  });

  it("does not run the node's click actions for its own open and close, and refuses a blocked target", () => {
    const nodeClick = vi.fn();
    const navigate = vi.fn();
    render(<Harness nodeClick={nodeClick} navigate={navigate} />);
    fireEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    expect(nodeClick).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("Bad"));
    expect(navigate).not.toHaveBeenCalled();
    expect(screen.getByRole("navigation")).toBeInTheDocument();
    expect(nodeClick).not.toHaveBeenCalled();
  });

  it("opens from a bound property and marks the selected item current", () => {
    render(<NavigationDrawer nodeId="d" locale="en-US" onChange={() => {}} props={{ items: ITEMS, open: true, selected: "1/0" }} />);
    expect(screen.getByRole("link", { name: "Pumps" })).toHaveAttribute("aria-current", "page");
  });

  it("stays shut and disabled when not enabled", () => {
    render(<NavigationDrawer nodeId="d" locale="en-US" onChange={() => {}} props={{ items: ITEMS, open: true, enabled: false }} />);
    expect(screen.queryByRole("navigation")).toBeNull();
    expect(screen.getByRole("button", { name: "Open navigation" })).toBeDisabled();
  });

  it("is registered in the navigation palette", () => {
    const module = componentRegistry().get("navigation-drawer")!;
    expect(allowedComponentTypes().has("navigation-drawer")).toBe(true);
    expect(module.category).toBe("navigation");
  });
});
