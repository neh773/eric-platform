import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ViewRenderer, createSession } from "@oip/hmi-runtime";
import { componentRegistry } from "@oip/components";
import { Canvas } from "./Canvas";
import { embedBench } from "./embedBench";
import { benchView, sampleProject } from "./sampleProject";
import { createTagRuntime, seedStudio } from "./runtime";

describe("live preview", () => {
  it("renders a mock tag change into the bound numeric", async () => {
    const runtime = createTagRuntime(seedStudio);
    render(
      <ViewRenderer
        view={sampleProject.views[0]!}
        registry={componentRegistry()}
        transport={runtime.transport}
        session={createSession({ id: "preview", locale: "en-US", theme: "daylight" })}
      />,
    );
    const level = () => document.querySelector("[data-node-id='level'] [data-testid='numeric-value']");
    await waitFor(() => expect(level()).toHaveTextContent("62.4 m"));
    runtime.server.set("plant/tank/level", 12.5);
    await waitFor(() => expect(level()).toHaveTextContent("12.5 m"));
    expect(document.querySelector("[data-theme='daylight']")).toBeTruthy();
    runtime.server.close();
  });

  it("renders 500 bound numerics and updates them in one batch", async () => {
    const runtime = createTagRuntime(seedStudio);
    const view = benchView(500);
    const started = performance.now();
    render(
      <ViewRenderer
        view={view}
        registry={componentRegistry()}
        transport={runtime.transport}
        session={createSession({ id: "bench", locale: "en-US" })}
      />,
    );
    await screen.findAllByTestId("numeric-value");
    const nodes = document.querySelectorAll("[data-testid='numeric-value']");
    expect(nodes).toHaveLength(500);
    expect(nodes[0]).toHaveAttribute("data-value", "0");
    const updateStarted = performance.now();
    for (let index = 0; index < 500; index += 1) runtime.server.set(`bench/${index}`, 1000 + index);
    await screen.findByText("1499");
    const elapsed = performance.now() - updateStarted;
    expect(document.querySelector("[data-node-id='n499'] [data-testid='numeric-value']")).toHaveAttribute("data-value", "1499");
    expect(elapsed).toBeLessThan(1000);
    expect(performance.now() - started).toBeLessThan(5000);
    runtime.server.close();
  });

  it("renders 500 bound numerics on the design-mode canvas and updates them in one batch", async () => {
    const runtime = createTagRuntime(seedStudio);
    const view = benchView(500);
    render(
      <Canvas
        view={view}
        registry={componentRegistry()}
        transport={runtime.transport}
        session={createSession({ id: "bench-canvas", locale: "en-US" })}
        selectedId="n0"
        onSelect={() => {}}
        onFrame={() => {}}
      />,
    );
    await screen.findAllByTestId("numeric-value");
    expect(document.querySelectorAll("[data-testid='canvas'] [data-testid='numeric-value']")).toHaveLength(500);
    const updateStarted = performance.now();
    for (let index = 0; index < 500; index += 1) runtime.server.set(`bench/${index}`, 1000 + index);
    await screen.findByText("1499");
    expect(performance.now() - updateStarted).toBeLessThan(1000);
    runtime.server.close();
  });

  it("updates 50 embedded views from one tag batch", async () => {
    const runtime = createTagRuntime(seedStudio);
    for (let index = 0; index < 50; index += 1) runtime.server.set(`embed/${index}`, 0);
    const { shell, child } = embedBench();
    render(
      <ViewRenderer
        view={shell}
        views={[child]}
        registry={componentRegistry()}
        transport={runtime.transport}
        session={createSession({ id: "embed", locale: "en-US" })}
      />,
    );
    await screen.findAllByTestId("numeric-value");
    expect(document.querySelectorAll("[data-testid='numeric-value']")).toHaveLength(50);
    const updateStarted = performance.now();
    for (let index = 0; index < 50; index += 1) runtime.server.set(`embed/${index}`, 1000 + index);
    await screen.findByText("1049");
    expect(performance.now() - updateStarted).toBeLessThan(1000);
    expect(document.querySelector("[data-node-id='e49__value'] [data-testid='numeric-value']")).toHaveAttribute("data-value", "1049");
    runtime.server.close();
  });
});
