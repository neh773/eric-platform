import { render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { CoordinateScene } from "@oip/hmi-runtime";
import { Pipe, parsePipePoints, pipeGeometry, resolvePipeRoute } from "./pipe";
import { routePipe } from "./pipeRoute";
import { allowedComponentTypes, componentRegistry, defaultsFromSchema } from "./registry";

/** HMP-128: flow pipes drawn through points, with joints, stroke props and optional flow animation. */

const draw = (props: Record<string, unknown>) => render(<Pipe nodeId="p" locale="en-US" onChange={vi.fn()} props={props} />);
const L = [{ x: 10, y: 10 }, { x: 10, y: 60 }, { x: 90, y: 60 }];

describe("pipe points", () => {
  it("reads {x,y} objects, [x,y] pairs and JSON text, skipping anything else", () => {
    expect(parsePipePoints([{ x: 1, y: 2 }, [3, 4], { x: "5", y: 6 }, null, [1], { x: Number.NaN, y: 1 }])).toEqual([{ x: 1, y: 2 }, { x: 3, y: 4 }]);
    expect(parsePipePoints('[{"x":1,"y":2},[3,4]]')).toEqual([{ x: 1, y: 2 }, { x: 3, y: 4 }]);
    expect(parsePipePoints("not json")).toEqual([]);
    expect(parsePipePoints("")).toEqual([]);
    expect(parsePipePoints({ x: 1, y: 2 })).toEqual([]);
    expect(parsePipePoints(Array.from({ length: 900 }, (_, i) => [i, i]))).toHaveLength(500);
  });

  it("measures the path and a view box that holds the pipe's own thickness", () => {
    const geometry = pipeGeometry(L, 10, 2)!;
    expect(geometry.path).toBe("M10 10 L10 60 L90 60");
    expect(geometry.length).toBe(130);
    // pad = 10/2 + 2 + 1 = 8
    expect(geometry.viewBox).toBe("2 2 96 66");
    expect(pipeGeometry([{ x: 1, y: 1 }], 10, 2)).toBeNull();
  });
});

describe("pipe component", () => {
  it("is registered as a display component whose defaults draw a bent pipe", () => {
    const module = componentRegistry().get("pipe")!;
    expect(module.category).toBe("display");
    expect(allowedComponentTypes().has("pipe")).toBe(true);
    const { container } = draw(defaultsFromSchema(module.schema));
    expect(container.querySelector(".oip-pipe-body")).not.toBeNull();
    expect(container.querySelectorAll(".oip-pipe-joint")).toHaveLength(0);
  });

  it("draws the body with its colour, width and outline, and a cap at each inner joint", () => {
    const { container } = draw({ points: L, width: 14, stroke: "#0077cc", outline: "#001122", outlineWidth: 3, showJoints: true });
    const body = container.querySelector(".oip-pipe-body")!;
    expect(body.getAttribute("stroke")).toBe("#0077cc");
    expect(body.getAttribute("stroke-width")).toBe("14");
    const paths = container.querySelectorAll("path");
    expect(paths[0]!.getAttribute("stroke")).toBe("#001122");
    expect(paths[0]!.getAttribute("stroke-width")).toBe("20");
    expect(container.querySelectorAll(".oip-pipe-joint")).toHaveLength(1);
    const withoutCaps = draw({ points: L, joints: "miter" });
    expect(withoutCaps.container.querySelector(".oip-pipe-body")!.getAttribute("stroke-linejoin")).toBe("miter");
    expect(withoutCaps.container.querySelectorAll(".oip-pipe-joint")).toHaveLength(0);
  });

  it("animates flow only when asked, at the speed and direction given", () => {
    const still = draw({ points: L });
    expect(still.container.querySelector(".oip-pipe-flow")).toBeNull();
    expect(still.container.querySelector(".oip-pipe")!.getAttribute("data-flowing")).toBe("false");
    const flowing = draw({ points: L, flow: true, flowSpeed: 20, flowDirection: "reverse", width: 10 });
    const flow = flowing.container.querySelector<SVGPathElement>(".oip-pipe-flow")!;
    expect(flowing.container.querySelector(".oip-pipe")!.getAttribute("data-flowing")).toBe("true");
    // dash 8 + gap 12 = a 20 px cycle at 20 px/s is one second.
    expect(flow.style.animationDuration).toBe("1s");
    expect(flow.style.animationDirection).toBe("reverse");
    expect(flow.getAttribute("stroke-dasharray")).toBe("8 12");
  });

  it("follows changed properties, as bindings would drive them", () => {
    const { container, rerender } = draw({ points: L, stroke: "#111111" });
    rerender(<Pipe nodeId="p" locale="en-US" onChange={vi.fn()} props={{ points: [...L, { x: 90, y: 100 }], stroke: "#222222", flow: true }} />);
    expect(container.querySelector(".oip-pipe-body")!.getAttribute("stroke")).toBe("#222222");
    expect(container.querySelector(".oip-pipe-body")!.getAttribute("d")).toBe("M10 10 L10 60 L90 60 L90 100");
    expect(container.querySelector(".oip-pipe-flow")).not.toBeNull();
  });

  it("refuses a colour that is not a plain colour, and says when there is nothing to draw", () => {
    const { container } = draw({ points: L, stroke: "url(https://evil.test/x.svg#p)", outline: "red;background:url(x)", flowColor: "javascript:1", flow: true });
    expect(container.querySelector(".oip-pipe-body")!.getAttribute("stroke")).toBe("#7a8794");
    expect(container.querySelectorAll("path")[0]!.getAttribute("stroke")).toBe("#3c4650");
    expect(container.querySelector(".oip-pipe-flow")!.getAttribute("stroke")).toBe("#d8e8f5");
    const empty = draw({ points: [{ x: 1, y: 1 }] });
    expect(empty.container.textContent).toContain("Pipe needs two points");
  });

  it("animates a positive flow forward and a negative flow backward, and draws a tee at the end", () => {
    const forward = draw({ points: L, flow: 40 });
    expect(forward.container.querySelector(".oip-pipe")!.getAttribute("data-direction")).toBe("forward");
    const reverse = draw({ points: L, flow: -12, tee: "end" });
    expect(reverse.container.querySelector(".oip-pipe")!.getAttribute("data-direction")).toBe("reverse");
    expect(reverse.container.querySelector(".oip-pipe-tee")).not.toBeNull();
    expect(draw({ points: L, flow: 0 }).container.querySelector(".oip-pipe-flow")).toBeNull();
  });

  it("routes from a tank outlet to a pump suction and draws a path that starts and ends on those ports", () => {
    const straight = routePipe({ x: 0, y: 10, exit: "right" }, { x: 80, y: 10, exit: "left" });
    expect(straight[0]).toEqual({ x: 0, y: 10 });
    expect(straight[straight.length - 1]).toEqual({ x: 80, y: 10 });
    expect(straight.every((point, index) => index === 0 || point.x === straight[index - 1]!.x || point.y === straight[index - 1]!.y)).toBe(true);
    const children = [
      { id: "well", type: "cylindrical-tank", name: "Well", frame: { x: 0, y: 0, width: 100, height: 100 }, props: {} },
      { id: "p1", type: "pump", name: "P1", frame: { x: 200, y: 0, width: 100, height: 100 }, props: { direction: "right" } },
    ];
    const route = resolvePipeRoute({ from: "well.outlet", to: "p1.suction" }, children)!;
    expect(route[0]).toEqual({ x: 84, y: 50 });
    expect(route[route.length - 1]).toEqual({ x: 217, y: 54 });
    expect(route.every((point, index) => index === 0 || point.x === route[index - 1]!.x || point.y === route[index - 1]!.y)).toBe(true);
    const { container } = render(
      <CoordinateScene.Provider value={{ width: 400, height: 200, children }}>
        <Pipe nodeId="pipe" locale="en-US" onChange={vi.fn()} placement={{ x: 0, y: 0, width: 8, height: 8 }} props={{ from: "well.outlet", to: "p1.suction", width: 5, flow: 10 }} />
      </CoordinateScene.Provider>,
    );
    const body = container.querySelector(".oip-pipe-body")!;
    expect(body.getAttribute("d")).toContain("M84 50");
    expect(body.getAttribute("d")).toContain("217 54");
    expect(container.querySelector(".oip-pipe")!.getAttribute("data-linked")).toBe("true");
  });
});
