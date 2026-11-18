import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PlcWindowsBanner, showPlcWindowsBanner } from "./plcBanner";

describe("PLC Windows banner", () => {
  it("shows on Windows and stays hidden on Linux", () => {
    expect(showPlcWindowsBanner("windows")).toBe(true);
    expect(showPlcWindowsBanner("linux")).toBe(false);
    const view = render(<PlcWindowsBanner os="windows" />);
    expect(screen.getByTestId("plc-windows-banner").textContent).toContain("real-time kernel");
    expect(screen.getByTestId("plc-windows-banner").querySelector("button")).toBeNull();
    view.rerender(<PlcWindowsBanner os="linux" />);
    expect(screen.queryByTestId("plc-windows-banner")).toBeNull();
  });
});
