import { execFileSync } from "node:child_process";
import { mkdtempSync, readdirSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

describe("production build", () => {
  it("omits the latency bench routes and the test hook", () => {
    const outDir = mkdtempSync(path.join(tmpdir(), "oip-designer-"));
    try {
      execFileSync(
        "corepack",
        ["pnpm", "--filter", "@oip/designer", "exec", "vite", "build", "--mode", "production", "--outDir", outDir, "--emptyOutDir", "--logLevel", "error"],
        { cwd: webRoot, env: { ...process.env, NODE_ENV: "production" }, stdio: "pipe", shell: process.platform === "win32" },
      );
      const files = readdirSync(path.join(outDir, "assets")).filter((name) => name.endsWith(".js"));
      expect(files.length).toBeGreaterThan(0);
      const source = files.map((name) => readFileSync(path.join(outDir, "assets", name), "utf8")).join("\n");
      expect(source).not.toContain("__oipTest");
      expect(source).not.toContain("/latency");
      expect(source).not.toContain("/bench");
      expect(source).not.toContain("/bench-embed");
      expect(source).toContain("/hmi");
    } finally {
      rmSync(outDir, { recursive: true, force: true });
    }
  }, 60_000);
});
