import { readFileSync } from "node:fs";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AlarmStatus, allowedImageSource, Checkbox, Dropdown, Image, Slider, Table } from "./extra";

/** Accessible, themeable components.
 */

const rows = [
  { id: "a", name: "North", level: 2 },
  { id: "b", name: "South", level: 10 },
  { id: "c", name: "East", level: 4 },
];

describe("expanded components", () => {
  it("sorts, filters, pages, selects, and edits a table", () => {
    const onChange = vi.fn();
    render(
      <Table
        nodeId="table"
        locale="en-US"
        onChange={onChange}
        props={{
          data: rows,
          columns: [
            { field: "name", header: "Name", sortable: true },
            { field: "level", header: "Level", sortable: true, editable: true },
          ],
          pageSize: 2,
          rowKey: "id",
        }}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Level" }));
    expect(screen.getByRole("columnheader", { name: "Level" })).toHaveAttribute("aria-sort", "ascending");
    expect(screen.getAllByRole("row")[1]).toHaveTextContent("North");
    fireEvent.click(screen.getByRole("button", { name: "Next page" }));
    expect(screen.getByText("Page 2 of 2")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Filter table"), { target: { value: "east" } });
    expect(screen.getByText("East")).toBeInTheDocument();
    expect(screen.queryByText("North")).toBeNull();
    fireEvent.click(screen.getByText("East"));
    expect(screen.getByRole("row", { selected: true })).toHaveTextContent("East");
    fireEvent.change(screen.getByLabelText("Level c"), { target: { value: "11" } });
    expect(onChange).toHaveBeenCalledWith("data", expect.arrayContaining([expect.objectContaining({ id: "c", level: "11" })]));
  });

  it("selects one dropdown value or several", () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <Dropdown nodeId="d" locale="en-US" onChange={onChange} props={{ label: "Area", value: "n", options: [{ value: "n", label: "North" }, { value: "s", label: "South" }] }} />,
    );
    expect(screen.getByRole("combobox", { name: "Area" })).toHaveValue("n");
    fireEvent.change(screen.getByRole("combobox", { name: "Area" }), { target: { value: "s" } });
    expect(onChange).toHaveBeenCalledWith("value", "s");
    rerender(
      <Dropdown nodeId="d" locale="en-US" onChange={onChange} props={{ label: "Areas", multi: true, value: ["n"], options: [{ value: "n", label: "North" }, { value: "s", label: "South" }] }} />,
    );
    expect(screen.getByRole("listbox", { name: "Areas" })).toHaveAttribute("aria-multiselectable", "true");
    fireEvent.click(screen.getByRole("option", { name: "South" }));
    expect(onChange).toHaveBeenCalledWith("value", ["n", "s"]);
  });

  it("exposes a slider, a checkbox, an unconnected alarm table, and an image", () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <div>
        <Slider nodeId="s" locale="en-US" onChange={onChange} props={{ label: "Level", min: 0, max: 80, step: 1, value: 40 }} />
        <Checkbox nodeId="c" locale="en-US" onChange={onChange} props={{ label: "Enable pump", value: true }} />
        <AlarmStatus
          nodeId="a"
          locale="en-US"
          onChange={onChange}
          props={{ rows: [{ id: "1", displayPath: "Tank", source: "sim", state: "Active", priority: "High", activeTime: "10:00" }] }}
        />
        <Image nodeId="i" locale="en-US" onChange={onChange} props={{ alt: "Tank photo", fit: "contain" }} />
      </div>,
    );
    const slider = screen.getByRole("slider", { name: "Level" });
    expect(slider).toHaveAttribute("aria-valuenow", "40");
    expect(slider).toHaveAttribute("aria-valuemin", "0");
    expect(slider).toHaveAttribute("aria-valuemax", "80");
    fireEvent.change(slider, { target: { value: "55" } });
    expect(onChange).toHaveBeenCalledWith("value", 55);
    expect(screen.getByRole("checkbox", { name: "Enable pump" })).toBeChecked();
    const alarm = document.querySelector(".oip-alarm");
    expect(alarm).toHaveAttribute("data-source", "unconnected");
    fireEvent.change(screen.getByLabelText("Filter alarms"), { target: { value: "nope" } });
    expect(screen.queryByText("Tank")).toBeNull();
    fireEvent.change(screen.getByLabelText("Filter alarms"), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Acknowledge Tank" }));
    expect(screen.getByText("Acknowledged")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Shelve Tank" }));
    expect(screen.getByText("Shelved")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Tank photo" }).tagName).toBe("DIV");
    rerender(<Image nodeId="i" locale="en-US" onChange={onChange} props={{ source: "/tank.svg", alt: "Tank photo", fit: "cover" }} />);
    expect(screen.getByRole("img", { name: "Tank photo" }).tagName).toBe("IMG");
    expect(screen.getByRole("img", { name: "Tank photo" })).toHaveAttribute("src", "/tank.svg");
    const css = readFileSync("components/src/components.css", "utf8");
    for (const name of [".oip-table", ".oip-dropdown", ".oip-slider", ".oip-check", ".oip-alarm", ".oip-image"]) {
      expect(css).toContain(name);
    }
    expect(css).toContain("var(--oip-color-ink)");
    expect(css).toContain("var(--oip-color-surface)");
  });

  it("allows http(s), relative, and data:image sources and rejects other schemes", () => {
    expect(allowedImageSource(" https://cdn.example/tank.png ")).toBe("https://cdn.example/tank.png");
    expect(allowedImageSource("http://cdn.example/tank.png")).toBe("http://cdn.example/tank.png");
    expect(allowedImageSource("/tank.svg")).toBe("/tank.svg");
    expect(allowedImageSource("./tank.svg")).toBe("./tank.svg");
    expect(allowedImageSource("../img/tank.png")).toBe("../img/tank.png");
    expect(allowedImageSource("data:image/png;base64,aaaa")).toBe("data:image/png;base64,aaaa");
    expect(allowedImageSource("data:image/svg+xml,<svg></svg>")).toBe("data:image/svg+xml,<svg></svg>");
    for (const blocked of ["javascript:alert(1)", "JavaScript:alert(1)", "data:text/html,<script>bad</script>", "blob:https://example/id", "file:///etc/passwd", "//cdn.example/tank.png"]) {
      expect(allowedImageSource(blocked)).toBeNull();
    }
    const { rerender } = render(<Image nodeId="i" locale="en-US" onChange={() => {}} props={{ source: "javascript:alert(1)", alt: "Blocked" }} />);
    expect(screen.getByRole("img", { name: "Blocked" }).tagName).toBe("DIV");
    expect(document.querySelector("img")).toBeNull();
    rerender(<Image nodeId="i" locale="en-US" onChange={() => {}} props={{ source: "data:text/html,<script>bad</script>", alt: "Blocked" }} />);
    expect(document.querySelector("img")).toBeNull();
    rerender(<Image nodeId="i" locale="en-US" onChange={() => {}} props={{ source: "data:image/png;base64,aaaa", alt: "Pixels" }} />);
    expect(screen.getByRole("img", { name: "Pixels" })).toHaveAttribute("src", "data:image/png;base64,aaaa");
  });
});
