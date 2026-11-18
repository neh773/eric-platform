import { readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

/** An unclosed rule nests every later rule under it once the bundler joins the files, and the whole app loses its
 * styles (it happened once after a merge). Each stylesheet must close every block it opens. */
const webRoot = path.resolve(__dirname, "../..");
const packages = ["admin", "components", "designer", "hmi-runtime"];
const sharedStyles = [path.join(webRoot, "styles")];

function cssFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) return name === "node_modules" || name === "dist" ? [] : cssFiles(full);
    return name.endsWith(".css") ? [full] : [];
  });
}

/** Index of the first brace that opens a block inside a style rule or closes nothing, the text length when a block
 * is left open, or -1. At-rule blocks (@media, @supports, @keyframes) may hold rules; style rules may not. */
function unclosedAt(css: string): number {
  const text = css
    .replace(/\/\*[\s\S]*?\*\//g, (c) => c.replace(/[^\n]/g, " "))
    .replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, (c) => " ".repeat(c.length));
  const stack: boolean[] = [];
  let start = 0;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === "{") {
      if (stack.length > 0 && !stack[stack.length - 1]) return i;
      stack.push(text.slice(start, i).trim().startsWith("@"));
      start = i + 1;
    } else if (ch === "}") {
      if (stack.pop() === undefined) return i;
      start = i + 1;
    } else if (ch === ";") start = i + 1;
  }
  return stack.length === 0 ? -1 : text.length;
}

describe("stylesheets", () => {
  const files = [...packages.flatMap((p) => cssFiles(path.join(webRoot, p, "src"))), ...sharedStyles.flatMap((dir) => cssFiles(dir))];
  it("finds the stylesheets", () => expect(files.length).toBeGreaterThan(3));
  for (const file of files) {
    it(`${path.relative(webRoot, file)} closes every block`, () => {
      const css = readFileSync(file, "utf8");
      const at = unclosedAt(css);
      const line = at < 0 ? 0 : css.slice(0, at).split("\n").length;
      expect(line, `unbalanced brace near line ${line}`).toBe(0);
    });
  }
});
