import { describe, expect, it } from "vitest";
import { evaluateExpression, tagPathsInExpression, type ExpressionScope } from "./expression";

/** Expression subset used by expression bindings.
 */

const empty: ExpressionScope = { resolve: () => null };

describe("expressions", () => {
  it("honors arithmetic precedence, if, abs, and string concatenation", () => {
    expect(evaluateExpression("1+2*3", empty)).toBe(7);
    expect(evaluateExpression("(1+2)*3", empty)).toBe(9);
    expect(evaluateExpression("if(1 < 2, 'yes', 'no')", empty)).toBe("yes");
    expect(evaluateExpression("abs(-3)", empty)).toBe(3);
    expect(evaluateExpression("'a' + 1", empty)).toBe("a1");
  });

  it("reads a tag reference and drops the provider segment", () => {
    const scope: ExpressionScope = {
      resolve(ref) {
        return ref === "tag:plant/tank" ? 12 : null;
      },
    };
    expect(tagPathsInExpression("{[default]plant/tank} + 1")).toEqual(["plant/tank"]);
    expect(evaluateExpression("{[default]plant/tank} + 1", scope)).toBe(13);
  });
});
