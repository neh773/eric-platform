/** A chart built from the design form. Element ids stay inside the chart name rules. */

export const CHART_NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
const IDENT = /^[a-z_][a-z0-9_]{0,62}$/;

export interface ChartDraft {
  executionMode: "callable" | "runAlways" | "disabled";
  parameters: string;
  variables: string;
  actionScript: string;
  enclosingChart: string;
  parallelLeft: string;
  parallelRight: string;
  expression: string;
  loop: boolean;
}

export function emptyChart(): ChartDraft {
  return {
    executionMode: "callable",
    parameters: "",
    variables: "",
    actionScript: "",
    enclosingChart: "",
    parallelLeft: "",
    parallelRight: "",
    expression: "true",
    loop: false,
  };
}

export function chartProblem(name: string, draft: ChartDraft): string | null {
  if (!CHART_NAME.test(name)) return "Use a short lowercase name. Digits and underscores are allowed.";
  const params = splitNames(draft.parameters);
  const vars = splitNames(draft.variables);
  if (params === null || vars === null) return "Parameter and variable names use letters, digits, and underscores.";
  if (new Set([...params, ...vars]).size !== params.length + vars.length) return "Each parameter and variable needs its own name.";
  const left = draft.parallelLeft.trim();
  const right = draft.parallelRight.trim();
  if ((left && !right) || (!left && right)) return "A parallel step needs a script on both sides.";
  if (!draft.expression.trim()) return "A transition needs an expression.";
  if (draft.enclosingChart.trim() && !CHART_NAME.test(draft.enclosingChart.trim())) return "The enclosed chart name is not valid.";
  return null;
}

/** Begin, optional work, an optional parallel, a transition, then end or a jump back to an anchor. */
export function buildChart(draft: ChartDraft): Record<string, unknown> {
  const elements: Record<string, unknown>[] = [{ id: "begin", type: "begin" }];
  const links: { from: string; to: string }[] = [];
  let prev = "begin";
  const link = (to: string) => {
    links.push({ from: prev, to });
    prev = to;
  };
  if (draft.loop) {
    elements.push({ id: "again", type: "anchor" });
    link("again");
  }
  if (draft.actionScript.trim()) {
    elements.push({ id: "work", type: "action", onStart: [{ type: "script", source: draft.actionScript }] });
    link("work");
  }
  if (draft.enclosingChart.trim()) {
    elements.push({ id: "child", type: "enclosing", chart: draft.enclosingChart.trim(), execution: "block" });
    link("child");
  }
  const left = draft.parallelLeft.trim();
  const right = draft.parallelRight.trim();
  if (left && right) {
    elements.push({
      id: "together",
      type: "parallel",
      branches: [
        { elements: [{ id: "left", type: "action", onStart: [{ type: "script", source: left }] }], links: [] },
        { elements: [{ id: "right", type: "action", onStart: [{ type: "script", source: right }] }], links: [] },
      ],
    });
    link("together");
  }
  elements.push({ id: "done", type: "transition", expression: draft.expression.trim() || "true" });
  link("done");
  if (draft.loop) {
    elements.push({ id: "back", type: "jump", anchor: "again" });
    link("back");
  } else {
    elements.push({ id: "end", type: "end" });
    link("end");
  }
  return {
    executionMode: draft.executionMode,
    parameters: splitNames(draft.parameters)?.map((name) => ({ name })) ?? [],
    variables: splitNames(draft.variables)?.map((name) => ({ name })) ?? [],
    elements,
    links,
  };
}

function splitNames(raw: string): string[] | null {
  const names = raw.split(",").map((part) => part.trim()).filter(Boolean);
  if (names.some((name) => !IDENT.test(name))) return null;
  return names;
}
