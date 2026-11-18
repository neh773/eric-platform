import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { LedDisplay, LinearScale, Markdown, PieChart, RangeSelector, Thermometer, parseSlices, renderMarkdown } from "./batch2";

describe("phase 4 palette pieces", () => {
  it("parses pie slices and draws one path per name", () => {
    expect(parseSlices("Run:3, Idle:1, bad")).toEqual([
      { name: "Run", value: 3 },
      { name: "Idle", value: 1 },
    ]);
    render(<PieChart nodeId="pie" locale="en-US" props={{ title: "Load", slices: "Run:3, Idle:1" }} onChange={() => {}} />);
    expect(screen.getByRole("img", { name: "Load" }).querySelectorAll("path")).toHaveLength(2);
  });

  it("moves a range window and shows a scale marker and a thermometer fill", () => {
    render(<RangeSelector nodeId="range" locale="en-US" props={{ points: "1,2,3", start: 0, end: 2 }} onChange={() => {}} />);
    expect(screen.getByLabelText("Range end")).toHaveValue(2);
    render(<LinearScale nodeId="scale" locale="en-US" props={{ label: "Level", value: 25, min: 0, max: 100 }} onChange={() => {}} />);
    expect(document.querySelector("[data-value='25']")).toBeTruthy();
    render(<Thermometer nodeId="temp" locale="en-US" props={{ label: "Temp", value: 50, min: 0, max: 100 }} onChange={() => {}} />);
    expect(document.querySelector("[data-fill='level']")).toBeTruthy();
    render(<LedDisplay nodeId="led" locale="en-US" props={{ value: "42", digits: 4 }} onChange={() => {}} />);
    expect(screen.getByLabelText("42").textContent).toContain("42");
  });

  it("renders markdown as text, including a bold span, and does not keep a raw tag", () => {
    expect(renderMarkdown("# Title\nA **bold** note\n<script>alert(1)</script>")).toEqual([
      { kind: "h", parts: [{ bold: false, text: "Title" }] },
      { kind: "p", parts: [{ bold: false, text: "A " }, { bold: true, text: "bold" }, { bold: false, text: " note" }] },
      { kind: "p", parts: [{ bold: false, text: "<script>alert(1)</script>" }] },
    ]);
    render(<Markdown nodeId="note" locale="en-US" props={{ text: "See **this**" }} onChange={() => {}} />);
    expect(screen.getByText("this").tagName).toBe("STRONG");
  });
});
